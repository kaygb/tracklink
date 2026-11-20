/**
 * 服务端冒烟测试：node test/smoke.mjs [baseUrl] [uploadKey] [adminUser] [adminPass]
 * 无第三方依赖，直接用内置 fetch。
 */
const base = process.argv[2] ?? 'http://127.0.0.1:18080';
const uploadKey = process.argv[3] ?? 'testkey123';
const adminUser = process.argv[4] ?? 'admin';
const adminPass = process.argv[5] ?? 'admin12345';

let pass = 0;
let fail = 0;

function check(name, cond, extra) {
  if (cond) {
    pass++;
    console.log(`  ✅ ${name}`);
  } else {
    fail++;
    console.log(`  ❌ ${name}`, extra ?? '');
  }
}

async function call(path, { method = 'GET', body, token, headers } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(headers ?? {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  return { status: res.status, data };
}

const deviceId = 'smoke-device-0001';
const now = Date.now();

console.log(`\n目标服务端：${base}\n`);

const health = await call('/api/health');
check('健康检查', health.status === 200 && health.data.ok === true);

const badLogin = await call('/api/auth/login', {
  method: 'POST',
  body: { username: adminUser, password: 'wrong-password' },
});
check('错误密码被拒绝', badLogin.status === 401, badLogin.data);

const login = await call('/api/auth/login', {
  method: 'POST',
  body: { username: adminUser, password: adminPass },
});
check('管理员登录', login.status === 200 && !!login.data.token, login.data);
const token = login.data?.token;
check('返回用户角色为 admin', login.data?.user?.role === 'admin');

const noAuth = await call('/api/devices');
check('未登录访问设备列表被拒绝', noAuth.status === 401);

const badKey = await call('/api/device/ping', { headers: { 'X-Upload-Key': 'wrong' } });
check('错误上传密钥被拒绝', badKey.status === 401);

const ping = await call('/api/device/ping', {
  headers: { 'X-Upload-Key': uploadKey, 'X-Device-Id': deviceId },
});
check('设备 ping（密钥校验）', ping.status === 200 && ping.data.ok === true, ping.data);

const points = Array.from({ length: 12 }, (_, i) => ({
  ts: now - (11 - i) * 60_000,
  lat: 30.5 + i * 0.001,
  lon: 114.3 + i * 0.0012,
  acc: 8,
  spd: 12.5,
  brg: 90,
  alt: 30,
  bat: 88,
}));

const ingest = await call('/api/device/points', {
  method: 'POST',
  headers: { 'X-Upload-Key': uploadKey },
  body: { deviceId, name: '冒烟测试机', model: 'Pytest', battery: 87, points },
});
check('轨迹批量上报', ingest.status === 200 && ingest.data.accepted === 12, ingest.data);

const dupe = await call('/api/device/points', {
  method: 'POST',
  headers: { 'X-Upload-Key': uploadKey },
  body: { deviceId, name: '冒烟测试机', points },
});
check('重复点位幂等（去重）', dupe.status === 200 && dupe.data.accepted === 0, dupe.data);

const badPoint = await call('/api/device/points', {
  method: 'POST',
  headers: { 'X-Upload-Key': uploadKey },
  body: { deviceId, name: '冒烟测试机', points: [{ ts: now, lat: 999, lon: 0 }] },
});
check('非法经纬度被丢弃', badPoint.status === 200 && badPoint.data.accepted === 0, badPoint.data);

const devices = await call('/api/devices', { token });
check('设备列表可见该设备', devices.status === 200 && devices.data.devices.some((d) => d.id === deviceId));
const dev = devices.data?.devices?.find((d) => d.id === deviceId);
check('设备显示在线状态', dev?.online === true);
check('今日里程已统计', (dev?.distanceToday ?? 0) > 0, dev?.distanceToday);

const track = await call(`/api/devices/${deviceId}/track?from=${now - 3600_000}&to=${now + 60_000}`, { token });
check('轨迹查询返回点位', track.status === 200 && track.data.points.length === 12, track.data?.stats);
check('轨迹里程大于 0', (track.data?.stats?.distance ?? 0) > 600, track.data?.stats);

const daily = await call(`/api/devices/${deviceId}/daily`, { token });
check('按天统计', daily.status === 200 && daily.data.days.length === 1, daily.data);

const createUser = await call('/api/users', {
  method: 'POST',
  token,
  body: { username: 'smoke_viewer', password: 'viewer123', role: 'viewer' },
});
const viewerId = createUser.data?.user?.id;
check('创建只读账号', createUser.status === 200 && !!viewerId, createUser.data);

const viewerLogin = await call('/api/auth/login', {
  method: 'POST',
  body: { username: 'smoke_viewer', password: 'viewer123' },
});
check('只读账号登录', viewerLogin.status === 200);
const viewerToken = viewerLogin.data?.token;

const viewerDevices = await call('/api/devices', { token: viewerToken });
check('未授权时只读账号看不到设备', viewerDevices.data?.devices?.length === 0, viewerDevices.data);

const forbidden = await call('/api/users', { token: viewerToken });
check('只读账号无权管理账号', forbidden.status === 403);

await call('/api/users/' + viewerId, {
  method: 'PATCH',
  token,
  body: { deviceIds: [deviceId] },
});
const viewerDevices2 = await call('/api/devices', { token: viewerToken });
check('授权后只读账号可见设备', viewerDevices2.data?.devices?.length === 1, viewerDevices2.data);

const rename = await call(`/api/devices/${deviceId}`, {
  method: 'PATCH',
  token,
  body: { name: '改过名的设备' },
});
check('管理员重命名设备', rename.status === 200);
const devices3 = await call('/api/devices', { token });
check('重命名已生效', devices3.data.devices.find((d) => d.id === deviceId)?.name === '改过名的设备');

const settings = await call('/api/settings', { token });
check('读取上传密钥', settings.data?.uploadKey === uploadKey, settings.data);

const delUser = await call('/api/users/' + viewerId, { method: 'DELETE', token });
check('删除账号', delUser.status === 200);

const page = await fetch(base + '/');
const html = await page.text();
check('Web 控制台可访问', page.status === 200 && html.includes('TrackLink'));

console.log(`\n结果：${pass} 通过 / ${fail} 失败\n`);
process.exit(fail ? 1 : 0);
