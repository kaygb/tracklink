# TrackLink · 轨迹云

一套完整的「实时轨迹记录 + 多端查看」系统，三件套：

| 组件 | 说明 | 技术栈 |
| --- | --- | --- |
| `server/` | 服务端：账号权限、设备管理、轨迹存储与查询、Web 控制台 | Node.js 22（内置 http + sqlite，**零第三方依赖**） |
| `android/tracker/` | **App1 上报端**：GNSS 实时记录并上传，低功耗 | Kotlin + Compose，纯 `LocationManager`（不依赖 Google 服务） |
| `android/viewer/` | **App2 查看端**：登录后看各设备轨迹、回放 | Kotlin + Compose + osmdroid（OSM 镜像底图，免 Key；可切天地图） |

```
   App1（多台手机，GPS/北斗）
        │  ① 批量上报轨迹点（X-Upload-Key 鉴权）
        ▼
   TrackLink 服务端（Node + SQLite）
        │  ② REST 查询 + Web 控制台
        ▼
   App2（查看端，多账号：管理员 / 只读 + 设备授权）
```

---

## 0. 两种部署形态（选一个）

| | 本地版 | 云端版（云开发） |
| --- | --- | --- |
| 服务端 | 本机 `node src/index.js` | CloudBase Web 函数 + PostgreSQL |
| 数据库 | SQLite 文件 | CloudBase PG |
| App 访问 | 局域网 `http://电脑IP:8080` | 公网 `https://...app.tcloudbase.com` |
| 适用 | 同 WiFi、内网、零成本 | 手机走 4G/5G、异地、长期在线 |
| 文档 | 本文件 | [`cloudbase/README.md`](cloudbase/README.md) |

**云端实例部署信息**（不入库，部署后自行记录）：

- API：`https://<环境ID>.<region>.app.tcloudbase.com`（云函数 HTTP 入口，部署后从 CloudBase 控制台获取）
- 控制台：`https://<环境ID>.<region>.tcloudbaseapp.com/tracklink/index.html`
- 管理员账号密码在部署时通过环境变量设置（`TL_ADMIN_PASS`），不要写进任何文档

> 改 App 默认地址：App 内置服务端地址、上传密钥、地图 Key、云环境域名均由 `android/local.properties` 构建期注入（见「构建 APK」章节），源码中不含真实值。

---

## 1. 启动服务端（约 1 分钟）

需要 **Node.js ≥ 22.5**（用到了内置 `node:sqlite`，不需要 `npm install`）。

```bash
cd server
node src/index.js
```

首次启动会自动创建数据库与管理账号，控制台输出：

```
=== TrackLink 轨迹服务端已启动 ===
本机访问   : http://127.0.0.1:8080
局域网访问 : http://192.168.0.25:8080      ← 手机端填这个
数据库     : .../server/data/tracklink.db
上传密钥   : xxxxxxxxxxxxxxxxxxxxxxxx      ← App1 填这个
[初始化] 已创建管理员账号：admin / 随机密码   ← 只显示一次
```

浏览器打开局域网地址即可进入 Web 控制台（看设备列表、改设备名、发账号、查看上传密钥）。

### 可配置环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8080` | 监听端口 |
| `ADMIN_USER` / `ADMIN_PASS` | `admin` / 随机 | **仅首次初始化**时生效 |
| `UPLOAD_KEY` | 随机生成并入库 | App1 上传密钥，改这里会覆盖库里的值 |
| `TOKEN_SECRET` | 随机生成并入库 | 登录 token 签名密钥 |
| `TOKEN_TTL_HOURS` | `168` | 登录有效期（小时） |
| `RETENTION_DAYS` | `90` | 轨迹保留天数，超期自动清理 |
| `DB_PATH` | `data/tracklink.db` | SQLite 文件路径 |

示例（局域网固定 admin 密码 + 固定密钥）：

```bash
ADMIN_PASS=myPass123 UPLOAD_KEY=myUploadKey PORT=8080 node src/index.js
```

Windows 下想开机自启，可把上面命令写成 `start.bat` 放进启动目录。

### 冒烟测试

```bash
node server/test/smoke.mjs http://127.0.0.1:8080 <上传密钥> <管理员账号> <管理员密码>
```

覆盖：健康检查、错误密码拒绝、上传密钥校验、批量上报、去重幂等、非法坐标丢弃、轨迹查询与里程统计、只读账号越权拦截、设备授权、重命名、Web 页面可达（26 项）。

