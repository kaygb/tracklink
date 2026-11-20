/**
 * 云函数本地冒烟：直接调用 exports.main，模拟 HTTP 事件
 * 用法：TL_PG_KEY=xxx node cloudbase/test/smoke-cloud.mjs
 */
const { createRequire } = await import('node:module');
const require = createRequire(import.meta.url);

const fn = require('../functions/tracklink/index.js');

let pass = 0;
let fail = 0;

function ev(method, path, { body, headers = {}, qs = '' } = {}) {
  return {
    httpMethod: method,
    path,
    headers: { 'content-type': 'application/json', ...headers },
    body: body ? JSON.stringify(body) : '',
    isBase64Encoded: false,
    queryString: qs,
    requestContext: { path },
  };
}

async function call(method, path, opts) {
  const res = await fn.main(ev(method, path, opts));
  let data = null;
  try {
    data = res.body ? JSON.parse(res.body) : null;
  } catch {
    data = res.body;
  }
  return { status: res.statusCode, data };
}

function check(name, cond, extra) {
  if (cond) {
    pass += 1;
    console.log(`  ✅ ${name}`);
  } else {
    fail += 1;
    console.log(`  ❌ ${name}`, extra === undefined ? '' : JSON.stringify(extra).slice(0, 300));
  }
}

const UPLOAD_KEY = process.env.TL_UPLOAD_KEY || 'tracklink-upload-key';
const DEV = 'dev-cloudsmoke001';

console.log('== 1. 健康检查 ==');
{
  const r = await call('GET', '/api/health');
  check('health 200', r.status === 200 && r.data?.ok === true, r);
}

console.log('== 2. 管理员登录 ==');
let token = '';
{
  const r = await call('POST', '/api/auth/login', {
    body: { username: 'admin', password: process.env.TL_ADMIN_PASS || 'admin12345' },
  });
  token = r.data?.token || '';
  check('login 拿到 token', r.status === 200 && !!token, r);
  const bad = await call('POST', '/api/auth/login', { body: { username: 'admin', password: 'wrong' } });
  check('错误密码 401', bad.status === 401, bad);
}

const auth = { Authorization: `Bearer ${token}` };

console.log('== 3. 设备上报（RPC）==');
{
  const now = Date.now();
  const points = Array.from({ length: 30 }, (_, i) => ({
    ts: now - (29 - i) * 30000,
    lat: 30.5 + i * 0.0004,
    lon: 114.3 + i * 0.0006,
    acc: 8,
    spd: 1.2,
    brg: 90,
    alt: 20,
    bat: 76,
  }));
  const r = await call('POST', '/api/device/points', {
    headers: { 'X-Upload-Key': UPLOAD_KEY, 'X-Device-Id': DEV },
    body: { deviceId: DEV, name: '云函数测试机', model: 'SMOKE', battery: 76, points },
  });
  check('上报 200', r.status === 200 && r.data?.ok, r);
  check('接受 30 点', r.data?.accepted === 30, r.data);

  const dup = await call('POST', '/api/device/points', {
    headers: { 'X-Upload-Key': UPLOAD_KEY },
    body: { deviceId: DEV, name: '云函数测试机', battery: 76, points },
  });
  check('重复上报被去重', dup.data?.accepted === 0, dup.data);

  const badKey = await call('POST', '/api/device/points', {
    headers: { 'X-Upload-Key': 'wrong-key' },
    body: { deviceId: DEV, points: [] },
  });
  check('错误上传密钥 401', badKey.status === 401, badKey);
}

console.log('== 4. Ping ==');
{
  const r = await call('GET', '/api/device/ping', {
    headers: { 'X-Upload-Key': UPLOAD_KEY, 'X-Device-Id': DEV },
  });
  check('ping 200 且回设备名', r.status === 200 && r.data?.deviceName === '云函数测试机', r);
}

console.log('== 5. 设备列表 ==');
{
  const r = await call('GET', '/api/devices', { headers: auth });
  const d = (r.data?.devices || []).find((x) => x.id === DEV);
  check('列表含测试设备', r.status === 200 && !!d, r.data);
  check('在线状态', d?.online === true, d);
  check('今日点数 30', d?.pointCountToday === 30, d);
  check('今日里程 > 1000m', (d?.distanceToday || 0) > 1000, d);
}

console.log('== 6. 轨迹查询 ==');
{
  const now = Date.now();
  const r = await call('GET', `/api/devices/${DEV}/track`, {
    headers: auth,
    qs: `from=${now - 3600_000}&to=${now + 60000}`,
  });
  check('轨迹 200', r.status === 200, r);
  check('点数 30', r.data?.stats?.points === 30, r.data?.stats);
  check('距离合理', (r.data?.stats?.distance || 0) > 1000, r.data?.stats);
  const denied = await call('GET', '/api/devices/dev-unknown999/track', { headers: auth });
  check('未知设备 404', denied.status === 404, denied);
  const noAuth = await call('GET', '/api/devices', {});
  check('无 token 401', noAuth.status === 401, noAuth);
}

console.log('== 7. 按日统计 ==');
{
  const r = await call('GET', `/api/devices/${DEV}/daily`, { headers: auth });
  check('daily 200 且至少一天', r.status === 200 && (r.data?.days?.length || 0) >= 1, r.data);
}

console.log('== 8. 账号与权限 ==');
let viewerId = 0;
{
  const uname = `smoke_${Date.now().toString().slice(-6)}`;
  const r = await call('POST', '/api/users', {
    headers: auth,
    body: { username: uname, password: 'viewer123', role: 'viewer', deviceIds: [DEV] },
  });
  viewerId = r.data?.user?.id || 0;
  check('创建只读账号', r.status === 200 && viewerId > 0, r);

  const list = await call('GET', '/api/users', { headers: auth });
  const created = (list.data?.users || []).find((u) => u.id === viewerId);
  check('只读账号已授权设备', created?.deviceIds?.includes(DEV), created);

  const login2 = await call('POST', '/api/auth/login', { body: { username: uname, password: 'viewer123' } });
  const t2 = login2.data?.token;
  check('只读账号可登录', login2.status === 200 && !!t2, login2);

  const restricted = await call('GET', '/api/users', { headers: { Authorization: `Bearer ${t2}` } });
  check('只读账号访问用户管理 403', restricted.status === 403, restricted);

  const settingsDenied = await call('GET', '/api/settings', { headers: { Authorization: `Bearer ${t2}` } });
  check('只读账号访问设置 403', settingsDenied.status === 403, settingsDenied);

  const del = await call('DELETE', `/api/users/${viewerId}`, { headers: auth });
  check('删除测试账号', del.status === 200, del);
}

console.log('== 9. 设置与密钥 ==');
{
  const s = await call('GET', '/api/settings', { headers: auth });
  check('设置含上传密钥', s.status === 200 && !!s.data?.uploadKey, s.data);
  check('后端标识为 cloudbase-pg', s.data?.backend === 'cloudbase-pg', s.data);
}

console.log('== 10. 清理测试设备 ==');
{
  const r = await call('DELETE', `/api/devices/${DEV}`, { headers: auth });
  check('删除测试设备', r.status === 200, r);
  const after = await call('GET', '/api/devices', { headers: auth });
  check('设备已移除', !(after.data?.devices || []).some((d) => d.id === DEV), after.data);
  const points = await call('GET', `/api/devices/${DEV}/track`, { headers: auth });
  check('设备不存在后 404', points.status === 404, points);
}

console.log(`\n===== 结果：通过 ${pass} / 失败 ${fail} =====`);
process.exit(fail === 0 ? 0 : 1);
