package com.lunashare.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunashare.app.adb.AdbTunnelConfig
import com.lunashare.app.adb.AdbTunnelStateHolder
import com.lunashare.app.adb.AdbTunnelStore
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.frpc.mefrp.MefrpConfigStore
import com.lunashare.app.model.ShareConfig
import com.lunashare.app.service.MdnsBroadcaster
import com.lunashare.app.model.RemoteConnection
import com.lunashare.app.service.ShareStateHolder
import com.lunashare.app.ui.theme.StatusRunning
import com.lunashare.app.ui.theme.StatusStopped
import com.lunashare.app.ui.theme.StatusError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServiceListScreen(
    configStore: ShareConfigStore,
    onEditShare: (ShareConfig) -> Unit,
    onCreateNew: () -> Unit,
    onStartStop: (ShareConfig) -> Unit,
    onDeleteShare: (ShareConfig) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    refreshTrigger: Int = 0,
    onOpenAdbTunnel: () -> Unit = {},
    onStartStopAdb: () -> Unit = {},
    onDeleteAdb: () -> Unit = {},
    onEditRemote: (RemoteConnection) -> Unit = {}
) {
    val shareStates by ShareStateHolder.shareStates.collectAsState()
    var expandedLogId by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    // 只存 id：弹窗渲染时按 id 实时重读最新配置（mefrp 的 nodeHost 由 ShareService
    // 启动共享时才回填持久化，快照会拿到空 nodeHost → 公网链接显示「尚未同步」）
    var linkDialogShareId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mefrpStore = remember { MefrpConfigStore(context) }

    // ── 连接共享（远程客户端）：配置 + 运行时状态 ──
    val remoteStore = remember { com.lunashare.app.config.RemoteConnectionStore(context) }
    val remoteConfigs = remember(refreshKey) { remoteStore.listConfigs() }
    val remoteStates by com.lunashare.app.remote.RemoteConnectionManager.states.collectAsState()

    // ── ADB 穿透（全局单例）：配置 + 运行时状态 ──
    val adbStore = remember { AdbTunnelStore(context) }
    var adbConfig by remember(refreshKey) { mutableStateOf(adbStore.loadConfig()) }
    val adbState by AdbTunnelStateHolder.state.collectAsState()

    LaunchedEffect(refreshTrigger) {
        refreshKey++
    }

    val shares = remember(refreshKey) { configStore.listConfigs() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNew) {
                Icon(Icons.Default.Add, contentDescription = "新建共享")
            }
        }
    ) { padding ->
        if (linkDialogShareId != null) {
            // 按 id 读最新配置。mefrp nodeHost 在启动共享后由 ShareService 回填持久化，
            // 打开弹窗时可能还没回填（显示「尚未同步」）——用状态 + 轮询驱动重读：
            // 只要有 mefrp 隧道地址还没同步，每秒重读一次，同步到就停。
            var dialogConfig by remember(linkDialogShareId) {
                mutableStateOf(configStore.loadConfig(linkDialogShareId!!))
            }
            LaunchedEffect(linkDialogShareId, dialogConfig) {
                val hasPendingMefrpHost = dialogConfig?.activeTunnels()
                    ?.any { it.second.provider == "mefrp" && it.second.nodeHost.isBlank() } == true
                if (hasPendingMefrpHost) {
                    while (dialogConfig?.activeTunnels()
                            ?.any { it.second.provider == "mefrp" && it.second.nodeHost.isBlank() } == true
                    ) {
                        delay(1000)
                        dialogConfig = configStore.loadConfig(linkDialogShareId!!)
                    }
                }
            }
            val s = dialogConfig ?: return@Scaffold
            // 每 2s 重读本机/虚拟网卡 IP：弹窗打开期间若 EasyTier（或其它组网 App）刚建立隧道，
            // 虚拟 IP 能自动出现在链接列表里，无需关掉重开弹窗。
            var ipTick by remember(linkDialogShareId) { mutableStateOf(0) }
            LaunchedEffect(linkDialogShareId) {
                while (true) { delay(2000); ipTick++ }
            }
            val ip = remember(ipTick) { getLocalIpAddress() }
            val virtIps = remember(ipTick) { getVirtualNetworkIps() }
            val auth = if (s.username.isNotBlank()) "${urlEncode(s.username)}:${urlEncode(s.password)}@" else ""
            val linkItems = buildList {
                // mDNS 开关开启且 HTTP 服务可用时，提供免记 IP 的本地名称访问入口。
                // mDNS 广播的即是 HTTP(WebDAV) 服务，端口与下方 WebDAV 链接一致，必须带上端口，
                // 否则 http://lunashare.local 走默认 80 端口访问不到（服务跑在自定义端口上）。
                if (configStore.isMdnsEnabled() && s.httpEnabled && s.httpPort > 0) {
                    add("本地名称访问（lunashare.local，免记IP）" to "http://${MdnsBroadcaster.HOST_NAME}.local:${s.httpPort}")
                }
                if (s.httpEnabled && s.httpPort > 0) add("WebDAV 访问链接" to "http://$auth$ip:${s.httpPort}")
                if (s.ftpEnabled && s.ftpPort > 0) add("FTP 访问链接" to "ftp://$auth$ip:${s.ftpPort}")
                if (s.smbEnabled && s.smbPort > 0) {
                    val unc = "\\\\$ip\\${s.name.replace(":", "_")}"
                    add("SMB 访问" to "$unc (macOS smb://$ip:${s.smbPort}/, Linux mount -o port=${s.smbPort})")
                }
                // EasyTier 等虚拟组网地址：手机装 EasyTier（App/VpnService）并连上网络后，
                // 虚拟 IP 出现在 tun 网卡上，异地设备可直接用该 IP 访问共享服务（免公网免穿透）。
                // EasyTier 默认虚拟网段为 10.126.126.0/24，命中即标 EasyTier；其它 tun 地址标为通用组网。
                virtIps.forEach { (vName, vip) ->
                    if (s.httpEnabled && s.httpPort > 0)
                        add("$vName 访问链接（WebDAV）" to "http://$auth$vip:${s.httpPort}")
                    if (s.ftpEnabled && s.ftpPort > 0)
                        add("$vName 访问链接（FTP）" to "ftp://$auth$vip:${s.ftpPort}")
                    if (s.smbEnabled && s.smbPort > 0) {
                        val unc = "\\\\$vip\\${s.name.replace(":", "_")}"
                        add("$vName 访问（SMB）" to "$unc (macOS smb://$vip:${s.smbPort}/)")
                    }
                }
                s.activeTunnels().forEach { (svc, t) ->
                    // mefrp 的真实连接地址不在 node/list（hostname 对普通用户恒为空），
                    // 而在 proxy/list 的 data.nodes——创建/启动隧道时已回填到 nodeHost；
                    // 旧数据 nodeHost 为空时按 nodeId 从节点缓存兜底（通常也为空）。
                    val host = t.nodeHost.ifBlank {
                        if (t.provider == "mefrp")
                            mefrpStore.loadNodes().firstOrNull { it.nodeId == t.nodeId }?.hostname.orEmpty()
                        else ""
                    }
                    if (host.isNotBlank()) {
                        when (t.type.lowercase()) {
                            "http", "https" ->
                                // OpenFrp 七层 HTTP 隧道：用分配的域名直接 https 访问，无端口
                                add("公网访问链接($svc)" to "https://$host")
                            else -> {
                                if (t.remotePort != null && t.remotePort > 0) {
                                    val scheme = when (svc) {
                                        "ftp" -> "ftp"
                                        "smb" -> "smb"
                                        // http 服务（WebDAV）用 TCP 隧道映射，公网访问地址用 http 前缀
                                        else -> "http"
                                    }
                                    add("公网访问链接($svc)" to "$scheme://$host:${t.remotePort}")
                                }
                            }
                        }
                    } else if (t.provider == "mefrp") {
                        // mefrp 节点地址缺失（旧数据/尚未启动）：显式提示而不是静默不显示。
                        // 地址在启动共享时自动从服务端同步（proxy/list → nodeHost），无需手动刷新节点。
                        add("公网访问链接($svc)" to "mefrp 节点地址尚未同步——启动共享后自动获取；仍未出现请停止后重新启动共享")
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { linkDialogShareId = null },
                title = { Text("访问链接") },
                text = {
                    if (linkItems.isEmpty()) {
                        Text("请先启用 HTTP/WebDAV、FTP 或 SMB 服务，或创建公网隧道", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            linkItems.forEach { (label, url) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(label, style = MaterialTheme.typography.labelMedium)
                                        Text(
                                            url,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = {
                                        val clipboard =
                                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
                                        scope.launch { snackbarHostState.showSnackbar("已复制: $label") }
                                        linkDialogShareId = null
                                    }) { Icon(Icons.Default.ContentCopy, "复制 $label") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { linkDialogShareId = null }) { Text("关闭") } }
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (shares.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.FolderShared, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(16.dp))
                        Text("还没有共享配置", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("点击 + 创建第一个共享",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            } else {
                items(shares, key = { it.id }) { share ->
                    ShareCard(
                        share = share,
                        state = shareStates[share.id] ?: ShareStateHolder.ShareState(),
                        isExpanded = expandedLogId == share.id,
                        onToggleExpand = {
                            expandedLogId = if (expandedLogId == share.id) null else share.id
                        },
                        onEdit = { onEditShare(share) },
                        onStartStop = { onStartStop(share) },
                        onDelete = {
                            configStore.deleteConfig(share.id)
                            ShareStateHolder.removeShare(share.id)
                            refreshKey++
                            scope.launch { snackbarHostState.showSnackbar("已删除: ${share.name}") }
                        },
                        onNameChanged = { newName ->
                            configStore.saveConfig(share.copy(name = newName))
                            refreshKey++
                        },
                        onDuplicate = {
                            // 复制共享时不复制公网穿透信息（http/ftp/smb 隧道配置）。
                            // 否则副本与原共享会引用同一个 OpenFrp 代理（相同 proxyId/节点/远程端口），
                            // 两共享同时开启会冲突，且任一方删除穿透都会让另一方不可用且无法删除。
                            // 账号令牌（openFrpFrpcToken）保留，副本仍保持登录态，由用户另行创建独立隧道。
                            val copy = share.copy(
                                id = UUID.randomUUID().toString(),
                                name = "${share.name} (副本)",
                                httpTunnel = null,
                                ftpTunnel = null,
                                smbTunnel = null
                            )
                            configStore.saveConfig(copy)
                            refreshKey++
                            scope.launch { snackbarHostState.showSnackbar("已复制: ${copy.name}") }
                        },
                        onCopyLink = { s -> linkDialogShareId = s.id }
                    )
                }
            }

            // ── ADB 穿透卡片：仅已配置时显示；未配置入口在右下角 + 弹窗 ──
            if (adbConfig != null && adbConfig!!.proxyId > 0) {
                item(key = "adb_tunnel") {
                    AdbTunnelCard(
                        config = adbConfig!!,
                        state = adbState,
                        onOpen = onOpenAdbTunnel,
                        onStartStop = onStartStopAdb,
                        onDelete = onDeleteAdb
                    )
                }
            }

            // ── 连接共享卡片：每个远程连接一张（连接/断开/编辑/复制/删除）──
            items(remoteConfigs, key = { "remote_${it.id}" }) { rc ->
                RemoteConnCard(
                    config = rc,
                    state = remoteStates[rc.id] ?: com.lunashare.app.remote.RemoteConnectionManager.RemoteState(),
                    onStartStop = {
                        val st = remoteStates[rc.id] ?: com.lunashare.app.remote.RemoteConnectionManager.RemoteState()
                        if (st.connected || st.connecting) {
                            com.lunashare.app.remote.RemoteConnectionManager.disconnect(context, rc.id)
                        } else {
                            // 以最新持久化配置连接（编辑后无需重启 app）
                            remoteStore.loadConfig(rc.id)?.let { latest ->
                                com.lunashare.app.remote.RemoteConnectionManager.connect(context, latest)
                            }
                        }
                    },
                    onEdit = { onEditRemote(rc) },
                    onDuplicate = {
                        // 复制连接：新 id + 名称加后缀，其余配置原样保留
                        val copy = rc.copy(id = UUID.randomUUID().toString(), name = "${rc.name} (副本)")
                        remoteStore.saveConfig(copy)
                        refreshKey++
                        scope.launch { snackbarHostState.showSnackbar("已复制: ${copy.name}") }
                    },
                    onDelete = {
                        com.lunashare.app.remote.RemoteConnectionManager.remove(context, rc.id)
                        remoteStore.deleteConfig(rc.id)
                        refreshKey++
                        scope.launch { snackbarHostState.showSnackbar("已删除: ${rc.name}") }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareCard(
    share: ShareConfig,
    state: ShareStateHolder.ShareState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onStartStop: () -> Unit,
    onDelete: () -> Unit,
    onNameChanged: (String) -> Unit,
    onDuplicate: () -> Unit,
    onCopyLink: (ShareConfig) -> Unit
) {
    val isAnyRunning = state.isAnyRunning
    val statusColor = when {
        isAnyRunning -> StatusRunning
        state.lastError != null -> StatusError
        else -> StatusStopped
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isEditingName by remember { mutableStateOf(false) }
    var editName by remember(share.name) { mutableStateOf(share.name) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除共享") },
            text = { Text("确定要删除「${share.name}」吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggleExpand, onLongClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header row: indicator + name + status ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(modifier = Modifier.size(10.dp)) { drawCircle(statusColor) }
                Spacer(Modifier.width(10.dp))

                if (isEditingName) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.titleSmall
                    )
                    IconButton(onClick = { onNameChanged(editName); isEditingName = false }) {
                        Icon(Icons.Default.Check, contentDescription = "确认")
                    }
                    IconButton(onClick = { isEditingName = false }) {
                        Icon(Icons.Default.Close, contentDescription = "取消")
                    }
                } else {
                    Text(
                        text = share.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Copy link button
                    IconButton(onClick = { onCopyLink(share) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Link, "复制链接", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { isEditingName = true; editName = share.name }) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Share info line (常驻)：端口号 + 图标，运行时亮绿 ──
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (share.httpEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, null, Modifier.size(14.dp),
                            tint = if (state.httpRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text(":${share.httpPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.httpRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (share.ftpEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, Modifier.size(14.dp),
                            tint = if (state.ftpRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text(":${share.ftpPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.ftpRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (share.smbEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, null, Modifier.size(14.dp),
                            tint = if (state.smbRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text(":${share.smbPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.smbRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // OpenFrp / frpc1 indicator
                if (share.activeTunnels().isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Public, null, Modifier.size(14.dp),
                            tint = if (state.isAnyFrpcRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "FRPC",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isAnyFrpcRunning) StatusRunning
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (share.localDir.isNotBlank()) {
                    Text(
                        text = share.localDir.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Action buttons ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                // Start/Stop
                Button(
                    onClick = onStartStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAnyRunning) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(
                        if (isAnyRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null, Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isAnyRunning) "停止" else "启动", fontSize = 13.sp)
                }

                // Edit
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", fontSize = 13.sp)
                }

                // Duplicate
                OutlinedButton(
                    onClick = onDuplicate,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                }

                // Delete
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 13.sp)
                }
            }

            // ── Expandable log ──
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("日志", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { ShareStateHolder.clearLogs(share.id) }) {
                            Text("清空", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        val logs = state.combinedLogs.ifEmpty { listOf("(暂无日志)") }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                                // 最新日志置顶显示，打开即见最新，无需滚动
                                Text(
                                    text = logs.asReversed().joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdbTunnelCard(
    config: AdbTunnelConfig,
    state: AdbTunnelStateHolder.AdbTunnelState,
    onOpen: () -> Unit,
    onStartStop: () -> Unit,
    onDelete: () -> Unit
) {
    val running = state.tunnelRunning
    val statusColor = if (running) StatusRunning else StatusStopped
    val statusText = when {
        running -> "ADB 运行中"
        config.proxyId > 0 -> "已停止"
        else -> "未配置"
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除 ADB 穿透") },
            text = { Text("确定要删除 ADB 穿透隧道吗？此操作会停止隧道并在 OpenFrp 删除该代理。") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(10.dp)) { drawCircle(statusColor) }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "ADB 穿透",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(color = statusColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                    Text(statusText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
            }

            Spacer(Modifier.height(4.dp))
            if (config.proxyId > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Router, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("代理 ${config.proxyId} · ${config.nodeHost.ifBlank { "未知节点" }} : ${config.remotePort ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.connectCommand.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(state.connectCommand, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Button(
                    onClick = onStartStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (running) "停止" else "启动", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("配置", fontSize = 13.sp)
                }
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Canvas(
    modifier: Modifier,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    androidx.compose.foundation.Canvas(modifier = modifier, onDraw = draw)
}

/** 连接共享卡片（远程 WebDAV/SMB/FTP 客户端），操作排布对齐 ShareCard。 */
@Composable
private fun RemoteConnCard(
    config: com.lunashare.app.model.RemoteConnection,
    state: com.lunashare.app.remote.RemoteConnectionManager.RemoteState,
    onStartStop: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when {
        state.connected -> StatusRunning
        state.connecting -> StatusStopped
        state.error != null -> StatusError
        else -> StatusStopped
    }
    val statusText = when {
        state.connected -> "已连接"
        state.connecting -> "连接中"
        state.error != null -> "连接失败"
        else -> "未连接"
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除连接") },
            text = { Text("确定要删除「${config.name}」吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(10.dp)) { drawCircle(statusColor) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = config.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "连接共享 · ${RemoteConnection.protocolLabel(config.protocol)} · ${config.displayAddress()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(color = statusColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                    Text(statusText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
            }
            if (state.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(state.error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Button(
                    onClick = onStartStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.connected || state.connecting) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    ),
                    enabled = !state.connecting,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(if (state.connected) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.connected) "断开" else "连接", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", fontSize = 13.sp)
                }

                // Duplicate
                OutlinedButton(
                    onClick = onDuplicate,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                }

                // Delete
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 13.sp)
                }
            }
        }
    }
}

private fun urlEncode(s: String): String {
    return java.net.URLEncoder.encode(s, "UTF-8")
}

/** Get the device's local IP address on WiFi. */
private fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.name.startsWith("wlan") ||
                networkInterface.name.startsWith("eth") ||
                networkInterface.name.startsWith("ap")) {
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "192.168.1.1"
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return "192.168.1.1"
}

/**
 * 枚举虚拟组网网卡（tun）上的 IPv4 地址，返回 (标签, IP) 列表。
 * 手机上 EasyTier 走 VpnService，接口名以 "tun" 开头；默认虚拟网段 10.126.126.0/24，
 * 命中默认段的标为「EasyTier」，其余 tun 地址（自定义网段或其它组网 App）标为「组网」。
 * 返回值已按 IP 去重；未运行任何组网 App 时返回空列表（弹窗中不显示对应条目）。
 */
private fun getVirtualNetworkIps(): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.name.startsWith("tun")) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    val ip = addr.hostAddress ?: continue
                    val label = if (ip.startsWith("10.126.126.")) "EasyTier" else "组网"
                    out.add(label to ip)
                }
            }
        }
    } catch (_: Exception) {}
    return out.distinctBy { it.second }
}
