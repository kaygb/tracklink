export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type,Authorization',
  'Access-Control-Allow-Methods': 'GET,POST,PATCH,DELETE,OPTIONS',
  'Access-Control-Max-Age': '86400',
};

export function json(res, status, body) {
  const data = JSON.stringify(body ?? null);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    'Cache-Control': 'no-store',
    ...CORS,
  });
  res.end(data);
}

export function empty(res, status = 204) {
  res.writeHead(status, CORS);
  res.end();
}

export function readJson(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(new HttpError(413, '请求体过大'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new HttpError(400, 'JSON 格式错误'));
      }
    });
    req.on('error', reject);
  });
}

/** 两点球面距离（米） */
export function haversine(lat1, lon1, lat2, lon2) {
  const R = 6371008.8;
  const toRad = Math.PI / 180;
  const dLat = (lat2 - lat1) * toRad;
  const dLon = (lon2 - lon1) * toRad;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * 轨迹里程：跳过时间间隔过长（轨迹断裂）与明显漂移（速度超 55m/s）的点对
 */
export function trackDistance(points) {
  let meters = 0;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    const dt = (b.ts - a.ts) / 1000;
    if (dt <= 0) continue;
    const d = haversine(a.lat, a.lon, b.lat, b.lon);
    if (dt > 300) continue; // 超过 5 分钟视为轨迹断开（设备休眠/离线）
    if (d / dt > 55) continue; // 相对论速度，必然是漂移
    meters += d;
  }
  return meters;
}

export function clampStr(v, max, fallback = '') {
  const s = String(v ?? '').trim();
  if (!s) return fallback;
  return s.length > max ? s.slice(0, max) : s;
}

export function toInt(v, fallback = null) {
  const n = Number.parseInt(String(v ?? ''), 10);
  return Number.isFinite(n) ? n : fallback;
}

export function startOfDay(ts = Date.now(), tzOffsetMinutes = 480) {
  const d = new Date(ts + tzOffsetMinutes * 60_000);
  d.setUTCHours(0, 0, 0, 0);
  return d.getTime() - tzOffsetMinutes * 60_000;
}

/** 简易登录限流：同一 IP 连续失败过多则短暂封禁 */
export class LoginGuard {
  constructor(maxFails = 8, windowMs = 5 * 60_000) {
    this.maxFails = maxFails;
    this.windowMs = windowMs;
    this.map = new Map();
  }
  check(ip) {
    const rec = this.map.get(ip);
    if (rec && rec.until > Date.now()) return false;
    return true;
  }
  fail(ip) {
    const now = Date.now();
    const rec = this.map.get(ip) ?? { count: 0, until: 0 };
    if (rec.until && rec.until < now) rec.count = 0;
    rec.count += 1;
    if (rec.count >= this.maxFails) {
      rec.until = now + this.windowMs;
      rec.count = 0;
    }
    this.map.set(ip, rec);
  }
  ok(ip) {
    this.map.delete(ip);
  }
}
