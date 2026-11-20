package com.kezez.tracklink.viewer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AccentGreen,
                    onPrimary = Color(0xFF06170F),
                    background = BgColor,
                    surface = PanelColor,
                    onSurface = TextPrimaryColor,
                ),
            ) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { ViewerPrefs(context) }
    val api = remember { ViewerApi(prefs) }
    var loggedIn by rememberSaveable { mutableStateOf(prefs.token.isNotEmpty()) }
    var openedDevice by remember { mutableStateOf<DeviceItem?>(null) }
    var configDevice by remember { mutableStateOf<DeviceItem?>(null) }
    var showAdmin by rememberSaveable { mutableStateOf(false) }

    val current = openedDevice
    when {
        !loggedIn -> LoginScreen(
            prefs = prefs,
            api = api,
            onLoggedIn = { loggedIn = true },
        )
        showAdmin -> {
            BackHandler { showAdmin = false }
            AdminScreen(
                api = api,
                prefs = prefs,
                onBack = { showAdmin = false },
            )
        }
        current != null -> {
            // BackHandler 后注册的先响应：弹窗开着时返回先关弹窗，再返回才退出轨迹页
            BackHandler(enabled = configDevice == null) { openedDevice = null }
            BackHandler(enabled = configDevice != null) { configDevice = null }
            TrackScreen(
                api = api,
                device = current,
                onBack = { openedDevice = null },
                isAdmin = prefs.role == "admin",
                onOpenConfig = { configDevice = current },
            )
            // 对话框必须叠加在轨迹页上；放独立 when 分支会把页面顶掉（背景变黑）
            configDevice?.let {
                DeviceConfigDialog(
                    api = api,
                    device = it,
                    onDismiss = { configDevice = null },
                )
            }
        }
        else -> DevicesScreen(
            prefs = prefs,
            api = api,
            onOpen = { openedDevice = it },
            onLogout = {
                prefs.clearSession()
                loggedIn = false
            },
            onOpenAdmin = { showAdmin = true },
        )
    }
}

private fun appVersion(context: android.content.Context): String = try {
    "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (_: Exception) {
    ""
}

