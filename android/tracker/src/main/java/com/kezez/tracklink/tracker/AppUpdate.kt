package com.kezez.tracklink.tracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内检查更新：从云端静态托管的 version.json 读取最新版本，
 * 有新版时用浏览器打开 APK 下载链接（无需安装来源授权，兼容所有 ROM）。
 */
object AppUpdate {
    /** 云开发静态托管上的版本清单（发版时随 APK 一起更新）；域名由 BuildConfig 注入 */
    private val VERSION_URL = "${BuildConfig.UPDATE_BASE}/apk/version.json"

    data class Info(val versionName: String, val versionCode: Int, val url: String, val notes: String)

    fun currentVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (_: Exception) {
        0
    }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** 同步网络调用，调用方自行切到 IO 线程。返回 null = 已是最新或检查失败 */
    fun check(app: String, currentCode: Int): Info? = try {
        val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val root = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val entry = root.optJSONObject(app) ?: return null
        val code = entry.optInt("versionCode", 0)
        val url = entry.optString("url")
        if (code > currentCode && url.isNotBlank()) {
            Info(entry.optString("versionName"), code, url, entry.optString("notes"))
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    fun openDownload(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
