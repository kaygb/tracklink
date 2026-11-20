package com.kezez.tracklink.viewer

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * 底图瓦片源。
 *
 * 优先级：App 内设置的天地图 Key > 编译期预置 Key > OSM 镜像。
 *
 * osmdroid 默认的 MAPNIK 指向 tile.openstreetmap.org，该域名在多数国内网络下会直接超时，
 * 表现为「只有轨迹线、没有底图」。
 */
object TileSources {

    /**
     * 编译期预置的天地图 Key（浏览器端类型）。
     * App 内「地图」对话框填的 Key 优先级更高，留空则用这个。
     */
    // 真实 Key 由 local.properties 经 BuildConfig 注入（不入库）
    private const val PRESET_TIANDITU_KEY = BuildConfig.TDT_KEY

    /**
     * 实测可从国内直连的 OSM 镜像；官方域名已排除（超时）。
     * 注意两点：
     * 1. osmdroid 6.1 的 getBaseUrl() 不补结尾斜杠，URL 必须以 "/" 结尾，
     *    否则会拼成 "https://host2/0/3.png" 这种非法主机名；
     * 2. 只放路径结构为 /{z}/{x}/{y}.png 的镜像。tile.openstreetmap.fr 已改为 /osmfr/ 前缀
     *    且返回 404，不能和 .de 混在一个源里轮询。
     */
    private val OSM_HOSTS = arrayOf(
        "https://tile.openstreetmap.de/",
        "https://tile.openstreetmap.de/",
    )

    private val TDT_HOSTS = (0..7).map { "https://t$it.tianditu.gov.cn" }.toTypedArray()

    /** 离线包目录 / 缓存目录的父级，统一放在 files 下 */
    private const val OSM_BASE_DIR = "osmdroid"

    private const val KEY_PACK_STAMP = "offline_pack_stamp"

    /** 是否叠加天地图 cva 中文注记层（vec 底图本身不含地名） */
    private const val TDT_ANNOTATION = true

    private var tuningApplied = false

    /**
     * osmdroid 全局参数，一次配置终身有效。
     * 默认只有 2 个下载线程，一屏 20 张瓦片要排队很久，这是「加载慢」的一大半原因。
     */
    fun applyTuning(context: Context) {
        if (tuningApplied) return
        tuningApplied = true
        ensureOfflinePack(context)
        Configuration.getInstance().apply {
            // 天地图会对 User-Agent 做白名单校验：非 Mozilla/5.0 开头的 UA 直接 403，
            // 所以不能用默认的包名。带上应用标识也符合 OSM 的瓦片使用政策。
            userAgentValue =
                "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) ${context.packageName}"
            osmdroidBasePath = File(context.filesDir, OSM_BASE_DIR)
            osmdroidTileCache = File(context.filesDir, "$OSM_BASE_DIR/tiles")
            setTileDownloadThreads(6)
            setTileDownloadMaxQueueSize(80)
            // 缓存放大到 200MB：常看的城市看完一次后基本不再走网络
            setTileFileSystemCacheMaxBytes(200L * 1024 * 1024)
            setTileFileSystemCacheTrimBytes(160L * 1024 * 1024)
            // 瓦片有效期拉长到 60 天，避免反复回源校验
            setExpirationExtendedDuration(60L * 24 * 3600 * 1000)
        }
    }

