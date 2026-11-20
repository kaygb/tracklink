package com.kezez.tracklink.tracker

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 亮色主题：浅灰底 + 白卡片 + 深色正文；绿 Accent 加深到 #0E9F6E 保证白底上 ≥ 4.5:1
private val Accent = Color(0xFF0E9F6E)
private val Panel = Color(0xFFFFFFFF)
private val Bg = Color(0xFFF4F6F8)
private val TextPrimary = Color(0xFF1A2530)
private val TextSecondary = Color(0xFF4A5A68)
private val TextFaint = Color(0xFF6B7A88)
private val Warn = Color(0xFFB45309)
private val Danger = Color(0xFFD93025)

/** 最近任务卡片隐藏开关：运行时即时生效，无需重启应用 */
private fun applyRecentsHidden(context: Context, hidden: Boolean) {
    runCatching {
        context.getSystemService(ActivityManager::class.java)
            .appTasks
            .forEach { it.setExcludeFromRecents(hidden) }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        applyRecentsHidden(this, prefs.hideRecents)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Accent,
                    onPrimary = Color(0xFF06170F),
                    background = Bg,
                    surface = Panel,
                    onSurface = TextPrimary,
                ),
            ) {
                TrackerScreen(prefs)
            }
        }
        // 支持外部拉起直接开始记录（供自动化部署/快捷方式使用）
        if (intent?.getBooleanExtra("start_tracking", false) == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            TrackService.start(this)
        }
    }
}