@Composable
private fun LoginScreen(prefs: ViewerPrefs, api: ViewerApi, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    var server by rememberSaveable { mutableStateOf(prefs.serverUrl) }
    var username by rememberSaveable { mutableStateOf(prefs.username.ifBlank { "admin" }) }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier.fillMaxSize().background(BgColor).safeDrawingPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("迹览", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimaryColor)
            Text(
                "连接 TrackLink 服务端，查看各设备的历史轨迹 · " + appVersion(context),
                fontSize = 12.sp, color = DimColor,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("服务端地址") },
                placeholder = { Text("服务端地址（安装包已内置默认值，留空即用）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = DangerColor)
            Button(
                onClick = {
                    if (loading) return@Button
                    loading = true
                    error = ""
                    scope.launch {
                        try {
                            prefs.serverUrl = server
                            withContext(Dispatchers.IO) { api.login(username.trim(), password) }
                            onLoggedIn()
                        } catch (e: Exception) {
                            error = e.message ?: "登录失败"
                        } finally {
                            loading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = AccentGreen)
                else Text("登录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DevicesScreen(
    prefs: ViewerPrefs,
    api: ViewerApi,
    onOpen: (DeviceItem) -> Unit,
    onLogout: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    val context = LocalContext.current
    val isAdmin = prefs.role == "admin"
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableStateOf(0) }
    var showMapSettings by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(reloadTick) {
        while (true) {
            try {
                val list = withContext(Dispatchers.IO) { api.devices() }
                devices = list
                error = ""
            } catch (e: ApiException) {
                error = e.message ?: "加载失败"
                if (e.message?.contains("重新登录") == true) {
                    prefs.clearSession()
                    onLogout()
                }
            } catch (e: Exception) {
                error = e.message ?: "网络异常"
            } finally {
                loading = false
            }
            delay(30_000L)
        }
    }

    Column(Modifier.fillMaxSize().background(BgColor).safeDrawingPadding()) {
        // 顶栏：左侧标题独占，右侧仅「刷新」图标 + 「⋯」更多菜单，避免挤压标题换行
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "迹览",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reloadTick++ }, enabled = !checkingUpdate) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新",
                    tint = AccentGreen,
                )
            }
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = DimColor,
                    )
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    if (prefs.role == "admin") {
                        DropdownMenuItem(
                            text = { Text("管理中心", fontSize = 14.sp) },
                            onClick = {
                                showMoreMenu = false
                                onOpenAdmin()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("地图设置", fontSize = 14.sp) },
                        onClick = {
                            showMoreMenu = false
                            showMapSettings = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (checkingUpdate) "正在检查更新…" else "检查更新", fontSize = 14.sp) },
                        enabled = !checkingUpdate,
                        onClick = {
                            showMoreMenu = false
                            checkingUpdate = true
                            updateScope.launch {
                                val cur = AppUpdate.currentVersionCode(context)
                                val info = withContext(Dispatchers.IO) { AppUpdate.check("viewer", cur) }
                                checkingUpdate = false
                                Toast.makeText(
                                    context,
                                    when {
                                        info != null -> "发现新版本 v${info.versionName}，已打开下载"
                                        else -> "已是最新版本（如刚发版请稍后再试）"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                                info?.let { AppUpdate.openDownload(context, it.url) }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("退出登录", fontSize = 14.sp, color = DangerColor) },
                        onClick = {
                            showMoreMenu = false
                            onLogout()
                        },
                    )
                }
            }
        }
        // 副标题行：账号信息 + 版本号
        Text(
            "${prefs.username}（${if (prefs.role == "admin") "管理员" else "只读"}） · ${devices.size} 台设备 · ${appVersion(context)}",
            fontSize = 12.sp,
            color = DimColor,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (showMapSettings) {
            MapSettingsDialog(
                context = context,
                onDismiss = { showMapSettings = false },
            )
        }

        if (error.isNotEmpty()) {
            Text(
                "⚠️ $error",
                fontSize = 12.sp, color = DangerColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (loading && devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentGreen)
            }
            return@Column
        }

        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有设备上报轨迹\nApp1 装好后会自动连接服务端并开始记录",
                    fontSize = 13.sp, color = DimColor,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(devices, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    isAdmin = prefs.role == "admin",
                    onClick = {
                        if (device.lastPoint == null) {
                            Toast.makeText(context, "该设备还没有轨迹点", Toast.LENGTH_SHORT).show()
                        } else {
                            onOpen(device)
                        }
                    },
                    onRename = {
                        updateScope.launch {
                            runCatching { withContext(Dispatchers.IO) { api.renameDevice(device.id, it) } }
                                .onSuccess { reloadTick++ }
                                .onFailure { e ->
                                    Toast.makeText(context, e.message ?: "重命名失败", Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    onDelete = {
                        updateScope.launch {
                            runCatching { withContext(Dispatchers.IO) { api.deleteDevice(device.id) } }
                                .onSuccess {
                                    Toast.makeText(context, "设备已删除", Toast.LENGTH_SHORT).show()
                                    reloadTick++
                                }
                                .onFailure { e ->
                                    Toast.makeText(context, e.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceItem,
    isAdmin: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelColor),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(9.dp).background(
                        if (device.online) AccentGreen else Color(0xFF5B6875), CircleShape,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(device.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    if (device.online) "在线" else "离线",
                    fontSize = 11.sp, color = if (device.online) AccentGreen else DimColor,
                )
                if (isAdmin) {
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "设备管理",
                                tint = DimColor,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("重命名", fontSize = 14.sp) },
                                onClick = {
                                    showMenu = false
                                    showRename = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除设备", fontSize = 14.sp, color = DangerColor) },
                                onClick = {
                                    showMenu = false
                                    showDelete = true
                                },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniStat("今日里程", fmtDistance(device.distanceToday))
                MiniStat("今日点数", "${device.pointCountToday}")
                MiniStat("电量", if (device.battery < 0) "—" else "${device.battery}%")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniStat("最后定位", fmtTime(device.lastPointTs))
                MiniStat("最后上报", fmtTime(device.lastSeen))
            }
            if (device.model.isNotBlank()) {
                Text(device.model, fontSize = 10.sp, color = DimColor, fontFamily = FontFamily.Monospace)
            }
        }
    }
    if (showRename) {
        RenameDeviceDialog(
            current = device.name,
            onDismiss = { showRename = false },
            onConfirm = {
                showRename = false
                onRename(it)
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除设备", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("确定删除「${device.name}」？该设备的全部历史轨迹将一并删除，且不可恢复。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                }) { Text("删除", color = DangerColor) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消", color = DimColor) }
            },
        )
    }
}

@Composable
private fun RenameDeviceDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名设备", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("设备名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != current,
                onClick = { onConfirm(name.trim()) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DimColor) }
        },
    )
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = DimColor)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ==================== 管理中心（管理员，功能对齐 Web 管理端） ====================

@Composable
private fun AdminScreen(api: ViewerApi, prefs: ViewerPrefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var uploadKey by remember { mutableStateOf("") }
    var globalInfo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }

    suspend fun reloadAll() {
        try {
            users = withContext(Dispatchers.IO) { api.users() }
            val st = withContext(Dispatchers.IO) { api.settings() }
            uploadKey = st.uploadKey
            globalInfo = "轨迹保留 ${st.retentionDays} 天 · 登录有效期 ${st.tokenTtlHours} 小时"
            devices = withContext(Dispatchers.IO) { api.devices() }
            error = ""
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(tick) {
        loading = true
        reloadAll()
    }

    fun act(block: suspend () -> String?) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { msg ->
                    msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    tick++
                }
                .onFailure { e ->
                    Toast.makeText(context, e.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // 对话框状态
    var aclUser by remember { mutableStateOf<AdminUser?>(null) }
    var resetUser by remember { mutableStateOf<AdminUser?>(null) }
    var showAddUser by remember { mutableStateOf(false) }
    var showChangePwd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(BgColor).safeDrawingPadding()) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 20.sp, color = TextPrimaryColor)
            }
            Text(
                "管理中心",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { tick++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = AccentGreen)
            }
        }

        if (loading && users.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentGreen)
            }
            return@Column
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 系统设置 ----
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = PanelColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("系统设置", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryColor)
                        if (globalInfo.isNotEmpty()) {
                            Text(globalInfo, fontSize = 11.sp, color = DimColor)
                        }
                        Text("App1 上传密钥（X-Upload-Key）", fontSize = 12.sp, color = DimColor)
                        Text(
                            uploadKey.ifBlank { "—" },
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AccentGreen,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "TrackLink 服务端：${prefs.serverUrl}\n上传密钥：$uploadKey",
                                    )
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "分享接入信息"),
                                )
                            }) { Text("分享接入信息", fontSize = 13.sp) }
                        }
                        Text(
                            "密钥烧录在收集端安装包内，不支持在线轮换；如确需更换，需重新打包并推送新版本。",
                            fontSize = 10.sp, color = DimColor,
                        )
                    }
                }
            }

            // ---- 账号与授权 ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "账号与授权",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryColor,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAddUser = true }) { Text("+ 新增账号", fontSize = 13.sp) }
                }
            }
            items(users, key = { it.id }) { u ->
                UserRow(
                    user = u,
                    isSelf = u.username == prefs.username,
                    onAcl = { aclUser = u },
                    onResetPwd = { resetUser = u },
                    onToggleRole = {
                        val to = if (u.role == "admin") "viewer" else "admin"
                        act {
                            api.updateUser(u.id, role = to)
                            "已切换为${if (to == "admin") "管理员" else "只读"}"
                        }
                    },
                    onDelete = {
                        act {
                            api.deleteUser(u.id)
                            "账号已删除"
                        }
                    },
                )
            }

            // ---- 修改我的密码 ----
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = PanelColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前账号", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryColor)
                        Text(
                            "${prefs.username}（${if (prefs.role == "admin") "管理员" else "只读"}）",
                            fontSize = 12.sp, color = DimColor,
                        )
                        TextButton(onClick = { showChangePwd = true }) { Text("修改我的密码", fontSize = 13.sp) }
                    }
                }
            }

            if (error.isNotEmpty()) {
                item { Text("⚠️ $error", fontSize = 12.sp, color = DangerColor) }
            }
        }
    }

    // ---- 对话框区 ----
    aclUser?.let { u ->
        AclDialog(
            user = u,
            devices = devices,
            onDismiss = { aclUser = null },
            onConfirm = { ids ->
                aclUser = null
                act {
                    api.updateUser(u.id, deviceIds = ids)
                    "授权已更新（${ids.size} 台设备）"
                }
            },
        )
    }
    resetUser?.let { u ->
        ResetPasswordDialog(
            username = u.username,
            onDismiss = { resetUser = null },
            onConfirm = { pwd ->
                resetUser = null
                act {
                    api.updateUser(u.id, password = pwd)
                    "密码已重置"
                }
            },
        )
    }
    if (showAddUser) {
        AddUserDialog(
            onDismiss = { showAddUser = false },
            onConfirm = { username, pwd, role ->
                showAddUser = false
                act {
                    api.createUser(username, pwd, role)
                    "账号已创建"
                }
            },
        )
    }
    if (showChangePwd) {
        ChangeOwnPasswordDialog(
            onDismiss = { showChangePwd = false },
            onConfirm = { old, new ->
                showChangePwd = false
                act {
                    api.changeOwnPassword(old, new)
                    "密码已修改"
                }
            },
        )
    }
}