---

## 2. App1 · 轨迹上报端（v1.7.10 零配置）

### 安装即用

1. 安装 `TrackLink-Tracker-v1.7.10.apk`，打开，授予定位权限 —— 完事。
2. 服务端地址与上传密钥已内置（默认连云端实例），启动后自动连接、自动开始记录，**没有开始/停止按钮**；想彻底停用直接卸载。
3. 界面只展示：运行状态（定位采集 / 静止休眠）、已记录/待上传、里程、最后上传时间、GNSS 卫星（参与定位/可见）、服务器连接状态灯、设备 ID。
4. **常驻通知开关**（保活卡内）：默认开启；关闭后通知栏完全不显示（startForeground 合规发布后立即撤销，服务保持前台优先级，记录不受影响）。
5. **隐藏后台开关**：开启后最近任务列表不显示本应用卡片（运行时即时生效）；默认关闭。
6. 版本号与查看端同步发版（两端一致，便于识别）。

### 检查更新

- 两个 App 主界面都有「检查更新」入口（App1 状态页底部版本卡；App2 设备列表顶部「更新」按钮）。
- 更新清单与 APK 均托管在云开发静态托管：`https://<环境ID>.<region>.tcloudbaseapp.com/apk/version.json`（App 内由 `local.properties` 的 `TRACKLINK_UPDATE_BASE` 注入）。
- 发现新版本后跳浏览器下载 APK 安装。
- **发版流程**：构建 → APK + version.json 上传托管 `apk/` 目录 → zip 复制到 `Y:\TrackLinkApp` → 上传资料库。

### 采样与省电（由 App2 远程下发）

App1 本机**不做任何采样设置**，全部参数由管理员在轨迹查看端按设备下发，App1 在每次上报成功后自动拉取并热应用（约 1 分钟内生效，无需重启）：

| 参数 | 默认 | 范围 | 作用 |
| --- | --- | --- | --- |
| 采样间隔 | 15 秒 | 5–300 秒 | 移动时最短取点间隔 |
| 最小位移 | 10 米 | 0–500 米 | 位移不足不上报，过滤原地抖动 |
| 精度上限 | 50 米 | 5–500 米 | 精度更差的点直接丢弃 |
| 静止休眠 | 3 分钟 | 0–30 分钟 | 持续静止后注销 GNSS（0 = 不休眠） |
| 上报周期 | 60 秒 | 15–600 秒 | 搊批上传间隔 |
| 心跳间隔 | 300 秒 | 60–1800 秒 | 静止待机时的保活上报 |

省电机制：

- **静止即休眠，移动必唤醒**：超过静止阈值就 `removeUpdates` 释放 GNSS；休眠期间挂 `TYPE_SIGNIFICANT_MOTION` 硬件触发器（超低功耗，走几步立即唤醒）。**兑底保险**：部分设备没有该传感器或触发不可靠，v1.5.0 起休眠期间每隔一个心跳周期短暂开一次定位探测（45 秒窗口）——窗口内拿到定位且位移足够就保持唤醒记录，没动就继续睡。最坏情况下停止响应不超过一个心跳周期（默认 5 分钟），绝不会再"睡死"。
- **不轮询、不唤醒**：没有 AlarmManager/JobScheduler 定时唤醒，全靠系统定位回调与前台服务。
- **批量上传**：点位先写本地队列（断网不丢），每上报周期最多 200 点一次 HTTP 请求，请求结束立即释放唤醒锁。
- **心跳**：没有新点时每心跳间隔发一次空包维持"在线"状态，不触发 GPS；配置变更也随心跳拉取。

实测参考：静止待机状态耗电基本与普通后台应用持平（1%~2%/小时，因机型与网络而异）；连续骑行/驾车记录时约 6%~10%/小时。

### 后台存活（App1 内置引导）

- App1 的「后台保活」卡会检查并引导两项：**电池优化白名单**（未设置时黄色 ⚠️ + 「去设置」直达）与 **Android 13+ 通知权限**。
- 国产 ROM（MIUI/EMUI/ColorOS…）需额外允许「自启动」「后台弹窗/后台运行」。
- 通知栏常驻通知是前台服务的标识，**不要滑动清除**（清除后服务仍会运行，但部分 ROM 会杀）。
- 重启后自动恢复记录（BootReceiver，无需任何开关）。

### 关于 GPS / 北斗

