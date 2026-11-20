package com.kezez.tracklink.viewer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

import kotlin.math.abs

private const val DAY_MS = 86_400_000L

private val AxisTrackColor = Color(0xFFD5DDE4)
private val AxisTickColor = Color(0xFFB7C2CC)
private val AxisLabelColor = Color(0xFF6B7A88)

@Composable
fun TrackScreen(
    api: ViewerApi,
    device: DeviceItem,
    onBack: () -> Unit,
    isAdmin: Boolean = false,
    onOpenConfig: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var range by rememberSaveable { mutableStateOf("today") }
    var customFrom by rememberSaveable { mutableStateOf(0L) }
    var customTo by rememberSaveable { mutableStateOf(0L) }
    var showRangePanel by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<TrackData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    val mapRef = remember { mutableStateOf<MapView?>(null) }
    val currentMarker = remember { mutableStateOf<Marker?>(null) }

    // 本机位置：默认展示轨迹；点「本机」定位并显示与轨迹的距离，再点「轨迹」回到全景
    var locateMode by remember { mutableStateOf(false) }
    var myLocInfo by remember { mutableStateOf("") }
    var locateRequest by remember { mutableStateOf(0) }
    val myMarkerRef = remember { mutableStateOf<Marker?>(null) }

    // 高德 SDK 引擎（设置里选了「高德」时启用，与 osmdroid 互斥）
    val useAmap = remember { TileSources.usingAmap(context) }
    val amapRef = remember { mutableStateOf<AmapTrackView?>(null) }

    // 底图源 / Key 变化时重建地图（App 内改完设置回来立即生效）
    val tdtKey = remember { TileSources.signatureOf(context) }

    val locPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) locateRequest++ else myLocInfo = "没有定位权限"
    }

    LaunchedEffect(range, customFrom, customTo) {
        loading = true
        error = ""
        playing = false
        try {
            val from = if (range == "custom" && customFrom > 0) customFrom else rangeStart(range)
            val to = if (range == "custom" && customTo > 0) customTo else rangeEnd(range)
            data = withContext(Dispatchers.IO) { api.track(device.id, from, to) }
        } catch (e: Exception) {
            error = e.message ?: "加载轨迹失败"
            data = null
        } finally {
            loading = false
        }
    }

    val points = data?.points ?: emptyList()

    LaunchedEffect(data, mapRef.value, amapRef.value) {
        amapRef.value?.let { amapView ->
            progress = 0f
            amapView.render(points)
            return@LaunchedEffect
        }
        val map = mapRef.value ?: return@LaunchedEffect
        map.overlays.removeAll { it is Polyline || it is Marker }
        currentMarker.value = null
        progress = 0f
        if (points.isEmpty()) {
            map.invalidate()
            return@LaunchedEffect
        }
        val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
        if (geoPoints.size > 1) {
            val polyline = Polyline(map).apply {
                outlinePaint.color = 0xFF0E9F6E.toInt()
                outlinePaint.strokeWidth = 10f
                setPoints(geoPoints)
            }
            map.overlays.add(polyline)
        }
        map.overlays.add(
            Marker(map).apply {
                position = geoPoints.first()
                title = "起点"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            },
        )
        if (geoPoints.size > 1) {
            map.overlays.add(
                Marker(map).apply {
                    position = geoPoints.last()
                    title = "终点"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                },
            )
        }
        currentMarker.value = Marker(map).apply {
            position = geoPoints.first()
            title = "当前位置"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        currentMarker.value?.let { map.overlays.add(it) }
        map.invalidate()
        fitTrackToMap(map, geoPoints, context)
    }

    LaunchedEffect(playing, data) {
        if (!playing) return@LaunchedEffect
        if (points.size <= 1) {
            playing = false
            return@LaunchedEffect
        }
        while (playing && progress < points.size - 1) {
            delay(120)
            progress = (progress + 1f).coerceAtMost((points.size - 1).toFloat())
        }
        playing = false
    }

    LaunchedEffect(progress, data) {
        val point = points.getOrNull(progress.toInt()) ?: return@LaunchedEffect
        amapRef.value?.let { amapView ->
            amapView.moveCurrent(progress.toInt(), playing)
            return@LaunchedEffect
        }
        val map = mapRef.value ?: return@LaunchedEffect
        val geo = GeoPoint(point.lat, point.lon)
        currentMarker.value?.position = geo
        if (playing) map.controller.setCenter(geo)
        map.invalidate()
    }

    // 「本机」定位：拿到 WGS-84 位置 → 算距轨迹最近距离 → 地图移动过去并放标记
    LaunchedEffect(locateRequest) {
        if (locateRequest == 0 || !locateMode) return@LaunchedEffect
        myLocInfo = "定位中…"
        val loc = withContext(Dispatchers.IO) { fetchMyLocation(context) }
        if (loc == null) {
            myLocInfo = "定位失败，请检查定位开关"
            return@LaunchedEffect
        }
        val dist = nearestTrackDistanceM(loc.latitude, loc.longitude, points)
        myLocInfo = when {
            points.isEmpty() -> "本机位置已标记（该时段无轨迹）"
            else -> "距轨迹最近 ${fmtNearest(dist)}"
        }
        if (useAmap) {
            amapRef.value?.showMyLocation(loc.latitude, loc.longitude)
        } else {
            mapRef.value?.let { map ->
                val geo = GeoPoint(loc.latitude, loc.longitude)
                if (myMarkerRef.value == null) {
                    myMarkerRef.value = Marker(map).apply {
                        position = geo
                        title = "本机位置"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        map.overlays.add(this)
                    }
                } else {
                    myMarkerRef.value?.position = geo
                }
                map.controller.setZoom(16.0)
                map.controller.setCenter(geo)
                map.invalidate()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BgColor)) {
        key(tdtKey) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                if (useAmap) {
                    // 换底图重建时先释放旧实例（否则 native 资源泄漏/渲染异常）
                    amapRef.value?.runCatching { onDestroy() }
                    AmapTrackView(ctx).also {
                        it.onCreate(null)
                        amapRef.value = it
                        // 进入本页时 App 通常已处于前台，不会再触发 ON_RESUME 事件，
                        // 必须在创建后立即启动渲染循环，否则地图永远空白
                        it.onResume()
                    }.view
                } else {
                    // 必须在 MapView 构造前铺好离线包：tile provider 是构造时就建好的
                    TileSources.applyTuning(ctx)
                    MapView(ctx).apply {
                        TileSources.apply(this, ctx)
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(false)
                        controller.setZoom(15.0)
                        controller.setCenter(
                            GeoPoint(
                                device.lastPoint?.lat ?: 30.5,
                                device.lastPoint?.lon ?: 114.3,
                            ),
                        )
                        mapRef.value = this
                    }
                }
            },
        )
        }

        // 右侧悬浮：本机位置 / 回轨迹
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = {
                    if (!locateMode) {
                        locateMode = true
                        val fine = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (fine) locateRequest++ else locPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    } else {
                        locateMode = false
                        myLocInfo = ""
                        amapRef.value?.hideMyLocation()
                        myMarkerRef.value?.let { m ->
                            mapRef.value?.overlays?.remove(m)
                            myMarkerRef.value = null
                            mapRef.value?.invalidate()
                        }
                        // 回到轨迹全景
                        if (useAmap) {
                            amapRef.value?.refit()
                        } else {
                            mapRef.value?.let {
                                fitTrackToMap(it, points.map { p -> GeoPoint(p.lat, p.lon) }, context)
                            }
                        }
                    }
                },
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (locateMode) AccentGreen else PanelColor,
                    contentColor = if (locateMode) Color.White else TextPrimaryColor,
                ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    if (locateMode) "轨迹" else "本机",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
            if (myLocInfo.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .background(PanelColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(myLocInfo, fontSize = 10.sp, color = TextPrimaryColor)
                }
            }
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // 顶部信息卡
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelColor.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(10.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                            Text("← 返回", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(device.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        if (isAdmin && onOpenConfig != null) {
                            TextButton(
                                onClick = onOpenConfig,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) { Text("配置", fontSize = 13.sp) }
                        }
                        if (device.battery >= 0) {
                            Text("${device.battery}%", fontSize = 12.sp, color = DimColor)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        StatBlock("总里程", data?.let { fmtDistance(it.distance) } ?: "—")
                        StatBlock("点数", "${points.size}")
                        StatBlock("时长", data?.let { fmtDuration(it.duration) } ?: "—")
                        StatBlock("均速", data?.let { "${it.avgSpeed} km/h" } ?: "—")
                    }
                    if (error.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("⚠️ $error", fontSize = 11.sp, color = DangerColor)
                    }
                    // 心跳时间：设备最后一次上报（含心跳空包）的时刻，相对时间每 30s 重算
                    if (device.lastSeen > 0) {
                        Spacer(Modifier.height(4.dp))
                        var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                delay(30_000)
                                nowTick = System.currentTimeMillis()
                            }
                        }
                        val agoMin = ((nowTick - device.lastSeen) / 60_000L).coerceAtLeast(0)
                        Text(
                            "心跳 ${fmtTime(device.lastSeen)} · " +
                                if (agoMin < 1) "刚刚" else "${agoMin} 分钟前",
                            fontSize = 11.sp,
                            color = DimColor,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 底部分段选择 + 时间轴 + 回放
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelColor.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(10.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RangeChip("今天", range == "today") { range = "today"; showRangePanel = false }
                        RangeChip("昨天", range == "yesterday") { range = "yesterday"; showRangePanel = false }
                        RangeChip("近 7 天", range == "7d") { range = "7d"; showRangePanel = false }
                        RangeChip(customChipLabel(customFrom, customTo), range == "custom") {
                            if (range == "custom" && showRangePanel) {
                                showRangePanel = false
                            } else {
                                if (customFrom <= 0L || customTo <= 0L) {
                                    customFrom = dayStartOf(System.currentTimeMillis())
                                    customTo = System.currentTimeMillis()
                                }
                                range = "custom"
                                showRangePanel = true
                            }
                        }
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = AccentGreen)
                    }
                    // 滚动条式时间范围（监控/行车记录仪风格）：双把手拖动选起止，拖动结束即查询
                    if (showRangePanel) {
                        Spacer(Modifier.height(6.dp))
                        CustomRangePanel(
                            initialFrom = customFrom,
                            initialTo = customTo,
                            onCommit = { s, e ->
                                customFrom = s
                                customTo = e
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (points.size <= 1) return@Button
                                if (progress >= points.size - 1) progress = 0f
                                playing = !playing
                            },
                            modifier = Modifier.size(44.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(if (playing) "⏸" else "▶", fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Slider(
                            value = progress.coerceIn(0f, (points.size - 1).coerceAtLeast(1).toFloat()),
                            onValueChange = {
                                playing = false
                                progress = it
                            },
                            valueRange = 0f..(points.size - 1).coerceAtLeast(1).toFloat(),
                            enabled = points.size > 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val cur = points.getOrNull(progress.toInt())
                    Text(
                        if (cur == null) "该时段没有轨迹点"
                        else "第 ${progress.toInt() + 1}/${points.size} 点 · " +
                            SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(cur.ts)) +
                            " · 精度 ${cur.acc}m",
                        fontSize = 11.sp,
                        color = DimColor,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    // 只在离开页面时释放地图；不能把 mapRef 当 key，否则 key 变化会把自己置空
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> amapRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> amapRef.value?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapRef.value?.onDetach()
            amapRef.value?.onDestroy()
        }
    }
}

// ---------------- 本机位置 ----------------

/** 取一次本机位置：优先 5 分钟内的缓存定位，否则单次定位请求（阻塞，须在 IO 线程调用）。失败返回 null */
private fun fetchMyLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    val fresh = providers
        .mapNotNull { p ->
            runCatching { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }.getOrNull()
        }
        .filter { System.currentTimeMillis() - it.time < 5 * 60_000L }
        .maxByOrNull { it.time }
    if (fresh != null) return fresh

    val provider = providers.firstOrNull {
        runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
    } ?: return null

    // 单次定位：回调发生在主 looper，用 latch 等待（本函数运行在 IO 线程，不卡 UI）
    val result = java.util.concurrent.atomic.AtomicReference<Location?>(null)
    val latch = java.util.concurrent.CountDownLatch(1)
    val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            result.set(l)
            runCatching { lm.removeUpdates(this) }
            latch.countDown()
        }

        @Deprecated("系统回调，保留空实现")
        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
    }
    val registered = runCatching {
        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        true
    }.getOrDefault(false)
    if (registered) {
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { lm.removeUpdates(listener) }
    }
    return result.get()
}

/** 本机位置到轨迹的最近距离（米） */
private fun nearestTrackDistanceM(lat: Double, lon: Double, pts: List<TrackPointView>): Double {
    var best = Double.MAX_VALUE
    val r = FloatArray(1)
    for (p in pts) {
        Location.distanceBetween(lat, lon, p.lat, p.lon, r)
        if (r[0] < best) best = r[0].toDouble()
    }
    return best
}

private fun fmtNearest(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.2f km", meters / 1000.0)
    else "${meters.toInt()} m"

// ---------------- 轨迹视野 ----------------

/** osmdroid 自适应轨迹视野（宽高未测量时重试） */
private fun fitTrackToMap(
    map: MapView,
    geoPoints: List<GeoPoint>,
    context: Context,
    attempt: Int = 0,
) {
    map.post {
        if (map.width <= 0 || map.height <= 0) {
            if (attempt < 5) map.postDelayed({ fitTrackToMap(map, geoPoints, context, attempt + 1) }, 80)
            return@post
        }
        runCatching {
            if (geoPoints.size > 1) {
                val box = BoundingBox.fromGeoPoints(geoPoints)
                map.zoomToBoundingBox(box, false, 110)
                prefetchAround(map, context, box)
            } else if (geoPoints.isNotEmpty()) {
                map.controller.setZoom(17.0)
                map.controller.setCenter(geoPoints.first())
            }
            map.invalidate()
        }.onFailure { android.util.Log.w("TrackScreen", "轨迹缩放失败：${it.message}") }
    }
}

// ---------------- 自定义时间段（滚动条式，无弹框） ----------------

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp) })
}

