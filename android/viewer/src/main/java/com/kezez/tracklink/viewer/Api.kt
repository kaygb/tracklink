package com.kezez.tracklink.viewer

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class ViewerUser(val username: String, val role: String)

data class LatLon(val lat: Double, val lon: Double)

data class DeviceItem(
    val id: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val battery: Int,
    val lastSeen: Long,
    val lastPoint: LatLon?,
    val lastPointTs: Long,
    val distanceToday: Int,
    val pointCountToday: Int,
)

data class TrackPointView(val ts: Long, val lat: Double, val lon: Double, val acc: Int, val spd: Double)

/** 管理中心的账号条目 */
data class AdminUser(val id: Int, val username: String, val role: String, val deviceIds: List<String>)

/** 系统设置（管理员）：上传密钥与全局参数 */
data class AdminSettings(val uploadKey: String, val retentionDays: Int, val tokenTtlHours: Int)

/** 设备采样/省电配置：App2 编辑并下发，App1 在下次上报时自动应用 */
data class DeviceConfig(
    val sampleIntervalS: Int,
    val minDistanceM: Int,
    val accuracyMaxM: Int,
    val sleepAfterMin: Int,
    val batchSeconds: Int,
    val heartbeatS: Int,
) {
    fun toJson() = JSONObject().apply {
        put("sampleIntervalS", sampleIntervalS)
        put("minDistanceM", minDistanceM)
        put("accuracyMaxM", accuracyMaxM)
        put("sleepAfterMin", sleepAfterMin)
        put("batchSeconds", batchSeconds)
        put("heartbeatS", heartbeatS)
    }

    companion object {
        val DEFAULT = DeviceConfig(15, 10, 50, 3, 60, 300)
    }
}

data class TrackData(
    val points: List<TrackPointView>,
    val distance: Int,
    val duration: Int,
    val avgSpeed: Double,
)

class ApiException(message: String) : Exception(message)

/** 查看端配置：服务端地址 + 登录态 */
class ViewerPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tracklink_viewer", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", DEFAULT_SERVER).orEmpty()
        set(v) {
            var url = v.trim()
            if (url.isEmpty()) url = DEFAULT_SERVER
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            sp.edit().putString("server", url.trimEnd('/')).apply()
        }

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v).apply()

    var username: String
        get() = sp.getString("username", "").orEmpty()
        set(v) = sp.edit().putString("username", v).apply()

    var role: String
        get() = sp.getString("role", "").orEmpty()
        set(v) = sp.edit().putString("role", v).apply()

    fun clearSession() {
        sp.edit().remove("token").remove("username").remove("role").apply()
    }

    companion object {
        // 真实服务端地址由 local.properties 经 BuildConfig 注入（不入库）
        const val DEFAULT_SERVER = BuildConfig.DEFAULT_SERVER
    }
}

class ViewerApi(private val prefs: ViewerPrefs) {

    fun login(username: String, password: String): ViewerUser {
        val res = request("/api/auth/login", "POST", JSONObject().apply {
            put("username", username)
            put("password", password)
        }, auth = false)
        prefs.token = res.optString("token")
        val user = res.optJSONObject("user") ?: JSONObject()
        val u = ViewerUser(user.optString("username"), user.optString("role"))
        prefs.username = u.username
        prefs.role = u.role
        return u
    }

