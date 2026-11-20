/**
 * TrackLink 云函数入口（CloudBase Web 函数 / HTTP 类型）
 * 由 scf_bootstrap 执行 `node index.js`，监听 PORT（默认 9000）。
 * 同时保留 exports.main，便于本地按事件方式调试。
 */
const http = require('node:http');
const { Readable } = require('node:stream');
const { createDb } = require('./pg.js');
const { createRouter } = require('./router.js');
const { hashPassword } = require('./auth.js');
const { HttpError, json } = require('./util.js');

// 线上部署时 CloudBase 会自动注入 TCB_ENV / SCF_NAMESPACE，无需在此写死环境 ID
const ENV_ID = process.env.TCB_ENV || process.env.SCF_NAMESPACE || process.env.TL_PG_ENV || '';

const config = {
  tokenSecret: process.env.TL_TOKEN_SECRET || 'CHANGE_ME_tracklink_token_secret',
  uploadKey: process.env.TL_UPLOAD_KEY || 'tracklink-upload-key',
  adminPass: process.env.TL_ADMIN_PASS || 'admin12345',
  retentionDays: Number(process.env.TL_RETENTION_DAYS || 180),
  tokenTtlMs: Number(process.env.TL_TOKEN_TTL_HOURS || 720) * 3600_000,
};

const db = createDb({
  envId: process.env.TL_PG_ENV || ENV_ID,
  apiKey: process.env.TL_PG_KEY || '',
});

const route = createRouter({ db, config });

/** 首次调用时确保存在管理员账号（幂等，靠 username 唯一约束兜底并发） */
let initPromise = null;
function ensureInit() {
  if (!initPromise) {
    initPromise = (async () => {
      if (!process.env.TL_PG_KEY) throw new HttpError(500, '缺少 TL_PG_KEY 环境变量，无法访问数据库');
      const admins = await db.count('tl_users', { role: 'eq.admin' });
      if (admins > 0) return;
      await db.insert(
        'tl_users',
        { username: 'admin', pass: hashPassword(config.adminPass), role: 'admin', created_at: Date.now() },
        { ignoreDuplicates: true },
      );
      const existingKey = await db.first('tl_meta', { k: 'eq.upload_key' });
      if (!existingKey) {
        await db.insert('tl_meta', { k: 'upload_key', v: config.uploadKey }, { ignoreDuplicates: true });
      }
    })().catch((e) => {
      initPromise = null;
      throw e;
    });
  }
  return initPromise;
}

function clientIp(req) {
  const h = req.headers;
  return String(h['x-forwarded-for'] || '').split(',')[0].trim() || h['x-real-ip'] || 'unknown';
}

/** 统一处理一次请求（真实 req/res 与模拟 req/res 共用） */
async function handleRequest(ctx) {
  try {
    await ensureInit();
    const matched = await route(ctx);
    if (!matched) json(ctx.res, 404, { error: '接口不存在' });
  } catch (e) {
    const status = e instanceof HttpError ? e.status : (e.status || 500);
    if (status >= 500) console.error('[tracklink]', e);
    try {
      json(ctx.res, status, { error: e.message || '服务器内部错误' });
    } catch (e2) {
      console.error('[tracklink] 写响应失败', e2);
    }
  }
}

// ---------------- Web 函数：真实 HTTP 服务 ----------------

function nodeHandler(req, res) {
  if (req.method === 'OPTIONS') {
    // CORS 由网关统一回包（含 Access-Control-* 头），函数不再自己发，避免双值头
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url || '/', 'http://localhost');
  let path = url.pathname.replace(/\/+$/, '') || '/';
  // 容忍 /tracklink/api/... 这类前缀
  const idx = path.indexOf('/api/');
  if (idx > 0) path = path.slice(idx);

  if (!path.startsWith('/api/')) {
    json(res, 404, { error: '接口不存在' });
    return;
  }

  handleRequest({ req, res, path, url, ip: clientIp(req) });
}

const PORT = Number(process.env.PORT || 9000);
if (require.main === module) {
  const server = http.createServer(nodeHandler);
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[tracklink] listening on ${PORT}, env=${ENV_ID}`);
  });
}

// ---------------- 兼容：事件方式调用（本地冒烟用） ----------------

function normalizeEvent(event) {
  const rc = event.requestContext || {};
  let path = event.path || event.rawPath || rc.path || '/';
  const qsRaw = event.rawQueryString || event.queryString || '';
  const idx = path.indexOf('/api/');
  if (idx > 0) path = path.slice(idx);
  if (path.endsWith('/') && path.length > 1) path = path.replace(/\/+$/, '');
  const url = new URL(`https://fn.local${path}${typeof qsRaw === 'string' && qsRaw ? `?${qsRaw}` : ''}`);
  const headers = {};
  for (const [k, v] of Object.entries(event.headers || {})) {
    if (typeof v === 'string') headers[k.toLowerCase()] = v;
  }
  let bodyText = event.body ?? '';
  if (event.isBase64Encoded && bodyText) bodyText = Buffer.from(bodyText, 'base64').toString('utf8');
  return { path, url, headers, method: String(event.httpMethod || event.method || 'GET').toUpperCase(), bodyText };
}

function fakeRes() {
  const state = { status: 200, headers: {}, chunks: [] };
  return {
    state,
    writeHead(status, headers) {
      state.status = status;
      Object.assign(state.headers, headers || {});
      return this;
    },
    setHeader(k, v) {
      state.headers[k] = v;
    },
    end(chunk) {
      if (chunk) state.chunks.push(Buffer.from(chunk));
    },
  };
}

exports.main = async (event = {}) => {
  if (!event.httpMethod && (event.Type === 'Timer' || event.triggerName || event.type === 'timer')) {
    await ensureInit();
    await db.remove('tl_points', { ts: `lt.${Date.now() - config.retentionDays * 86400_000}` });
    return { ok: true, task: 'cleanup' };
  }

  const { path, url, headers, method, bodyText } = normalizeEvent(event);
  const res = fakeRes();
  const req = Readable.from(bodyText ? [Buffer.from(bodyText)] : []);
  req.headers = headers;
  req.method = method;
  await handleRequest({
    req,
    res,
    path,
    url,
    ip: headers['x-forwarded-for']?.split(',')[0]?.trim() || 'unknown',
  });
  return {
    statusCode: res.state.status,
    headers: { ...res.state.headers },
    body: Buffer.concat(res.state.chunks).toString('utf8'),
    isBase64Encoded: false,
  };
};
