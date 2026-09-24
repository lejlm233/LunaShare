package com.lunashare.app.ui

import android.content.Intent
import android.util.Log
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import java.security.SecureRandom
import com.lunashare.app.frpc.OpenFrpApiClient
import com.lunashare.app.frpc.OpenFrpConfigStore
import com.lunashare.app.frpc.mefrp.MefrpApiClient
import com.lunashare.app.frpc.mefrp.MefrpConfigStore
import com.lunashare.app.frpc.mefrp.MefrpCreateProxyRequest
import com.lunashare.app.frpc.mefrp.MefrpNode
import com.lunashare.app.frpc.mefrp.MefrpNodeStatus
import com.lunashare.app.frpc.mefrp.sortedForPicker
import com.lunashare.app.frpc.mefrp.MefrpProxy
import com.lunashare.app.frpc.model.NewProxyRequest
import com.lunashare.app.frpc.model.NodeAvailability
import com.lunashare.app.frpc.model.OpenFrpNode
import com.lunashare.app.frpc.model.OpenFrpProxy
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.model.ShareConfig
import com.lunashare.app.model.TunnelConfig
import com.lunashare.app.service.ShareService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler

/**
 * Config editor screen for a single file share.
 *
 * Shows a form for editing [share] settings.
 * On save, calls [onConfigChanged] with the updated share.
 * [onDismiss] is called when the user presses back/cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceConfigScreen(
    share: ShareConfig,
    onConfigChanged: (ShareConfig) -> Unit,
    configStore: ShareConfigStore,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // System back button → dismiss
    BackHandler { onDismiss() }

    var name by remember(share) { mutableStateOf(share.name) }
    var localDir by remember(share) { mutableStateOf(share.localDir) }
    var username by remember(share) { mutableStateOf(share.username) }
    var password by remember(share) { mutableStateOf(share.password) }
    var httpEnabled by remember(share) { mutableStateOf(share.httpEnabled) }
    var ftpEnabled by remember(share) { mutableStateOf(share.ftpEnabled) }
    var smbEnabled by remember(share) { mutableStateOf(share.smbEnabled) }
    var httpPort by remember(share) { mutableStateOf(share.httpPort.toString()) }
    var ftpPort by remember(share) { mutableStateOf(share.ftpPort.toString()) }
    var smbPort by remember(share) { mutableStateOf(share.smbPort.toString()) }
    var autoStart by remember(share) { mutableStateOf(share.autoStart) }
    var encPassword by remember(share) { mutableStateOf(share.encPassword) }
    var showEncPassword by remember(share) { mutableStateOf(false) }

    // Tunnel configs
    var httpTunnel by remember(share) { mutableStateOf(share.httpTunnel) }
    var ftpTunnel by remember(share) { mutableStateOf(share.ftpTunnel) }
    var smbTunnel by remember(share) { mutableStateOf(share.smbTunnel) }

    // OpenFrp store for API access
    val openFrpStore = remember { OpenFrpConfigStore(context) }
    val openFrpAccount = remember { openFrpStore.loadAccount() }
    val openFrpNodes = remember { mutableStateListOf(*openFrpStore.loadNodes().toTypedArray()) }
    val hasOpenFrpAccount = openFrpAccount.isLoggedIn

    // frpc login token (from getUserInfo().token) — distinct from the API
    // authorization header. Used to connect frpc to frps.
    var frpToken by remember { mutableStateOf(openFrpAccount.frpToken) }

    
    // mefrp（幻缘映射）第二服务商 state
    val mefrpStore = remember { MefrpConfigStore(context) }
    val mefrpApi = remember { MefrpApiClient() }
    val mefrpNodes = remember { mutableStateListOf(*mefrpStore.loadNodes().toTypedArray()) }
    var mefrpNodesError by remember { mutableStateOf<String?>(null) }

    // mefrp 节点「nodeId -> 节点名」映射：隧道卡节点字段兜底显示用
    val mefrpNodeNames = mefrpNodes.associate { it.nodeId to it.displayName }

    // 当前选中的 mefrp 节点（节点选择弹窗临时状态）
    var selectedMefrpNodeId by remember { mutableStateOf<Int?>(null) }

    // Creating tunnel state
    var creatingTunnel by remember { mutableStateOf(false) }
    var creatingService by remember { mutableStateOf<String?>(null) }
    var showNodePicker by remember { mutableStateOf<String?>(null) }
    var selectedNodeId by remember { mutableStateOf<Int?>(null) }

    // mefrp 节点运行状态（GET /auth/node/status：负载/在线数/版本，官网节点卡片同源）。
    // 打开节点选择弹窗时按需拉取一次，按 nodeId 与节点列表 join 展示。
    var mefrpNodeStatuses by remember { mutableStateOf<Map<Int, MefrpNodeStatus>>(emptyMap()) }
    LaunchedEffect(showNodePicker, openFrpStore.selectedProvider) {
        if (showNodePicker != null && openFrpStore.selectedProvider == "mefrp" &&
            mefrpStore.accessToken.isNotBlank() && mefrpNodeStatuses.isEmpty()
        ) {
            val st = withContext(Dispatchers.IO) {
                mefrpApi.getNodeStatus(mefrpStore.accessToken).getOrNull()
            }
            mefrpNodeStatuses = st.orEmpty().associateBy { it.nodeId }
        }
    }

    // Remote port input + inline error for the node-picker dialog (hoisted so
    // createTunnel() can auto-advance the port on "already in use" without closing).
    var remotePortInput by remember { mutableStateOf("") }
    var tunnelCreateError by remember { mutableStateOf<String?>(null) }

    // Reset the port field + clear any prior error each time the picker opens.
    LaunchedEffect(showNodePicker) {
        if (showNodePicker != null) {
            remotePortInput = ""
            tunnelCreateError = null
        }
    }

    val api = remember { OpenFrpApiClient() }
    val snackbarHostState = remember { SnackbarHostState() }

    /** Assemble the current ShareConfig from local UI state (shared by Save + create-tunnel). */
    fun buildUpdatedConfig(): ShareConfig = ShareConfig(
        id = share.id,
        name = name.ifBlank { "新共享" },
        localDir = localDir,
        username = username,
        password = password,
        httpEnabled = httpEnabled,
        ftpEnabled = ftpEnabled,
        smbEnabled = smbEnabled,
        httpPort = httpPort.toIntOrNull() ?: 8080,
        ftpPort = ftpPort.toIntOrNull() ?: 8021,
        smbPort = smbPort.toIntOrNull() ?: 8445,
        autoStart = autoStart,
        encPassword = encPassword,
        openFrpFrpcToken = frpToken.takeIf { it.isNotBlank() } ?: openFrpAccount.authorization,
        httpTunnel = httpTunnel,
        ftpTunnel = ftpTunnel,
        smbTunnel = smbTunnel
    )

    // Directory picker
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val path = uriToPath(context, uri)
            if (path != null) localDir = path else localDir = uri.toString()
        }
    }

    // Load account extras (frp token) + node list for creating tunnels
    LaunchedEffect(hasOpenFrpAccount) {
        if (!hasOpenFrpAccount) return@LaunchedEffect
        scope.launch {
            val creds = OpenFrpApiClient.Credentials(
                openFrpAccount.authorization,
                openFrpAccount.session
            )
            // 1) Fetch the frpc login token (getUserInfo.token). This is what frpc
            //    needs to connect to frps — NOT the API authorization header.
            if (frpToken.isBlank()) {
                val info = withContext(Dispatchers.IO) { api.getUserInfo(creds).getOrNull() }
                info?.token?.takeIf { it.isNotBlank() }?.let {
                    frpToken = it
                    openFrpStore.saveAccount(openFrpAccount.copy(frpToken = it))
                }
            }
            // 2) Make sure the persisted share config carries the correct frpc token,
            //    so ShareService uses it when (re)starting the tunnel.
            if (frpToken.isNotBlank() && share.openFrpFrpcToken != frpToken) {
                configStore.saveConfig(share.copy(openFrpFrpcToken = frpToken))
            }
            // 3) Load nodes (refresh if missing or any node lacks a usable port)
            if (openFrpNodes.isEmpty() || openFrpNodes.any { it.portValue == null }) {
                val list = withContext(Dispatchers.IO) { api.getNodeList(creds).getOrNull() }
                list?.let {
                    openFrpNodes.clear()
                    openFrpNodes.addAll(it)
                    openFrpStore.saveNodes(it)
                }
            }
        }
    }

    /**
     * mefrp 创建隧道：校验令牌 → 选节点 → createProxy → 回查 proxyId → 写回 TunnelConfig(provider=mefrp)。
     */
    fun createMefrpTunnel(service: String, port: Int, remotePort: Int? = null) {
        if (mefrpStore.accessToken.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("请先在「Frp 设置」页粘贴并验证 mefrp 访问令牌") }
            return
        }
        if (selectedMefrpNodeId == null) {
            showNodePicker = service
            return
        }
        val node = mefrpNodes.find { it.nodeId == selectedMefrpNodeId }
        if (node == null) {
            scope.launch { snackbarHostState.showSnackbar("请选择一个 mefrp 节点") }
            return
        }
        if (remotePort == null) {
            showNodePicker = service
            return
        }

        creatingTunnel = true
        creatingService = service
        val tunnelName = "lunashare_" + service.lowercase().replace(Regex("[^a-z0-9_]"), "_") +
                String.format("%04d", java.util.Random().nextInt(9999) + 1)

        scope.launch {
            tunnelCreateError = null
            var attemptPort = remotePort ?: 0
            var createdId = 0
            var proxyList = withContext(Dispatchers.IO) {
                mefrpApi.getProxyList(mefrpStore.accessToken).getOrNull() ?: emptyList()
            }

            // 幂等自愈：同名隧道已存在 → 直接复用其真实 ID
            val existing = proxyList.filter { it.proxyName == tunnelName }
                .let { matches -> matches.firstOrNull { it.isActive } ?: matches.firstOrNull() }
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
                                localPort = port,
                                remotePort = attemptPort
                            )
                        )
                    }
                    if (r.isSuccess) { lastErr = ""; return@repeat }
                    lastErr = r.exceptionOrNull()?.message ?: "未知错误"
                    // 同名/已存在 → 服务端已有该隧道（可能是上次创建成功但回查漏了），
                    // 视为已存在，清空错误走回查复用（幂等自愈，不重复创建）
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
                    if (tries < MAX_TRIES - 1) kotlinx.coroutines.delay(150)
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
                    creatingTunnel = false
                    creatingService = null
                    return@launch
                }
                // 回查真实 proxyId（createProxy 成功 data 为空，需按名字查）
                // 次数加长、间隔加大：服务端列表同步有延迟，避免「已创建但查不到」误报失败
                repeat(8) { attempt ->
                    proxyList = withContext(Dispatchers.IO) {
                        mefrpApi.getProxyList(mefrpStore.accessToken).getOrNull() ?: emptyList()
                    }
                    val match = proxyList.filter { it.proxyName == tunnelName }
                        .let { matches -> matches.firstOrNull { it.isActive } ?: matches.firstOrNull() }
                    if (match != null) { createdId = match.proxyId; return@repeat }
                    if (attempt < 7) kotlinx.coroutines.delay(1000)
                }
            }

            if (createdId <= 0) {
                tunnelCreateError = "未能获取隧道真实代理 ID，请重试或稍后刷新隧道列表"
                snackbarHostState.showSnackbar("创建失败: 未能获取隧道真实代理 ID")
                Log.e("MefrpTunnel", "refusing to save/start mefrp tunnel with invalid proxyId=$createdId")
                creatingTunnel = false
                creatingService = null
                return@launch
            }

            // 节点真实连接地址（公网访问地址的 host，如 ip.lhdyx.top）：
            // node/list 的 hostname 对普通用户恒为空，必须从 proxy/list 的
            // data.nodes 按 nodeId 取；拿不到则留空，ShareService 启动前还会兜底回填。
            val nodeHost = withContext(Dispatchers.IO) {
                mefrpApi.getProxyListWithNodes(mefrpStore.accessToken).getOrNull()
                    ?.nodes?.firstOrNull { it.nodeId == node.nodeId }?.hostname
            }.orEmpty()

            val tunnel = TunnelConfig(
                enabled = true,
                proxyId = createdId,
                nodeId = node.nodeId,
                nodeHost = nodeHost,
                nodePort = node.servicePort ?: 7000,
                remotePort = attemptPort,
                localPort = port,
                type = "tcp",
                name = tunnelName,
                provider = "mefrp"
            )
            when (service) {
                "http" -> httpTunnel = tunnel
                "ftp" -> ftpTunnel = tunnel
                "smb" -> smbTunnel = tunnel
            }
            configStore.saveConfig(buildUpdatedConfig())
            context.startService(
                Intent(context, ShareService::class.java).apply {
                    action = ShareService.ACTION_START_SHARE
                    putExtra(ShareService.EXTRA_SHARE_ID, share.id)
                }
            )
            showNodePicker = null
            selectedMefrpNodeId = null
            tunnelCreateError = null
            snackbarHostState.showSnackbar("隧道创建成功! 代理 ID: $createdId (端口 $attemptPort)")
            creatingTunnel = false
            creatingService = null
        }
    }

    /** 拉取 mefrp 节点列表（失败写入 mefrpNodesError，不静默）。 */
    fun refreshMefrpNodes() {
        if (mefrpStore.accessToken.isBlank()) {
            mefrpNodesError = "mefrp 未验证：请先在「Frp 设置」页验证访问令牌"
            return
        }
        mefrpNodesError = null
        scope.launch {
            val nodes = withContext(Dispatchers.IO) { mefrpApi.getNodeList(mefrpStore.accessToken).getOrNull() }
            if (nodes != null) {
                mefrpNodes.clear()
                mefrpNodes.addAll(nodes)
                mefrpStore.saveNodes(nodes)
                mefrpNodesError = null
            } else {
                mefrpNodesError = "节点拉取失败：请检查访问令牌/网络后重试"
            }
        }
    }

    fun createTunnel(service: String, port: Int, remotePort: Int? = null) {
        // 服务商分流：mefrp 走独立的创建流程（服务商全局在「Frp 设置」页选择）
        if (openFrpStore.selectedProvider == "mefrp") {
            createMefrpTunnel(service, port, remotePort)
            return
        }
        if (!hasOpenFrpAccount) {
            scope.launch { snackbarHostState.showSnackbar("请先在「Frp 设置」页登录 OpenFrp") }
            return
        }
        if (selectedNodeId == null) {
            showNodePicker = service
            return
        }

        val node = openFrpNodes.find { it.id == selectedNodeId }
        if (node == null) {
            scope.launch { snackbarHostState.showSnackbar("请选择一个节点") }
            return
        }

        // Defensive: a real creation always carries a remote port (collected by the picker).
        if (remotePort == null) {
            showNodePicker = service
            return
        }

        creatingTunnel = true
        creatingService = service

            // OpenFrp 隧道名仅允许小写字母/数字/下划线（不含大写与连字符），原 LunaShare-HTTP 被服务端拒。
            // 末尾追加 4 位随机数字，避免重名（重名会导致自愈误用旧隧道，或被服务端拒绝创建）。
            val tunnelName = "lunashare_" + service.lowercase().replace(Regex("[^a-z0-9_]"), "_") +
                    String.format("%04d", java.util.Random().nextInt(9999) + 1)

        scope.launch {
            tunnelCreateError = null
            val creds = OpenFrpApiClient.Credentials(
                openFrpAccount.authorization,
                openFrpAccount.session
            )

            // Ensure we have the frpc login token (getUserInfo.token). THIS is what frpc
            // needs to connect to frps — NOT the API authorization header. If it's still
            // blank (LaunchedEffect not finished, or getUserInfo failed on open), fetch it
            // now so we never fall back to the wrong token and reproduce "token doesn't match".
            if (frpToken.isBlank()) {
                val info = withContext(Dispatchers.IO) { api.getUserInfo(creds).getOrNull() }
                info?.token?.takeIf { it.isNotBlank() }?.let {
                    frpToken = it
                    openFrpStore.saveAccount(openFrpAccount.copy(frpToken = it))
                }
            }
            if (frpToken.isBlank()) {
                tunnelCreateError = "无法获取 frpc 登录令牌，请重新在 OpenFrp 设置页登录"
                snackbarHostState.showSnackbar("无法获取 frpc 登录令牌，请重新登录 OpenFrp")
                creatingTunnel = false
                creatingService = null
                return@launch
            }

            // Idempotent self-heal: if a tunnel with this exact name already exists
            // server-side (e.g. a previous run saved proxyId=0 and never started), adopt
            // its REAL id instead of calling newProxy again (which would fail with
            // "already exists"). This lets the user just re-tap "创建" to repair a
            // broken tunnel without manually deleting it first.
            var attemptPort = remotePort!!
            var created: OpenFrpProxy? = null
            var lastErrMsg = "未知错误"

            val existing = withContext(Dispatchers.IO) { api.getUserProxies(creds) }
                .getOrNull()
                ?.filter { it.name == tunnelName || it.proxyName == tunnelName }
                ?.let { matches -> matches.firstOrNull { it.isActive } ?: matches.firstOrNull() }

            if (existing != null) {
                Log.d("OpenFrpTunnel", "existing tunnel found by name=$tunnelName id=${existing.proxyIdValue}, adopting it (self-heal)")
                created = existing
            } else {
                // Auto-retry on "remote port already in use": try the next port instead of
                // failing outright, so the user never has to guess a free port by hand.
                val MAX_TRIES = 15
                repeat(MAX_TRIES) { tries ->
                    val r = withContext(Dispatchers.IO) {
                        api.newProxy(
                            creds,
                            NewProxyRequest(
                                node_id = node.id,
                                type = "tcp",
                                local_addr = "127.0.0.1",
                                local_port = port.toString(),
                                name = tunnelName,
                                remote_port = attemptPort
                            )
                        )
                    }
                    if (r.isSuccess) { created = r.getOrNull(); return@repeat }
                    lastErrMsg = r.exceptionOrNull()?.message ?: "未知错误"
                    // 同名/已存在 → 服务端已有该隧道，视为已存在走回查复用（幂等自愈）
                    val existsErr = lastErrMsg.contains("同名") || lastErrMsg.contains("已存在") ||
                            lastErrMsg.contains("重复") || lastErrMsg.contains("already")
                    if (existsErr) { return@repeat }
                    // Only retry when the failure is specifically a port-occupancy issue.
                    val occupied = lastErrMsg.contains("被占用") || lastErrMsg.contains("不可用") ||
                            lastErrMsg.contains("端口")
                    if (!occupied) return@repeat
                    val range = node.allowPortRange
                    if (range != null && attemptPort >= range.last) return@repeat
                    attemptPort += 1
                    remotePortInput = attemptPort.toString()
                    tunnelCreateError = "端口 ${attemptPort - 1} 已被占用，已自动尝试 $attemptPort"
                    if (tries < MAX_TRIES - 1) kotlinx.coroutines.delay(150)
                }
            }

            val result: Result<OpenFrpProxy> = if (created != null) {
                Result.success(created as OpenFrpProxy)
            } else {
                Result.failure(Exception(lastErrMsg))
            }

            result.onSuccess { proxy ->
                // newProxy may return the real id directly, or (data:null) we must
                // look it up by name. Retry a few times for server-side sync lag.
                var fullProxy: OpenFrpProxy? = null
                repeat(4) { attempt ->
                    val proxiesResult = withContext(Dispatchers.IO) { api.getUserProxies(creds) }
                    val allProxies = proxiesResult.getOrNull() ?: emptyList()
                    Log.d("OpenFrpTunnel", "lookup attempt=$attempt name=$tunnelName, got ${allProxies.size} proxies: " +
                            allProxies.joinToString { "id=${it.proxyIdValue},name=${it.name},proxyName=${it.proxyName}" })
                    fullProxy = allProxies.filter { it.name == tunnelName || it.proxyName == tunnelName }
                        .let { matches -> matches.firstOrNull { it.isActive } ?: matches.firstOrNull() }
                    if (fullProxy != null) return@repeat
                    if (attempt < 3) kotlinx.coroutines.delay(700)
                }
                Log.d("OpenFrpTunnel", "matched fullProxy=${fullProxy?.proxyIdValue}, newProxy proxyIdValue=${proxy.proxyIdValue}")
                val realProxyId = fullProxy?.proxyIdValue ?: proxy.proxyIdValue
                val realRemotePort = fullProxy?.remotePortValue ?: attemptPort

                // Guard: a proxy id of 0 means the real id could not be resolved
                // (e.g. server-side sync lag or API field mismatch). Never persist
                // or launch a 0-id tunnel — frpc -p 0 would exit immediately with
                // "未能获取到任何可用配置". Surface the error instead.
                if (realProxyId <= 0) {
                    tunnelCreateError = "未能获取隧道真实代理 ID (proxyId=$realProxyId)，请重试或稍后刷新隧道列表"
                    snackbarHostState.showSnackbar("创建失败: 未能获取隧道真实代理 ID，请重试")
                    Log.e("OpenFrpTunnel", "refusing to save/start tunnel with invalid proxyId=$realProxyId (name=$tunnelName)")
                    creatingTunnel = false
                    creatingService = null
                    return@launch
                }

                val tunnel = TunnelConfig(
                    enabled = true,
                    proxyId = realProxyId,
                    nodeId = node.id,
                    nodeHost = node.serverHost.ifBlank { "待获取" },
                    nodePort = node.portValue ?: 7000,
                    remotePort = realRemotePort,
                    localPort = port,
                    type = "tcp",
                    name = tunnelName
                )

                when (service) {
                    "http" -> httpTunnel = tunnel
                    "ftp" -> ftpTunnel = tunnel
                    "smb" -> smbTunnel = tunnel
                }

                // Persist immediately + start frpc so the tunnel actually comes online.
                configStore.saveConfig(buildUpdatedConfig())
                context.startService(
                    Intent(context, ShareService::class.java).apply {
                        action = ShareService.ACTION_START_SHARE
                        putExtra(ShareService.EXTRA_SHARE_ID, share.id)
                    }
                )

                showNodePicker = null
                selectedNodeId = null
                tunnelCreateError = null
                snackbarHostState.showSnackbar(
                    "隧道创建成功! 代理 ID: $realProxyId (端口 $realRemotePort)"
                )
            }.onFailure { err ->
                // KEEP the picker open so the user can fix the port and retry
                // immediately — do NOT close the dialog on failure.
                tunnelCreateError = "创建失败: ${err.message}"
                snackbarHostState.showSnackbar("创建失败: ${err.message}")
            }

            creatingTunnel = false
            creatingService = null
        }
    }

    fun deleteTunnel(service: String) {
        val tunnel = when (service) {
            "http" -> httpTunnel
            "ftp" -> ftpTunnel
            "smb" -> smbTunnel
            else -> null
        } ?: return

        // mefrp 隧道删除走 mefrp API（按 provider 分流）
        if (tunnel.provider == "mefrp") {
            scope.launch {
                if (mefrpStore.accessToken.isBlank()) {
                    snackbarHostState.showSnackbar("mefrp 访问令牌为空，无法删除隧道")
                    return@launch
                }
                val realId = withContext(Dispatchers.IO) {
                    val list = runCatching { mefrpApi.getProxyList(mefrpStore.accessToken).getOrNull() }
                        .getOrNull() ?: emptyList<MefrpProxy>()
                    list.find { it.proxyName == tunnel.name }?.proxyId
                        ?: tunnel.proxyId.takeIf { it != 0 }
                }
                if (realId == null || realId == 0) {
                    snackbarHostState.showSnackbar("无法解析隧道 ID，删除失败")
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    mefrpApi.deleteProxy(mefrpStore.accessToken, realId)
                }
                result.onSuccess {
                    when (service) {
                        "http" -> httpTunnel = null
                        "ftp" -> ftpTunnel = null
                        "smb" -> smbTunnel = null
                    }
                    configStore.saveConfig(buildUpdatedConfig())
                    snackbarHostState.showSnackbar("隧道已删除")
                }.onFailure { err ->
                    snackbarHostState.showSnackbar("删除隧道失败: ${err.message}")
                }
            }
            return
        }

        scope.launch {
            val creds = OpenFrpApiClient.Credentials(
                openFrpAccount.authorization,
                openFrpAccount.session
            )
            // The persisted proxyId may be 0/wrong, so always resolve the real id
            // by name from the server before deleting.
            val realId = withContext(Dispatchers.IO) {
                val list = runCatching { api.getUserProxies(creds).getOrNull() }
                    .getOrNull() ?: emptyList<OpenFrpProxy>()
                list.find { it.name == tunnel.name || it.proxyName == tunnel.name }?.proxyIdValue
                    ?: tunnel.proxyId.takeIf { it != 0 }
            }
            if (realId == null || realId == 0) {
                snackbarHostState.showSnackbar("无法解析隧道 ID，删除失败")
                return@launch
            }
            Log.d("OpenFrpTunnel", "delete resolving name=${tunnel.name} -> realId=$realId (stored=${tunnel.proxyId})")
            val result = withContext(Dispatchers.IO) {
                api.removeProxy(creds, realId)
            }
            result.onSuccess {
                when (service) {
                    "http" -> httpTunnel = null
                    "ftp" -> ftpTunnel = null
                    "smb" -> smbTunnel = null
                }
                configStore.saveConfig(buildUpdatedConfig())
                snackbarHostState.showSnackbar("隧道已删除")
            }.onFailure { err ->
                snackbarHostState.showSnackbar("删除隧道失败: ${err.message}")
            }
        }
    }

    fun toggleTunnel(service: String, enabled: Boolean) {
        when (service) {
            "http" -> httpTunnel = httpTunnel?.copy(enabled = enabled)
            "ftp" -> ftpTunnel = ftpTunnel?.copy(enabled = enabled)
            "smb" -> smbTunnel = smbTunnel?.copy(enabled = enabled)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("编辑共享") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                // ── Share name ──
                Text("共享名称", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, placeholder = { Text("例如: 我的文档") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // ── Folder section ──
                Text("文件夹", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = localDir, onValueChange = { localDir = it },
                    label = { Text("共享文件夹路径") },
                    placeholder = { Text("选择或输入要共享的文件夹路径") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { try { dirPickerLauncher.launch(null) } catch (_: Exception) {} },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择文件夹", style = MaterialTheme.typography.labelMedium)
                    }
                    if (localDir.isNotBlank()) {
                        TextButton(onClick = { localDir = "" }) {
                            Text("清除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider()

                // ── Auth section ──
                Text("访问认证", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("设置用户名和密码以限制访问。留空则不启用认证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("用户名") }, placeholder = { Text("留空则不启用") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("密码") }, placeholder = { Text("留空则不启用") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        visualTransformation = PasswordVisualTransformation()
                    )
                }

                HorizontalDivider()

                // ── Service selection with per-service tunnels ──
                Text("启用的服务", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("选择要启用的文件共享协议，并设置对应的端口。每个服务可独立创建 OpenFrp 隧道映射到公网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // HTTP/WebDAV
                ServiceCardWithTunnel(
                    enabled = httpEnabled,
                    onEnabledChange = { httpEnabled = it },
                    icon = { Icon(Icons.Default.Language, null, Modifier.size(24.dp)) },
                    title = "HTTP/WebDAV",
                    description = "通过浏览器或 WebDAV 客户端访问文件",
                    portValue = httpPort,
                    onPortChange = { httpPort = it.filter { c -> c.isDigit() } },
                    tunnel = httpTunnel,
                    onCreateTunnel = {
                        createTunnel("http", httpPort.toIntOrNull() ?: 8080)
                    },
                    onToggleTunnel = { enabled -> toggleTunnel("http", enabled) },
                    onDeleteTunnel = { deleteTunnel("http") },
                    hasAccount = if (openFrpStore.selectedProvider == "mefrp") mefrpStore.accessToken.isNotBlank() else hasOpenFrpAccount,
                    currentProvider = openFrpStore.selectedProvider,
                    mefrpNodeNames = mefrpNodeNames
                )

                // FTP
                ServiceCardWithTunnel(
                    enabled = ftpEnabled,
                    onEnabledChange = { ftpEnabled = it },
                    icon = { Icon(Icons.Default.Folder, null, Modifier.size(24.dp)) },
                    title = "FTP",
                    description = "通过 FTP 客户端访问文件",
                    portValue = ftpPort,
                    onPortChange = { ftpPort = it.filter { c -> c.isDigit() } },
                    tunnel = ftpTunnel,
                    onCreateTunnel = {
                        createTunnel("ftp", ftpPort.toIntOrNull() ?: 8021)
                    },
                    onToggleTunnel = { enabled -> toggleTunnel("ftp", enabled) },
                    onDeleteTunnel = { deleteTunnel("ftp") },
                    hasAccount = if (openFrpStore.selectedProvider == "mefrp") mefrpStore.accessToken.isNotBlank() else hasOpenFrpAccount,
                    currentProvider = openFrpStore.selectedProvider,
                    mefrpNodeNames = mefrpNodeNames
                )

                // SMB
                ServiceCardWithTunnel(
                    enabled = smbEnabled,
                    onEnabledChange = { smbEnabled = it },
                    icon = { Icon(Icons.Default.Computer, null, Modifier.size(24.dp)) },
                    title = "SMB",
                    description = "通过 Windows/macOS 网络共享访问",
                    portValue = smbPort,
                    onPortChange = { smbPort = it.filter { c -> c.isDigit() } },
                    tunnel = smbTunnel,
                    onCreateTunnel = {
                        createTunnel("smb", smbPort.toIntOrNull() ?: 8445)
                    },
                    onToggleTunnel = { enabled -> toggleTunnel("smb", enabled) },
                    onDeleteTunnel = { deleteTunnel("smb") },
                    hasAccount = if (openFrpStore.selectedProvider == "mefrp") mefrpStore.accessToken.isNotBlank() else hasOpenFrpAccount,
                    currentProvider = openFrpStore.selectedProvider,
                    mefrpNodeNames = mefrpNodeNames
                )

                // 文件加密口令（电脑端脚本加密上传用）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("文件加密口令", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "电脑端命令行脚本上传文件时用于自动加密，手机端自动解密还原。与连接账号密码相互独立。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = encPassword,
                            onValueChange = { encPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("留空则不启用加密上传") },
                            visualTransformation = if (showEncPassword) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showEncPassword = !showEncPassword }) {
                                    Icon(
                                        imageVector = if (showEncPassword) Icons.Default.VisibilityOff
                                                      else Icons.Default.Visibility,
                                        contentDescription = if (showEncPassword) "隐藏口令" else "显示口令"
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            val rand = ByteArray(24)
                            SecureRandom().nextBytes(rand)
                            val pw = android.util.Base64.encodeToString(
                                rand,
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                            )
                            encPassword = pw
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("LunaShare 加密口令", pw))
                            Toast.makeText(context, "已生成并复制加密口令，请粘贴到电脑端脚本配置", Toast.LENGTH_LONG).show()
                        }) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("生成加密口令并复制")
                        }
                    }
                }

                if (if (openFrpStore.selectedProvider == "mefrp") mefrpStore.accessToken.isBlank() else !hasOpenFrpAccount) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info, null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (openFrpStore.selectedProvider == "mefrp") {
                                    "创建隧道需要先在「Frp 设置」页粘贴并验证 mefrp 访问令牌。"
                                } else {
                                    "创建隧道需要先在「Frp 设置」页登录 OpenFrp。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                HorizontalDivider()

                
                HorizontalDivider()

                // ── Auto-start ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("开启 App 启动共享", style = MaterialTheme.typography.titleMedium)
                        Text("打开 App 时自动启动此共享服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoStart, onCheckedChange = { autoStart = it })
                }

                Spacer(Modifier.height(8.dp))

                // ── Save button ──
                Button(
                    onClick = {
                        onConfigChanged(buildUpdatedConfig())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("保存配置")
                }

                Spacer(Modifier.height(32.dp))
            }
        }

    // ── Node picker dialog ──
    if (showNodePicker != null) {
        val service = showNodePicker
        val localPort = when (service) {
            "http" -> httpPort.toIntOrNull() ?: 8080
            "ftp" -> ftpPort.toIntOrNull() ?: 8021
            "smb" -> smbPort.toIntOrNull() ?: 8445
            else -> 8080
        }
        AlertDialog(
            onDismissRequest = {
                if (!creatingTunnel) {
                    showNodePicker = null
                    selectedNodeId = null
                }
            },
            title = {
                Text(
                    if (openFrpStore.selectedProvider == "mefrp") "选择 mefrp 节点（幻缘映射）" else "选择 OpenFrp 节点"
                )
            },
            text = {
                Column {
                    Text(
                        if (openFrpStore.selectedProvider == "mefrp") {
                            "选择一个 mefrp 节点创建 TCP 隧道 (本地端口 $localPort)"
                        } else {
                            "选择一个 OpenFrp 节点创建 TCP 隧道 (本地端口 $localPort)"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))

                    // 节点卡片列表：固定高度内滚动，远程端口输入框保持可见不被滚走
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (openFrpStore.selectedProvider == "mefrp") {
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
                        } else {
                            val sortedNodes = openFrpNodes.sortedBy { node ->
                                // 大类：可用(0) → 受限(1) → 不可用(2)
                                val availRank = when {
                                    node.availability == NodeAvailability.UNAVAILABLE -> 2
                                    node.availability == NodeAvailability.RESTRICTED -> 1
                                    else -> 0
                                }
                                // 同档内：普通用户可用(normal)排最前
                                val normalFlag =
                                    if (node.group.isNullOrBlank() || node.group!!.lowercase().contains("normal")) 0 else 1
                                availRank * 10 + normalFlag
                            }
                            sortedNodes.forEach { node ->
                                NodeStatusCard(
                                    node = node,
                                    selected = node.id == selectedNodeId,
                                    onClick = {
                                        selectedNodeId = node.id
                                        // Pre-fill a valid remote port for the chosen node
                                        remotePortInput = node.suggestedRemotePort(localPort).toString()
                                    },
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // 远程端口（固定显示，不随节点列表滚动）
                    val isMefrpPicker = openFrpStore.selectedProvider == "mefrp"
                    val selectedNode = if (isMefrpPicker) null else openFrpNodes.find { it.id == selectedNodeId }
                    val selectedMefrpNode =
                        if (isMefrpPicker) mefrpNodes.find { it.nodeId == selectedMefrpNodeId } else null
                    OutlinedTextField(
                        value = remotePortInput,
                        onValueChange = { remotePortInput = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("远程端口 (remote_port)") },
                        placeholder = { Text("例如 12345") },
                        singleLine = true,
                        isError = remotePortInput.isNotBlank() && (
                            (selectedNode != null &&
                                (selectedNode!!.allowPortRange?.let { r ->
                                    remotePortInput.toIntOrNull()?.let { p -> p !in r } ?: false
                                } ?: false)) ||
                            (selectedMefrpNode != null &&
                                selectedMefrpNode!!.portRanges.let { ranges ->
                                    ranges.isNotEmpty() &&
                                        (remotePortInput.toIntOrNull()?.let { p -> ranges.none { p in it } } ?: false)
                                })
                            ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        when {
                            isMefrpPicker && selectedMefrpNode != null ->
                                "端口范围: " +
                                    (selectedMefrpNode.portRanges.takeIf { it.isNotEmpty() }
                                        ?.joinToString(",") { "${it.first}-${it.last}" } ?: "不限")
                            isMefrpPicker -> "请先选择 mefrp 节点"
                            else -> selectedNode?.allowPortHint ?: "请先选择节点"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Inline error (creation failed but the dialog stays open)
                    tunnelCreateError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val svc = service ?: "http"
                        val rp = remotePortInput.toIntOrNull()
                        if (openFrpStore.selectedProvider == "mefrp") {
                            val mNode = mefrpNodes.find { it.nodeId == selectedMefrpNodeId }
                            val mRanges = mNode?.portRanges ?: emptyList()
                            when {
                                selectedMefrpNodeId == null -> {
                                    scope.launch { snackbarHostState.showSnackbar("请选择一个 mefrp 节点") }
                                }
                                rp == null || rp !in 1..65535 -> {
                                    scope.launch { snackbarHostState.showSnackbar("请输入有效的远程端口 (1-65535)") }
                                }
                                mRanges.isNotEmpty() && mRanges.none { rp in it } -> {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "远程端口超出节点范围 ${mRanges.first().first}-${mRanges.first().last}"
                                        )
                                    }
                                }
                                else -> {
                                    showNodePicker = null
                                    createMefrpTunnel(svc, localPort, rp)
                                }
                            }
                        } else {
                            val node = openFrpNodes.find { it.id == selectedNodeId }
                            val range = node?.allowPortRange
                            when {
                                selectedNodeId == null -> {
                                    scope.launch { snackbarHostState.showSnackbar("请选择一个节点") }
                                }
                                rp == null || rp !in 1..65535 -> {
                                    scope.launch { snackbarHostState.showSnackbar("请输入有效的远程端口 (1-65535)") }
                                }
                                range != null && rp !in range -> {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "远程端口超出节点范围 ${range.first}-${range.last}"
                                        )
                                    }
                                }
                                else -> {
                                    showNodePicker = null
                                    createTunnel(svc, localPort, rp)
                                }
                            }
                        }
                    },
                    enabled = if (openFrpStore.selectedProvider == "mefrp")
                        (selectedMefrpNodeId != null && !creatingTunnel)
                    else
                        (selectedNodeId != null && !creatingTunnel)
                ) {
                    if (creatingTunnel && creatingService == showNodePicker) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("创建中...")
                    } else {
                        Text("创建隧道")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNodePicker = null
                        selectedNodeId = null
                    },
                    enabled = !creatingTunnel
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ServiceCardWithTunnel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    portValue: String,
    onPortChange: (String) -> Unit,
    tunnel: TunnelConfig?,
    onCreateTunnel: () -> Unit,
    onToggleTunnel: (Boolean) -> Unit,
    onDeleteTunnel: () -> Unit,
    hasAccount: Boolean,
    currentProvider: String = "openfrp",
    /** mefrp 节点「nodeId -> 节点名」映射：节点字段兜底显示用（nodeHost 为空时）。 */
    mefrpNodeNames: Map<Int, String> = emptyMap()
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    icon()
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (enabled) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = portValue,
                    onValueChange = onPortChange,
                    label = { Text("端口") },
                    placeholder = { Text("8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(140.dp)
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                if (tunnel != null) {
                    // Tunnel exists - show info card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Route, null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("公网隧道",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        if (tunnel.enabled) {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                Icons.Default.CheckCircle, null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                    Text(
                                        "${if (tunnel.provider == "mefrp") "mefrp · " else "OpenFrp · "}代理 ID: ${tunnel.proxyId} · 节点: ${
                                            if (tunnel.provider == "mefrp") {
                                                tunnel.nodeHost.ifBlank {
                                                    mefrpNodeNames[tunnel.nodeId] ?: "节点 #${tunnel.nodeId}"
                                                }
                                            } else {
                                                tunnel.nodeHost.ifBlank { "未知" }
                                            }
                                        }${
                                            tunnel.remotePort?.let { " · 远端端口: $it" } ?: ""
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "启用穿透",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Switch(
                                        checked = tunnel.enabled,
                                        onCheckedChange = { onToggleTunnel(it) },
                                        // 另一服务商的已开隧道：灰色禁用，提示切回对应服务商管理
                                        enabled = currentProvider == tunnel.provider
                                    )
                                    if (currentProvider != tunnel.provider) {
                                        Text(
                                            "这条" + (if (tunnel.provider == "mefrp") "mefrp" else "OpenFrp") +
                                                "隧道属于另一服务商（当前是" +
                                                (if (currentProvider == "mefrp") "mefrp" else "OpenFrp") +
                                                "）：切回对应服务商后可管理；隧道仍按自身服务商继续运行。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                                TextButton(onClick = onDeleteTunnel) {
                                    Text("删除隧道",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } else {
                    // No tunnel - show create button
                    OutlinedButton(
                        onClick = onCreateTunnel,
                        enabled = hasAccount,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (hasAccount) "创建公网隧道" else "需要先登录 OpenFrp")
                    }
                }
            }
        }
    }
}

// ── Helper: convert SAF tree URI to file path ──

private fun uriToPath(context: android.content.Context, uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":")
        val type = parts.getOrNull(0)
        val relPath = parts.getOrNull(1) ?: ""
        if (type.equals("primary", ignoreCase = true)) {
            val base = android.os.Environment.getExternalStorageDirectory().absolutePath
            if (relPath.isBlank()) base else "$base/$relPath"
        } else {
            "/storage/$type/$relPath"
        }
    } catch (_: Exception) { null }
}
