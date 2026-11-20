# TrackLink · 云开发（CloudBase）部署版

同一套 API，换一个后端：本地版跑 `node src/index.js` + SQLite，云端版跑**云函数（Web 函数）+ CloudBase PostgreSQL**。

## 线上地址

部署后从 CloudBase 控制台获取，不要写进文档。结构如下：

| 用途 | 地址 |
| --- | --- |
| API（App 填这个） | `https://<环境ID>.<region>.app.tcloudbase.com` |
| Web 控制台 | `https://<环境ID>.<region>.tcloudbaseapp.com/tracklink/index.html` |
| 环境 ID | 部署时指定，App/测试脚本经环境变量或构建配置注入 |

App 里只填 **host（不含 `/api`）**，客户端自己拼 `/api/...`。

## 架构

```
App1 / App2 / 浏览器
        │ HTTPS
        ▼
CloudBase HTTP 网关  /api/*  ──路径透传──▶  Web 函数 tracklink（Node 20）
                                              │  fetch + service_role API Key
                                              ▼
                                  PostgREST 网关 /v1/rdb/rest
                                              │
                                              ▼
                              CloudBase PostgreSQL（tl_* 表 + tl_ingest_points）
```

为什么不是云托管：本环境是体验版（`baas_trial`），云托管未开通；且 PG 是 **SHARED 租户**，只有 SDK/网关通道、没有 TCP 直连，所以不能按常规 `pg` 客户端连库。走 PostgREST 网关是这条路上最稳的方案。

## 目录

```
cloudbase/
├── migrations/
│   └── 20261105171800_tracklink_init_schema.sql   # 建表 + 批量写入 PG 函数
├── functions/
│   └── tracklink/
│       ├── index.js        # Web 函数入口（http server）+ 事件式兼容导出
│       ├── router.js       # 全部 API 路由（本地版的 PG 改写版）
│       ├── pg.js           # PostgREST 数据访问层
│       ├── auth.js         # scrypt 密码 + 无状态 token（与本地版同源）
│       ├── util.js
│       └── scf_bootstrap   # Web 函数启动脚本
└── test/
    ├── smoke-cloud.mjs     # 本地直接调用函数入口
    └── smoke-remote.mjs    # 打线上网关的 HTTP 冒烟
```

## 函数环境变量

| 变量 | 说明 |
| --- | --- |
| `TL_PG_KEY` | **service_role API Key**（PostgREST 凭证，务必备份后再隐藏） |
| `TL_PG_ENV` | PG 所属环境 ID |
| `TL_TOKEN_SECRET` | 登录 token 的 HMAC 密钥，泄漏等于登录态可伪造 |
| `TL_UPLOAD_KEY` | App1 上传密钥，可在网页控制台一键轮换 |
| `TL_ADMIN_PASS` | 仅首次初始化管理员时使用；库里已有 admin 后改它不会改密码 |
| `TL_RETENTION_DAYS` | 点位保留天数，定时触发器按此清理 |

## 部署步骤（可复现）

```bash
# 1. 建表 + 函数（首次）
#    用 managePgDatabase(applyMigration) 应用 migrations/20261105171800_tracklink_init_schema.sql

# 2. 创建 Web 函数
#    type=HTTP, runtime=Nodejs20.19, handler=index.main, timeout=30
#    代码目录 cloudbase/functions，函数目录名必须等于函数名 tracklink

# 3. 建网关路由：/api → WEB_SCF:tracklink，开启路径透传，关闭路径鉴权

# 4. 静态托管上传 server/public/index.html 到 /tracklink/index.html

# 5. 冒烟
node cloudbase/test/smoke-remote.mjs <host> <uploadKey> admin <adminPass>
```

## 数据库

表统一 `tl_` 前缀，避免和同环境其它项目撞名：

| 表 | 用途 |
| --- | --- |
| `tl_users` | 账号（`role`: admin / viewer） |
| `tl_devices` | 设备档案 + 最后位置缓存 + 在线判断（`last_seen`） |
| `tl_points` | 轨迹点，`(device_id, ts)` 唯一，天然幂等去重 |
| `tl_acl` | 只读账号的设备授权 |
| `tl_meta` | 运行时配置（如 `upload_key`） |

批量上报走 PG 函数 `tl_ingest_points(jsonb)`，一次往返内完成「设备 upsert + 点位去重插入 + 设备缓存更新」，天然原子，替代了本地版 SQLite 的 BEGIN/COMMIT。2000 点也是单次请求。

## 与本地版的差异

| 项 | 本地版 | 云端版 |
| --- | --- | --- |
| 服务形态 | 长驻 Node 进程 | Web 函数（按请求伸缩） |
| 数据库 | SQLite 文件 | CloudBase PostgreSQL |
| 事务 | `BEGIN/COMMIT` | PG 函数内原子执行 |
| 登录限流 | 进程内 Map | 每个实例独立计数（弱化，公网建议加网关限频） |
| 静态控制台 | 服务端同目录直出 | 静态托管，页面内置 API host |
| 定时清理 | 进程内定时 | timer 触发器（未配置则跳过） |

## 注意事项

- **API Key 与 TL_TOKEN_SECRET 都是敏感值**，别写进仓库、别贴群里。密码轮换在查看端 App 管理中心即可完成。
- **管理员初始密码在部署时通过环境变量 `TL_ADMIN_PASS` 设置**，不要写进任何文档或仓库；如已在别处泄露，登录后在管理中心改密。
- APK 内置了上传密钥（方便开箱即用，由 `local.properties` 构建期注入）。密钥属于敏感值，APK 不外传；如需更换需重新打包推送新版本。
- 体验版环境有免费额度限制，轨迹点多了会占用 PG 存储；`TL_RETENTION_DAYS=180` 生效需要配 timer 触发器，或定期手动清理。
- 云函数冷启动约 1–2 秒，App 端超时都设了 8/25 秒，不会误判。