@Composable
private fun UserRow(
    user: AdminUser,
    isSelf: Boolean,
    onAcl: () -> Unit,
    onResetPwd: () -> Unit,
    onToggleRole: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.username, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (user.role == "admin") "管理员" else "只读",
                        fontSize = 10.sp,
                        color = if (user.role == "admin") AccentGreen else DimColor,
                        modifier = Modifier
                            .background(
                                if (user.role == "admin") Color(0xFFE7F6EF) else Color(0xFFEEF1F4),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                    if (isSelf) {
                        Spacer(Modifier.width(4.dp))
                        Text("（我）", fontSize = 10.sp, color = DimColor)
                    }
                }
                Text(
                    if (user.role == "admin") "可查看全部设备"
                    else "可看 ${user.deviceIds.size} 台设备",
                    fontSize = 11.sp, color = DimColor,
                )
            }
            if (user.role != "admin") {
                TextButton(onClick = onAcl) { Text("授权", fontSize = 13.sp) }
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.MoreVert, contentDescription = "更多",
                        tint = DimColor, modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (user.role != "admin") {
                        DropdownMenuItem(
                            text = { Text("设为管理员", fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                onToggleRole()
                            },
                        )
                    } else if (!isSelf) {
                        DropdownMenuItem(
                            text = { Text("降为只读", fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                onToggleRole()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重置密码", fontSize = 14.sp) },
                        onClick = {
                            showMenu = false
                            onResetPwd()
                        },
                    )
                    if (!isSelf) {
                        DropdownMenuItem(
                            text = { Text("删除账号", fontSize = 14.sp, color = DangerColor) },
                            onClick = {
                                showMenu = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除账号", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("确定删除账号「${user.username}」？", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = DangerColor) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = DimColor) }
            },
        )
    }
}

/** 授权对话框：勾选该只读账号可查看的设备 */
@Composable
private fun AclDialog(
    user: AdminUser,
    devices: List<DeviceItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember { mutableStateOf(user.deviceIds.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("授权 · ${user.username}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                if (devices.isEmpty()) {
                    Text("还没有设备", fontSize = 12.sp, color = DimColor)
                }
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = d.id in selected.value,
                            onCheckedChange = { checked ->
                                selected.value = if (checked) selected.value + d.id else selected.value - d.id
                            },
                        )
                        Text("${d.name}（${d.id}）", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.value.toList()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DimColor) }
        },
    )
}

@Composable
private fun ResetPasswordDialog(username: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重置密码 · $username", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it },
                label = { Text("新密码（≥6 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton(enabled = pwd.length >= 6, onClick = { onConfirm(pwd) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DimColor) }
        },
    )
}

@Composable
private fun AddUserDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增账号", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(40) },
                    label = { Text("用户名（3-40 位字母数字）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("密码（≥6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("角色：", fontSize = 13.sp, color = DimColor)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(
                            selected = role == "viewer",
                            onClick = { role = "viewer" },
                        )
                        Text("只读", fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.RadioButton(
                            selected = role == "admin",
                            onClick = { role = "admin" },
                        )
                        Text("管理员", fontSize = 13.sp)
                    }
                }
                Text("创建后可在该账号的「授权」里勾选可查看的设备。", fontSize = 10.sp, color = DimColor)
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.matches(Regex("[\\w.@-]{3,40}")) && pwd.length >= 6,
                onClick = { onConfirm(username.trim(), pwd, role) },
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DimColor) }
        },
    )
}