    /**
     * 把随包内置的离线瓦片包铺到 osmdroid 的扫描目录。
     *
     * osmdroid 6.1 的 MapTileFileArchiveProvider 在构造时扫描 osmdroidBasePath（只扫一层，
     * 不递归），把每个 *.zip / *.sqlite / *.mbtiles 交给 ArchiveFileFactory 注册成 IArchiveFile。
     * 命中条件是 ZipFileArchive 用 tileSource.getTileRelativeFilenameString(index) 去查条目，
     * 即 **包内路径必须是「源名/层级/列/行.png」**。少这一层前缀不会报错，只会静默查不到 ——
     * 这是之前两次「飞行模式下地图空白」的根因。
     *
     * 必须在 MapView 构造之前执行：provider 在 MapView 构造时就建好了，建完再复制就晚了。
     */
    private fun ensureOfflinePack(context: Context) {
        runCatching {
            val dir = File(context.filesDir, OSM_BASE_DIR)
            dir.mkdirs()
            val assets = context.assets
            val names = assets.list("") ?: return
            val packs = names.filter {
                it.endsWith(".zip") || it.endsWith(".sqlite") || it.endsWith(".mbtiles")
            }

            // 升级 APK 后内置包可能已更新，靠 lastUpdateTime 判断，一次性清掉旧副本重铺
            val sp = context.getSharedPreferences("tracklink_tiles", Context.MODE_PRIVATE)
            val stamp = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(-1L)
            if (sp.getLong(KEY_PACK_STAMP, -1L) != stamp) {
                packs.forEach { File(dir, it).takeIf(File::exists)?.delete() }
                sp.edit().putLong(KEY_PACK_STAMP, stamp).apply()
            }

            for (asset in packs) {
                val dest = File(dir, asset)
                if (dest.exists() && dest.length() > 0) continue
                assets.open(asset).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                android.util.Log.w("TileSources", "离线瓦片包已铺开：$asset ${dest.length() / 1024} KB")
            }
            verifyOfflinePack(dir)
        }.onFailure {
            android.util.Log.w("TileSources", "铺开离线包失败：${it.message}")
        }
    }

