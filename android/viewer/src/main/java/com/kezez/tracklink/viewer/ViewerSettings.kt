package com.kezez.tracklink.viewer

import android.content.Context

/**
 * 客户端设置。
 *
 * 天地图 Key 做成 App 内可填，改完立即生效，不需要重新打包。
 * 另外留一个「强制用 OSM 镜像」的开关：天地图免费 Key 有日配额，
 * 用尽时能一键降级，不必等我们发新包。
 */
class ViewerSettings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("tracklink_viewer", Context.MODE_PRIVATE)

    /** 天地图 Key（https://console.tianditu.gov.cn 免费申请，类型选「浏览器端」） */
    var tiandituKey: String
        get() = sp.getString(KEY_TDT, "").orEmpty().trim()
        set(v) = sp.edit().putString(KEY_TDT, v.trim()).apply()

    /** true = 忽略 Key，强制走 OSM 镜像 */
    var forceOsm: Boolean
        get() = sp.getBoolean(KEY_FORCE_OSM, false)
        set(v) = sp.edit().putBoolean(KEY_FORCE_OSM, v).apply()

    /** 底图引擎："auto"（osmdroid：天地图/OSM 镜像）或 "amap"（高德 SDK） */
    var mapSource: String
        get() = sp.getString(KEY_SOURCE, "auto").orEmpty().ifBlank { "auto" }
        set(v) = sp.edit().putString(KEY_SOURCE, v).apply()

    private companion object {
        const val KEY_TDT = "tianditu_key"
        const val KEY_FORCE_OSM = "force_osm"
        const val KEY_SOURCE = "map_source"
    }
}