private fun customChipLabel(from: Long, to: Long): String {
    if (from <= 0 || to <= 0) return "自定义"
    val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)
    return "自定义 ${fmt.format(Date(from))} ~ ${fmt.format(Date(to))}"
}

private fun dayStartOf(millis: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dateFmt = SimpleDateFormat("MM/dd", Locale.CHINA)
private val fullFmt = SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)

/**
 * 自定义时间段面板：日期微调 + 双把手滚动时间轴（监控/行车记录仪风格）。
 * 拖动过程只更新本地状态，松手后才回调 onCommit 触发轨迹查询。
 */
@Composable
private fun CustomRangePanel(initialFrom: Long, initialTo: Long, onCommit: (Long, Long) -> Unit) {
    val now = System.currentTimeMillis()
    var startDay by remember { mutableStateOf(dayStartOf(if (initialFrom > 0) initialFrom else now)) }
    var endDay by remember { mutableStateOf(dayStartOf(if (initialTo > 0) initialTo else now)) }
    val fromState = remember { mutableStateOf(if (initialFrom > 0) initialFrom else dayStartOf(now)) }
    val toState = remember { mutableStateOf(if (initialTo > 0) initialTo else now) }

    fun commit() = onCommit(fromState.value, toState.value)

    Column {
        // 日期微调行：开始 ‹ MM/dd ›    结束 ‹ MM/dd ›
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateStepper("开始", startDay) { delta ->
                val newDay = startDay + delta * DAY_MS
                startDay = newDay
                if (startDay > endDay) endDay = startDay
                // 开始日期后移时把起点时刻约束进新日界
                if (fromState.value < startDay) fromState.value = startDay
                if (fromState.value > toState.value) toState.value = fromState.value
                commit()
            }
            Spacer(Modifier.weight(1f))
            DateStepper("结束", endDay) { delta ->
                val newDay = endDay + delta * DAY_MS
                endDay = newDay
                if (endDay < startDay) startDay = endDay
                // 结束日期前移时把终点时刻约束进新日界（含当天最后一毫秒）
                if (toState.value > endDay + DAY_MS - 1) toState.value = endDay + DAY_MS - 1
                if (fromState.value > toState.value) fromState.value = toState.value
                commit()
            }
        }
        Spacer(Modifier.height(4.dp))
        // 起止时刻显示
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "开始 " + timeFmt.format(Date(fromState.value)),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = AccentGreen, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "结束 " + timeFmt.format(Date(toState.value)),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = TextPrimaryColor, fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(2.dp))
        TimeRangeAxis(
            axisStart = startDay,
            axisEnd = endDay + DAY_MS - 1,
            fromState = fromState,
            toState = toState,
            onDragEnd = { commit() },
        )
    }
}