App 使用系统 `LocationManager` 的 GNSS 能力：手机芯片自动在 GPS / 北斗 / GLONASS / Galileo 间选星定位，无需也无法在应用层"指定北斗"。室内 GPS 无信号时：系统网络定位（NETWORK/FUSED Provider）可用则直接补点；不可用或 GPS 持续无 fix 超 3 分钟时，自动切换高德定位 SDK（WiFi/基站混合，无 GMS 设备也可用）补点（GCJ-02 自动逆变换回 WGS-84，间隔 ≥ 1 分钟），保证室内也有轨迹点。

---

## 3. App2 · 轨迹查看端（v1.7.10）

1. 安装 `TrackLink-Viewer-v1.7.10.apk`。
2. 填服务端地址 + 账号密码登录（管理员看全部设备；只读账号只看被授权设备）。
3. 设备列表每 30 秒自动刷新：在线状态、电量、今日里程/点数、最后定位时间。
4. 点设备 → 地图看轨迹：
   - 绿色折线为轨迹，标记为起点/终点；
   - 切换「今天 / 昨天 / 近 7 天」；
   - 底部 ▶ 可回放轨迹（沿轨迹移动的当前位置标记），滑块可手动定位到任意点；
   - 顶部显示总里程、点数、时长、平均速度；
   - **时间段：今天 / 昨天 / 近 7 天 / 自定义**（点「自定义」展开滚动时间轴：日期 ± 微调 + 双把手拖动选起止，监控/行车记录仪风格，拖动松手即查询）。
   - **本机位置**：轨迹页右侧悬浮「本机」按钮，点击定位本机并显示与轨迹的最近距离；再点「轨迹」回到全景视野。
   - **心跳时间**：顶部信息卡显示设备最近一次上报（含心跳空包）的时间与相对时长（每 30 秒重算）。
5. **配置下发（仅管理员）**：轨迹页顶部「配置」按钮 → 六个参数（− + 步进或直接输入数值）→ 保存即下发，App1 在下次上报时自动拉取生效。
6. **底图三选一（地图设置）**：高德 SDK（需打包内置 Key，轨迹自动 WGS-84→GCJ-02 纠偏）/ 天地图（App 内可改 Key）/ OSM 镜像。离线瓦片包仅 osmdroid 引擎可用。

### 关于地图底图

底图源集中在 `viewer/.../TileSources.kt`，两种模式：

| 模式 | 触发条件 | 说明 |
|---|---|---|
| **OSM 镜像**（默认） | 天地图 Key 为空 | 免申请，开箱可用。**不用 `tile.openstreetmap.org`** —— 该域名国内基本超时，是「只有轨迹没有地图」的元凶 |
| **天地图** | App 内「地图」按钮填 Key（立即生效）或编译期预置 | 国家地理信息公共服务平台，合规、国内直连快；`vec` 底图 + `cva` 注记双图层 |

**填天地图 Key 不用重新打包**：App2 右上角「地图」→ 输入 Key → 保存，地图立即重建。
Key 存在 `SharedPreferences(tracklink_viewer)`，也会同时用于离线包的源名匹配。

天地图坐标系是 EPSG:3857 + CGCS2000，**与 GPS 原始坐标一致，无需 GCJ-02 纠偏**；这也是不推荐直接抓高德瓦片的原因——高德是 GCJ-02，轨迹会和路网错开几百米。

#### 加载速度优化（v1.3.0）

`TileSources.applyTuning()` 在 `MapView` 构造前统一设置：

| 项 | 默认 | 现在 | 效果 |
|---|---|---|---|
| 瓦片下载线程 | 2 | 6 | 一屏瓦片并发拉取，首屏快一截 |
| 下载队列 | 40 | 80 | 快速平移不丢请求 |
| 磁盘缓存 | 极小 | 200 MB / 触发线 160 MB | 常看的城市只看一次网络 |
| 瓦片有效期 | 短 | 60 天 | 不再反复回源校验 |

进轨迹页后还会用 `CacheManager.downloadAreaAsyncNoUI` 预取该区域 z13–z16。

#### 离线瓦片包（v1.3.0）

APK 内置 `assets/OSM-Mirror.zip`（武汉 z10–z13，37 张，1.1 MB）。运行时铺到
`files/osmdroid/`，osmdroid 的 `MapTileFileArchiveProvider` 会自动扫描并优先命中：
**包内有就本地读，没有才走网络**；z13 以上的视野由 `MapTileApproximater` 拿 z13 放大填充。

