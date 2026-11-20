package com.kezez.tracklink.tracker

import org.json.JSONArray
import org.json.JSONObject

/** 一个待上传的轨迹点（时间戳毫秒，速度 m/s） */
data class TrackPoint(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val speed: Float,
    val bearing: Float,
    val alt: Double,
    val battery: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("lat", lat)
        put("lon", lon)
        put("acc", acc.round1())
        put("spd", speed.round1())
        put("brg", bearing.round1())
        put("alt", alt.round1())
        put("bat", battery)
    }

    companion object {
        fun fromJson(o: JSONObject): TrackPoint = TrackPoint(
            ts = o.optLong("ts"),
            lat = o.optDouble("lat"),
            lon = o.optDouble("lon"),
            acc = o.optDouble("acc", 0.0).toFloat(),
            speed = o.optDouble("spd", 0.0).toFloat(),
            bearing = o.optDouble("brg", 0.0).toFloat(),
            alt = o.optDouble("alt", 0.0),
            battery = o.optInt("bat", -1),
        )

        fun listToJson(points: List<TrackPoint>): String {
            val arr = JSONArray()
            points.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String): List<TrackPoint> = try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = fromJson(o)
                    if (p.lat in -90.0..90.0 && p.lon in -180.0..180.0 && p.ts > 0) add(p)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private fun Float.round1(): Double = (Math.round(this * 10.0) / 10.0)
private fun Double.round1(): Double = Math.round(this * 10.0) / 10.0