/** 日期一行：标签 + ‹ 日期 ›，点击箭头 ±1 天 */
@Composable
private fun DateStepper(label: String, day: Long, onDelta: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = DimColor)
        IconButton(onClick = { onDelta(-1) }, modifier = Modifier.size(30.dp)) {
            Text("‹", fontSize = 16.sp, color = TextPrimaryColor, fontWeight = FontWeight.SemiBold)
        }
        Text(
            dateFmt.format(Date(day)),
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = TextPrimaryColor, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(46.dp),
        )
        IconButton(onClick = { onDelta(1) }, modifier = Modifier.size(30.dp)) {
            Text("›", fontSize = 16.sp, color = TextPrimaryColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 双把手时间轴：横轴覆盖 [axisStart, axisEnd]（本地毫秒），拖动精度 1 分钟。
 * 状态经 MutableState 直读写（拖动回调里保证拿到最新值），刻度按跨度自适应。
 */
@Composable
private fun TimeRangeAxis(
    axisStart: Long,
    axisEnd: Long,
    fromState: MutableState<Long>,
    toState: MutableState<Long>,
    onDragEnd: () -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    var width by remember { mutableStateOf(0f) }
    val from = fromState.value
    val to = toState.value
    val span = (axisEnd - axisStart).coerceAtLeast(60_000L)
    val pxPerMs = if (width > 0f) width / span.toDouble() else 0.0

    var dragWhich by remember { mutableStateOf(0) }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(axisStart, axisEnd) {
                detectDragGestures(
                    onDragStart = { off ->
                        val f = ((fromState.value - axisStart) * pxPerMs).toFloat()
                        val t = ((toState.value - axisStart) * pxPerMs).toFloat()
                        dragWhich = if (abs(off.x - f) <= abs(off.x - t)) 0 else 1
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (pxPerMs <= 0.0) return@detectDragGestures
                        // 拖动粒度：1 分钟
                        val dMs = (amount.x / pxPerMs / 60_000.0).toLong() * 60_000L
                        if (dragWhich == 0) {
                            fromState.value = (fromState.value + dMs).coerceIn(axisStart, toState.value)
                        } else {
                            toState.value = (toState.value + dMs).coerceIn(fromState.value, axisEnd - 1)
                        }
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        val w = size.width
        val axisY = size.height * 0.45f
        val fX = ((from - axisStart).toDouble() / span * w).toFloat()
        val tX = ((to - axisStart).toDouble() / span * w).toFloat()

        // 轨道 + 选区
        drawRoundRect(
            AxisTrackColor,
            topLeft = Offset(0f, axisY - 5f),
            size = Size(w, 10f),
            cornerRadius = CornerRadius(5f),
        )
        drawRoundRect(
            AccentGreen,
            topLeft = Offset(fX, axisY - 5f),
            size = Size((tX - fX).coerceAtLeast(2f), 10f),
            cornerRadius = CornerRadius(5f),
        )

        // 刻度：跨度自适应（≤36h 每 3 小时；≤3 天每 6 小时；否则每天 0 点）
        val stepMs = when {
            span <= 36 * 3_600_000L -> 3 * 3_600_000L
            span <= 3 * DAY_MS -> 6 * 3_600_000L
            else -> DAY_MS
        }
        var tick = axisStart
        while (tick <= axisEnd) {
            val x = ((tick - axisStart).toDouble() / span * w).toFloat()
            drawLine(AxisTickColor, Offset(x, axisY - 11f), Offset(x, axisY + 11f), 2f)
            val label = if (stepMs >= DAY_MS) dateFmt.format(Date(tick)) else timeFmt.format(Date(tick))
            drawText(
                textMeasurer,
                label,
                topLeft = Offset(x - 22f, axisY + 13f),
                style = TextStyle(fontSize = 9.sp, color = AxisLabelColor),
            )
            tick += stepMs
        }

        // 把手：白底绿描边圆点
        drawCircle(Color.White, radius = 10f, center = Offset(fX, axisY))
        drawCircle(AccentGreen, radius = 10f, center = Offset(fX, axisY), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawCircle(Color.White, radius = 10f, center = Offset(tX, axisY))
        drawCircle(AccentGreen, radius = 10f, center = Offset(tX, axisY), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = DimColor)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 轨迹周边瓦片预取。
 *
 * 首屏只加载当前视野的十几张瓦片，用户一拖动又要等网络。这里在后台把轨迹周边
 * z13~z16 的瓦片批量拉进本地缓存（上限 200MB），之后拖动、回放基本不用再等网络。
 */
private fun prefetchAround(map: MapView, context: Context, box: BoundingBox) {
    runCatching {
        val latSpan = (box.latNorth - box.latSouth).coerceAtLeast(0.004)
        val lonSpan = (box.lonEast - box.lonWest).coerceAtLeast(0.004)
        // 视野外扩 40%，覆盖用户拖动一屏的范围
        val padLat = latSpan * 0.4
        val padLon = lonSpan * 0.4
        val area = BoundingBox(
            box.latNorth + padLat, box.lonEast + padLon,
            box.latSouth - padLat, box.lonWest - padLon,
        )
        val cm = CacheManager(map)
        // 瓦片太多就只预取到 z15，避免一次拉几百张
        val maxZoom = if (cm.possibleTilesInArea(area, 13, 16) > 400) 15 else 16
        val count = cm.possibleTilesInArea(area, 13, maxZoom)
        android.util.Log.d("TrackScreen", "预取 z13-$maxZoom 共 $count 张")
        cm.downloadAreaAsyncNoUI(context, area, 13, maxZoom, null)
    }.onFailure { android.util.Log.w("TrackScreen", "预取失败：${it.message}") }
}