> 🔴 **包内路径必须是 `<源名>/<z>/<x>/<y>.png`**
> `ZipFileArchive` 用 `tileSource.getTileRelativeFilenameString()` 查条目，少了源名这一层
> 前缀它不会报错，只会静默查不到 —— 这就是「飞行模式下地图一片空白」的真凶。
> 源名对照：OSM → `OSM-Mirror`，天地图 → `TDT-vec` / `TDT-cva`。

自检日志（release 包里也保留，`Log.w` 不会被 R8 剥掉）：

```bash
adb logcat -s TileSources:W
# 离线瓦片包已铺开：OSM-Mirror.zip 1140 KB
# 离线包 OSM-Mirror.zip：37 张，前缀 [OSM-Mirror]，可用
```

出现「前缀与源名不匹配，会被忽略」就说明包名/目录名和 tile source 对不上。

**生成/替换离线包**（零依赖脚本，见 `tools/fetch_tiles.py`）：

```bash
# OSM 镜像包（自动前缀 OSM-Mirror）
python tools/fetch_tiles.py --source osm \
    --bbox 30.45,114.25,30.60,114.45 --zmin 10 --zmax 13 \
    --out android/viewer/src/main/assets/OSM-Mirror.zip

# 天地图矢量底图包（自动前缀 TDT-vec）；注记包加 --layer cva 输出 TDT-cva.zip
python tools/fetch_tiles.py --source tianditu --key <你的key> --layer vec \
    --bbox 30.45,114.25,30.60,114.45 --zmin 10 --zmax 15 \
    --out android/viewer/src/main/assets/TDT-vec.zip
```

升级 APK 后旧副本会自动作废（靠 `lastUpdateTime` 比对），不用手动清数据。

飞行模式实测（`docs/screenshots/offline-wuhan.png`）：接口报 `Unable to resolve host`、
`ping` 不可达，底图仍渲染出武昌/汉口/汉阳、长江与徐家棚街道。

> ⚠️ 合规提示：正式对外发布/商用请使用有测绘资质的底图服务（天地图、高德、百度等）并按其授权条款使用，同时遵守《地图管理条例》对国界线的表示要求。OSM 底图仅建议用于内部测试。

---

## 4. REST API

鉴权：管理/只读账号用 `Authorization: Bearer <token>`；设备上报用 `X-Upload-Key`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | `{username,password}` → `{token,user}` |
| GET | `/api/auth/me` | 当前账号 |
| POST | `/api/auth/password` | `{oldPassword,newPassword}` 修改自己密码 |
| GET | `/api/devices` | 设备列表（含在线/电量/今日里程） |
| GET | `/api/devices/:id/track?from&to&limit` | 轨迹点 `[ts,lat,lon,acc,spd]` + 统计 |
| GET | `/api/devices/:id/daily?from&to` | 按天里程统计 |
| PATCH | `/api/devices/:id` | 重命名 / 备注（管理员） |
| DELETE | `/api/devices/:id` | 删除设备及轨迹（管理员） |
| POST | `/api/device/points` | **App1 批量上报**（`X-Upload-Key`） |
| GET | `/api/device/config` | **App1 拉取下发配置**（`X-Upload-Key`；无配置时返回默认值） |
| GET | `/api/devices/:id/config` | 查看设备配置（管理员/被授权只读） |
| PUT | `/api/devices/:id/config` | **App2 下发配置**（管理员；服务端 clamp 到合法范围） |
| GET | `/api/device/ping` | 设备连接测试 |
| GET/POST | `/api/users` | 账号列表 / 新建（管理员） |
| PATCH/DELETE | `/api/users/:id` | 改角色密码、设备授权 / 删除（管理员） |
| GET | `/api/settings` | 上传密钥、保留天数（管理员） |
| GET | `/api/health` | 健康检查（公开） |

上报请求示例：

```json
POST /api/device/points
Headers: X-Upload-Key: <密钥>
{
  "deviceId": "dev-xxxxxxxxxxxxxxxxxxxx",
  "name": "老王的车机",
  "model": "Xiaomi 23127PN0CC",
  "battery": 87,
  "points": [{"ts":1730000000000,"lat":30.5,"lon":114.3,"acc":8.2,"spd":12.5,"brg":90,"alt":30,"bat":87}]
}
```

