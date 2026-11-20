package com.kezez.tracklink.tracker

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 高德定位 SDK 返回 GCJ-02（火星坐标），服务端/轨迹统一存 WGS-84（GPS 原始坐标）。
 * 逆变换用迭代法：正向变换两次逼近，残差 < 1 米，室内网络定位精度下完全够用。
 */
object CoordConvert {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        // 境外（高德无偏移区域）原样返回
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return lat to lon
        var wLat = lat
        var wLon = lon
        repeat(3) {
            val (gLat, gLon) = wgs84ToGcj02(wLat, wLon)
            wLat += lat - gLat
            wLon += lon - gLon
        }
        return wLat to wLon
    }

    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val offsetLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        val offsetLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return (lat + offsetLat) to (lon + offsetLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
