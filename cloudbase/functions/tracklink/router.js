const { HttpError, cleanTrackPoints, clampStr, haversine, json, readJson, startOfDay, toInt, trackDistance, LoginGuard } = require('./util.js');
const { hashPassword, randomKey, safeEqual, signToken, verifyPassword, verifyToken } = require('./auth.js');

const TZ_OFFSET_MIN = 480; // 统一按 UTC+8 切天
const DAY_MS = 86400_000;

/** 采样/省电配置：字段、边界与默认值（App1 的全部可调项） */
const CONFIG_FIELDS = [
  ['sampleIntervalS', 5, 300, 15],
  ['minDistanceM', 0, 500, 10],
  ['accuracyMaxM', 5, 500, 50],
  ['sleepAfterMin', 0, 30, 3],
  ['batchSeconds', 15, 600, 60],
  ['heartbeatS', 60, 1800, 300],
];

function sanitizeConfig(body = {}) {
  const out = {};
  for (const [key, min, max, def] of CONFIG_FIELDS) {
    const n = Number(body[key]);
    out[key] = Number.isFinite(n) ? Math.round(Math.min(max, Math.max(min, n))) : def;
  }
  return out;
}

const CONFIG_COLUMNS = {
  sampleIntervalS: 'sample_interval_s',
  minDistanceM: 'min_distance_m',
  accuracyMaxM: 'accuracy_max_m',
  sleepAfterMin: 'sleep_after_min',
  batchSeconds: 'batch_seconds',
  heartbeatS: 'heartbeat_s',
};

/** camelCase 配置对象 → snake_case 列（写库用） */
function configToRow(config) {
  const row = {};
  for (const [key] of CONFIG_FIELDS) row[CONFIG_COLUMNS[key]] = config[key];
  return row;
}

function rowToConfig(row) {
  if (!row) return null; // 无自定义配置行 → 调用方用默认值
  const out = {};
  for (const [key] of CONFIG_FIELDS) {
    const v = row[CONFIG_COLUMNS[key]];
    out[key] = v == null ? null : Number(v);
  }
  return Object.values(out).some((v) => v == null) ? null : out;
}

function rowToUser(row) {
  return { id: Number(row.id), username: row.username, role: row.role, createdAt: Number(row.created_at) };
}

function deviceView(row, distanceToday, pointCountToday) {
  const lastSeen = row.last_seen == null ? null : Number(row.last_seen);
  const online = !!lastSeen && Date.now() - lastSeen < 15 * 60_000;
  return {
    id: row.id,
    name: row.name,
    model: row.model ?? '',
    note: row.note ?? '',
    createdAt: Number(row.created_at),
    lastSeen,
    battery: row.battery,
    online,
    lastPoint: row.last_lat == null
      ? null
      : { lat: Number(row.last_lat), lon: Number(row.last_lon), ts: Number(row.last_ts), acc: Number(row.last_acc) },
    distanceToday,
    pointCountToday,
  };
}

