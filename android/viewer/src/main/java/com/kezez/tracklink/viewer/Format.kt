package com.kezez.tracklink.viewer

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// 亮色主题：浅灰底 + 白卡片 + 深色正文；绿 Accent 加深到 #0E9F6E 保证白底上文字/图标 ≥ 4.5:1
val AccentGreen = Color(0xFF0E9F6E)
val PanelColor = Color(0xFFFFFFFF)
val BgColor = Color(0xFFF4F6F8)
// 白底上的次要文字 #4A5A68 对比度 ≈ 7.5:1，小字号也清晰
val DimColor = Color(0xFF4A5A68)
val TextPrimaryColor = Color(0xFF1A2530)
val DangerColor = Color(0xFFD93025)

fun fmtTime(ts: Long): String {
    if (ts <= 0) return "—"
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return fmt.format(Date(ts))
}

fun fmtDistance(meters: Int): String =
    if (meters >= 1000) String.format(Locale.US, "%.2f km", meters / 1000.0) else "$meters m"

fun fmtDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h${m}m" else "${m}m"
}

/** 区间起点：今天 0 点 / 昨天 0 点 / N 天前 0 点 */
fun rangeStart(kind: String, now: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return when (kind) {
        "yesterday" -> cal.timeInMillis - 86_400_000L
        "7d" -> cal.timeInMillis - 6 * 86_400_000L
        else -> cal.timeInMillis
    }
}

fun rangeEnd(kind: String, now: Long = System.currentTimeMillis()): Long = when (kind) {
    "yesterday" -> rangeStart("today", now) - 1
    else -> now
}
