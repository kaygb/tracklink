/**
 * 云端 HTTP 冒烟：直接打线上网关地址
 * 用法：node cloudbase/test/smoke-remote.mjs <baseUrl> <uploadKey> <adminUser> <adminPass>
 */
const [base, uploadKey, adminUser, adminPass] = process.argv.slice(2);
if (!base) {
  console.error('用法: node smoke-remote.mjs <baseUrl> <uploadKey> <adminUser> <adminPass>');
  process.exit(1);
}

let pass = 0;
let fail = 0;

function check(name, cond, extra) {
  if (cond) {
    pass += 1;
    console.log(`  ✅ ${name}`);
  } else {
    fail += 1;
    console.log(`  ❌ ${name}`, extra === undefined ? '' : JSON.stringify(extra).slice(0, 300));
  }
}

async function req(method, path, { body, headers = {}, qs = '' } = {}) {
  const url = `${base}${path}${qs ? `?${qs}` : ''}`;
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
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

const DEV = `dev-remote${Date.now().toString().slice(-6)}`;

console.log(`== 云端冒烟 ${base} ==`);

console.log('-- 健康检查 --');
{
  const r = await req('GET', '/health');
  check('health 200', r.status === 200 && r.data?.ok, r);
}

console.log('-- 登录 --');
let token = '';
{
  const r = await req('POST', '/auth/login', { body: { username: adminUser, password: adminPass } });
  token = r.data?.token || '';
  check('管理员登录成功', r.status === 200 && !!token, r);
  const bad = await req('POST', '/auth/login', { body: { username: adminUser, password: 'nope' } });
  check('错误密码被拒', bad.status === 401, bad);
}
const auth = { Authorization: `Bearer ${token}` };

console.log('-- 设备上报 --');
{
  const now = Date.now();
  const points = Array.from({ length: 40 }, (_, i) => ({
    ts: now - (39 - i) * 30000,
    lat: 30.5 + i * 0.00035,
    lon: 114.3 + i * 0.00055,
    acc: 7,
    spd: 1.4,
    brg: 88,
    alt: 24,
    bat: 82,
  }));
  const r = await req('POST', '/device/points', {
    headers: { 'X-Upload-Key': uploadKey, 'X-Device-Id': DEV },
    body: { deviceId: DEV, name: '云端真机测试', model: 'RemoteSmoke', battery: 82, points },
  });
  check('上报 200 且 40 点入库', r.status === 200 && r.data?.accepted === 40, r);

  const dup = await req('POST', '/device/points', {
    headers: { 'X-Upload-Key': uploadKey },
    body: { deviceId: DEV, name: '云端真机测试', battery: 82, points },
  });
  check('重复批次去重', dup.data?.accepted === 0, dup.data);

  const bad = await req('POST', '/device/points', {
    headers: { 'X-Upload-Key': 'bad' },
    body: { deviceId: DEV, points: [] },
  });
  check('错误上传密钥 401', bad.status === 401, bad);
}

console.log('-- 设备列表与轨迹 --');
{
  const list = await req('GET', '/devices', { headers: auth });
  const d = (list.data?.devices || []).find((x) => x.id === DEV);
  check('设备出现在列表', !!d, list.data);
  check('在线', d?.online === true, d);
  check('今日 40 点', d?.pointCountToday === 40, d);
  check('里程 > 1000m', (d?.distanceToday || 0) > 1000, d?.distanceToday);

  const now = Date.now();
  const track = await req('GET', `/devices/${DEV}/track`, {
    headers: auth,
    qs: `from=${now - 3600_000}&to=${now + 120000}`,
  });
  check('轨迹返回 40 点', track.data?.stats?.points === 40, track.data?.stats);
  check('轨迹距离合理', (track.data?.stats?.distance || 0) > 1000, track.data?.stats);
  const first = track.data?.points?.[0];
  check('首点为数值坐标', Number.isFinite(first?.[1]) && Number.isFinite(first?.[2]), first);

  const daily = await req('GET', `/devices/${DEV}/daily`, { headers: auth });
  check('按日统计有数据', (daily.data?.days?.length || 0) >= 1, daily.data);
}

console.log('-- 权限 --');
{
  const noAuth = await req('GET', '/devices');
  check('无 token 401', noAuth.status === 401, noAuth);

  const uname = `rv_${Date.now().toString().slice(-6)}`;
  const created = await req('POST', '/users', {
    headers: auth,
    body: { username: uname, password: 'viewer123', role: 'viewer', deviceIds: [DEV] },
  });
  check('创建只读账号', created.status === 200 && !!created.data?.user?.id, created);

  const l2 = await req('POST', '/auth/login', { body: { username: uname, password: 'viewer123' } });
  const t2 = l2.data?.token;
  check('只读账号登录', l2.status === 200 && !!t2, l2);

  const users = await req('GET', '/users', { headers: { Authorization: `Bearer ${t2}` } });
  check('只读账号无法管理用户', users.status === 403, users);

  const own = await req('GET', `/devices/${DEV}/track`, { headers: { Authorization: `Bearer ${t2}` } });
  check('只读账号可看授权设备轨迹', own.status === 200, own.status);

  if (created.data?.user?.id) {
    await req('DELETE', `/users/${created.data.user.id}`, { headers: auth });
  }
}

console.log('-- 设置 --');
{
  const s = await req('GET', '/settings', { headers: auth });
  check('设置可读且后端为 PG', s.status === 200 && s.data?.backend === 'cloudbase-pg', s.data);
  check('上传密钥与配置一致', s.data?.uploadKey === uploadKey, s.data?.uploadKey ? '已配置' : '缺失');
}

console.log('-- 清理 --');
{
  const del = await req('DELETE', `/devices/${DEV}`, { headers: auth });
  check('删除测试设备', del.status === 200, del);
  const after = await req('GET', '/devices', { headers: auth });
  check('设备已移除', !(after.data?.devices || []).some((d) => d.id === DEV), after.data);
}

console.log(`\n===== 云端结果：通过 ${pass} / 失败 ${fail} =====`);
process.exit(fail === 0 ? 0 : 1);