@Composable
private fun ChangeOwnPasswordDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改我的密码", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text("原密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = new1,
                    onValueChange = { new1 = it },
                    label = { Text("新密码（≥6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = new2,
                    onValueChange = { new2 = it },
                    label = { Text("确认新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (new1.isNotEmpty() && new1 != new2) {
                    Text("两次输入的新密码不一致", fontSize = 11.sp, color = DangerColor)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = old.isNotEmpty() && new1.length >= 6 && new1 == new2,
                onClick = { onConfirm(old, new1) },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DimColor) }
        },
    )
}

/** 底图设置：高德 / 天地图 / OSM 镜像三选一（Key 均已内置） */
@Composable
private fun MapSettingsDialog(context: android.content.Context, onDismiss: () -> Unit) {
    val settings = remember { ViewerSettings(context) }
    var source by remember { mutableStateOf(settings.mapSource) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("地图底图") },
        text = {
            Column {
                SourceRow("高德地图", "SDK 渲染，定位/路况体验最好", source == "amap") { source = "amap" }
                Spacer(Modifier.height(8.dp))
                SourceRow("天地图", "合规底图，国内直连快（osmdroid）", source == "tdt") { source = "tdt" }
                Spacer(Modifier.height(8.dp))
                SourceRow("OSM 镜像", "免 Key 但首屏较慢（osmdroid）", source == "osm") { source = "osm" }
                Spacer(Modifier.height(10.dp))
                Text(
                    when (source) {
                        "amap" -> "高德 SDK（Key 已内置），轨迹自动做 WGS-84 → GCJ-02 纠偏。"
                        "tdt" -> "天地图（Key 已内置）。免费 Key 有日配额，用尽时可切其他底图。"
                        else -> "OSM 镜像免 Key，国内访问速度一般，作为兜底。"
                    } + "\n离线瓦片包（武汉 z10–z14）仅 osmdroid 引擎可用。",
                    fontSize = 11.sp, color = DimColor,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.forceOsm = source == "osm"
                settings.mapSource = source
                onDismiss()
                Toast.makeText(context, "已保存，重新进入轨迹页生效", Toast.LENGTH_SHORT).show()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DimColor) }
        },
    )
}