function createRouter({ db, config }) {
  const loginGuard = new LoginGuard();

  async function authUser(req) {
    const header = String(req.headers.authorization ?? '');
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    const payload = verifyToken(token, config.tokenSecret);
    if (!payload) throw new HttpError(401, '登录已失效，请重新登录');
    const row = await db.first('tl_users', { id: db.eq(payload.uid) });
    if (!row) throw new HttpError(401, '账号不存在');
    return rowToUser(row);
  }

  function requireAdmin(user) {
    if (user.role !== 'admin') throw new HttpError(403, '需要管理员权限');
  }

  async function visibleDeviceIds(user) {
    if (user.role === 'admin') return null; // null = 全部
    const rows = await db.select('tl_acl', { columns: 'device_id', filter: { user_id: db.eq(user.id) } });
    return new Set((rows ?? []).map((r) => r.device_id));
  }

  async function assertDeviceVisible(user, deviceId) {
    const ids = await visibleDeviceIds(user);
    if (ids === null) return;
    if (!ids.has(deviceId)) throw new HttpError(403, '无权查看该设备');
  }

  function requireDeviceKey(req) {
    const key = String(req.headers['x-upload-key'] ?? '');
    if (!safeEqual(key, config.uploadKey)) throw new HttpError(401, '上传密钥不正确');
  }

  async function currentUploadKey() {
    const row = await db.first('tl_meta', { k: db.eq('upload_key') });
    return row?.v || config.uploadKey;
  }

  async function login(ctx) {
    const ip = ctx.ip;
    if (!loginGuard.check(ip)) throw new HttpError(429, '尝试过于频繁，请 5 分钟后再试');
    const body = await readJson(ctx.req);
    const username = clampStr(body.username, 40);
    const password = String(body.password ?? '');
    const row = await db.first('tl_users', { username: db.eq(username) });
    if (!row || !verifyPassword(password, row.pass)) {
      loginGuard.fail(ip);
      throw new HttpError(401, '用户名或密码错误');
    }
    loginGuard.ok(ip);
    const user = rowToUser(row);
    return {
      token: signToken({ uid: user.id, role: user.role }, config.tokenSecret, config.tokenTtlMs),
      user,
      expiresIn: Math.floor(config.tokenTtlMs / 1000),
    };
  }

  async function me(ctx) {
    const user = await authUser(ctx.req);
    return { user };
  }

  async function listDevices(ctx) {
    const user = await authUser(ctx.req);
    const ids = await visibleDeviceIds(user);
    const dayStart = startOfDay(Date.now(), TZ_OFFSET_MIN);
    const rows = (await db.select('tl_devices', {
      order: 'last_seen.desc.nullslast,created_at.desc',
    })).filter((r) => ids === null || ids.has(r.id));

    // 一次取当天全部点位，避免逐设备查询（原 SQLite 版是 N+1）
    const pts = rows.length
      ? await db.select('tl_points', {
        columns: 'device_id,ts,lat,lon',
        filter: { ts: db.gte(dayStart), device_id: `in.(${rows.map((r) => r.id).join(',')})` },
        order: 'ts.asc',
      })
      : [];
    const byDevice = new Map();
    for (const p of pts ?? []) {
      if (!byDevice.has(p.device_id)) byDevice.set(p.device_id, []);
      byDevice.get(p.device_id).push({ ts: Number(p.ts), lat: Number(p.lat), lon: Number(p.lon) });
    }

    return {
      serverTime: Date.now(),
      devices: rows.map((r) => {
        const list = byDevice.get(r.id) ?? [];
        return deviceView(r, Math.round(trackDistance(list)), list.length);
      }),
    };
  }

  async function getTrack(ctx) {
    const user = await authUser(ctx.req);
    const id = ctx.params.id;
    await assertDeviceVisible(user, id);
    const device = await db.first('tl_devices', { id: db.eq(id) });
    if (!device) throw new HttpError(404, '设备不存在');
    const to = toInt(ctx.url.searchParams.get('to'), Date.now());
    const from = toInt(ctx.url.searchParams.get('from'), to - DAY_MS);
    const limit = Math.min(Math.max(toInt(ctx.url.searchParams.get('limit'), 20000), 10), 50000);
    if (from > to) throw new HttpError(400, '时间范围不合法');
    const rows = await db.select('tl_points', {
      columns: 'ts,lat,lon,acc,spd',
      filter: { device_id: db.eq(id), ts: [db.gte(from), db.lte(to)] },
      order: 'ts.asc',
      limit,
    });
    // 剔除跳点（GNSS 冷启动脏坐标等），保证图形与统计口径一致
    const points = cleanTrackPoints(rows ?? []).map((p) => [
      Number(p.ts),
      Number(Number(p.lat).toFixed(6)),
      Number(Number(p.lon).toFixed(6)),
      Math.round(p.acc ?? 0),
      Math.round((p.spd ?? 0) * 10) / 10,
    ]);
    const distance = Math.round(trackDistance(points.map((p) => ({ ts: p[0], lat: p[1], lon: p[2] }))));
    const duration = points.length > 1 ? Math.round((points[points.length - 1][0] - points[0][0]) / 1000) : 0;
    return {
      device: { id: device.id, name: device.name },
      from,
      to,
      truncated: points.length >= limit,
      stats: {
        distance,
        duration,
        points: points.length,
        avgSpeed: duration > 0 ? Math.round((distance / duration) * 3.6 * 10) / 10 : 0,
      },
      points,
    };
  }

  async function dailyStats(ctx) {
    const user = await authUser(ctx.req);
    const id = ctx.params.id;
    await assertDeviceVisible(user, id);
    const to = toInt(ctx.url.searchParams.get('to'), Date.now());
    const from = toInt(ctx.url.searchParams.get('from'), to - 7 * DAY_MS);
    const rows = await db.select('tl_points', {
      columns: 'ts,lat,lon',
      filter: { device_id: db.eq(id), ts: [db.gte(from), db.lte(to)] },
      order: 'ts.asc',
      limit: 200000,
    });
    const buckets = new Map();
    for (const p of rows ?? []) {
      const ts = Number(p.ts);
      const day = new Date(ts + TZ_OFFSET_MIN * 60_000).toISOString().slice(0, 10);
      if (!buckets.has(day)) buckets.set(day, []);
      buckets.get(day).push({ ts, lat: Number(p.lat), lon: Number(p.lon) });
    }
    return {
      days: [...buckets.entries()]
        .map(([date, pts]) => ({ date, points: pts.length, distance: Math.round(trackDistance(pts)) }))
        .sort((a, b) => a.date.localeCompare(b.date)),
    };
  }

  async function updateDevice(ctx) {
    const user = await authUser(ctx.req);
    requireAdmin(user);
    const id = ctx.params.id;
    if (!(await db.first('tl_devices', { id: db.eq(id) }))) throw new HttpError(404, '设备不存在');
    const body = ctx.body;
    const name = clampStr(body.name, 40);
    const note = clampStr(body.note, 200);
    const patch = {};
    if (name) patch.name = name;
    if (note) patch.note = note;
    if (Object.keys(patch).length) await db.update('tl_devices', patch, { id: db.eq(id) });
    return { ok: true };
  }

  async function deleteDevice(ctx) {
    const user = await authUser(ctx.req);
    requireAdmin(user);
    const id = ctx.params.id;
    await db.remove('tl_acl', { device_id: db.eq(id) });
    const removed = await db.remove('tl_devices', { id: db.eq(id) });
    if (!removed?.length) throw new HttpError(404, '设备不存在');
    return { ok: true };
  }

  /** App1 上报：批量点位 + 心跳（PG 函数内原子完成） */
  async function ingestPoints(ctx) {
    requireDeviceKey(ctx.req);
    const body = await readJson(ctx.req, 2 * 1024 * 1024);
    const deviceId = clampStr(body.deviceId ?? ctx.req.headers['x-device-id'], 64);
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(deviceId)) throw new HttpError(400, '设备 ID 不合法');
    const name = clampStr(body.name ?? ctx.req.headers['x-device-name'], 40, '未命名设备');
    const model = clampStr(body.model, 60);
    const battery = Number.isFinite(Number(body.battery)) ? Math.round(Number(body.battery)) : null;
    const now = Date.now();

    const rawPoints = Array.isArray(body.points) ? body.points : [];
    const clean = [];
    for (const p of rawPoints.slice(0, 2000)) {
      const ts = toInt(p.ts ?? p.t, null);
      const lat = Number(p.lat);
      const lon = Number(p.lon);
      if (ts === null || ts < 1_000_000_000_000 || Math.abs(now - ts) > 400 * DAY_MS) continue;
      if (!Number.isFinite(lat) || lat < -90 || lat > 90) continue;
      if (!Number.isFinite(lon) || lon < -180 || lon > 180) continue;
      clean.push({
        ts,
        lat,
        lon,
        acc: Number.isFinite(Number(p.acc)) ? Number(p.acc) : null,
        spd: Number.isFinite(Number(p.spd)) ? Number(p.spd) : null,
        brg: Number.isFinite(Number(p.brg)) ? Number(p.brg) : null,
        alt: Number.isFinite(Number(p.alt)) ? Number(p.alt) : null,
        bat: Number.isFinite(Number(p.bat)) ? Math.round(Number(p.bat)) : battery,
      });
    }

    const result = await db.rpc('tl_ingest_points', {
      p_device_id: deviceId,
      p_name: name,
      p_model: model,
      p_battery: battery,
      p_now: now,
      p_points: clean,
    });

    return {
      ok: true,
      accepted: Number(result?.inserted ?? 0),
      received: rawPoints.length,
      serverTime: now,
    };
  }

  /** App1 连接测试 */
  async function devicePing(ctx) {
    requireDeviceKey(ctx.req);
    const deviceId = clampStr(ctx.req.headers['x-device-id'], 64);
    const device = deviceId ? await db.first('tl_devices', { id: db.eq(deviceId) }) : null;
    return {
      ok: true,
      serverTime: Date.now(),
      deviceName: device?.name ?? null,
      message: '连接正常，上传密钥有效',
    };
  }

  /** App1 拉取下发配置（X-Upload-Key + X-Device-Id） */
  async function deviceGetConfig(ctx) {
    requireDeviceKey(ctx.req);
    const deviceId = clampStr(ctx.req.headers['x-device-id'], 64);
    const row = deviceId ? await db.first('tl_device_config', { device_id: db.eq(deviceId) }) : null;
    const config = rowToConfig(row) ?? sanitizeConfig();
    return { ok: true, config, updatedAt: row ? Number(row.updated_at) : null, serverTime: Date.now() };
  }

  /** App2 读取某设备配置（可见设备即可读） */
  async function getDeviceConfig(ctx) {
    const user = await authUser(ctx.req);
    const id = ctx.params.id;
    await assertDeviceVisible(user, id);
    if (!(await db.first('tl_devices', { id: db.eq(id) }))) throw new HttpError(404, '设备不存在');
    const row = await db.first('tl_device_config', { device_id: db.eq(id) });
    return { config: rowToConfig(row) ?? sanitizeConfig(), updatedAt: row ? Number(row.updated_at) : null };
  }

  /** App2 下发配置（管理员） */
  async function putDeviceConfig(ctx) {
    const user = await authUser(ctx.req);
    requireAdmin(user);
    const id = ctx.params.id;
    if (!(await db.first('tl_devices', { id: db.eq(id) }))) throw new HttpError(404, '设备不存在');
    const config = sanitizeConfig(ctx.body);
    const now = Date.now();
    const existing = await db.first('tl_device_config', { device_id: db.eq(id) }, 'device_id');
    if (existing) {
      await db.update('tl_device_config', { ...configToRow(config), updated_at: now }, { device_id: db.eq(id) });
    } else {
      await db.insert('tl_device_config', { device_id: id, ...configToRow(config), updated_at: now });
    }
    return { ok: true, config, updatedAt: now };
  }

  async function listUsers(ctx) {
    const user = await authUser(ctx.req);
    requireAdmin(user);
    const users = await db.select('tl_users', { order: 'id.asc' });
    const acl = await db.select('tl_acl', { columns: 'user_id,device_id' });
    return {
      users: (users ?? []).map((r) => ({
        ...rowToUser(r),
        deviceIds: (acl ?? []).filter((a) => Number(a.user_id) === Number(r.id)).map((a) => a.device_id),
      })),
    };
  }

  async function setAcl(userId, deviceIds) {
    if (!Array.isArray(deviceIds)) return;
    await db.remove('tl_acl', { user_id: db.eq(userId) });
    const valid = [];
    for (const id of deviceIds.slice(0, 500)) {
      if (typeof id !== 'string') continue;
      if (await db.first('tl_devices', { id: db.eq(id) }, 'id')) valid.push({ user_id: userId, device_id: id });
    }
    if (valid.length) await db.insert('tl_acl', valid, { ignoreDuplicates: true });
  }

  async function createUser(ctx) {
    const user = await authUser(ctx.req);
    requireAdmin(user);
    const body = ctx.body;
    const username = clampStr(body.username, 40);
    const password = String(body.password ?? '');
    const role = body.role === 'admin' ? 'admin' : 'viewer';
    if (!/^[\w.@-]{3,40}$/.test(username)) throw new HttpError(400, '用户名需 3-40 位字母数字');
    if (password.length < 6) throw new HttpError(400, '密码至少 6 位');
    if (await db.first('tl_users', { username: db.eq(username) })) throw new HttpError(409, '用户名已存在');
    const rows = await db.insert('tl_users', {
      username,
      pass: hashPassword(password),
      role,
      created_at: Date.now(),
    });
    const id = Number(rows?.[0]?.id);
    await setAcl(id, body.deviceIds ?? []);
    const created = await db.first('tl_users', { id: db.eq(id) });
    return { user: rowToUser(created) };
  }

  async function updateUser(ctx) {
    const me_ = await authUser(ctx.req);
    requireAdmin(me_);
    const id = toInt(ctx.params.id, null);
    const target = id ? await db.first('tl_users', { id: db.eq(id) }) : null;
    if (!target) throw new HttpError(404, '账号不存在');
    const body = ctx.body;
    if (body.role && body.role !== target.role) {
      if (target.role === 'admin') {
        const admins = await db.count('tl_users', { role: db.eq('admin') });
        if (admins <= 1) throw new HttpError(400, '至少保留一个管理员');
      }
      await db.update('tl_users', { role: body.role === 'admin' ? 'admin' : 'viewer' }, { id: db.eq(id) });
    }
    if (body.password) {
      if (String(body.password).length < 6) throw new HttpError(400, '密码至少 6 位');
      await db.update('tl_users', { pass: hashPassword(String(body.password)) }, { id: db.eq(id) });
    }
    if (body.deviceIds !== undefined) await setAcl(id, body.deviceIds);
    const fresh = await db.first('tl_users', { id: db.eq(id) });
    return { user: rowToUser(fresh) };
  }

  async function deleteUser(ctx) {
    const me_ = await authUser(ctx.req);
    requireAdmin(me_);
    const id = toInt(ctx.params.id, null);
    const target = id ? await db.first('tl_users', { id: db.eq(id) }) : null;
    if (!target) throw new HttpError(404, '账号不存在');
    if (Number(target.id) === me_.id) throw new HttpError(400, '不能删除当前登录账号');
    if (target.role === 'admin') {
      const admins = await db.count('tl_users', { role: db.eq('admin') });
      if (admins <= 1) throw new HttpError(400, '至少保留一个管理员');
    }
    await db.remove('tl_acl', { user_id: db.eq(id) });
    await db.remove('tl_users', { id: db.eq(id) });
    return { ok: true };
  }

  async function getSettings(ctx) {
    const user = await authUser(ctx.req);
    requireAdmin(user);
    return {
      uploadKey: await currentUploadKey(),
      retentionDays: config.retentionDays,
      tokenTtlHours: Math.round(config.tokenTtlMs / 3600_000),
      port: null,
      backend: 'cloudbase-pg',
    };
  }

  /** 登录用户可修改自己的密码 */
  async function changeOwnPassword(ctx) {
    const user = await authUser(ctx.req);
    const body = ctx.body;
    const row = await db.first('tl_users', { id: db.eq(user.id) });
    if (!verifyPassword(String(body.oldPassword ?? ''), row.pass)) {
      throw new HttpError(400, '原密码不正确');
    }
    if (String(body.newPassword ?? '').length < 6) throw new HttpError(400, '新密码至少 6 位');
    await db.update('tl_users', { pass: hashPassword(String(body.newPassword)) }, { id: db.eq(user.id) });
    return { ok: true };
  }

  async function health() {
    return { ok: true, serverTime: Date.now(), backend: 'cloudbase-pg', version: 2 };
  }

  const routes = [
    ['GET', /^\/api\/health$/, health],
    ['POST', /^\/api\/auth\/login$/, login, { body: false }],
    ['GET', /^\/api\/auth\/me$/, me],
    ['POST', /^\/api\/auth\/password$/, changeOwnPassword],
    ['GET', /^\/api\/devices$/, listDevices],
    ['GET', /^\/api\/devices\/(?<id>[A-Za-z0-9_-]{8,64})\/track$/, getTrack],
    ['GET', /^\/api\/devices\/(?<id>[A-Za-z0-9_-]{8,64})\/daily$/, dailyStats],
    ['PATCH', /^\/api\/devices\/(?<id>[A-Za-z0-9_-]{8,64})$/, updateDevice],
    ['DELETE', /^\/api\/devices\/(?<id>[A-Za-z0-9_-]{8,64})$/, deleteDevice],
    ['POST', /^\/api\/device\/points$/, ingestPoints, { body: false }],
    ['GET', /^\/api\/device\/ping$/, devicePing],
    ['GET', /^\/api\/device\/config$/, deviceGetConfig],
    ['GET', /^\/api\/devices\/(?<id>[A-Za-z0-9_-]{8,64})\/config$/, getDeviceConfig],
    ['PUT', /^\/api\/devices\/(?<id>[A-Za-z0-9_-]{8,64})\/config$/, putDeviceConfig],
    ['GET', /^\/api\/users$/, listUsers],
    ['POST', /^\/api\/users$/, createUser],
    ['PATCH', /^\/api\/users\/(?<id>\d+)$/, updateUser],
    ['DELETE', /^\/api\/users\/(?<id>\d+)$/, deleteUser],
    ['GET', /^\/api\/settings$/, getSettings],
  ];

  return async function handle(ctx) {
    for (const [method, pattern, fn, opts] of routes) {
      const m = pattern.exec(ctx.path);
      if (!m) continue;
      if (method !== ctx.req.method) continue;
      ctx.params = m.groups ?? {};
      if (opts?.body !== false) ctx.body = await readJson(ctx.req);
      const result = await fn(ctx);
      if (result !== undefined) json(ctx.res, 200, result);
      return true;
    }
    return false;
  };
}

module.exports = { createRouter, haversine };
