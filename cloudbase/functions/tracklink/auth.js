const crypto = require('node:crypto');

const SCRYPT_N = 16384;
const KEY_LEN = 32;

/** 密码哈希：scrypt$N$salt$hash（base64url），无第三方依赖 */
function hashPassword(pw) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(pw), salt, KEY_LEN, { N: SCRYPT_N });
  return `scrypt$${SCRYPT_N}$${salt.toString('base64url')}$${hash.toString('base64url')}`;
}

function verifyPassword(pw, stored) {
  try {
    const [algo, n, saltB64, hashB64] = String(stored).split('$');
    if (algo !== 'scrypt') return false;
    const salt = Buffer.from(saltB64, 'base64url');
    const expected = Buffer.from(hashB64, 'base64url');
    const actual = crypto.scryptSync(String(pw), salt, expected.length, { N: Number(n) });
    return crypto.timingSafeEqual(expected, actual);
  } catch {
    return false;
  }
}

function b64u(input) {
  return Buffer.from(input).toString('base64url');
}

function hmac(data, secret) {
  return crypto.createHmac('sha256', secret).update(data).digest('base64url');
}

/** 无状态 token：v1.<payload>.<sig> */
function signToken(payload, secret, ttlMs) {
  const body = { ...payload, exp: Date.now() + ttlMs };
  const raw = b64u(JSON.stringify(body));
  return `v1.${raw}.${hmac(raw, secret)}`;
}

function verifyToken(token, secret) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3 || parts[0] !== 'v1') return null;
  const [, raw, sig] = parts;
  const expect = hmac(raw, secret);
  if (sig.length !== expect.length) return null;
  if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expect))) return null;
  try {
    const payload = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
    if (!payload || typeof payload.exp !== 'number' || payload.exp < Date.now()) return null;
    return payload;
  } catch {
    return null;
  }
}

/** 定长比较，避免时序侧信道 */
function safeEqual(a, b) {
  const ba = Buffer.from(String(a ?? ''));
  const bb = Buffer.from(String(b ?? ''));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

function randomKey(bytes = 24) {
  return crypto.randomBytes(bytes).toString('base64url');
}

module.exports = { hashPassword, verifyPassword, signToken, verifyToken, safeEqual, randomKey };
