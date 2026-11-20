package com.kezez.tracklink.viewer

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84（GPS/北斗原始坐标，服务端存的就是它）→ GCJ-02（高德坐标系）。
 * 轨迹点不转换直接画在高德上会整体偏移约 300~600 米。
 * 境外点（高德无偏移区域）原样返回。
 */
object CoordConvert {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return lat to lon
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

/**
 * 高德底图上的轨迹渲染封装：与 osmdroid 路径（TrackScreen 的 mapRef 分支）平行的实现。
 * 生命周期由 TrackScreen 的 AndroidView/DisposableEffect 驱动。
 */
class AmapTrackView(context: Context) {

    val view: MapView = MapView(context)

    private var amap: AMap? = null
    private var polyline: Polyline? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var currentMarker: Marker? = null
    private var myLocationMarker: Marker? = null
    private var fittedOnce = false

    /** GCJ-02 轨迹序列缓存，供回放复用 */
    private var gcjPoints: List<LatLng> = emptyList()

    fun onCreate(savedInstanceState: Bundle?) {
        // 高德 SDK（8.1.0+）强制隐私合规：未调用时 SDK 拒绝工作，地图永远空白
        runCatching {
            MapsInitializer.updatePrivacyShow(view.context, true, true)
            MapsInitializer.updatePrivacyAgree(view.context, true)
        }
        view.onCreate(savedInstanceState)
        amap = view.map.apply {
            mapType = AMap.MAP_TYPE_NORMAL
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isMyLocationButtonEnabled = false
            isTrafficEnabled = false
        }
    }

    fun onResume() = view.onResume()
    fun onPause() = view.onPause()

    fun onDestroy() {
        clearOverlays()
        view.onDestroy()
        amap = null
    }

    fun onSaveInstanceState(outState: Bundle) = view.onSaveInstanceState(outState)

    /** 画整条轨迹（输入为 WGS-84），并自适应视野（SDK 内部异常不外抛，避免闪退） */
    fun render(points: List<TrackPointView>) {
        runCatching {
            val map = amap ?: return
            clearOverlays()
            fittedOnce = false
            if (points.isEmpty()) return

        gcjPoints = points.map { p ->
            val (la, lo) = CoordConvert.wgs84ToGcj02(p.lat, p.lon)
            LatLng(la, lo)
        }
        polyline = map.addPolyline(
            PolylineOptions()
                .addAll(gcjPoints)
                .width(12f)
                .color(Color.parseColor("#0E9F6E")),
        )
        startMarker = map.addMarker(
            MarkerOptions().position(gcjPoints.first()).title("起点")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)),
        )
        if (gcjPoints.size > 1) {
            endMarker = map.addMarker(
                MarkerOptions().position(gcjPoints.last()).title("终点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
            )
        }
        currentMarker = map.addMarker(
            MarkerOptions().position(gcjPoints.first()).title("当前位置")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
        )
            fitBounds()
        }.onFailure { android.util.Log.w("AmapTrack", "render 失败：${it.message}") }
    }

    /** 回放：把当前位置标记挪到第 index 个点 */
    fun moveCurrent(index: Int, follow: Boolean) {
        runCatching {
            val pos = gcjPoints.getOrNull(index) ?: return
            currentMarker?.position = pos
            if (follow) amap?.animateCamera(CameraUpdateFactory.changeLatLng(pos), 200, null)
        }.onFailure { android.util.Log.w("AmapTrack", "moveCurrent 失败：${it.message}") }
    }

    /** 显示本机位置标记（输入 WGS-84，内部转 GCJ-02），并移动相机到该点 */
    fun showMyLocation(lat: Double, lon: Double, zoom: Float = 16f) {
        runCatching {
            val map = amap ?: return
            val (la, lo) = CoordConvert.wgs84ToGcj02(lat, lon)
            val pos = LatLng(la, lo)
            if (myLocationMarker == null) {
                myLocationMarker = map.addMarker(
                    MarkerOptions().position(pos).title("本机位置")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)),
                )
            } else {
                myLocationMarker?.position = pos
            }
            amap?.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, zoom), 300, null)
        }.onFailure { android.util.Log.w("AmapTrack", "showMyLocation 失败：${it.message}") }
    }

    /** 隐藏本机位置标记 */
    fun hideMyLocation() {
        runCatching {
            myLocationMarker?.remove()
            myLocationMarker = null
        }
    }

    /** 回到轨迹全景视野（从本机位置模式返回时用） */
    fun refit() {
        fittedOnce = false
        fitBounds()
    }

    private fun fitBounds() {
        val map = amap ?: return
        if (gcjPoints.isEmpty() || fittedOnce) return
        val builder = LatLngBounds.Builder()
        gcjPoints.forEach { builder.include(it) }
        val update = CameraUpdateFactory.newLatLngBounds(builder.build(), 80)
        // 地图没完成首次布局时 newLatLngBounds 会抛异常，挂到 loaded 回调兜底
        runCatching { map.moveCamera(update) }
            .onFailure {
                map.setOnMapLoadedListener { map.moveCamera(update) }
            }
        fittedOnce = true
    }

    private fun clearOverlays() {
        polyline?.remove(); polyline = null
        startMarker?.remove(); startMarker = null
        endMarker?.remove(); endMarker = null
        currentMarker?.remove(); currentMarker = null
        myLocationMarker?.remove(); myLocationMarker = null
    }
}
