package com.kezez.tracklink.tracker

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

sealed interface UploadResult {
    data class Ok(val accepted: Int) : UploadResult
    data object AuthError : UploadResult
    data class Failed(val message: String) : UploadResult
}

sealed interface PingResult {
    data class Ok(val message: String) : PingResult
    data object AuthError : PingResult
    data class Failed(val message: String) : PingResult
}

sealed interface ConfigResult {
    /** changed = 配置与本地不同（调用方需要应用并重排采样循环） */
    data class Ok(val changed: Boolean, val raw: JSONObject) : ConfigResult
    data object AuthError : ConfigResult
    data class Failed(val message: String) : ConfigResult
}

/** 极简 HTTP 客户端，只用 JDK 自带 HttpURLConnection，不引入任何网络库 */
class ApiClient(private val prefs: Prefs) {

    fun ping(): PingResult = try {
        val conn = open("/api/device/ping", "GET")
        val code = conn.responseCode
        val body = readBody(conn, code)
        conn.disconnect()
        when {
            code == 200 -> PingResult.Ok(JSONObject(body).optString("message", "连接正常"))
            code == 401 -> PingResult.AuthError
            else -> PingResult.Failed("服务端返回 $code：${errorOf(body)}")
        }
    } catch (e: Exception) {
        PingResult.Failed(e.message ?: "网络异常")
    }

    fun upload(points: List<TrackPoint>, battery: Int): UploadResult = try {
        val payload = JSONObject().apply {
            put("deviceId", prefs.deviceId)
            put("name", prefs.deviceName)
            put("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            put("battery", battery)
            put("points", org.json.JSONArray(TrackPoint.listToJson(points)))
        }
        val conn = open("/api/device/points", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        val body = readBody(conn, code)
        conn.disconnect()
        when {
            code == 200 -> UploadResult.Ok(JSONObject(body).optInt("accepted", points.size))
            code == 401 -> UploadResult.AuthError
            else -> UploadResult.Failed("服务端返回 $code：${errorOf(body)}")
        }
    } catch (e: Exception) {
        UploadResult.Failed(e.message ?: "网络异常")
    }

    /** 拉取服务端下发的采样/省电配置（App2 修改后，App1 在下次心跳/上报时取到） */
    fun fetchConfig(): ConfigResult = try {
        val conn = open("/api/device/config", "GET")
        val code = conn.responseCode
        val body = readBody(conn, code)
        conn.disconnect()
        when {
            code == 200 -> {
                val json = JSONObject(body)
                val config = json.optJSONObject("config") ?: JSONObject()
                ConfigResult.Ok(changed = prefs.applyServerConfig(config), raw = config)
            }
            code == 401 -> ConfigResult.AuthError
            else -> ConfigResult.Failed("服务端返回 $code：${errorOf(body)}")
        }
    } catch (e: Exception) {
        ConfigResult.Failed(e.message ?: "网络异常")
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL(prefs.serverUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.setRequestProperty("X-Upload-Key", prefs.uploadKey)
        conn.setRequestProperty("X-Device-Id", prefs.deviceId)
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(stream.reader()).use { it.readText() }
    }

    private fun errorOf(body: String): String = runCatching {
        JSONObject(body).optString("error", body.take(120))
    }.getOrDefault(body.take(120))
}