@Composable
private fun TrackerScreen(prefs: Prefs) {
    val context = LocalContext.current

    val running by TrackingState.running.collectAsStateWithLifecycle()
    val sleeping by TrackingState.sleeping.collectAsStateWithLifecycle()
    val pending by TrackingState.pending.collectAsStateWithLifecycle()
    val recorded by TrackingState.recorded.collectAsStateWithLifecycle()
    val distance by TrackingState.distanceM.collectAsStateWithLifecycle()
    val lastUpload by TrackingState.lastUploadAt.collectAsStateWithLifecycle()
    val message by TrackingState.message.collectAsStateWithLifecycle()
    val serverOk by TrackingState.serverOk.collectAsStateWithLifecycle()
    val satellitesUsed by TrackingState.satellitesUsed.collectAsStateWithLifecycle()
    val satellitesVisible by TrackingState.satellitesVisible.collectAsStateWithLifecycle()
    val gnssSummary by TrackingState.gnssSummary.collectAsStateWithLifecycle()

    // 电池优化状态：从设置页返回后刷新
    var ignoringBattery by remember { mutableStateOf(batteryIgnored(context)) }
    var silentNotif by remember { mutableStateOf(prefs.silentNotification) }
    var hideRecents by remember { mutableStateOf(prefs.hideRecents) }
    var notifiedGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            TrackService.start(context)
        } else {
            Toast.makeText(context, "没有定位权限，无法记录轨迹", Toast.LENGTH_LONG).show()
        }
    }

    // 零配置开机即用：装上就自动开始，没有权限自动拉起授权弹窗
    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) {
            if (!TrackingState.running.value) TrackService.start(context)
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    // 采样参数卡轮询刷新：服务进程在后台热应用下发配置后，界面 3 秒内跟上
    var cfgSummary by remember { mutableStateOf(prefs.summary()) }
    var cfgSyncedAt by remember { mutableStateOf(prefs.configUpdatedAt) }
    LaunchedEffect(Unit) {
        while (true) {
            cfgSummary = prefs.summary()
            cfgSyncedAt = prefs.configUpdatedAt
            delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "迹录", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                appVersion(context), fontSize = 12.sp, color = TextFaint,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            "GNSS 定位（GPS/北斗自动选优）· 自动连接服务端 · 采样与省电参数由管理端下发",
            fontSize = 12.sp, color = TextSecondary,
        )

        // 运行状态
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(
                                when {
                                    running && sleeping -> Warn
                                    running -> Accent
                                    else -> Color(0xFF6B7A8A)
                                },
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            running && sleeping -> "运行中 · 静止休眠"
                            running -> "运行中 · 定位采集"
                            else -> "启动中…"
                        },
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("已记录", "$recorded 点", Modifier.weight(1f))
                    Stat("待上传", "$pending 点", Modifier.weight(1f))
                    Stat("本段里程", String.format(Locale.US, "%.2f km", distance / 1000), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat(
                        "最后上传",
                        if (lastUpload == 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.US)
                            .format(Date(lastUpload)),
                        Modifier.weight(1f),
                    )
                    Stat("设备 ID", prefs.deviceId, Modifier.weight(2f))
                }
                // 服务器连接状态（依最近一次上报/心跳结果）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                when (serverOk) {
                                    true -> Accent
                                    false -> Danger
                                    else -> TextFaint
                                },
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (serverOk) {
                            true -> "服务器已连接"
                            false -> "服务器连接异常，将自动重试"
                            else -> "等待首次上报…"
                        },
                        fontSize = 12.sp,
                        color = when (serverOk) {
                            true -> Accent
                            false -> Danger
                            else -> TextFaint
                        },
                    )
                }
                // GNSS 卫星信息
                if (satellitesVisible > 0) {
                    Text(
                        "GNSS 卫星：参与定位 $satellitesUsed / 可见 $satellitesVisible",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (gnssSummary.isNotEmpty()) {
                    Text(
                        gnssSummary,
                        fontSize = 11.sp,
                        color = TextFaint,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (message.isNotEmpty()) {
                    Text(message, fontSize = 12.sp, color = TextSecondary)
                }
            }
        }

        // 当前生效参数（App2 下发）
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("采样参数", fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (cfgSyncedAt == 0L) "默认值（未同步过）"
                        else "已同步 " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(cfgSyncedAt)),
                        fontSize = 11.sp, color = Accent,
                    )
                }
                Text(cfgSummary, fontSize = 13.sp, color = TextPrimary)
            }
        }

        // 检查更新状态
        val scope = rememberCoroutineScope()
        var checking by remember { mutableStateOf(false) }
        var checkMsg by remember { mutableStateOf("") }

        // 后台保活引导
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("后台保活", fontWeight = FontWeight.Medium, color = TextPrimary)
                KeepAliveRow(
                    ok = ignoringBattery,
                    title = "电池优化白名单",
                    desc = if (ignoringBattery) "已允许后台持续定位" else "未设置，系统可能在后台终止记录",
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    KeepAliveRow(
                        ok = notifiedGranted,
                        title = "通知权限",
                        desc = if (notifiedGranted) "常驻通知可展示运行状态" else "缺少通知权限，状态通知不显示",
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                // 常驻通知开关：关闭后通知栏完全不显示（startForeground 合规发布后立即撤销，记录不受影响）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("常驻通知", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(
                            if (silentNotif) "已隐藏：通知栏不显示任何通知，记录仍在后台继续"
                            else "在通知栏显示运行状态",
                            fontSize = 11.sp, color = TextFaint,
                        )
                    }
                    Switch(
                        checked = !silentNotif,
                        onCheckedChange = { show ->
                            silentNotif = !show
                            prefs.silentNotification = !show
                            // 服务在跑时立刻应用：静默→撤销通知，恢复→重新显示
                            runCatching {
                                context.startService(
                                    Intent(context, TrackService::class.java)
                                        .setAction(TrackService.ACTION_REFRESH),
                                )
                            }
                        },
                    )
                }
                // 隐藏后台开关：最近任务列表不显示本应用卡片
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("隐藏后台", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(
                            if (hideRecents) "已隐藏：最近任务列表不显示本应用"
                            else "在最近任务列表显示卡片",
                            fontSize = 11.sp, color = TextFaint,
                        )
                    }
                    Switch(
                        checked = hideRecents,
                        onCheckedChange = { hide ->
                            hideRecents = hide
                            prefs.hideRecents = hide
                            applyRecentsHidden(context, hide)
                        },
                    )
                }
            }
        }

        // 版本与检查更新（云端托管 APK，点按跳浏览器下载）
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "版本 v${AppUpdate.currentVersionName(context)}",
                        fontWeight = FontWeight.Medium, color = TextPrimary,
                    )
                    Text(
                        if (checkMsg.isEmpty()) "点击右侧按钮检查新版本" else checkMsg,
                        fontSize = 11.sp, color = TextFaint,
                    )
                }
                TextButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        checkMsg = "正在检查…"
                        scope.launch {
                            val cur = AppUpdate.currentVersionCode(context)
                            val info = withContext(Dispatchers.IO) { AppUpdate.check("tracker", cur) }
                            checking = false
                            checkMsg = when {
                                info != null -> "发现新版本 v${info.versionName}，已打开下载"
                                info == null -> "已是最新版本"
                                else -> "检查失败，请确认网络"
                            }
                            info?.let { AppUpdate.openDownload(context, it.url) }
                        }
                    },
                ) { Text(if (checking) "检查中…" else "检查更新", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeepAliveRow(ok: Boolean, title: String, desc: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "✅" else "⚠️",
            fontSize = 16.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = TextPrimary)
            Text(desc, fontSize = 11.sp, color = if (ok) TextFaint else Warn)
        }
        if (!ok) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF06170F),
                ),
            ) { Text("去设置", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = TextPrimary)
    }
}

private fun appVersion(context: android.content.Context): String = try {
    "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (_: Exception) {
    ""
}

private fun batteryIgnored(context: android.content.Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