服务端对点位做校验（经纬度范围、时间戳、设备 ID 格式）、`(device_id, ts)` 唯一去重，重复上报幂等。

---

## 5. 构建 APK

```bash
cd android
export JAVA_HOME="C:/Users/User/.jdks/jbr-21.0.11"   # Android Studio 自带 JBR 21 或任意 JDK 17+
./gradlew :tracker:assembleRelease :viewer:assembleRelease
```

产物：`tracker/build/outputs/apk/release/TrackLink-Tracker-v1.7.10-release.apk`、
`viewer/build/outputs/apk/release/TrackLink-Viewer-v1.6.1-release.apk`。
Release 当前用调试签名（方便直接安装体验），正式发布请替换为自己的 keystore：

```kotlin
// tracker/build.gradle.kts / viewer/build.gradle.kts
signingConfig = signingConfigs.getByName("debug")   // ← 换成 release 签名配置
```

环境要求：AGP 8.13.2 / Kotlin 2.1.21 / compileSdk 36 / minSdk 26 / JDK 21。

### 密钥配置（不入库）

所有真实密钥都放在 `android/local.properties`（已被 .gitignore 排除），构建时经
manifestPlaceholder / BuildConfig 注入，源码与 git 历史中不含任何真实密钥：

```properties
TRACKLINK_UPLOAD_KEY=tl_xxx...   # 上传密钥（tracker BuildConfig.UPLOAD_KEY 默认值）
TRACKLINK_AMAP_KEY=xxx...        # 高德 Android Key（两端 manifest meta-data，lbs.amap.com）
TRACKLINK_TDT_KEY=xxx...         # 天地图浏览器端 Key（viewer BuildConfig.TDT_KEY）
```

字段缺失时按空值构建：收集端上报会被服务端 401 拒绝、高德底图不渲染、天地图回退到用户在
App 内手填的 Key；GPS 轨迹记录不受影响。

---

## 6. 常见问题

| 现象 | 排查 |
| --- | --- |
| App1 显示「上传密钥不正确」 | 服务端启动日志或 Web 控制台「系统设置」里的密钥与手机端不一致；密钥烧录在 App1 包内，如需更换需重新打包推送新版本 |
| App1 一直「待上传」不减少 | 手机与电脑不在同一 WiFi / 电脑防火墙拦了 8080 端口 / 地址写成 `localhost` |
| 后台一段时间就没数据了 | 电池策略设置成「不限制」，并允许自启动；检查通知栏常驻通知是否还在 |
| 轨迹点很稀 | 采样间隔与最小位移调小；精度上限调大（室外空旷处再试） |
| 轨迹有跳点 | 已内置"异常跳点过滤"（速度 > 60m/s 且精度差），隧道/高楼间偶发可忽略 |
| App2 地图空白 / 只有轨迹线没有底图 | v1.2.0 已修复默认瓦片源（原 `tile.openstreetmap.org` 国内超时）。若仍空白，看 logcat 里 `OsmDroid` 关键字：`UnknownHostException` 是 URL 拼接问题，`HTTP 404` 是镜像路径变了 |
| 断网/弱网时地图一片空白 | v1.3.0 已内置离线包。先看 `adb logcat -s TileSources:W`：没有「已铺开」说明 assets 里没打包；出现「前缀与源名不匹配」说明 zip 内少了 `<源名>/` 这一层目录 |
| 离线包只覆盖武汉，别的城市还是要联网 | 用 `tools/fetch_tiles.py` 按目标城市 bbox 重新生成，或直接沿用在线模式（首次浏览后进 200 MB 磁盘缓存） |
| App2 地图缩到全球、标记相距半个地球 | 轨迹里有跨洲脏点（GNSS 冷启动会给默认坐标）。服务端已内置 `cleanTrackPoints` 跳点清洗，首点异常时用第二点顶替 |
| Web 控制台打不开 | 用 `http://电脑IP:8080`，不要用 `https`；确认服务端进程还在 |

---

## 7. 已验证记录

本版本已在 Android 17（API 36）模拟器 + 本机 Node 服务端上跑通全链路：