@Composable
private fun SourceRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = TextPrimaryColor)
            Text(desc, fontSize = 11.sp, color = DimColor)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** 单行参数：名称 + 可直接输入的数值框 + 「−」「+」步进（步进 1） */
@Composable
private fun ConfigStepper(
    label: String,
    hint: String,
    value: Int,
    unit: String,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, color = TextPrimaryColor)
                Text(hint, fontSize = 10.sp, color = DimColor)
            }
            TextButton(
                onClick = { onChange((value - 1).coerceIn(range)) },
                enabled = value - 1 >= range.first,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("−", fontSize = 18.sp) }
            // 直接输入数值（纯数字键盘，越界自动夹回合法范围）
            var text by remember(value) { mutableStateOf(value.toString()) }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(4)
                    text = digits
                    digits.toIntOrNull()?.let { onChange(it.coerceIn(range)) }
                },
                modifier = Modifier.width(86.dp),
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentGreen,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                suffix = { Text(unit, fontSize = 9.sp, color = DimColor) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
            TextButton(
                onClick = { onChange((value + 1).coerceIn(range)) },
                enabled = value + 1 <= range.last,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("+", fontSize = 18.sp) }
        }
    }
}

/** 设备采样/省电配置下发：App2 编辑 → 服务端 → App1 下次上报时拉取生效 */
@Composable
private fun DeviceConfigDialog(api: ViewerApi, device: DeviceItem, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf<DeviceConfig?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(device.id) {
        try {
            config = withContext(Dispatchers.IO) { api.deviceConfig(device.id) }
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("配置 · ${device.name}", fontSize = 17.sp) },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = AccentGreen)
                }
            } else if (error.isNotEmpty()) {
                Text("⚠️ $error", fontSize = 12.sp, color = DangerColor)
            } else {
                val c = config ?: return@AlertDialog
                Column {
                    ConfigStepper(
                        "采样间隔", "GPS 定位点间隔，越大越省电",
                        c.sampleIntervalS, "秒", 5..300,
                    ) { config = c.copy(sampleIntervalS = it) }
                    ConfigStepper(
                        "最小位移", "位移小于该值不上报，过滤原地抖动",
                        c.minDistanceM, "米", 0..500,
                    ) { config = c.copy(minDistanceM = it) }
                    ConfigStepper(
                        "精度上限", "定位精度差于该值时丢弃",
                        c.accuracyMaxM, "米", 5..500,
                    ) { config = c.copy(accuracyMaxM = it) }
                    ConfigStepper(
                        "静止休眠", "持续静止后降低采样频率，0 不休眠",
                        c.sleepAfterMin, "分钟", 0..30,
                    ) { config = c.copy(sleepAfterMin = it) }
                    ConfigStepper(
                        "上报周期", "攒一批轨迹点的上报间隔",
                        c.batchSeconds, "秒", 15..600,
                    ) { config = c.copy(batchSeconds = it) }
                    ConfigStepper(
                        "心跳间隔", "静止待机时的保活上报间隔",
                        c.heartbeatS, "秒", 60..1800,
                    ) { config = c.copy(heartbeatS = it) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "保存后设备在下一次上报时自动拉取生效（约 1 分钟内）。",
                        fontSize = 10.sp, color = DimColor,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && error.isEmpty() && config != null && !saving,
                onClick = {
                    val c = config ?: return@TextButton
                    saving = true
                    scope.launch {
                        try {
                            config = withContext(Dispatchers.IO) { api.putDeviceConfig(device.id, c) }
                            Toast.makeText(context, "已下发，设备下次上报时生效", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.message ?: "保存失败"
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) "下发中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消", color = DimColor) }
        },
    )
}
