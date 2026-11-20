package com.kezez.tracklink.tracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 轨迹记录前台服务。
 *
 * 省电策略（核心，全部参数由 App2 下发、本服务热应用）：
 * 1. 只在前台服务里订阅 GNSS，采样受「最小时间间隔 + 最小位移」双重约束，静止时不产生点；
 * 2. 连续 sleepAfterMin 分钟没有定位 → 注销 GNSS 更新（释放定位芯片）进入休眠，0 = 不休眠；
 * 3. 休眠期间只挂 TYPE_SIGNIFICANT_MOTION 硬件触发器（超低功耗），检测到位移立刻唤醒 GNSS；
 * 4. 点位先落盘（离线队列），每 batchSeconds 秒批量上传一次，单次最多 200 点，上传时短暂持唤醒锁；
 * 5. 没有新点时每 heartbeatS 秒发一次心跳，保证服务端在线状态准确，不额外唤醒 GPS；
 * 6. 每次上报成功后节流拉取下发配置，参数变化即时生效（重注册定位 + 重排循环）。
 */
class TrackService : Service() {

    companion object {
        const val ACTION_START = "com.kezez.tracklink.action.START"

        /** 仅刷新前台通知（渠道/静默状态可能已变），由设置页发起 */
        const val ACTION_REFRESH = "com.kezez.tracklink.action.REFRESH"
        const val ACTION_STOP = "com.kezez.tracklink.action.STOP"
        private const val TAG = "TrackService"
        private const val CHANNEL_ID = "tracklink_tracking"
        private const val CHANNEL_SILENT_ID = "tracklink_silent"
        private const val NOTIFICATION_ID = 4101
        private const val IDLE_CHECK_MS = 15_000L
        private const val NOTIFY_TICK_MS = 30_000L

        /** GPS 无 fix 多久后触发高德网络定位补点 */
        private const val AMAP_FALLBACK_AFTER_MS = 3 * 60_000L
        /** 高德补点定位最小间隔（省流量/防频繁请求） */
        private const val AMAP_RETRY_INTERVAL_MS = 60_000L

        /** 休眠探测窗口：短暂开启定位判断是否在移动，窗口结束仍无定位则继续睡 */
        private const val PROBE_WINDOW_MS = 45_000L

        /** 配置拉取节流：最短间隔。下发后约 1~2 分钟内生效（设备量小，请求成本可忽略） */
        private const val CONFIG_FETCH_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, TrackService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var queue: QueueStore
    private lateinit var api: ApiClient
    private lateinit var batteryManager: BatteryManager
    private lateinit var powerManager: PowerManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var lastAccepted: TrackPoint? = null
    private var gpsRegistered = false
    private var motionArmed = false
    private var uploading = false
    private var sessionDistance = 0.0
    private var lastHeartbeatAt = 0L
    private var lastUploadAt = 0L
    private var startedAt = 0L
    private var significantMotionSensor: Sensor? = null
    private var probeStartAt = 0L
    private var probeWindowOpen = false

    /** 高德网络定位补点：无 GMS 设备的系统 NETWORK_PROVIDER 常不可用，用它 WiFi/基站定位 */
    private var amapClient: AMapLocationClient? = null
    private var amapRequesting = false
    private var lastAmapTryAt = 0L
    private val amapListener = AMapLocationListener { location ->
        amapRequesting = false
        if (location == null || location.errorCode != AMapLocation.LOCATION_SUCCESS) {
            Log.d(TAG, "高德补点定位失败：${location?.errorCode} ${location?.errorInfo}")
            return@AMapLocationListener
        }
        // GCJ-02 → WGS-84（服务端统一存 GPS 原始坐标）
        val (wLat, wLon) = CoordConvert.gcj02ToWgs84(location.latitude, location.longitude)
        val loc = Location("amap")
        loc.latitude = wLat
        loc.longitude = wLon
        loc.accuracy = location.accuracy
        loc.time = if (location.time > 0) location.time else System.currentTimeMillis()
        handleLocation(loc)
    }

    /** GNSS 卫星状态：统计可见/参与定位数并按星座汇总 */
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var visible = 0
            var used = 0
            val counts = LinkedHashMap<String, IntArray>()
            for (i in 0 until status.satelliteCount) {
                val name = when (status.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> "GPS"
                    GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
                    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                    GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
                    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                    else -> "其他"
                }
                val arr = counts.getOrPut(name) { IntArray(2) }
                arr[0]++
                visible++
                if (status.usedInFix(i)) {
                    arr[1]++
                    used++
                }
            }
            TrackingState.satellitesVisible.value = visible
            TrackingState.satellitesUsed.value = used
            TrackingState.gnssSummary.value =
                counts.entries.joinToString(" · ") { "${it.key} ${it.value[1]}/${it.value[0]}" }
        }