| 验证项 | 结果 |
| --- | --- |
| 服务端 26 项接口冒烟（鉴权/上报/去重/越权/授权/统计） | 全部通过 |
| App1 前台服务启动、定位采集（`adb emu geo fix` 注入 GNSS） | 通过，服务类型 = location |
| App1 批量上传 → 服务端入库、在线状态、今日里程统计 | 通过（80 点 / 1.45 km / online=true） |
| App2 登录态、设备列表（在线、电量、今日里程、点数） | 通过 |
| App2 地图轨迹折线 + 起终点标记 + 范围切换 | 通过 |
| App2 轨迹回放（滑块 + 播放，标记沿轨迹移动） | 通过 |
| release 包（R8 混淆压缩）功能 | 通过，App1 1.14 MB / App2 2.66 MB（含 1.1 MB 离线瓦片包） |

> 底图在 v1.2.0 已实测出图（模拟器内确认可下载瓦片并渲染街道级地图）。v1.1.0 及更早版本因默认瓦片源不可达，只能看到轨迹线。

### v1.7.10 移除「轮换上传密钥」功能（2026-11-08 晨）

- 背景：收集端零配置化后，密钥烧录在 App1 安装包内，App 内无法修改；在线轮换会导致所有收集端断连且无法自救。
- **彻底移除**轮换能力：服务端删除 `POST /api/settings/upload-key` 路由（本地 Node 版 + cloudbase 云函数版同步删除）；Web 管理页与查看端管理中心移除「重新生成」按钮，保留密钥展示与分享接入信息，提示语改为「需重新打包推送新版本」。旧版本客户端调用该接口将返回 404。
- 两端 1.7.10 (code 20) 同步发布（tracker 无功能变化仅版本对齐）。

### v1.7.9 管理中心：App 内全功能管理（2026-11-08 晨）

- 定位调整：Web 管理端退化为纯 API 消费者，查看端 App 管理员即可完成全部管理功能（服务端 API 全部现成，零改动）。
- **管理中心**（主页 ⋮ 菜单，仅管理员可见）：
  - 系统设置：查看 App1 上传密钥、分享接入信息；显示轨迹保留天数/登录有效期。
  - 账号与授权：账号列表、新增账号（只读/管理员）、只读账号授权设备（勾选对话框）、设为管理员/降为只读、重置密码、删除账号（保护：不能删自己、至少保留一个管理员由服务端校验）。
  - 修改我的密码（旧密码校验）。
- 主页设备卡片：管理员可 ⋮ 重命名 / 删除设备（删除需确认，连轨迹一起删）。
- 两端 1.7.9 (code 19) 同步发布（tracker 无功能变化仅版本对齐）。

### v1.7.8 查看端主页 UI 重组（2026-11-07 深夜）

- 问题：顶栏一行塞下标题 + 4 个文字按钮（地图/刷新/更新/退出），TextButton 最小宽度把标题挤到换行。
- 重组：标题「迹览」独占一行左侧（加大加粗）；右侧仅「刷新」图标按钮 + 「⋯」更多菜单；**地图设置 / 检查更新 / 退出登录**收纳进 DropdownMenu（退出为红色警示色）；账号/设备数/版本号单独一行副标题。
- viewer 新增 material-icons-core 依赖。两端 1.7.8 (code 18) 同步发布（tracker 无功能变化仅版本对齐）。

### v1.7.7 首次启动立即上报+定位（2026-11-07 晚）

- 需求：收集端首次打开就要立刻在管理端/查看端看到新设备。
- 实现：服务拉起记录时调用 firstReport() —— ① 立即上报一次心跳（不等 batchSeconds 周期），服务端收到空包即注册设备；② 立即拿首个定位：优先取 GPS/NETWORK/FUSED 的最近缓存定位（5 分钟内）喂入统一过滤链，缓存不可用则立刻强制走高德网络定位补点（不等 3 分钟 GPS 无 fix 判定）。
- 两端 1.7.7 (code 17) 同步发布（viewer 无功能变化仅版本对齐）。

### v1.7.6 网络定位补点（2026-11-07 上午）

- 问题：系统 NETWORK_PROVIDER 在无 GMS 的国产 ROM 上常不存在，室内/GPS 无信号时轨迹彻底断线。
- **高德定位 SDK 补点**：GPS 无 fix 超 3 分钟（或待机探测窗口内）自动单次定位（Battery_Saving 模式，WiFi/基站混合），GCJ-02 迭代逆变换回 WGS-84（残差 < 1m）后走统一入点过滤链；间隔 ≥ 1 分钟防频繁请求。
- 系统 Provider 注册增强：NETWORK/FUSED（API 31+）不再依赖 isProviderEnabled 判断，直接 runCatching 注册。
- tracker proguard keep com.amap.api.location / com.loc；manifest 加高德 Key meta-data + 隐私合规接口。
- 两端 1.7.6 (code 16) 同步发布（viewer 无功能变化仅版本对齐）。