    /**
     * 自检：读出包内顶层目录名，跟实际用到的 tile source 名对齐。
     * osmdroid 查不到时不会报任何错，只能靠这里把问题显式暴露出来。
     */
    private fun verifyOfflinePack(dir: File) {
        val expected = setOf(osm.name(), "TDT-vec", "TDT-cva")
        dir.listFiles()?.filter { it.extension == "zip" }?.forEach { zip ->
            runCatching {
                java.util.zip.ZipFile(zip).use { zf ->
                    val prefixes = LinkedHashSet<String>()
                    var count = 0
                    val en = zf.entries()
                    while (en.hasMoreElements()) {
                        val name = en.nextElement().name
                        if (name.contains('/')) prefixes.add(name.substringBefore('/'))
                        count++
                    }
                    val usable = prefixes.any { it in expected }
                    android.util.Log.w(
                        "TileSources",
                        "离线包 ${zip.name}：$count 张，前缀 $prefixes，${
                            if (usable) "可用" else "前缀与源名不匹配，会被忽略"
                        }",
                    )
                }
            }.onFailure { android.util.Log.w("TileSources", "离线包 ${zip.name} 无法读取：${it.message}") }
        }
    }

    val osm: XYTileSource by lazy {
        XYTileSource(
            "OSM-Mirror", 1, 19, 256, ".png", OSM_HOSTS,
            "© OpenStreetMap contributors",
        )
    }

    /** 按 layer + key 缓存；name 固定，换 Key 时本地缓存目录才不会失效 */
    private val tdtCache = HashMap<String, OnlineTileSourceBase>()

    /**
     * 天地图 WMTS 源。
     * layer = vec（矢量底图，不含地名）/ cva（中文注记，需叠加在底图之上）
     *
     * 扩展名必须是 ".png"：ZipFileArchive 用「源名/z/x/y.png」查离线包，
     * 留空会拼成 "TDT-vec/13/6716/3381." 这种带尾点的路径，包永远命中不了。
     */
    private fun tdt(layer: String, key: String): OnlineTileSourceBase =
        tdtCache.getOrPut("$layer|$key") {
            object : OnlineTileSourceBase(
                "TDT-$layer", 1, 18, 256, ".png", TDT_HOSTS, "© 天地图",
            ) {
                private var logged = false
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val z = MapTileIndex.getZoom(pMapTileIndex)
                    val x = MapTileIndex.getX(pMapTileIndex)
                    val y = MapTileIndex.getY(pMapTileIndex)
                    val host = TDT_HOSTS[Math.floorMod(x + y, TDT_HOSTS.size)]
                    // 服务名是 vec_w / cva_w（下划线），不是 /vec/w/ —— 写成后者会一直 404
                    val url = "$host/${layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                        "&LAYER=$layer&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles" +
                        "&TILEMATRIX=$z&TILEROW=$y&TILECOL=$x&tk=$key"
                    if (!logged) {
                        logged = true
                        android.util.Log.w("TileSources", "天地图请求样例：$url")
                    }
                    return url
                }
            }
        }

    /** 当前生效的天地图 Key；为空表示走 OSM 镜像 */
    fun keyOf(context: Context): String =
        ViewerSettings(context).tiandituKey.ifBlank { PRESET_TIANDITU_KEY }

    /** 天地图可用 = 没被强制降级，且拿得到 Key */
    fun usingTianditu(context: Context): Boolean =
        !ViewerSettings(context).forceOsm && keyOf(context).isNotBlank()

    /** 当前底图引擎："amap" = 高德 SDK；否则 osmdroid 走天地图/OSM */
    fun sourceOf(context: Context): String = ViewerSettings(context).mapSource

    fun usingAmap(context: Context): Boolean = sourceOf(context) == "amap"

    /** 界面上显示的底图名称 */
    fun labelOf(context: Context): String = when {
        usingAmap(context) -> "高德地图"
        usingTianditu(context) -> "天地图"
        else -> "OSM 镜像"
    }

    /**
     * 底图身份标识：底图源或 Key 一变，这个值就变。
     * 界面拿它当 `key()` 用，能强制重建 MapView —— 因为 tile source 是构造时绑定的。
     */
    fun signatureOf(context: Context): String = when {
        usingAmap(context) -> "amap"
        usingTianditu(context) -> "tdt:${keyOf(context)}"
        else -> "osm"
    }

    /**
     * 应用到 MapView。只应在 factory 里调用一次（会向 overlays 追加注记层）。
     */
    fun apply(map: MapView, context: Context) {
        applyTuning(context)
        // 关键：关掉 osmdroid 的「离线优先」。
        //
        // MapTileProviderBasic 默认 setOfflineFirst(true)，把 MapTileApproximater 排在
        // 下载器之前。approximater 会把离线包里的低层级瓦片（最狠会一路降到 z10 的整城
        // 瓦片）放大几十倍占位，而且插入后真实瓦片不再替换 —— 表现就是「满屏糊成一片色块，
        // 明明网络瓦片已经下载成功了也不显示」。
        //
        // 改成 false（网络优先）：有网直接出清晰瓦片；没网时下载器失败后仍会回落到
        // approximater，离线包照常可用。
        preferNetwork(map.tileProvider)

        if (!usingTianditu(context)) {
            map.setTileSource(osm)
            return
        }
        val key = keyOf(context)
        map.setTileSource(tdt("vec", key))
        android.util.Log.w(
            "TileSources",
            "底图=${labelOf(context)} 源=${map.tileProvider.tileSource.name()}",
        )
        // vec 底图不带文字，再叠一层 cva 注记
        if (TDT_ANNOTATION) {
            val provider = MapTileProviderBasic(context, tdt("cva", key))
            preferNetwork(provider)
            val annotation = TilesOverlay(provider, context).apply {
                setLoadingBackgroundColor(0x00000000)
                setLoadingLineColor(0x00000000)
            }
            map.overlays.add(0, annotation)
        }
    }

    private fun preferNetwork(provider: org.osmdroid.tileprovider.MapTileProviderBase) {
        (provider as? MapTileProviderBasic)?.setOfflineFirst(false)
    }
}
