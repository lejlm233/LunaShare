package com.lunashare.app.easytier

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 组网 Tab（EasyTier）：配置卡 + 连接卡 + 日志卡。
 * 启动流程（对齐官方 mobile_vpn.ts）：EasyTierManager.start（mobile cfg 核心
 * 挂起等 fd、控制面照常）→ 轮询虚拟 IP → tun 建立 + fd 注入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by EasyTierStateHolder.state.collectAsState()

    // 档案列表与当前档案 id（档案切换 = 重载表单）
    var profiles by remember { mutableStateOf(EasyTierManager.loadProfiles(context)) }
    var currentPid by remember { mutableStateOf(EasyTierManager.currentProfileId(context)) }
    val currentProfile = profiles.find { it.id == currentPid } ?: profiles.firstOrNull()

    fun reloadProfiles() {
        profiles = EasyTierManager.loadProfiles(context)
        currentPid = EasyTierManager.currentProfileId(context)
    }

    val cfg = remember(currentPid) { mutableStateOf(EasyTierManager.loadConfig(context)) }
    val snackbar = remember { SnackbarHostState() }

    // 表单本地状态（以磁盘配置初始化；formVersion 变化 = 导入 TOML / 切档案，重置表单）
    var formVersion by remember { mutableStateOf(0) }
    var networkName by remember(formVersion) { mutableStateOf(cfg.value.networkName) }
    var networkSecret by remember(formVersion) { mutableStateOf(cfg.value.networkSecret) }
    var hostname by remember(formVersion) { mutableStateOf(cfg.value.hostname.ifBlank { "lunashare" }) }
    var peersText by remember(formVersion) { mutableStateOf(cfg.value.peers.joinToString("\n")) }
    var virtualIp by remember(formVersion) { mutableStateOf(cfg.value.virtualIp) }
    var secretVisible by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var bootAutoStart by remember { mutableStateOf(EasyTierManager.isBootAutoStart(context)) }

    // TOML 原文编辑模式（导入后可用；true = 连接时优先用原文而非表单）
    var tomlMode by remember { mutableStateOf(false) }

    fun msg(s: String) = scope.launch { snackbar.showSnackbar(s) }

    // TOML 导入：解析 → 填充可编辑表单；未知字段提示（原文模式可保留）
    val tomlPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                msg("读取文件失败")
            } else {
                runCatching { TomlMini.parse(text) }.onSuccess { doc ->
                    val parsed = TomlMini.toEasyTierConfig(doc)
                    EasyTierManager.saveConfig(context, parsed)
                    cfg.value = parsed
                    formVersion++
                    EasyTierStateHolder.setImportedToml(text)
                    EasyTierStateHolder.setError(null)
                    EasyTierStateHolder.addLog("已导入 TOML 并解析进表单 (${text.length} 字符)")
                    if (doc.unknown.isNotEmpty()) {
                        EasyTierStateHolder.addLog("未映射字段: ${doc.unknown.joinToString(", ")}")
                        msg("未解析字段将仅在原文模式生效: ${doc.unknown.take(6).joinToString(", ")}")
                    } else {
                        msg("已解析进表单，可直接编辑")
                    }
                }.onFailure { e ->
                    // 解析失败：退回原文模式，让用户手工修
                    EasyTierStateHolder.setImportedToml(text)
                    tomlMode = true
                    msg("TOML 解析失败，已进入原文编辑: ${e.message}")
                }
            }
        }
    }

    // 表单当前值 → 配置对象（保存 / 连接共用）
    fun currentCfg() = EasyTierManager.EasyTierConfig(
        networkName = networkName, networkSecret = networkSecret,
        hostname = hostname, peers = peersText.lines().map { it.trim() }.filter { it.isNotBlank() },
        virtualIp = virtualIp
    )

    fun saveFormConfig() {
        if (networkName.isBlank()) {
            msg("网络名不能为空")
            return
        }
        EasyTierManager.saveConfig(context, currentCfg())
        reloadProfiles()
        val name = profiles.find { it.id == currentPid }?.name ?: ""
        EasyTierStateHolder.addLog("配置已保存到档案「$name」（主机名: $hostname）")
        msg("配置已保存到「$name」")
    }

    /**
     * 启动组网。
     *
     * 荣耀/部分定制系统管控：对侧载 debug 应用调用 VpnService.prepare() 会直接
     * SIGKILL 进程（实测死点在 prepare 内部，无任何崩溃日志）。但用户在系统弹窗
     * 点过一次「允许」后，appops ACTIVATE_VPN=allow 即持久化，establish() 内部
     * 自行校验该授权即可通过。因此这里跳过 prepare() 直接启动核心；
     * 若从未授权，LunaVpnService.establish() 会返回 null 并在日志卡提示。
     */
    fun startWithConsent() {
        if (tomlMode && state.importedToml.isNullOrBlank() && networkName.isBlank()) {
            msg("请先填写网络名或导入 TOML")
            return
        }
        if (!tomlMode && networkName.isBlank()) {
            msg("请先填写网络名")
            return
        }
        EasyTierManager.saveConfig(context, currentCfg())
        val toml = (if (tomlMode) EasyTierStateHolder.get().importedToml else null)
            ?: EasyTierManager.buildToml(currentCfg())
        EasyTierManager.start(context, toml)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 连接卡 ──
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                state.vpnRunning -> "已连接"
                                state.coreRunning -> "连接中..."
                                else -> "未连接"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        state.virtualIp?.let {
                            Text("虚拟 IP: $it", style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                        if (state.connSummary.isNotBlank()) {
                            Text(state.connSummary, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when {
                        state.coreRunning -> {
                            // 已连接显示成功图标；仅「连接中」（核心在跑但 VPN 未建立）才转圈
                            if (state.vpnRunning) {
                                Icon(
                                    Icons.Default.CheckCircle, null,
                                    Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                CircularProgressIndicator(Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            FilledTonalButton(onClick = { EasyTierManager.stop(context) }) {
                                Text("停止")
                            }
                        }
                        else -> Button(onClick = { startWithConsent() }) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("连接")
                        }
                    }
                }
                state.lastError?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }

            // ── 组网设备卡（连接后实时显示组网内节点，官方 GUI 同源信息）──
            if (state.coreRunning) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("组网设备", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${state.peers.size} 台",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.peers.isEmpty()) {
                            Text(
                                "等待节点信息...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            state.peers.forEach { row -> PeerRowItem(row) }
                        }
                        // 会话统计：时长 + tun0 收发流量 + 自动重连次数
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = buildString {
                                    append("本会话 ${formatDuration(state.stats.sessionMs)}")
                                    append("  ·  ↓${formatBytes(state.stats.rxBytes)}")
                                    append("  ↑${formatBytes(state.stats.txBytes)}")
                                    if (state.stats.reconnects > 0) {
                                        append("  ·  重连 ${state.stats.reconnects} 次")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // ── 配置卡（导入 TOML 可解析进表单编辑，或切原文模式直接改 TOML）──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lan, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("组网配置", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        // 当前档案名（点击管理档案）
                        TextButton(onClick = { showProfiles = true }) {
                            Icon(Icons.Default.Folder, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                currentProfile?.name ?: "配置档案",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        TextButton(onClick = { tomlPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.UploadFile, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导入 TOML")
                        }
                    }

                    if (state.importedToml != null) {
                        // 模式切换：表单（推荐，编辑后按表单生成 TOML） / TOML 原文（完整保留导入内容）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = !tomlMode,
                                onClick = { tomlMode = false },
                                label = { Text("表单模式") }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = tomlMode,
                                onClick = { tomlMode = true },
                                label = { Text("TOML 原文") }
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { EasyTierStateHolder.setImportedToml(null); tomlMode = false }) {
                                Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                                Text("清除导入")
                            }
                        }
                        if (tomlMode) {
                            Text(
                                "连接时使用下方 TOML（可编辑；no_tun 会自动注入）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = state.importedToml!!,
                                onValueChange = { EasyTierStateHolder.setImportedToml(it) },
                                label = { Text("TOML 配置") },
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                                ),
                                minLines = 6, maxLines = 16,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                "已解析进表单，可直接编辑；连接时按表单重新生成 TOML",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TomlFormFields(
                                networkName = networkName, onNetworkName = { networkName = it },
                                networkSecret = networkSecret, onNetworkSecret = { networkSecret = it },
                                hostname = hostname, onHostname = { hostname = it },
                                peersText = peersText, onPeersText = { peersText = it },
                                virtualIp = virtualIp, onVirtualIp = { virtualIp = it },
                                secretVisible = secretVisible, onSecretVisible = { secretVisible = !secretVisible }
                            )
                        }
                    } else {
                        TomlFormFields(
                            networkName = networkName, onNetworkName = { networkName = it },
                            networkSecret = networkSecret, onNetworkSecret = { networkSecret = it },
                            hostname = hostname, onHostname = { hostname = it },
                            peersText = peersText, onPeersText = { peersText = it },
                            virtualIp = virtualIp, onVirtualIp = { virtualIp = it },
                            secretVisible = secretVisible, onSecretVisible = { secretVisible = !secretVisible }
                        )
                    }

                    // 表单可编辑时提供显式保存（改完不连接也不丢）
                    if (state.importedToml == null || !tomlMode) {
                        OutlinedButton(onClick = { saveFormConfig() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("保存配置")
                        }
                    }

                    // 启动自动连接（MainActivity onCreate 读取；配合系统开机自启 = 开机自动组网）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("启动自动连接", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "app 启动时自动用当前档案发起组网",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bootAutoStart,
                            onCheckedChange = {
                                bootAutoStart = it
                                EasyTierManager.setBootAutoStart(context, it)
                                EasyTierStateHolder.addLog("启动自动连接: ${if (it) "开" else "关"}")
                            }
                        )
                    }
                }
            }

            // ── 日志卡 ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Article, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("日志", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { EasyTierStateHolder.clearLogs() }) { Text("清空") }
                    }
                    // 日志区限高 240dp 内部滚动（与共享页日志卡同款）；最新日志置顶，打开即见
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val logs = state.logs.ifEmpty { listOf("暂无日志") }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Text(
                                text = logs.asReversed().joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 档案管理对话框：切换 / 存为新档案 / 删除 ──
    if (showProfiles) {
        var newName by remember { mutableStateOf("") }
        BackHandler { showProfiles = false }
        AlertDialog(
            onDismissRequest = { showProfiles = false },
            title = { Text("配置档案") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profiles.isEmpty()) {
                        Text("暂无档案", style = MaterialTheme.typography.bodySmall)
                    }
                    profiles.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = p.id == currentPid,
                                onClick = {
                                    showProfiles = false
                                    if (p.id != currentPid) {
                                        // 切走前把当前表单存回旧档案，避免丢编辑
                                        if (networkName.isNotBlank()) {
                                            EasyTierManager.saveConfig(context, currentCfg())
                                        }
                                        EasyTierManager.setCurrentProfile(context, p.id)
                                        EasyTierStateHolder.setImportedToml(null)
                                        reloadProfiles()
                                        cfg.value = EasyTierManager.loadConfig(context)
                                        formVersion++
                                        EasyTierStateHolder.addLog("已切换到档案「${p.name}」")
                                    }
                                }
                            )
                            Text(p.name, modifier = Modifier.weight(1f))
                            if (profiles.size > 1) {
                                IconButton(onClick = {
                                    EasyTierStateHolder.addLog("已删除档案「${p.name}」")
                                    val newCfg = EasyTierManager.deleteProfile(context, p.id)
                                    reloadProfiles()
                                    cfg.value = newCfg
                                    formVersion++
                                }) {
                                    Icon(Icons.Default.Delete, "删除档案",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    // 存为新档案：把当前表单内容落成一个新档案
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("新档案名") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        enabled = newName.isNotBlank() && networkName.isNotBlank(),
                        onClick = {
                            EasyTierManager.addProfile(context, newName.trim(), currentCfg())
                            reloadProfiles()
                            showProfiles = false
                            EasyTierStateHolder.addLog("已创建档案「${newName.trim()}」并设为当前")
                            msg("档案「${newName.trim()}」已创建")
                        }
                    ) { Text("将当前配置存为新档案") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfiles = false }) { Text("关闭") }
            }
        )
    }
}

/** 会话时长格式化：<1h 显示 mm 分，≥1h 显示 h:mm。 */
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}h${m}m" else "${m}m"
}

/** 字节数人性化（统计行用）。 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024L * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** 组网表单字段（表单模式 / 未导入 TOML 时共用）。 */
@Composable
private fun TomlFormFields(
    networkName: String, onNetworkName: (String) -> Unit,
    networkSecret: String, onNetworkSecret: (String) -> Unit,
    hostname: String, onHostname: (String) -> Unit,
    peersText: String, onPeersText: (String) -> Unit,
    virtualIp: String, onVirtualIp: (String) -> Unit,
    secretVisible: Boolean, onSecretVisible: () -> Unit,
) {
    OutlinedTextField(
        value = networkName, onValueChange = onNetworkName,
        label = { Text("网络名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = networkSecret, onValueChange = onNetworkSecret,
        label = { Text("网络密码") }, singleLine = true,
        visualTransformation = if (secretVisible) androidx.compose.ui.text.input.VisualTransformation.None
        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onSecretVisible) {
                Icon(if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = hostname, onValueChange = onHostname,
        label = { Text("主机名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = peersText, onValueChange = onPeersText,
        label = { Text("公共节点（每行一个，如 wss://39.98.83.46:51012）") },
        minLines = 2, modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = virtualIp, onValueChange = onVirtualIp,
        label = { Text("虚拟 IP（留空自动分配）") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 组网设备列表行：主机名 + 虚拟 IP + 连接方式（P2P/中继）+ 延迟。 */
@Composable
private fun PeerRowItem(row: EasyTierStateHolder.PeerRow) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.hostname, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    if (row.isLocal) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "本机", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (row.virtualIp.isNotBlank()) {
                    Text(
                        row.virtualIp, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                row.tunnelType?.let {
                    Text(
                        "通道: ${it.lowercase()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when {
                        row.isLocal -> "本机节点"
                        row.cost <= 1 -> "P2P 直连"
                        else -> "中继 x${row.cost - 1}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.isLocal || row.cost <= 1) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary
                )
                row.latencyMs?.let {
                    Text(
                        "$it ms", style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