    fun devices(): List<DeviceItem> {
        val res = request("/api/devices", "GET")
        val arr = res.optJSONArray("devices") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val lp = d.optJSONObject("lastPoint")
                add(
                    DeviceItem(
                        id = d.optString("id"),
                        name = d.optString("name"),
                        model = d.optString("model"),
                        online = d.optBoolean("online"),
                        battery = if (d.isNull("battery")) -1 else d.optInt("battery", -1),
                        lastSeen = d.optLong("lastSeen"),
                        lastPoint = lp?.let { LatLon(it.optDouble("lat"), it.optDouble("lon")) },
                        lastPointTs = lp?.optLong("ts") ?: 0L,
                        distanceToday = d.optInt("distanceToday"),
                        pointCountToday = d.optInt("pointCountToday"),
                    ),
                )
            }
        }
    }

    fun track(deviceId: String, from: Long, to: Long): TrackData {
        val res = request("/api/devices/$deviceId/track?from=$from&to=$to&limit=50000", "GET")
        val arr = res.optJSONArray("points") ?: return TrackData(emptyList(), 0, 0, 0.0)
        val points = buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                add(
                    TrackPointView(
                        ts = p.optLong(0),
                        lat = p.optDouble(1),
                        lon = p.optDouble(2),
                        acc = p.optInt(3),
                        spd = p.optDouble(4),
                    ),
                )
            }
        }
        val stats = res.optJSONObject("stats") ?: JSONObject()
        return TrackData(
            points = points,
            distance = stats.optInt("distance"),
            duration = stats.optInt("duration"),
            avgSpeed = stats.optDouble("avgSpeed", 0.0),
        )
    }

    fun deviceConfig(deviceId: String): DeviceConfig {
        val res = request("/api/devices/$deviceId/config", "GET")
        val c = res.optJSONObject("config") ?: JSONObject()
        fun f(key: String, def: Int) =
            if (c.has(key) && !c.isNull(key)) c.optInt(key, def) else def
        return DeviceConfig(
            sampleIntervalS = f("sampleIntervalS", DeviceConfig.DEFAULT.sampleIntervalS),
            minDistanceM = f("minDistanceM", DeviceConfig.DEFAULT.minDistanceM),
            accuracyMaxM = f("accuracyMaxM", DeviceConfig.DEFAULT.accuracyMaxM),
            sleepAfterMin = f("sleepAfterMin", DeviceConfig.DEFAULT.sleepAfterMin),
            batchSeconds = f("batchSeconds", DeviceConfig.DEFAULT.batchSeconds),
            heartbeatS = f("heartbeatS", DeviceConfig.DEFAULT.heartbeatS),
        )
    }

    fun putDeviceConfig(deviceId: String, config: DeviceConfig): DeviceConfig {
        val res = request("/api/devices/$deviceId/config", "PUT", config.toJson())
        val c = res.optJSONObject("config") ?: return config
        return DeviceConfig(
            sampleIntervalS = c.optInt("sampleIntervalS", config.sampleIntervalS),
            minDistanceM = c.optInt("minDistanceM", config.minDistanceM),
            accuracyMaxM = c.optInt("accuracyMaxM", config.accuracyMaxM),
            sleepAfterMin = c.optInt("sleepAfterMin", config.sleepAfterMin),
            batchSeconds = c.optInt("batchSeconds", config.batchSeconds),
            heartbeatS = c.optInt("heartbeatS", config.heartbeatS),
        )
    }

    // ---------------- 管理员功能（与 Web 管理端同源 API） ----------------

    /** 重命名设备 */
    fun renameDevice(deviceId: String, name: String) {
        request("/api/devices/$deviceId", "PATCH", JSONObject().put("name", name))
    }

    /** 删除设备（连轨迹一起删） */
    fun deleteDevice(deviceId: String) {
        request("/api/devices/$deviceId", "DELETE")
    }

    /** 账号列表（含各账号可看的设备 ID） */
    fun users(): List<AdminUser> {
        val res = request("/api/users", "GET")
        val arr = res.optJSONArray("users") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i) ?: continue
                val ids = buildList {
                    val a = u.optJSONArray("deviceIds")
                    a?.let { for (j in 0 until it.length()) add(it.optString(j)) }
                }
                add(
                    AdminUser(
                        id = u.optInt("id"),
                        username = u.optString("username"),
                        role = u.optString("role", "viewer"),
                        deviceIds = ids,
                    ),
                )
            }
        }
    }

    /** 新增账号 */
    fun createUser(username: String, password: String, role: String): AdminUser {
        val res = request("/api/users", "POST", JSONObject().apply {
            put("username", username)
            put("password", password)
            put("role", role)
            put("deviceIds", org.json.JSONArray())
        })
        val u = res.optJSONObject("user") ?: JSONObject()
        return AdminUser(u.optInt("id"), u.optString("username"), u.optString("role", "viewer"), emptyList())
    }

    /** 修改账号：角色/密码/可看设备（传 null 不改） */
    fun updateUser(id: Int, role: String? = null, password: String? = null, deviceIds: List<String>? = null) {
        val body = JSONObject()
        role?.let { body.put("role", it) }
        password?.takeIf { it.isNotEmpty() }?.let { body.put("password", it) }
        deviceIds?.let { body.put("deviceIds", org.json.JSONArray(it)) }
        if (body.length() == 0) return
        request("/api/users/$id", "PATCH", body)
    }

    /** 删除账号（不能删自己，至少保留一个管理员由服务端校验） */
    fun deleteUser(id: Int) {
        request("/api/users/$id", "DELETE")
    }

    /** 系统设置：上传密钥等 */
    fun settings(): AdminSettings {
        val res = request("/api/settings", "GET")
        return AdminSettings(
            uploadKey = res.optString("uploadKey"),
            retentionDays = res.optInt("retentionDays"),
            tokenTtlHours = res.optInt("tokenTtlHours"),
        )
    }

    /** 修改自己的密码 */
    fun changeOwnPassword(oldPassword: String, newPassword: String) {
        request("/api/auth/password", "POST", JSONObject().apply {
            put("oldPassword", oldPassword)
            put("newPassword", newPassword)
        })
    }

    private fun request(path: String, method: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val conn = (URL(prefs.serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 25000
            setRequestProperty("Accept", "application/json")
            if (auth && prefs.token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer ${prefs.token}")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            body?.let { conn.outputStream.use { os -> os.write(it.toString().toByteArray()) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(it.reader()).use { r -> r.readText() } }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw ApiException(
                    when (code) {
                        // 服务端会区分「用户名或密码错误」与「登录已失效」，优先透传
                        401 -> msg.ifBlank { "登录已失效，请重新登录" }
                        403 -> msg.ifBlank { "无权查看该设备" }
                        else -> msg.ifBlank { "服务端返回 $code" }
                    },
                )
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