        override fun onStopped() {
            TrackingState.satellitesVisible.value = 0
            TrackingState.satellitesUsed.value = 0
            TrackingState.gnssSummary.value = ""
        }

        override fun onStarted() = Unit
        override fun onFirstFix(ttffMillis: Int) = Unit
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            TrackingState.note("定位开关已关闭：$provider")
        }

        @Deprecated("系统回调，保留空实现")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_SIGNIFICANT_MOTION) return
            motionArmed = false
            if (!gpsRegistered) {
                cancelWakeProbe()
                registerLocation()
                TrackingState.note("检测到移动，已唤醒定位")
                notifyChanged()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * 休眠唤醒兑底：部分设备没有 SignificantMotion 传感器或触发不可靠，
     * 只靠它会永远睡下去。休眠期间每隔一个心跳周期短暂开一次定位（探测窗口），
     * 窗口内拿到定位且位移足够 → 保持唤醒；没动/拿不到定位 → 继续休眠。
     */
    private val wakeProbe = object : Runnable {
        override fun run() {
            if (gpsRegistered) return
            probeStartAt = System.currentTimeMillis()
            probeWindowOpen = true
            TrackingState.note("待机探测：短暂开启定位检查移动")
            registerLocation()
            // 室内探测窗口 GPS 往往拿不到，同步走高德网络定位补点
            maybeAmapFallback(force = true)
            handler.postDelayed({
                probeWindowOpen = false
                if (!gpsRegistered) return@postDelayed
                // 窗口内没有任何定位（室内/信号差）→ 继续休眠；有定位由 handleLocation 决定去留
                if (TrackingState.lastFixAt.value <= probeStartAt) sleepLocation()
            }, PROBE_WINDOW_MS)
        }
    }

    private val notifyTick = object : Runnable {
        override fun run() {
            notifyChanged()
            handler.postDelayed(this, NOTIFY_TICK_MS)
        }
    }

    private val uploadTick = object : Runnable {
        override fun run() {
            if (queue.size > 0) {
                triggerUpload()
            } else if (System.currentTimeMillis() - lastHeartbeatAt > prefs.heartbeatS * 1000L) {
                triggerUpload(heartbeatOnly = true)
            }
            // 周期来自下发配置，可能被热更新，所以每次按最新值重排
            handler.postDelayed(this, prefs.batchSeconds * 1000L)
        }
    }

    private val idleTick = object : Runnable {
        override fun run() {
            val idleMin = prefs.idleTimeoutMin
            val idleMs = idleMin * 60_000L
            val lastActive = maxOf(TrackingState.lastFixAt.value, startedAt)
            if (idleMin > 0 && gpsRegistered && System.currentTimeMillis() - lastActive > idleMs) {
                sleepLocation()
            }
            // GPS 活跃但长时间无 fix（室内/城市峡谷）→ 高德网络定位补点
            maybeAmapFallback()
            handler.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        locationManager = getSystemService(LocationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        batteryManager = getSystemService(BatteryManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        queue = QueueStore(this)
        api = ApiClient(prefs)
        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        createChannel()
        // 高德定位 SDK 隐私合规（同地图 SDK，不调不工作）
        runCatching {
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
        }
        TrackingState.pending.value = queue.size
        // GNSS 卫星状态监听：只在 GNSS 引擎活动时回调，常驻注册无额外功耗
        if (hasLocationPermission()) {
            runCatching {
                locationManager.registerGnssStatusCallback(gnssCallback, handler)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REFRESH) {
            // 仅刷新通知外观（常驻通知开关切换后由设置页发起）
            if (TrackingState.running.value) {
                notifyChanged()
                return START_STICKY
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        val shouldTrack = intent?.action == ACTION_START || prefs.tracking
        if (!shouldTrack) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission()) {
            TrackingState.note("缺少定位权限，无法开始记录")
            shutdown()
            return START_NOT_STICKY
        }

        prefs.tracking = true
        TrackingState.running.value = true
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        if (!gpsRegistered) registerLocation()
        armMotionSensor()
        scheduleLoops()
        TrackingState.note("正在记录轨迹")
        notifyChanged()
        firstReport()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ---------------- 定位 ----------------

    @Suppress("MissingPermission", "DEPRECATION")
    private fun registerLocation() {
        if (!hasLocationPermission()) return
        val interval = prefs.intervalSec * 1000L
        val minDist = prefs.minDistanceM.toFloat()
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, interval, minDist, locationListener, Looper.getMainLooper(),
            )
            gpsRegistered = true
            startedAt = System.currentTimeMillis()
            TrackingState.sleeping.value = false
        }.onFailure {
            TrackingState.note("定位注册失败：${it.message}")
            return
        }
        // 网络定位补点：不依赖 isProviderEnabled 判断（部分 ROM 误报），直接尝试注册
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, interval * 2, minDist,
                locationListener, Looper.getMainLooper(),
            )
        }.onFailure { Log.d(TAG, "系统网络定位不可用：${it.message}") }
        // 融合定位（API 31+，有 GMS 的设备最准）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER, interval * 2, minDist,
                    locationListener, Looper.getMainLooper(),
                )
            }.onFailure { Log.d(TAG, "融合定位不可用：${it.message}") }
        }
    }

    @Suppress("MissingPermission")
    private fun sleepLocation() {
        if (!gpsRegistered) return
        runCatching { locationManager.removeUpdates(locationListener) }
        gpsRegistered = false
        probeWindowOpen = false
        TrackingState.sleeping.value = true
        TrackingState.note("静止中，定位已休眠（省电）")
        armMotionSensor()
        scheduleWakeProbe()
        notifyChanged()
    }

    private fun probeIntervalMs(): Long = maxOf(60_000L, prefs.heartbeatS * 1000L)

    /**
     * 首次启动（每次服务拉起记录时）：
     * 1. 立即上报一次心跳 → 服务端注册设备，管理端/查看端马上能看到；
     * 2. 立即拿首个定位 → 优先取系统各 Provider 的最近缓存，缓存不可用则立刻走高德网络定位补点。
     */
    private fun firstReport() {
        triggerUpload(heartbeatOnly = true)
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }
        val fresh = providers.mapNotNull { p ->
            runCatching {
                if (locationManager.isProviderEnabled(p)) locationManager.getLastKnownLocation(p) else null
            }.getOrNull()
        }.maxByOrNull { it.time }
        if (fresh != null && System.currentTimeMillis() - fresh.time < 5 * 60_000L) {
            handleLocation(fresh)
        } else {
            // 缓存不可用 → 立即走高德网络定位（不等 GPS 3 分钟无 fix 的常规判定）
            maybeAmapFallback(force = true)
        }
    }

    /** GPS 长时间无 fix 时用高德网络定位补点（探测窗口强制触发） */
    private fun maybeAmapFallback(force: Boolean = false) {
        if (!hasLocationPermission()) return
        val now = System.currentTimeMillis()
        if (amapRequesting || now - lastAmapTryAt < AMAP_RETRY_INTERVAL_MS) return
        val gpsStale = TrackingState.lastFixAt.value == 0L ||
            now - TrackingState.lastFixAt.value > AMAP_FALLBACK_AFTER_MS
        val need = force || (gpsRegistered && gpsStale)
        if (!need) return
        lastAmapTryAt = now
        amapRequesting = true
        runCatching {
            val client = amapClient ?: AMapLocationClient(this).also { amapClient = it }
            val option = AMapLocationClientOption().apply {
                isOnceLocation = true
                locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
            }
            client.setLocationOption(option)
            client.setLocationListener(amapListener)
            client.startLocation()
        }.onFailure {
            amapRequesting = false
            Log.w(TAG, "高德补点定位启动失败：${it.message}")
        }
    }

    private fun scheduleWakeProbe() {
        handler.removeCallbacks(wakeProbe)
        if (!TrackingState.sleeping.value) return
        handler.postDelayed(wakeProbe, probeIntervalMs())
    }

    private fun cancelWakeProbe() {
        handler.removeCallbacks(wakeProbe)
        probeWindowOpen = false
    }

    private fun armMotionSensor() {
        val sensor = significantMotionSensor ?: return
        if (motionArmed || gpsRegistered) return
        motionArmed = runCatching {
            sensorManager.registerListener(motionListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
    }

    private fun handleLocation(location: Location) {
        val isProbe = probeWindowOpen
        TrackingState.lastFixAt.value = System.currentTimeMillis()

        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        if (accuracy > prefs.maxAccuracyM) {
            TrackingState.note("精度 ${accuracy.roundToInt()}m 太差，已丢弃")
            if (isProbe) {
                TrackingState.note("探测：定位质量差，继续休眠")
                sleepLocation()
            }
            return
        }

        val previous = lastAccepted
        if (previous != null) {
            val dtSec = (location.time - previous.ts) / 1000.0
            if (dtSec <= 0) return
            val moved = distanceMeters(previous.lat, previous.lon, location.latitude, location.longitude)
            if (moved / dtSec > 60.0 && accuracy > 20f) {
                TrackingState.note("检测到异常跳点，已丢弃")
                return
            }
            if (moved < prefs.minDistanceM) {
                // 探测窗口内确认没位移 → 立即回去休眠，不白白开着 GPS
                if (isProbe) {
                    TrackingState.note("探测：未检测到位移，继续休眠")
                    sleepLocation()
                }
                return
            }
            sessionDistance += moved
        }

        val point = TrackPoint(
            ts = location.time,
            lat = location.latitude,
            lon = location.longitude,
            acc = accuracy,
            speed = if (location.hasSpeed()) location.speed else 0f,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            alt = if (location.hasAltitude()) location.altitude else 0.0,
            battery = batteryPercent(),
        )
        lastAccepted = point
        queue.add(point)

        TrackingState.pending.value = queue.size
        TrackingState.recorded.value += 1
        TrackingState.distanceM.value = sessionDistance
        TrackingState.note("已记录 ${TrackingState.recorded.value} 点 · 精度 ${accuracy.roundToInt()}m")

        if (queue.size >= 20) triggerUpload()
        notifyChanged()
    }

    // ---------------- 上传 ----------------

    private fun scheduleLoops() {
        handler.removeCallbacks(notifyTick)
        handler.removeCallbacks(uploadTick)
        handler.removeCallbacks(idleTick)
        handler.post(notifyTick)
        handler.postDelayed(uploadTick, prefs.batchSeconds * 1000L)
        handler.postDelayed(idleTick, IDLE_CHECK_MS)
    }

    /**
     * 拉取服务端下发的采样/省电配置（节流）。
     * 有变化时：重新注册定位（新间隔/位移生效）+ 重排上报循环，无需重启服务。
     */
    private fun maybeFetchConfig() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastConfigFetchAt < CONFIG_FETCH_INTERVAL_MS) return
        prefs.lastConfigFetchAt = now
        when (val r = api.fetchConfig()) {
            is ConfigResult.Ok -> {
                if (r.changed) {
                    TrackingState.note("配置已更新：${prefs.summary()}")
                    Log.i(TAG, "收到下发配置并已应用：${prefs.summary()}")
                    if (gpsRegistered) {
                        runCatching { locationManager.removeUpdates(locationListener) }
                        gpsRegistered = false
                        registerLocation()
                    }
                    handler.post { scheduleLoops() }
                    // 心跳间隔可能变了，休眠中则按新周期重排探测
                    if (TrackingState.sleeping.value) scheduleWakeProbe()
                    notifyChanged()
                }
            }
            ConfigResult.AuthError -> Log.w(TAG, "拉取配置被拒：上传密钥不正确")
            is ConfigResult.Failed -> Log.w(TAG, "拉取配置失败：${r.message}")
        }
    }

    private fun triggerUpload(heartbeatOnly: Boolean = false) {
        if (uploading) return
        uploading = true
        scope.launch {
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackLink:upload")
            runCatching { wakeLock.acquire(30_000L) }
            try {
                var rounds = 0
                while (rounds++ < 20) {
                    val batch = queue.snapshot().take(QueueStore.BATCH_SIZE)
                    if (batch.isEmpty() && !(heartbeatOnly && rounds == 1)) break
                    when (val result = api.upload(batch, batteryPercent())) {
                        is UploadResult.Ok -> {
                            if (batch.isNotEmpty()) queue.removeUploaded(batch.size)
                            lastUploadAt = System.currentTimeMillis()
                            lastHeartbeatAt = lastUploadAt
                            TrackingState.lastUploadAt.value = lastUploadAt
                            TrackingState.pending.value = queue.size
                            TrackingState.serverOk.value = true
                            TrackingState.note(
                                if (batch.isEmpty()) "心跳已上报" else "已上传 ${batch.size} 点",
                            )
                            // 顺带检查 App2 是否下发了新配置
                            maybeFetchConfig()
                            if (batch.isEmpty()) break
                        }
                        UploadResult.AuthError -> {
                            TrackingState.serverOk.value = false
                            TrackingState.note("上传密钥不正确，请核对后重装或联系管理员")
                            Log.w(TAG, "上传被拒绝：上传密钥不正确")
                            break
                        }
                        is UploadResult.Failed -> {
                            TrackingState.serverOk.value = false
                            TrackingState.note("上传失败：${result.message}（稍后重试）")
                            Log.w(TAG, "上传失败：${result.message}")
                            break
                        }
                    }
                }
            } finally {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
                uploading = false
                handler.post { notifyChanged() }
            }
        }
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "轨迹记录", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "轨迹记录服务运行通知"
            setShowBadge(false)
            enableVibration(false)
        }
        // 静默渠道：仅用于 startForeground 合规发布的瞬间（重要性最低），随后即被撤销，通知栏不显示
        val silent = NotificationChannel(
            CHANNEL_SILENT_ID, "轨迹记录（静默）", NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "隐藏状态栏图标，后台记录不受影响"
            setShowBadge(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            createNotificationChannel(silent)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = if (TrackingState.sleeping.value) "静止休眠中" else "定位中"
        val text = "$state · 已记录 ${TrackingState.recorded.value} 点 · 待上传 ${queue.size}"
        return NotificationCompat.Builder(
            this,
            if (prefs.silentNotification) CHANNEL_SILENT_ID else CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("迹录 · 正在记录轨迹")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${TrackingState.message.value}"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // startForeground 合规发布后，静默模式立即撤销通知：服务保持前台优先级，通知栏完全不显示
        notifyChanged()
    }

    private fun notifyChanged() {
        if (!TrackingState.running.value) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (prefs.silentNotification) {
                // 静默：撤销通知（周期刷新/切开关时反复调用，幂等）
                nm.cancel(NOTIFICATION_ID)
            } else {
                nm.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    // ---------------- 收尾 ----------------

    @Suppress("MissingPermission")
    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { sensorManager.unregisterListener(motionListener) }
        runCatching { amapClient?.stopLocation(); amapClient?.onDestroy(); amapClient = null }
        amapRequesting = false
        motionArmed = false
        gpsRegistered = false
        scope.cancel()
    }

    private fun shutdown() {
        teardown()
        prefs.tracking = false
        TrackingState.running.value = false
        TrackingState.sleeping.value = false
        TrackingState.note("已停止记录")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun batteryPercent(): Int = runCatching {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }.getOrDefault(-1)

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }
}
