import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { initDb, getMeta, setMeta, purgeOldPoints } from './db.js';
import { createRouter } from './routes.js';
import { HttpError, LoginGuard, empty, json } from './util.js';
import { hashPassword, randomKey } from './auth.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const publicDir = path.join(rootDir, 'public');

const env = process.env;
const config = {
  port: Number(env.PORT ?? 8080),
  dbPath: path.resolve(rootDir, env.DB_PATH ?? 'data/tracklink.db'),
  tokenTtlMs: Number(env.TOKEN_TTL_HOURS ?? 168) * 3600_000,
  retentionDays: Number(env.RETENTION_DAYS ?? 90),
  uploadKey: '',
  tokenSecret: '',
};

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function bootstrap() {
  const db = initDb(config.dbPath);

  config.tokenSecret = env.TOKEN_SECRET || getMeta(db, 'token_secret');
  if (!config.tokenSecret) {
    config.tokenSecret = randomKey(32);
    setMeta(db, 'token_secret', config.tokenSecret);
  }

  config.uploadKey = env.UPLOAD_KEY || getMeta(db, 'upload_key');
  if (!config.uploadKey) {
    config.uploadKey = randomKey(24);
    setMeta(db, 'upload_key', config.uploadKey);
  }

  if (!getMeta(db, 'initialized')) {
    const username = env.ADMIN_USER || 'admin';
    const password = env.ADMIN_PASS || randomKey(9);
    db.prepare('INSERT INTO users (username, pass, role, created_at) VALUES (?, ?, ?, ?)')
      .run(username, hashPassword(password), 'admin', Date.now());
    setMeta(db, 'initialized', String(Date.now()));
    if (!env.ADMIN_PASS) {
      console.log(`\n[初始化] 已创建管理员账号：${username} / ${password}`);
      console.log('[初始化] 该密码只显示这一次，请立即在网页端「账号管理」里修改。\n');
    }
  }
  return db;
}

async function serveStatic(req, res, pathname) {
  const rel = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
  const file = path.resolve(publicDir, rel);
  if (!file.startsWith(publicDir)) {
    json(res, 403, { error: '禁止访问' });
    return;
  }
  try {
    const data = await fsp.readFile(file);
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream',
      'Content-Length': data.length,
      'Cache-Control': pathname === '/' ? 'no-store' : 'max-age=300',
    });
    res.end(data);
  } catch {
    json(res, 404, { error: '页面不存在' });
  }
}

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list ?? []) {
      if (ni.family === 'IPv4' && !ni.internal) out.push(ni.address);
    }
  }
  return out;
}

const db = bootstrap();
const handleApi = createRouter({ db, config, loginGuard: new LoginGuard() });

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const ip = req.socket.remoteAddress ?? 'unknown';

  if (req.method === 'OPTIONS') {
    empty(res, 204);
    return;
  }

  try {
    if (url.pathname === '/api/health') {
      json(res, 200, { ok: true, time: Date.now(), service: 'tracklink-server', version: '1.0.0' });
      return;
    }

    const handled = await handleApi({ req, res, url, path: url.pathname, ip });
    if (handled) return;

    if (url.pathname.startsWith('/api/')) {
      json(res, 404, { error: '接口不存在' });
      return;
    }

    if (req.method === 'GET') {
      await serveStatic(req, res, url.pathname);
      return;
    }

    json(res, 405, { error: '方法不允许' });
  } catch (err) {
    if (err instanceof HttpError) {
      json(res, err.status, { error: err.message });
    } else {
      console.error('[500]', req.method, url.pathname, err);
      json(res, 500, { error: '服务器内部错误' });
    }
  }
});

server.listen(config.port, '0.0.0.0', () => {
  const ips = lanAddresses();
  console.log('\n=== TrackLink 轨迹服务端已启动 ===');
  console.log(`本机访问   : http://127.0.0.1:${config.port}`);
  for (const ip of ips) console.log(`局域网访问 : http://${ip}:${config.port}`);
  console.log(`数据库     : ${config.dbPath}`);
  console.log(`上传密钥   : ${config.uploadKey}   ← App1 里填这个`);
  console.log(`数据保留   : ${config.retentionDays} 天`);
  console.log('==================================\n');
});

// 启动时与每 6 小时清理过期轨迹
const runPurge = () => {
  try {
    const n = purgeOldPoints(db, config.retentionDays);
    if (n) console.log(`[清理] 删除过期轨迹点 ${n} 个`);
  } catch (e) {
    console.error('[清理] 失败', e);
  }
};
runPurge();
setInterval(runPurge, 6 * 3600_000).unref();

const shutdown = () => {
  console.log('\n正在关闭服务端...');
  server.close(() => {
    try {
      db.close();
    } catch {
      /* ignore */
    }
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 3000).unref();
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