### v1.7.5 心跳显示 + 差异化图标（2026-11-07 晨）

- **心跳链路确认**：迹录心跳（空包）一直在真实上报（/api/device/points 空数组也会更新 last_seen）；此前只是查看端没展示。迹览设备列表原有「最后上报」，本次轨迹页顶部卡新增「心跳 MM-dd HH:mm · N 分钟前」，相对时间每 30s 自动重算。
- **图标差异化**：迹录 = S 形轨迹线 + 节点 + 终点光标（深绿底 #12261F）；迹览 = 折叠地图 + 定位针（深蓝底 #16222E）。均为 adaptive icon vector，此前两端同一绿针。
- 两端 1.7.5 (code 15) 构建发布；发版链路照旧（托管/归档/Y 盘/资料库）。

### v1.7.4 双端同步（2026-11-06 深夜）

**两端版本号同步约定**：今后每次发版两端统一版本号（versionName/versionCode 一致），便于识别与排查。

**迹览（viewer）v1.7.4 (14)**

- 轨迹页新增「本机位置」：右侧悬浮按钮，点击定位本机（WGS-84，高德自动纠偏 GCJ-02）并显示与轨迹的最近距离；再点「轨迹」回到全景。定位策略：优先 5 分钟内缓存，否则单次定位（IO 线程 CountDownLatch 等待 8s）。
- 自定义时间段改为滚动时间轴（监控/行车记录仪风格）：日期 ± 微调 + 双把手拖动（粒度 1 分钟，松手才触发查询），刻度按跨度自适应（≤36h 每 3h / ≤3 天每 6h / 更长每天 0 点）；彻底移除 DatePicker/TimeInput 弹框。
- 配置下发：六个参数支持直接输入数值（数字键盘、越界自动夹回合法范围），步进从 5/10/15/30 统一改为 1。

**迹录（tracker）v1.7.4 (14)**

- 常驻通知真隐藏：上一版 IMPORTANCE_MIN 只是折叠到抽屉底部仍可见，且 notifyTick 每 30s 重发；现改为 startForeground 合规发布后立即 cancel 通知（服务保持前台优先级），静默时所有 notify 改为 cancel，通知栏完全不显示。
- 「隐藏后台」改为开关：manifest 硬编码 excludeFromRecents 移除，改用 ActivityManager.AppTask.setExcludeFromRecents 运行时切换（onCreate 恢复 + 开关即时生效），默认显示。

### v1.7.3 / v1.7.1（2026-11-06 深夜）

**迹览（viewer）v1.7.3 (13)**

- 自定义时间段精确到分钟：日历选起止日期 + `TimeInput` 起止时间（如 10:30–11:05），查询任意分钟级历史区间
- 修复高德底图不加载：SDK 强制隐私合规接口（`updatePrivacyShow` / `updatePrivacyAgree`）+ 前台进入立即 `onResume`
- 修复配置弹窗背景异常（弹窗改为叠加渲染）
- 三底图源 Key 全部内置（高德 / 天地图），无需手动填写
- 修复 Web 管理端登录失败：云函数层移除手工 CORS 头（网关自动 echo Origin，应用层再发会造成 `Access-Control-Allow-Origin` 双值头被浏览器拒绝）

**迹录（tracker）v1.7.1 (11)**

- `excludeFromRecents`：隐藏最近任务卡片（后台更隐蔽，桌面图标/通知仍可进入）
- 常驻通知开关：静默模式用 `IMPORTANCE_MIN` 渠道，状态栏不显示图标，前台服务记录不受影响（渠道 importance 创建后不可改，故双渠道 + 切换时 `ACTION_REFRESH` 重建通知）
- 设备 ID 改用 `ANDROID_ID`（重装不变，避免查看端出现重复设备）

### v1.3.0 地图优化验证

