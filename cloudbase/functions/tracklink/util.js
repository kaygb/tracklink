class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

// CORS 由 CloudBase 网关统一处理（按 Origin 自动回包）；
// 应用层若再发 Access-Control-* 会与网关叠加成双值头，浏览器直接拒绝（ERR_FAILED）。

function json(res, status, body) {
  const data = JSON.stringify(body ?? null);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    'Cache-Control': 'no-store',
  });
  res.end(data);
}

function empty(res, status = 204) {
  res.writeHead(status);
  res.end();
}

function readJson(req, limit = 1024 * 1024) {
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
function haversine(lat1, lon1, lat2, lon2) {
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
function trackDistance(points) {
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

/**
 * 轨迹清洗：剔除速度明显不合理的跳点。
 *
 * 首点特殊处理——GNSS 冷启动（或模拟器）可能先给出一个陈旧/默认坐标，
 * 此时它与第二个点之间的速度必然离谱，用第二个点顶替它，保证序列从真实位置开始。
 *
 * @param {Array<{ts:number,lat:number,lon:number}>} points 已按 ts 升序
 * @param {number} maxSpeed 最大合理速度 m/s（默认 280，约 1008km/h，覆盖高铁与民航）
 */
function cleanTrackPoints(points, maxSpeed = 280) {
  const out = [];
  for (const p of points) {
    if (!Number.isFinite(p.lat) || !Number.isFinite(p.lon)) continue;
    if (Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180) continue;
    const last = out[out.length - 1];
    if (last) {
      const dt = Math.max((p.ts - last.ts) / 1000, 1);
      const d = haversine(last.lat, last.lon, p.lat, p.lon);
      if (d / dt > maxSpeed) {
        if (out.length === 1) out[0] = p;
        continue;
      }
    }
    out.push(p);
  }
  return out;
}

function clampStr(v, max, fallback = '') {
  const s = String(v ?? '').trim();
  if (!s) return fallback;
  return s.length > max ? s.slice(0, max) : s;
}

function toInt(v, fallback = null) {
  const n = Number.parseInt(String(v ?? ''), 10);
  return Number.isFinite(n) ? n : fallback;
}

function startOfDay(ts = Date.now(), tzOffsetMinutes = 480) {
  const d = new Date(ts + tzOffsetMinutes * 60_000);
  d.setUTCHours(0, 0, 0, 0);
  return d.getTime() - tzOffsetMinutes * 60_000;
}

/** 简易登录限流：同一 IP 连续失败过多则短暂封禁 */
class LoginGuard {
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

module.exports = { cleanTrackPoints, HttpError, json, empty, readJson, haversine, trackDistance, clampStr, toInt, startOfDay, LoginGuard };
