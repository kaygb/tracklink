import {
  HttpError,
  clampStr,
  haversine,
  json,
  readJson,
  startOfDay,
  toInt,
  trackDistance,
} from './util.js';
import { hashPassword, randomKey, safeEqual, signToken, verifyPassword, verifyToken } from './auth.js';

const TZ_OFFSET_MIN = 480; // 统一按 UTC+8 切天
const DAY_MS = 86400_000;

/** 采样/省电配置：字段、边界与默认值（App1 的全部可调项），与 cloudbase 版保持一致 */
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
  return { id: row.id, username: row.username, role: row.role, createdAt: row.created_at };
}

function deviceView(row, distanceToday, pointCountToday) {
  const online = !!row.last_seen && Date.now() - row.last_seen < 15 * 60_000;
  return {
    id: row.id,
    name: row.name,
    model: row.model ?? '',
    note: row.note ?? '',
    createdAt: row.created_at,
    lastSeen: row.last_seen,
    battery: row.battery,
    online,
    lastPoint: row.last_lat == null
      ? null
      : { lat: row.last_lat, lon: row.last_lon, ts: row.last_ts, acc: row.last_acc },
    distanceToday,
    pointCountToday,
  };
}

export function createRouter({ db, config, loginGuard }) {
  const q = {
    userById: db.prepare('SELECT * FROM users WHERE id = ?'),
    userByName: db.prepare('SELECT * FROM users WHERE username = ?'),
    devices: db.prepare('SELECT * FROM devices ORDER BY last_seen DESC NULLS LAST, created_at DESC'),
    device: db.prepare('SELECT * FROM devices WHERE id = ?'),
    aclOfUser: db.prepare('SELECT device_id FROM acl WHERE user_id = ?'),
    aclAll: db.prepare('SELECT user_id, device_id FROM acl'),
    upsertDevice: db.prepare(`
      INSERT INTO devices (id, name, model, created_at) VALUES (?, ?, ?, ?)
      ON CONFLICT(id) DO NOTHING`),
    touchDevice: db.prepare(`
      UPDATE devices SET last_seen = ?, battery = COALESCE(?, battery),
        last_lat = COALESCE(?, last_lat), last_lon = COALESCE(?, last_lon),
        last_ts = COALESCE(?, last_ts), last_acc = COALESCE(?, last_acc),
        name = CASE WHEN ? <> '' THEN ? ELSE name END
      WHERE id = ?`),
    insertPoint: db.prepare(`
      INSERT OR IGNORE INTO points (device_id, ts, lat, lon, acc, spd, brg, alt, bat)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`),
    pointsBetween: db.prepare(`
      SELECT ts, lat, lon, acc, spd FROM points
      WHERE device_id = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?`),
    deviceConfig: db.prepare('SELECT * FROM device_config WHERE device_id = ?'),
    upsertDeviceConfig: db.prepare(`
      INSERT INTO device_config
        (device_id, sample_interval_s, min_distance_m, accuracy_max_m, sleep_after_min, batch_seconds, heartbeat_s, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(device_id) DO UPDATE SET
        sample_interval_s = excluded.sample_interval_s,
        min_distance_m = excluded.min_distance_m,
        accuracy_max_m = excluded.accuracy_max_m,
        sleep_after_min = excluded.sleep_after_min,
        batch_seconds = excluded.batch_seconds,
        heartbeat_s = excluded.heartbeat_s,
        updated_at = excluded.updated_at`),
  };

  function authUser(req) {
    const header = String(req.headers.authorization ?? '');
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    const payload = verifyToken(token, config.tokenSecret);
    if (!payload) throw new HttpError(401, '登录已失效，请重新登录');
    const row = q.userById.get(payload.uid);
    if (!row) throw new HttpError(401, '账号不存在');
    return rowToUser(row);
  }

  function requireAdmin(user) {
    if (user.role !== 'admin') throw new HttpError(403, '需要管理员权限');
  }

  function visibleDeviceIds(user) {
    if (user.role === 'admin') return null; // null = 全部
    return new Set(q.aclOfUser.all(user.id).map((r) => r.device_id));
  }

  function assertDeviceVisible(user, deviceId) {
    const ids = visibleDeviceIds(user);
    if (ids === null) return;
    if (!ids.has(deviceId)) throw new HttpError(403, '无权查看该设备');
  }

  function requireDeviceKey(req) {
    const key = String(req.headers['x-upload-key'] ?? '');
    if (!safeEqual(key, config.uploadKey)) throw new HttpError(401, '上传密钥不正确');
  }

  async function login(ctx) {
    const ip = ctx.ip;
    if (!loginGuard.check(ip)) throw new HttpError(429, '尝试过于频繁，请 5 分钟后再试');
    const body = await readJson(ctx.req);
    const username = clampStr(body.username, 40);
    const password = String(body.password ?? '');
    const row = q.userByName.get(username);
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

  function me(ctx) {
    const user = authUser(ctx.req);
    return { user };
  }

  function listDevices(ctx) {
    const user = authUser(ctx.req);
    const ids = visibleDeviceIds(user);
    const dayStart = startOfDay(Date.now(), TZ_OFFSET_MIN);
    const rows = q.devices.all().filter((r) => ids === null || ids.has(r.id));
    const stats = db.prepare(
      'SELECT ts, lat, lon FROM points WHERE device_id = ? AND ts >= ? ORDER BY ts ASC',
    );
    return {
      serverTime: Date.now(),
      devices: rows.map((r) => {
        const pts = stats.all(r.id, dayStart);
        return deviceView(r, Math.round(trackDistance(pts)), pts.length);
      }),
    };
  }

  function getTrack(ctx) {
    const user = authUser(ctx.req);
    const id = ctx.params.id;
    assertDeviceVisible(user, id);
    const device = q.device.get(id);
    if (!device) throw new HttpError(404, '设备不存在');
    const to = toInt(ctx.url.searchParams.get('to'), Date.now());
    const from = toInt(ctx.url.searchParams.get('from'), to - DAY_MS);
    const limit = Math.min(Math.max(toInt(ctx.url.searchParams.get('limit'), 20000), 10), 50000);
    if (from > to) throw new HttpError(400, '时间范围不合法');
    const points = q.pointsBetween.all(id, from, to, limit).map((p) => [
      p.ts,
      Number(p.lat.toFixed(6)),
      Number(p.lon.toFixed(6)),
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

  function dailyStats(ctx) {
    const user = authUser(ctx.req);
    const id = ctx.params.id;
    assertDeviceVisible(user, id);
    const to = toInt(ctx.url.searchParams.get('to'), Date.now());
    const from = toInt(ctx.url.searchParams.get('from'), to - 7 * DAY_MS);
    const rows = q.pointsBetween.all(id, from, to, 200000).map((p) => ({ ts: p.ts, lat: p.lat, lon: p.lon }));
    const buckets = new Map();
    for (const p of rows) {
      const day = new Date(p.ts + TZ_OFFSET_MIN * 60_000).toISOString().slice(0, 10);
      if (!buckets.has(day)) buckets.set(day, []);
      buckets.get(day).push(p);
    }
    return {
      days: [...buckets.entries()]
        .map(([date, pts]) => ({ date, points: pts.length, distance: Math.round(trackDistance(pts)) }))
        .sort((a, b) => a.date.localeCompare(b.date)),
    };
  }

  function updateDevice(ctx) {
    const user = authUser(ctx.req);
    requireAdmin(user);
    const id = ctx.params.id;
    if (!q.device.get(id)) throw new HttpError(404, '设备不存在');
    const body = ctx.body;
    const name = clampStr(body.name, 40);
    const note = clampStr(body.note, 200);
    db.prepare('UPDATE devices SET name = COALESCE(?, name), note = COALESCE(?, note) WHERE id = ?')
      .run(name || null, note || null, id);
    return { ok: true };
  }

  function deleteDevice(ctx) {
    const user = authUser(ctx.req);
    requireAdmin(user);
    const r = db.prepare('DELETE FROM devices WHERE id = ?').run(ctx.params.id);
    if (!Number(r.changes)) throw new HttpError(404, '设备不存在');
    db.prepare('DELETE FROM acl WHERE device_id = ?').run(ctx.params.id);
    return { ok: true };
  }

  /** App1 上报：批量点位 + 心跳 */
  async function ingestPoints(ctx) {
    requireDeviceKey(ctx.req);
    const body = await readJson(ctx.req, 2 * 1024 * 1024);
    const deviceId = clampStr(body.deviceId ?? ctx.req.headers['x-device-id'], 64);
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(deviceId)) throw new HttpError(400, '设备 ID 不合法');
    const name = clampStr(body.name ?? ctx.req.headers['x-device-name'], 40, '未命名设备');
    const model = clampStr(body.model, 60);
    const battery = Number.isFinite(Number(body.battery)) ? Math.round(Number(body.battery)) : null;
    const now = Date.now();

    q.upsertDevice.run(deviceId, name || deviceId, model || null, now);

    const rawPoints = Array.isArray(body.points) ? body.points : [];
    let inserted = 0;
    let newest = null;
    db.exec('BEGIN');
    try {
      for (const p of rawPoints.slice(0, 2000)) {
        const ts = toInt(p.ts ?? p.t, null);
        const lat = Number(p.lat);
        const lon = Number(p.lon);
        if (ts === null || ts < 1_000_000_000_000 || Math.abs(now - ts) > 400 * DAY_MS) continue;
        if (!Number.isFinite(lat) || lat < -90 || lat > 90) continue;
        if (!Number.isFinite(lon) || lon < -180 || lon > 180) continue;
        const acc = Number.isFinite(Number(p.acc)) ? Number(p.acc) : null;
        const spd = Number.isFinite(Number(p.spd)) ? Number(p.spd) : null;
        const brg = Number.isFinite(Number(p.brg)) ? Number(p.brg) : null;
        const alt = Number.isFinite(Number(p.alt)) ? Number(p.alt) : null;
        const bat = Number.isFinite(Number(p.bat)) ? Math.round(Number(p.bat)) : battery;
        const r = q.insertPoint.run(deviceId, ts, lat, lon, acc, spd, brg, alt, bat);
        if (Number(r.changes)) inserted += 1;
        if (!newest || ts >= newest.ts) newest = { ts, lat, lon, acc, spd };
      }
      q.touchDevice.run(
        now,
        battery,
        newest?.lat ?? null,
        newest?.lon ?? null,
        newest?.ts ?? null,
        newest?.acc ?? null,
        name || '',
        name || '',
        deviceId,
      );
      db.exec('COMMIT');
    } catch (e) {
      db.exec('ROLLBACK');
      throw e;
    }
    return { ok: true, accepted: inserted, received: rawPoints.length, serverTime: now };
  }

  /** App1 连接测试 */
  function devicePing(ctx) {
    requireDeviceKey(ctx.req);
    const deviceId = clampStr(ctx.req.headers['x-device-id'], 64);
    const device = deviceId ? q.device.get(deviceId) : null;
    return {
      ok: true,
      serverTime: Date.now(),
      deviceName: device?.name ?? null,
      message: '连接正常，上传密钥有效',
    };
  }

  /** App1 拉取下发配置 */
  function deviceGetConfig(ctx) {
    requireDeviceKey(ctx.req);
    const deviceId = clampStr(ctx.req.headers['x-device-id'], 64);
    const row = deviceId ? q.deviceConfig.get(deviceId) : null;
    return {
      ok: true,
      config: rowToConfig(row) ?? sanitizeConfig(),
      updatedAt: row?.updated_at ?? null,
      serverTime: Date.now(),
    };
  }

  /** App2 读取设备配置（可见即可读） */
  function getDeviceConfig(ctx) {
    const user = authUser(ctx.req);
    const id = ctx.params.id;
    assertDeviceVisible(user, id);
    if (!q.device.get(id)) throw new HttpError(404, '设备不存在');
    const row = q.deviceConfig.get(id);
    return { config: rowToConfig(row) ?? sanitizeConfig(), updatedAt: row?.updated_at ?? null };
  }

  /** App2 下发配置（管理员） */
  function putDeviceConfig(ctx) {
    const user = authUser(ctx.req);
    requireAdmin(user);
    const id = ctx.params.id;
    if (!q.device.get(id)) throw new HttpError(404, '设备不存在');
    const config = sanitizeConfig(ctx.body);
    const now = Date.now();
    q.upsertDeviceConfig.run(
      id, config.sampleIntervalS, config.minDistanceM, config.accuracyMaxM,
      config.sleepAfterMin, config.batchSeconds, config.heartbeatS, now,
    );
    return { ok: true, config, updatedAt: now };
  }

  function listUsers(ctx) {
    const user = authUser(ctx.req);
    requireAdmin(user);
    const acl = q.aclAll.all();
    return {
      users: db.prepare('SELECT * FROM users ORDER BY id').all().map((r) => ({
        ...rowToUser(r),
        deviceIds: acl.filter((a) => a.user_id === r.id).map((a) => a.device_id),
      })),
    };
  }

  function setAcl(userId, deviceIds) {
    if (!Array.isArray(deviceIds)) return;
    db.prepare('DELETE FROM acl WHERE user_id = ?').run(userId);
    const ins = db.prepare('INSERT OR IGNORE INTO acl (user_id, device_id) VALUES (?, ?)');
    for (const id of deviceIds.slice(0, 500)) {
      if (typeof id === 'string' && q.device.get(id)) ins.run(userId, id);
    }
  }

  async function createUser(ctx) {
    const user = authUser(ctx.req);
    requireAdmin(user);
    const body = ctx.body;
    const username = clampStr(body.username, 40);
    const password = String(body.password ?? '');
    const role = body.role === 'admin' ? 'admin' : 'viewer';
    if (!/^[\w.@-]{3,40}$/.test(username)) throw new HttpError(400, '用户名需 3-40 位字母数字');
    if (password.length < 6) throw new HttpError(400, '密码至少 6 位');
    if (q.userByName.get(username)) throw new HttpError(409, '用户名已存在');
    const r = db.prepare('INSERT INTO users (username, pass, role, created_at) VALUES (?, ?, ?, ?)')
      .run(username, hashPassword(password), role, Date.now());
    const id = Number(r.lastInsertRowid);
    setAcl(id, body.deviceIds ?? []);
    return { user: rowToUser(q.userById.get(id)) };
  }

  async function updateUser(ctx) {
    const me_ = authUser(ctx.req);
    requireAdmin(me_);
    const id = toInt(ctx.params.id, null);
    const target = id ? q.userById.get(id) : null;
    if (!target) throw new HttpError(404, '账号不存在');
    const body = ctx.body;
    if (body.role && body.role !== target.role) {
      if (target.role === 'admin') {
        const admins = db.prepare("SELECT COUNT(*) AS c FROM users WHERE role='admin'").get().c;
        if (admins <= 1) throw new HttpError(400, '至少保留一个管理员');
      }
      db.prepare('UPDATE users SET role = ? WHERE id = ?').run(body.role === 'admin' ? 'admin' : 'viewer', id);
    }
    if (body.password) {
      if (String(body.password).length < 6) throw new HttpError(400, '密码至少 6 位');
      db.prepare('UPDATE users SET pass = ? WHERE id = ?').run(hashPassword(String(body.password)), id);
    }
    if (body.deviceIds !== undefined) setAcl(id, body.deviceIds);
    return { user: rowToUser(q.userById.get(id)) };
  }

  function deleteUser(ctx) {
    const me_ = authUser(ctx.req);
    requireAdmin(me_);
    const id = toInt(ctx.params.id, null);
    const target = id ? q.userById.get(id) : null;
    if (!target) throw new HttpError(404, '账号不存在');
    if (target.id === me_.id) throw new HttpError(400, '不能删除当前登录账号');
    if (target.role === 'admin') {
      const admins = db.prepare("SELECT COUNT(*) AS c FROM users WHERE role='admin'").get().c;
      if (admins <= 1) throw new HttpError(400, '至少保留一个管理员');
    }
    db.prepare('DELETE FROM users WHERE id = ?').run(id);
    return { ok: true };
  }

  function getSettings(ctx) {
    const user = authUser(ctx.req);
    requireAdmin(user);
    return {
      uploadKey: config.uploadKey,
      retentionDays: config.retentionDays,
      tokenTtlHours: Math.round(config.tokenTtlMs / 3600_000),
      port: config.port,
    };
  }

  async function changeOwnPassword(ctx) {
    const user = authUser(ctx.req);
    const body = ctx.body;
    const row = q.userById.get(user.id);
    if (!verifyPassword(String(body.oldPassword ?? ''), row.pass)) {
      throw new HttpError(400, '原密码不正确');
    }
    if (String(body.newPassword ?? '').length < 6) throw new HttpError(400, '新密码至少 6 位');
    db.prepare('UPDATE users SET pass = ? WHERE id = ?').run(hashPassword(String(body.newPassword)), user.id);
    return { ok: true };
  }

  const routes = [
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

export { haversine };
