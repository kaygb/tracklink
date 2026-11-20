/**
 * CloudBase PostgreSQL 数据访问层（PostgREST 网关）
 * 用 service_role API Key 走服务端通道，绕过 RLS。
 */

const REQUEST_TIMEOUT_MS = 12000;

function createDb({ envId, apiKey }) {
  const base = `https://${envId}.api.tcloudbasegateway.com/v1/rdb/rest`;

  async function call(path, { method = 'GET', body, prefer, raw = false } = {}) {
    const headers = {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    };
    if (prefer) headers.Prefer = prefer;

    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), REQUEST_TIMEOUT_MS);
    let res;
    try {
      res = await fetch(`${base}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: ctrl.signal,
      });
    } finally {
      clearTimeout(timer);
    }

    const text = await res.text();
    if (!res.ok) {
      const err = new Error(`数据库请求失败 HTTP ${res.status}: ${text.slice(0, 300)}`);
      err.status = 502;
      throw err;
    }
    if (raw) return { text, headers: res.headers };
    if (!text) return null;
    try {
      return JSON.parse(text);
    } catch {
      return null;
    }
  }

  /** 拼 filter：{ col: 'eq.value' } 或 { col: ['gte.1', 'lte.9'] } */
  function qs(params) {
    const sp = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined || v === null || v === '') continue;
      if (Array.isArray(v)) v.forEach((x) => sp.append(k, String(x)));
      else sp.append(k, String(v));
    }
    const s = sp.toString();
    return s ? `?${s}` : '';
  }

  return {
    /** 查列表 */
    async select(table, { columns = '*', filter = {}, order, limit, offset } = {}) {
      const params = { select: columns, ...filter };
      if (order) params.order = order;
      if (limit) params.limit = limit;
      if (offset) params.offset = offset;
      return call(`/${table}${qs(params)}`);
    },

    /** 查单条 */
    async first(table, filter, columns = '*') {
      const rows = await this.select(table, { columns, filter, limit: 1 });
      return Array.isArray(rows) && rows.length ? rows[0] : null;
    },

    /** 计数 */
    async count(table, filter = {}) {
      const { headers } = await call(`/${table}${qs({ select: 'id', ...filter, limit: 1 })}`, {
        raw: true,
        prefer: 'count=exact',
      });
      const range = headers.get('content-range') || '';
      const total = Number(range.split('/')[1]);
      return Number.isFinite(total) ? total : 0;
    },

    /** 插入，返回写入行（true=冲突忽略） */
    async insert(table, rows, { ignoreDuplicates = false } = {}) {
      const list = Array.isArray(rows) ? rows : [rows];
      if (!list.length) return [];
      return call(`/${table}`, {
        method: 'POST',
        body: list,
        prefer: `return=representation${ignoreDuplicates ? ',resolution=ignore-duplicates' : ''}`,
      });
    },

    /** 更新 */
    async update(table, patch, filter) {
      return call(`/${table}${qs({ ...filter, select: '*' })}`, {
        method: 'PATCH',
        body: patch,
        prefer: 'return=representation',
      });
    },

    /** 删除（返回被删行，便于调用方判断是否命中） */
    async remove(table, filter) {
      return call(`/${table}${qs({ ...filter })}`, {
        method: 'DELETE',
        prefer: 'return=representation',
      });
    },

    /** 调 PG 函数（单次往返内完成多步写，原子） */
    async rpc(fn, args) {
      return call(`/rpc/${fn}`, { method: 'POST', body: args });
    },

    /** 便捷：eq 条件 */
    eq: (v) => `eq.${v}`,
    gte: (v) => `gte.${v}`,
    lte: (v) => `lte.${v}`,
    isNull: () => 'is.null',
  };
}

module.exports = { createDb };