| 验证项 | 结果 |
| --- | --- |
| App1 零配置启动即上报（`autoStart` 默认开 + 授权后自动起服务） | 通过，重装后打开即「运行中」，无需任何设置 |
| 离线包自检日志 | 通过：`离线包 OSM-Mirror.zip：37 张，前缀 [OSM-Mirror]，可用` |
| **飞行模式（`ping` 不可达）下渲染底图** | **通过**：武汉武昌/汉口/汉阳、长江、长江大桥、徐家棚街道瓦片全部出自包内；z15 视野由 z13 瓦片放大填充 |
| 瓦片缓存/下载线程/预取调整后的首屏速度 | 明显快于 v1.2.0（线程 2→6，缓存 200 MB） |
| App 内改天地图 Key 免重装生效 | 通过（保存后地图重建） |

### v1.5.1 亮色主题（2026-11-06 晚）

- 双端 UI 从暗色切换为亮色：浅灰底 #F4F6F8 + 白卡片 + 深色正文，品牌绿加深为 #0E9F6E（白底上 ≥ 4.5:1 对比度）
- 轨迹线同步换深绿，在天地图亮色底图上更清晰
- tracker/viewer 版本号统一为 v1.5.1 (versionCode 6)

### v1.5.0 休眠唤醒修复验证（2026-11-06，模拟器无 SignificantMotion 传感器 = 最坏场景）

| 验证项 | 结果 |
| --- | --- |
| 静止 1 分钟后进入休眠（下发了 sleepAfterMin=1） | 通过 |
| 休眠期间每 60s 探测循环运转 | 通过（心跳/上报持续，message 循环切换） |
| mock 位置移动 ~600m 后自动唤醒并记录 | 通过（1 点 → 2 点，里程 0.92 km，新点已上传） |
| 版本号统一 tracker/viewer 均 v1.5.0 | 完成 |

### v1.5.0 配置下发端到端验证（2026-11-06）

| 验证项 | 结果 |
| --- | --- |
| 云端迁移 `tl_device_config` 表 | 通过（PushPGUserMigrations Succeed） |
| 云端配置 API：PUT 下发 / GET 读取 / 越界 clamp（9999→300）/ 401 | 全部通过 |
| App1（模拟器，零配置）自动连接云端并上报 | 通过（online=true，免任何设置） |
| App1 上报后自动拉配置并热应用 | 通过（状态栏出现「配置已更新: 采样 20s · 位移 15m · 休眠 2min · 上报 45s」） |
| 静止休眠生效 | 通过（持续静止后进入「运行中 · 静止休眠」） |
| App2 配置弹窗（6 参数 + − 步进 + 保存） | 通过，加载/下发链路写入云端 |
| App1 采样参数卡实时刷新 | 通过（修为 3 秒轮询，同步显示下发值） |

### 云端版验证

| 验证项 | 结果 |
| --- | --- |
| PG 表结构与 `tl_ingest_points` 函数迁移 | 通过 |
| 云函数本地冒烟（29 项，直调入口） | 全部通过 |
| 云端 HTTP 冒烟（23 项，打线上网关） | 全部通过 |
| App1（release）→ 云端 PG 上报 | 通过（4 点 / 1.15 km / online） |
| App2（release）→ 云端设备列表 + 轨迹页 | 通过（1.15 km / 4 点 / 起终点标记 / 回放控件） |
| Web 控制台（静态托管 + 跨域调 API） | 页面可达，内置 API host |

**v1.2.0 底图与轨迹清洗修复**

| 验证项 | 结果 |
| --- | --- |
| OSM 镜像瓦片下载（`tile.openstreetmap.de`） | 通过，街道级底图正常渲染 |
| 原 `tile.openstreetmap.org` 不可达复现 | 通过（12s 超时，确认是根因） |
| URL 拼接修正（结尾斜杠） | 通过，`UnknownHostException` 消失 |
| `cleanTrackPoints` 单测（跨洲首点/单点/空/高铁） | 4 场景全通过 |
| 服务端清洗上线后拉取轨迹 | 通过（4 点 → 3 点，异常坐标 0） |
| App2 地图缩放（布局完成后才 `zoomToBoundingBox`） | 通过，定位于街道级而非全球视图 |


## 8. 已知限制

- 本地版（`server/`）为明文 HTTP，仅限局域网自用；云端版（`cloudbase/`）走 HTTPS 网关，可公网使用。若要自己搭公网服务，必须加 HTTPS 反代 + 强密码。
- 单机 SQLite，适合个人/小团队（数台到数十台设备）；更高并发建议换 PostgreSQL。
- Web 控制台的轨迹预览是等比投影示意图，不含真实底图（真实地图在 App2）。
- 未做 App 内自动升级、推送、地理围栏告警（可作二期）。
