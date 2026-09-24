package com.lunashare.app.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunashare.app.adb.AdbTunnelConfig
import com.lunashare.app.adb.AdbTunnelManager
import com.lunashare.app.adb.AdbTunnelStateHolder
import com.lunashare.app.adb.AdbTunnelStore
import com.lunashare.app.frpc.OpenFrpApiClient
import com.lunashare.app.frpc.OpenFrpConfigStore
import com.lunashare.app.frpc.model.NewProxyRequest
import com.lunashare.app.frpc.model.NodeAvailability
import com.lunashare.app.frpc.model.OpenFrpNode
import com.lunashare.app.frpc.mefrp.MefrpApiClient
import com.lunashare.app.frpc.mefrp.MefrpConfigStore
import com.lunashare.app.frpc.mefrp.MefrpCreateProxyRequest
import com.lunashare.app.frpc.mefrp.MefrpNode
import com.lunashare.app.frpc.mefrp.MefrpNodeStatus
import com.lunashare.app.frpc.mefrp.sortedForPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ADB 端口穿透页（底部独立 Tab）。
 *
 * 负责三块：
 *  1. 手机本地 5555 是否已开启（root 一键开启 / 或复制 adb 命令在 PC 上执行）；
 *  2. 通过 OpenFrp / mefrp 把 127.0.0.1:5555 反向映射到公网（frpc 隧道）；
 *  3. 给出 PC 端 `adb connect <host>:<port>` 命令供复制。
 *
 * 服务商（provider）与「共享」里的 TunnelConfig.provider 同义：
 *  - OpenFrp：libfrpc.so，令牌 `-u <frpToken>`；
 *  - mefrp（幻缘映射）：libmefrpc.so，令牌 `-t <启动令牌>`，节点地址需从 proxy/list 回填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbTunnelScreen(
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    val state by AdbTunnelStateHolder.state.collectAsState()

    val adbStore = remember { AdbTunnelStore(context) }
    val openFrpStore = remember { OpenFrpConfigStore(context) }
    val api = remember { OpenFrpApiClient() }
    val openFrpAccount = remember { openFrpStore.loadAccount() }
    val hasOpenFrpAccount = openFrpAccount.isLoggedIn

    // mefrp（幻缘映射）第二服务商 state
    val mefrpStore = remember { MefrpConfigStore(context) }
    val mefrpApi = remember { MefrpApiClient() }
    val mefrpNodes = remember { mutableStateListOf(*mefrpStore.loadNodes().toTypedArray()) }
    var mefrpNodesError by remember { mutableStateOf<String?>(null) }
    var selectedMefrpNodeId by remember { mutableStateOf<Int?>(null) }
    // 节点运行状态（GET /auth/node/status：负载/在线数，官网节点卡片同源）
    var mefrpNodeStatuses by remember { mutableStateOf<Map<Int, MefrpNodeStatus>>(emptyMap()) }

    var config by remember { mutableStateOf(adbStore.loadConfig()) }

    // OpenFrp 节点（建隧道用）
    val nodes = remember { mutableStateListOf(*openFrpStore.loadNodes().toTypedArray()) }
    var frpToken by remember { mutableStateOf(openFrpAccount.frpToken) }

    // 服务商切换（OpenFrp / mefrp），默认与已有隧道一致，否则 OpenFrp
    var selectedProvider by remember { mutableStateOf(config?.provider ?: "openfrp") }
    val isMefrp = selectedProvider == "mefrp"
    // 建隧道准入：mefrp 看访问令牌，OpenFrp 看账号
    val hasAccount = if (isMefrp) mefrpStore.accessToken.isNotBlank() else hasOpenFrpAccount

    // 节点选择对话框状态（OpenFrp / mefrp 各一个）
    var showNodePicker by remember { mutableStateOf(false) }
    var showMefrpNodePicker by remember { mutableStateOf(false) }
    var selectedNodeId by remember { mutableStateOf<Int?>(null) }
    var remotePortInput by remember { mutableStateOf("") }
    var creatingTunnel by remember { mutableStateOf(false) }
    var tunnelCreateError by remember { mutableStateOf<String?>(null) }

    // 探测本地 5555 / root 状态
    fun detect() {
        scope.launch(Dispatchers.IO) {
            val listening = AdbTunnelManager.isAdbdListening()
            val root = AdbTunnelManager.isRootAvailable()
            AdbTunnelStateHolder.setAdbdListening(listening)
            AdbTunnelStateHolder.setRootAvailable(root)
        }
    }

    // 首次进入 + 配置变化时刷新
    LaunchedEffect(Unit) { detect() }
    LaunchedEffect(config?.proxyId) {
        config?.let {
            AdbTunnelStateHolder.setConnectInfo(it.nodeHost, it.remotePort)
        }
    }
    // 已有隧道存在时，让服务商选择与其一致（卡片展示用）
    LaunchedEffect(config?.provider) { config?.provider?.let { selectedProvider = it } }

    // OpenFrp：拉取 frpc 令牌 + 节点（若账号已登录且缺失）
    LaunchedEffect(hasOpenFrpAccount) {
        if (!hasOpenFrpAccount) return@LaunchedEffect
        scope.launch {
            val creds = OpenFrpApiClient.Credentials(openFrpAccount.authorization, openFrpAccount.session)
            if (frpToken.isBlank()) {
                val info = withContext(Dispatchers.IO) { api.getUserInfo(creds).getOrNull() }
                info?.token?.takeIf { it.isNotBlank() }?.let {
                    frpToken = it
                    openFrpStore.saveAccount(openFrpAccount.copy(frpToken = it))
                }
            }
            if (nodes.isEmpty() || nodes.any { it.portValue == null }) {
                val list = withContext(Dispatchers.IO) { api.getNodeList(creds).getOrNull() }
                list?.let {
                    nodes.clear(); nodes.addAll(it); openFrpStore.saveNodes(it)
                }
            }
        }
    }

    // mefrp：进入 mefrp 分支且令牌有效时按需拉取节点列表
    fun refreshMefrpNodes() {
        if (mefrpStore.accessToken.isBlank()) {
            mefrpNodesError = "mefrp 未验证：请先在「Frp 设置」页验证访问令牌"
            return
        }
        mefrpNodesError = null
        scope.launch {
            val list = withContext(Dispatchers.IO) { mefrpApi.getNodeList(mefrpStore.accessToken).getOrNull() }
            if (list != null) {
                mefrpNodes.clear(); mefrpNodes.addAll(list); mefrpStore.saveNodes(list); mefrpNodesError = null
            } else {
                mefrpNodesError = "节点拉取失败：请检查访问令牌/网络后重试"
            }
        }
    }
    LaunchedEffect(isMefrp) {
        if (isMefrp && mefrpStore.accessToken.isNotBlank() && (mefrpNodes.isEmpty() || mefrpNodesError != null)) {
            refreshMefrpNodes()
        }
    }
    // mefrp：打开节点选择弹窗时按需拉取一次节点运行状态
    LaunchedEffect(showMefrpNodePicker) {
        if (showMefrpNodePicker && mefrpStore.accessToken.isNotBlank() && mefrpNodeStatuses.isEmpty()) {
            scope.launch {
                val st = withContext(Dispatchers.IO) { mefrpApi.getNodeStatus(mefrpStore.accessToken).getOrNull() }
                mefrpNodeStatuses = st.orEmpty().associateBy { it.nodeId }
            }
        }
    }

    // ── 开启 5555（root → USB 兜底） ──
    fun enablePort() {
        scope.launch(Dispatchers.IO) {
            AdbTunnelStateHolder.addLog("正在尝试开启手机 5555 端口（root 模式）...")
            val r = AdbTunnelManager.enableAdbdPort(AdbTunnelManager.ADBD_PORT, persistIfRoot = false)
            AdbTunnelStateHolder.addLog(r.message)
            if (r.success) {
                AdbTunnelStateHolder.setAdbdListening(true)
            } else {
                r.fallbackCommand?.let { AdbTunnelStateHolder.addLog("PC 端命令（用 USB/无线调试 在电脑上执行）: $it") }
                withContext(Dispatchers.Main) {
                    snackbarHostState.showSnackbar("未能自动开启，请在电脑用 adb 命令开启（见下方按钮）")
                }
            }
            detect()
        }
    }

    fun disablePort() {
        scope.launch(Dispatchers.IO) {
            AdbTunnelStateHolder.addLog("正在关闭 5555 端口...")
            val r = AdbTunnelManager.disableAdbdPort(AdbTunnelManager.ADBD_PORT)
            AdbTunnelStateHolder.addLog(r.message)
            AdbTunnelStateHolder.setAdbdListening(!r.listening)
            detect()
        }
    }

    // ── 创建 OpenFrp 隧道（把 5555 映射到公网） ──
    fun createTunnel(node: OpenFrpNode, remotePort: Int) {
        if (!hasOpenFrpAccount) { scope.launch { snackbarHostState.showSnackbar("请先在 OpenFrp 设置页登录") }; return }
        val rp = remotePort
        creatingTunnel = true
        tunnelCreateError = null
        val tunnelName = "lunashare_adb" + String.format("%04d", java.util.Random().nextInt(9999) + 1)

        scope.launch {
            val creds = OpenFrpApiClient.Credentials(openFrpAccount.authorization, openFrpAccount.session)
            if (frpToken.isBlank()) {
                val info = withContext(Dispatchers.IO) { api.getUserInfo(creds).getOrNull() }
                info?.token?.takeIf { it.isNotBlank() }?.let { frpToken = it; openFrpStore.saveAccount(openFrpAccount.copy(frpToken = it)) }
            }
            if (frpToken.isBlank()) {
                tunnelCreateError = "无法获取 frpc 登录令牌，请重新登录 OpenFrp"
                snackbarHostState.showSnackbar("无法获取 frpc 登录令牌")
                creatingTunnel = false; return@launch
            }
            // 按名自愈：已存在同名隧道则直接采用
            var attemptPort = rp
            var created = withContext(Dispatchers.IO) { api.getUserProxies(creds) }.getOrNull()
                ?.filter { it.name == tunnelName || it.proxyName == tunnelName }
                ?.let { m -> m.firstOrNull { it.isActive } ?: m.firstOrNull() }
            if (created == null) {
                val MAX_TRIES = 15
                repeat(MAX_TRIES) { tries ->
                    val r = withContext(Dispatchers.IO) {
                        api.newProxy(creds, NewProxyRequest(
                            node_id = node.id, type = "tcp",
                            local_addr = "127.0.0.1",
                            local_port = AdbTunnelManager.ADBD_PORT.toString(),
                            name = tunnelName, remote_port = attemptPort
                        ))
                    }
                    if (r.isSuccess) { created = r.getOrNull(); return@repeat }
                    val msg = r.exceptionOrNull()?.message ?: "未知错误"
                    val occupied = msg.contains("被占用") || msg.contains("不可用") || msg.contains("端口")
                    if (!occupied) { tunnelCreateError = "创建失败: $msg"; return@repeat }
                    val range = node.allowPortRange
                    if (range != null && attemptPort >= range.last) { tunnelCreateError = "创建失败: 无可用端口"; return@repeat }
                    attemptPort += 1
                    remotePortInput = attemptPort.toString()
                    tunnelCreateError = "端口 ${attemptPort - 1} 已被占用，已自动尝试 $attemptPort"
                    if (tries < MAX_TRIES - 1) delay(150)
                }
            }

            val createdProxy = created
            if (createdProxy == null) {
                tunnelCreateError = tunnelCreateError ?: "创建失败"
                snackbarHostState.showSnackbar(tunnelCreateError ?: "创建失败")
                creatingTunnel = false; return@launch
            }

            // 解析真实 proxyId / 远程端口（newProxy 可能只返回 data:null）
            var realProxyId = createdProxy.proxyIdValue
            var realRemotePort = createdProxy.remotePortValue ?: attemptPort
            if (realProxyId <= 0 || realRemotePort <= 0) {
                repeat(4) { attempt ->
                    val list = withContext(Dispatchers.IO) { api.getUserProxies(creds) }.getOrNull() ?: emptyList()
                    val full = list.filter { it.name == tunnelName || it.proxyName == tunnelName }
                        .let { m -> m.firstOrNull { it.isActive } ?: m.firstOrNull() }
                    if (full != null) {
                        realProxyId = full.proxyIdValue
                        realRemotePort = full.remotePortValue ?: realRemotePort
                        return@repeat
                    }
                    if (attempt < 3) delay(700)
                }
            }
            if (realProxyId <= 0) {
                tunnelCreateError = "未能获取隧道真实代理 ID，请重试"
                snackbarHostState.showSnackbar("创建失败: 未能获取代理 ID")
                creatingTunnel = false; return@launch
            }

            val newCfg = AdbTunnelConfig(
                enabled = true,
                provider = "openfrp",
                proxyId = realProxyId,
                nodeId = node.id,
                nodeHost = node.serverHost.ifBlank { "待获取" },
                remotePort = realRemotePort,
                name = tunnelName,
                frpcToken = frpToken,
                localPort = AdbTunnelManager.ADBD_PORT
            )
            adbStore.saveConfig(newCfg)
            config = newCfg
            AdbTunnelStateHolder.setConnectInfo(newCfg.nodeHost, newCfg.remotePort)
            AdbTunnelStateHolder.addLog("ADB 公网隧道已创建 (代理 ID: $realProxyId, 端口: $realRemotePort)")

            showNodePicker = false
            selectedNodeId = null
            tunnelCreateError = null
            creatingTunnel = false
            // 立即启动 frpc 隧道
            onStartTunnel()
            snackbarHostState.showSnackbar("隧道创建成功! 代理 ID: $realProxyId")
        }
    }

    // ── 创建 mefrp 隧道（把 5555 映射到 mefrp 公网节点） ──
    fun createMefrpTunnel(remotePort: Int) {
        if (mefrpStore.accessToken.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("请先在「Frp 设置」页粘贴并验证 mefrp 访问令牌") }
            return
        }
        val node = mefrpNodes.find { it.nodeId == selectedMefrpNodeId }
        if (node == null) {
            scope.launch { snackbarHostState.showSnackbar("请选择一个 mefrp 节点") }
            return
        }
        val rp = remotePort
        if (rp <= 0) {
            scope.launch { snackbarHostState.showSnackbar("请输入有效远程端口") }
            return
        }

        creatingTunnel = true
        tunnelCreateError = null
        val tunnelName = "lunashare_adb" + String.format("%04d", java.util.Random().nextInt(9999) + 1)

        scope.launch {
            // 预先确保启动令牌（-t 用）已缓存
            var frpTokenCached = mefrpStore.frpcToken
            if (frpTokenCached.isBlank()) {
                frpTokenCached = withContext(Dispatchers.IO) { mefrpApi.getUserFrpToken(mefrpStore.accessToken).getOrNull() }.orEmpty()
                if (frpTokenCached.isNotBlank()) mefrpStore.frpcToken = frpTokenCached
            }

            var attemptPort = rp
            var createdId = 0
            var proxyList = withContext(Dispatchers.IO) { mefrpApi.getProxyList(mefrpStore.accessToken).getOrNull() } ?: emptyList()
            // 幂等自愈：同名隧道已存在 → 直接复用其真实 ID
            val existing = proxyList.filter { it.proxyName == tunnelName }
                .let { m -> m.firstOrNull { it.isActive } ?: m.firstOrNull() }
            if (existing != null) {
                createdId = existing.proxyId
            } else {
                val MAX_TRIES = 15
                var lastErr = "未知错误"
                repeat(MAX_TRIES) { tries ->
                    val r = withContext(Dispatchers.IO) {
                        mefrpApi.createProxy(
                            mefrpStore.accessToken,
                            MefrpCreateProxyRequest(
                                nodeId = node.nodeId,
                                proxyName = tunnelName,
                                proxyType = "tcp",
                                localIp = "127.0.0.1",
                                localPort = AdbTunnelManager.ADBD_PORT,
                                remotePort = attemptPort
                            )
                        )
                    }
                    if (r.isSuccess) { lastErr = ""; return@repeat }
                    lastErr = r.exceptionOrNull()?.message ?: "未知错误"
                    // 同名/已存在 → 服务端已有该隧道（可能是上次创建成功但回查漏了），视为已存在自愈复用
                    val existsErr = lastErr.contains("同名") || lastErr.contains("已存在") ||
                            lastErr.contains("重复") || lastErr.contains("already")
                    if (existsErr) { lastErr = ""; return@repeat }
                    val occupied = lastErr.contains("占用") || lastErr.contains("不可用") || lastErr.contains("端口")
                    if (!occupied) return@repeat
                    val maxPort = node.portRanges.maxOfOrNull { it.last }
                    if (maxPort != null && attemptPort >= maxPort) return@repeat
                    attemptPort += 1
                    remotePortInput = attemptPort.toString()
                    tunnelCreateError = "端口 ${attemptPort - 1} 已被占用，已自动尝试 $attemptPort"
                    if (tries < MAX_TRIES - 1) delay(150)
                }
                if (lastErr.isNotEmpty()) {
                    // 双保险：服务端若因节点需 VIP 权限而拒绝，给出明确提示
                    // （即使节点列表未标注 VIP，也能在创建失败时让用户知道原因）
                    val vipHint = lastErr.contains("vip", true) || lastErr.contains("专属") ||
                            lastErr.contains("权限") || lastErr.contains("等级") || lastErr.contains("会员")
                    val msg = if (vipHint)
                        "该节点为 VIP 专属节点，需 VIP 权限，普通用户无法创建" else "创建失败: $lastErr"
                    tunnelCreateError = msg
                    snackbarHostState.showSnackbar(msg)
                    creatingTunnel = false; return@launch
                }
                // 回查真实 proxyId（createProxy 成功 data 为空，需按名字查）
                repeat(8) { attempt ->
                    proxyList = withContext(Dispatchers.IO) { mefrpApi.getProxyList(mefrpStore.accessToken).getOrNull() } ?: emptyList()
                    val match = proxyList.filter { it.proxyName == tunnelName }
                        .let { m -> m.firstOrNull { it.isActive } ?: m.firstOrNull() }
                    if (match != null) { createdId = match.proxyId; return@repeat }
                    if (attempt < 7) delay(1000)
                }
            }

            if (createdId <= 0) {
                tunnelCreateError = "未能获取隧道真实代理 ID，请重试或稍后刷新"
                snackbarHostState.showSnackbar("创建失败: 未能获取隧道真实代理 ID")
                Log.e("AdbMefrp", "refusing to save/start mefrp ADB tunnel with invalid proxyId=$createdId")
                creatingTunnel = false; return@launch
            }

            // 节点真实连接地址（公网访问地址的 host，如 ip.lhdyx.top）：
            // node/list 的 hostname 对普通用户恒为空，必须从 proxy/list 的 data.nodes 按 nodeId 取；
            // 拿不到则留空，ShareService 启动前还会兜底回填。
            val nodeHost = withContext(Dispatchers.IO) {
                mefrpApi.getProxyListWithNodes(mefrpStore.accessToken).getOrNull()
                    ?.nodes?.firstOrNull { it.nodeId == node.nodeId }?.hostname
            }.orEmpty()

            val newCfg = AdbTunnelConfig(
                enabled = true,
                provider = "mefrp",
                proxyId = createdId,
                nodeId = node.nodeId,
                nodeHost = nodeHost,
                remotePort = attemptPort,
                name = tunnelName,
                frpcToken = "",   // mefrp 启动令牌运行时从 mefrpStore 取，不落 AdbTunnelConfig
                localPort = AdbTunnelManager.ADBD_PORT
            )
            adbStore.saveConfig(newCfg)
            config = newCfg
            AdbTunnelStateHolder.setConnectInfo(newCfg.nodeHost, newCfg.remotePort)
            AdbTunnelStateHolder.addLog("ADB mefrp 公网隧道已创建 (代理 ID: $createdId, 端口: $attemptPort)")

            showMefrpNodePicker = false
            selectedMefrpNodeId = null
            tunnelCreateError = null
            creatingTunnel = false
            // 立即启动 frpc 隧道
            onStartTunnel()
            snackbarHostState.showSnackbar("隧道创建成功! 代理 ID: $createdId")
        }
    }

    fun deleteTunnel() {
        val cfg = config ?: return
        scope.launch {
            if (cfg.provider == "mefrp") {
                if (mefrpStore.accessToken.isBlank()) {
                    snackbarHostState.showSnackbar("mefrp 访问令牌为空，无法删除隧道")
                    return@launch
                }
                val list = withContext(Dispatchers.IO) { mefrpApi.getProxyList(mefrpStore.accessToken).getOrNull() }
                    ?: emptyList<com.lunashare.app.frpc.mefrp.MefrpProxy>()
                val realId = list.find { it.proxyName == cfg.name }?.proxyId ?: cfg.proxyId.takeIf { it != 0 }
                if (realId == null || realId == 0) { snackbarHostState.showSnackbar("无法解析隧道 ID"); return@launch }
                val r = withContext(Dispatchers.IO) { mefrpApi.deleteProxy(mefrpStore.accessToken, realId) }
                r.onSuccess {
                    adbStore.clear(); config = null
                    AdbTunnelStateHolder.setConnectInfo("", null)
                    AdbTunnelStateHolder.setTunnelRunning(false)
                    AdbTunnelStateHolder.setEnabled(false)
                    AdbTunnelStateHolder.addLog("ADB 隧道已删除")
                    onStopTunnel(); snackbarHostState.showSnackbar("隧道已删除")
                }.onFailure { e -> snackbarHostState.showSnackbar("删除失败: ${e.message}") }
            } else {
                val creds = OpenFrpApiClient.Credentials(openFrpAccount.authorization, openFrpAccount.session)
                val realId = withContext(Dispatchers.IO) {
                    val list = runCatching { api.getUserProxies(creds).getOrNull() }.getOrNull()
                        ?: emptyList<com.lunashare.app.frpc.model.OpenFrpProxy>()
                    list.find { it.name == cfg.name || it.proxyName == cfg.name }?.proxyIdValue ?: cfg.proxyId.takeIf { it != 0 }
                }
                if (realId == null || realId == 0) { snackbarHostState.showSnackbar("无法解析隧道 ID"); return@launch }
                val r = withContext(Dispatchers.IO) { api.removeProxy(creds, realId) }
                r.onSuccess {
                    adbStore.clear(); config = null
                    AdbTunnelStateHolder.setConnectInfo("", null)
                    AdbTunnelStateHolder.setTunnelRunning(false)
                    AdbTunnelStateHolder.setEnabled(false)
                    AdbTunnelStateHolder.addLog("ADB 隧道已删除")
                    onStopTunnel(); snackbarHostState.showSnackbar("隧道已删除")
                }.onFailure { e -> snackbarHostState.showSnackbar("删除失败: ${e.message}") }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("ADB 端口穿透") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "把手机 adbd 的 TCP 5555 监听通过 OpenFrp / mefrp 反向映射到公网，PC 端即可在任意网络（含蜂窝数据）用 `adb connect` 远程控制手机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── 状态概览 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusRow(
                        icon = if (state.adbdListening) Icons.Default.CheckCircle else Icons.Default.Error,
                        tint = if (state.adbdListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        label = "手机 5555 端口",
                        value = if (state.adbdListening) "已在监听（可建隧道）" else "未开启"
                    )
                    HorizontalDivider()
                    StatusRow(
                        icon = Icons.Default.Memory,
                        tint = if (state.rootAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "Root",
                        value = if (state.rootAvailable) "可用（可永久开机自启）" else "不可用"
                    )
                }
            }

            // ── 第一步：开启手机 5555 ──
            Text("第一步 · 开启手机 5555 端口", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)

            if (state.adbdListening) {
                Button(onClick = { disablePort() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PowerOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("关闭 5555 端口（切回仅 USB）")
                }
            } else {
                Button(onClick = { enablePort() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Power, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("一键开启 5555（root 模式）")
                }
                OutlinedButton(onClick = { detect() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("刷新状态")
                }
            }

            // ── 复制 adb 命令（推荐方式） ──
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("在电脑上用 adb 命令开启 / 关闭（推荐）", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "手机已通过数据线或无线调试连上电脑后，在电脑终端执行下面命令即可。复制按钮可直接复制到剪贴板。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    // 开启命令
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text("adb tcpip ${AdbTunnelManager.ADBD_PORT}",
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString("adb tcpip ${AdbTunnelManager.ADBD_PORT}"))
                            scope.launch { snackbarHostState.showSnackbar("已复制开启命令") }
                        }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("复制开启端口命令")
                        }
                    }

                    // 关闭命令
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text("adb tcpip 0",
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString("adb tcpip 0"))
                            scope.launch { snackbarHostState.showSnackbar("已复制关闭命令") }
                        }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("复制关闭端口命令")
                        }
                    }

                    Text(
                        "说明：`adb tcpip 5555` 让手机 adbd 在 TCP 5555 监听（绑定所有网卡，含蜂窝数据）；`adb tcpip 0` 关闭该监听。开启后回到此页点「刷新状态」，待「手机 5555 端口」显示「已在监听」即可建公网隧道。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // ── 第二步：公网隧道 ──
            Text("第二步 · 公网隧道（OpenFrp / mefrp）", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)

            if (config != null && config!!.proxyId > 0) {
                val cfg = config!!
                Card(modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("ADB 反向隧道", style = MaterialTheme.typography.titleSmall)
                            if (state.tunnelRunning) {
                                AssistChip(onClick = {}, label = { Text("运行中") },
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp)) })
                            }
                        }
                        Text("${if (cfg.provider == "mefrp") "mefrp" else "OpenFrp"} · 代理 ID: ${cfg.proxyId} · 节点: ${cfg.nodeHost.ifBlank { "未知" }} · 远端端口: ${cfg.remotePort ?: "?"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))

                        // ── 开机自启开关 ──
                        var bootAuto by remember(cfg.bootAutoStart) { mutableStateOf(cfg.bootAutoStart) }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("开机自动启动此 ADB 穿透", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "重启后由 BootReceiver 自动拉起本隧道（前提是手机已开启 5555 监听：可用 root 或先用电脑 adb 命令开启）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = bootAuto,
                                onCheckedChange = { checked ->
                                    bootAuto = checked
                                    val updated = cfg.copy(bootAutoStart = checked)
                                    adbStore.saveConfig(updated)
                                    config = updated
                                    AdbTunnelStateHolder.addLog("开机自启已${if (checked) "开启" else "关闭"}")
                                }
                            )
                        }

                        if (state.connectCommand.isNotBlank()) {
                            Text("PC 端连接命令：", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(state.connectCommand, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.connectCommand)) }) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text("复制连接命令")
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.tunnelRunning) {
                                OutlinedButton(onClick = onStopTunnel, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp)); Text("停止隧道")
                                }
                            } else {
                                Button(onClick = onStartTunnel, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp)); Text("启动隧道")
                                }
                            }
                            TextButton(onClick = { deleteTunnel() }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else {
                // 服务商切换
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isMefrp,
                        onClick = { selectedProvider = "openfrp" },
                        label = { Text("OpenFrp") },
                        leadingIcon = if (!isMefrp) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null
                    )
                    FilterChip(
                        selected = isMefrp,
                        onClick = { selectedProvider = "mefrp" },
                        label = { Text("mefrp（幻缘映射）") },
                        leadingIcon = if (isMefrp) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null
                    )
                }

                if (isMefrp) {
                    if (!hasAccount) {
                        Card(modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(8.dp))
                                Text("创建隧道需先在「Frp 设置」页粘贴并验证 mefrp 访问令牌。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Button(onClick = { showMefrpNodePicker = true }, enabled = hasAccount,
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Router, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasAccount) "选择 mefrp 节点创建公网隧道" else "需要先验证 mefrp 令牌")
                    }
                    Text("创建隧道会把手机本地 127.0.0.1:5555 映射到 mefrp 公网节点，PC 端即可 `adb connect`。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    mefrpNodesError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    if (!hasAccount) {
                        Card(modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(8.dp))
                                Text("创建隧道需先在 OpenFrp 设置页登录。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Button(onClick = { showNodePicker = true }, enabled = hasAccount,
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Router, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (hasAccount) "选择节点创建公网隧道" else "需要先登录 OpenFrp")
                    }
                    Text("创建隧道会把手机本地 127.0.0.1:5555 映射到 OpenFrp 公网节点，PC 端即可 `adb connect`。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider()

            // ── 日志 ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { AdbTunnelStateHolder.clearLogs() }) { Text("清空") }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (state.logs.isEmpty()) {
                        Text("暂无日志", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        state.logs.takeLast(60).forEach { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // ── OpenFrp 节点选择对话框 ──
    if (showNodePicker) {
        AlertDialog(
            onDismissRequest = { if (!creatingTunnel) showNodePicker = false },
            title = { Text("选择 OpenFrp 节点") },
            text = {
                Column {
                    Text("选择一个节点创建 TCP 隧道（固定本地端口 ${AdbTunnelManager.ADBD_PORT}）",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                    ) {
                        val sorted = nodes.sortedBy { node ->
                            val rank = when (node.availability) {
                                NodeAvailability.UNAVAILABLE -> 2
                                NodeAvailability.RESTRICTED -> 1
                                else -> 0
                            }
                            val normal = if (node.group.isNullOrBlank() || node.group!!.lowercase().contains("normal")) 0 else 1
                            rank * 10 + normal
                        }
                        sorted.forEach { node ->
                            NodeStatusCard(
                                node = node,
                                selected = node.id == selectedNodeId,
                                onClick = {
                                    selectedNodeId = node.id
                                    remotePortInput = node.suggestedRemotePort(AdbTunnelManager.ADBD_PORT).toString()
                                },
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val selNode = nodes.find { it.id == selectedNodeId }
                    OutlinedTextField(
                        value = remotePortInput,
                        onValueChange = { remotePortInput = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("远程端口 (remote_port)") },
                        placeholder = { Text("例如 12345") },
                        singleLine = true,
                        isError = selNode != null && remotePortInput.isNotBlank() &&
                            (remotePortInput.toIntOrNull()?.let { p -> selNode!!.allowPortRange?.let { r -> p !in r } ?: false } ?: false),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(selNode?.allowPortHint ?: "请先选择节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tunnelCreateError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val node = nodes.find { it.id == selectedNodeId }
                        val rp = remotePortInput.toIntOrNull()
                        val range = node?.allowPortRange
                        when {
                            selectedNodeId == null -> scope.launch { snackbarHostState.showSnackbar("请选择一个节点") }
                            rp == null || rp !in 1..65535 -> scope.launch { snackbarHostState.showSnackbar("请输入有效远程端口 (1-65535)") }
                            range != null && rp !in range -> scope.launch { snackbarHostState.showSnackbar("远程端口超出范围 ${range.first}-${range.last}") }
                            else -> { showNodePicker = false; node?.let { createTunnel(it, rp!!) } }
                        }
                    },
                    enabled = selectedNodeId != null && !creatingTunnel
                ) {
                    if (creatingTunnel) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp)); Text("创建中...")
                    } else { Text("创建隧道") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showNodePicker = false; selectedNodeId = null }, enabled = !creatingTunnel) {
                    Text("取消")
                }
            }
        )
    }

    // ── mefrp 节点选择对话框 ──
    if (showMefrpNodePicker) {
        AlertDialog(
            onDismissRequest = { if (!creatingTunnel) showMefrpNodePicker = false },
            title = { Text("选择 mefrp 节点（幻缘映射）") },
            text = {
                Column {
                    Text("选择一个 mefrp 节点创建 TCP 隧道 (本地端口 ${AdbTunnelManager.ADBD_PORT})",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                    ) {
                        when {
                            mefrpNodes.isEmpty() && mefrpNodesError != null -> {
                                Text(mefrpNodesError ?: "节点加载失败",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            mefrpNodes.isEmpty() -> {
                                Text("暂无可用节点", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> {
                                // 在线优先 → 非 VIP 专属优先 → 负载低优先
                                mefrpNodes.sortedForPicker(mefrpNodeStatuses).forEach { node ->
                                    val selected = node.nodeId == selectedMefrpNodeId
                                    val status = mefrpNodeStatuses[node.nodeId]
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedMefrpNodeId = node.nodeId
                                                    remotePortInput = (node.suggestedRemotePort() ?: 10000).toString()
                                                }
                                                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                // 在线状态圆点（绿=在线 / 灰=离线）
                                                Box(
                                                    modifier = Modifier.size(8.dp).clip(CircleShape).background(
                                                        if (node.isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
                                                    )
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    node.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Badge { Text("mefrp", style = MaterialTheme.typography.labelSmall) }
                                                if (node.vip) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Badge(
                                                        containerColor = Color(0xFFD4A017),
                                                        contentColor = Color(0xFF212121)
                                                    ) { Text("VIP", style = MaterialTheme.typography.labelSmall) }
                                                }
                                                Spacer(Modifier.width(6.dp))
                                                // 负载 chip（官网节点卡片同款「负载 79%」）
                                                val load = status?.loadPercent
                                                if (load != null) {
                                                    val (bg, fg) = when (status.loadLevel) {
                                                        MefrpNodeStatus.LoadLevel.LOW ->
                                                            MaterialTheme.colorScheme.primaryContainer to
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                        MefrpNodeStatus.LoadLevel.HIGH ->
                                                            MaterialTheme.colorScheme.errorContainer to
                                                                MaterialTheme.colorScheme.onErrorContainer
                                                        else ->
                                                            MaterialTheme.colorScheme.tertiaryContainer to
                                                                MaterialTheme.colorScheme.onTertiaryContainer
                                                    }
                                                    Surface(color = bg, shape = MaterialTheme.shapes.small) {
                                                        Text(
                                                            "负载 $load%", style = MaterialTheme.typography.labelSmall,
                                                            color = fg,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                } else if (!node.isOnline) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.errorContainer,
                                                        shape = MaterialTheme.shapes.small
                                                    ) {
                                                        Text(
                                                            "离线", style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                buildString {
                                                    node.bandwidth?.takeIf { it.isNotBlank() }?.let { append("$it ") }
                                                    append("端口 ")
                                                    append(node.portRanges.joinToString(",") { "${it.first}-${it.last}" }
                                                        .ifBlank { "不限" })
                                                    node.allowType?.takeIf { it.isNotBlank() }?.let { append(" · ${it.uppercase()}") }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            // 描述（官网卡片的节点说明，如「衢州电信 复活的#9」）
                                            node.description?.takeIf { it.isNotBlank() }?.let {
                                                Text(
                                                    it, style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            // 运行状态小字：在线客户端/代理（负载已知时才展示，数据同源才可信）
                                            if (status != null && status.isOnline) {
                                                Text(
                                                    "在线客户端 ${status.onlineClient} · 在线隧道 ${status.onlineProxy}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val selMefrpNode = mefrpNodes.find { it.nodeId == selectedMefrpNodeId }
                    OutlinedTextField(
                        value = remotePortInput,
                        onValueChange = { remotePortInput = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("远程端口 (remote_port)") },
                        placeholder = { Text("例如 12345") },
                        singleLine = true,
                        isError = selMefrpNode != null && remotePortInput.isNotBlank() && run {
                            val p = remotePortInput.toIntOrNull()
                            val rs = selMefrpNode!!.portRanges
                            p != null && rs.isNotEmpty() && rs.none { p in it }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        selMefrpNode?.let { n ->
                            val ranges = n.portRanges
                            if (ranges.isEmpty()) "该节点无端口范围限制" else "允许端口: ${ranges.joinToString(",") { "${it.first}-${it.last}" }}"
                        } ?: "请先选择节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    tunnelCreateError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val node = mefrpNodes.find { it.nodeId == selectedMefrpNodeId }
                        val rp = remotePortInput.toIntOrNull()
                        val ranges = node?.portRanges
                        when {
                            selectedMefrpNodeId == null -> scope.launch { snackbarHostState.showSnackbar("请选择一个节点") }
                            rp == null || rp !in 1..65535 -> scope.launch { snackbarHostState.showSnackbar("请输入有效远程端口 (1-65535)") }
                            ranges != null && ranges.isNotEmpty() && ranges.none { rp in it } ->
                                scope.launch { snackbarHostState.showSnackbar("远程端口超出范围 ${ranges.joinToString(",") { "${it.first}-${it.last}" }}") }
                            else -> { showMefrpNodePicker = false; node?.let { createMefrpTunnel(rp ?: 0) } }
                        }
                    },
                    enabled = selectedMefrpNodeId != null && !creatingTunnel
                ) {
                    if (creatingTunnel) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp)); Text("创建中...")
                    } else { Text("创建隧道") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMefrpNodePicker = false; selectedMefrpNodeId = null }, enabled = !creatingTunnel) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    value: String
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
