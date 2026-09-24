package com.lunashare.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.lunashare.app.frpc.FrpcBinaryManager
import com.lunashare.app.frpc.OpenFrpApiClient
import com.lunashare.app.frpc.OpenFrpConfigStore
import com.lunashare.app.frpc.mefrp.MefrpApiClient
import com.lunashare.app.frpc.mefrp.MefrpConfigStore
import com.lunashare.app.frpc.mefrp.MefrpNode
import com.lunashare.app.frpc.mefrp.MefrpNodeStatus
import com.lunashare.app.frpc.mefrp.sortedForPicker
import com.lunashare.app.frpc.mefrp.MefrpUserInfo
import com.lunashare.app.frpc.model.*
import com.lunashare.app.ui.OpenFrpWebViewLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen for OpenFrp integration.
 *
 * Manages:
 *  - OpenFrp account login (via web view)
 *  - frpc binary status & verification
 *
 * Tunnel creation has moved to the ServiceConfigScreen — each service
 * (HTTP/FTP/SMB) has its own "Create Tunnel" button that automatically
 * maps the correct port.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenFrpScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // System back
    BackHandler { onDismiss() }

    val store = remember { OpenFrpConfigStore(context) }
    val binaryManager = remember { FrpcBinaryManager(context) }

    var account by remember { mutableStateOf(store.loadAccount()) }
    var userInfo by remember { mutableStateOf(account.userInfo) }
    var frpcVersion by remember { mutableStateOf(store.getFrpcVersion()) }
    var mefrpVersion by remember { mutableStateOf<String?>(null) }
    var verifyingFrpc by remember { mutableStateOf(false) }
    var frpcIncompatible by remember { mutableStateOf(false) }
    var showWebViewLogin by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Node status list (availability from OpenFrp getNodeList)
    var nodes by remember { mutableStateOf<List<OpenFrpNode>>(emptyList()) }
    var loadingNodes by remember { mutableStateOf(false) }
    var nodesError by remember { mutableStateOf<String?>(null) }

    // 全局穿透服务商（openfrp / mefrp），与编辑共享联动
    var selectedProvider by remember { mutableStateOf(store.selectedProvider) }

    // mefrp 节点状态（mefrp 模式下的节点列表）

    // Verify frpc binaries on first load（两个服务商的客户端版本都测一次，区块随服务商显示对应者）
    LaunchedEffect(Unit) {
        val version = withContext(Dispatchers.IO) { binaryManager.getVersion() }
        frpcVersion = version
        store.setFrpcVersion(version)
        frpcIncompatible = withContext(Dispatchers.IO) { binaryManager.isKnownIncompatible() }
        mefrpVersion = withContext(Dispatchers.IO) { binaryManager.getMefrpVersion() }
    }

    fun reloadAccount() {
        account = store.loadAccount()
        userInfo = account.userInfo
    }

    // ── mefrp（幻缘映射）第二服务商：访问令牌验证 + 缓存 ──────
    val mefrpStore = remember { MefrpConfigStore(context) }
    val mefrpApi = remember { MefrpApiClient() }
    var mefrpToken by remember { mutableStateOf(mefrpStore.accessToken) }
    var mefrpInfo by remember { mutableStateOf(mefrpStore.loadUserInfo()) }
    var mefrpChecking by remember { mutableStateOf(false) }
    var mefrpError by remember { mutableStateOf<String?>(null) }
    var mefrpNodes by remember { mutableStateOf<List<MefrpNode>>(mefrpStore.loadNodes()) }
    var loadingMefrpNodes by remember { mutableStateOf(false) }
    var mefrpNodesError by remember { mutableStateOf<String?>(null) }
    // mefrp 节点运行状态（GET /auth/node/status：负载/在线数/版本，官网节点卡片同源）
    var mefrpNodeStatuses by remember { mutableStateOf<Map<Int, MefrpNodeStatus>>(emptyMap()) }

    fun validateMefrpToken() {
        val token = mefrpToken.trim()
        if (token.isBlank()) return
        mefrpChecking = true
        mefrpError = null
        scope.launch {
            val info = withContext(Dispatchers.IO) { mefrpApi.getUserInfo(token).getOrNull() }
            if (info == null) {
                mefrpError = "令牌无效或网络错误，请检查后重试"
                mefrpChecking = false
                return@launch
            }
            mefrpStore.accessToken = token
            mefrpStore.saveUserInfo(info)
            mefrpInfo = info
            // 一并缓存 frpc 启动令牌与节点列表，便于「编辑共享」里直接建隧道
            val t = withContext(Dispatchers.IO) { mefrpApi.getUserFrpToken(token).getOrNull() }
            if (!t.isNullOrBlank()) mefrpStore.frpcToken = t
            val ns = withContext(Dispatchers.IO) { mefrpApi.getNodeList(token).getOrNull() }
            if (ns != null) mefrpStore.saveNodes(ns)
            mefrpChecking = false
            snackbarHostState.showSnackbar("mefrp 验证成功: ${info.displayName}")
        }
    }

    fun loadMefrpNodes() {
        val token = mefrpToken.trim()
        if (token.isBlank()) {
            mefrpNodesError = "mefrp 未验证：请先在账户区验证访问令牌"
            return
        }
        loadingMefrpNodes = true
        mefrpNodesError = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { mefrpApi.getNodeList(token) }
            // 节点状态（负载/在线数）与列表并行拉取，失败不阻塞列表展示
            val st = withContext(Dispatchers.IO) { mefrpApi.getNodeStatus(token).getOrNull() }
            if (st != null) mefrpNodeStatuses = st.associateBy { it.nodeId }
            loadingMefrpNodes = false
            res.onSuccess { list ->
                mefrpNodes = list
                mefrpStore.saveNodes(list)
            }
            res.onFailure { e ->
                mefrpNodesError = "节点拉取失败: ${e.message}"
            }
        }
    }

    fun logout() {
        store.clearAccount()
        account = OpenFrpAccount()
        userInfo = null
    }

    fun verifyFrpc() {
        verifyingFrpc = true
        scope.launch {
            if (selectedProvider == "mefrp") {
                mefrpVersion = withContext(Dispatchers.IO) { binaryManager.getMefrpVersion() }
            } else {
                val v = withContext(Dispatchers.IO) { binaryManager.getVersion() }
                frpcVersion = v
                store.setFrpcVersion(v)
            }
            verifyingFrpc = false
        }
    }

    fun loadNodes() {
        if (!account.isLoggedIn) return
        loadingNodes = true
        nodesError = null
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                OpenFrpApiClient().getNodeList(
                    OpenFrpApiClient.Credentials(account.authorization, account.session)
                )
            }
            loadingNodes = false
            res.onSuccess { nodes = it }
            res.onFailure { nodesError = it.message ?: "加载节点失败" }
        }
    }

    // Auto-load node list once the account is logged in
    LaunchedEffect(account.isLoggedIn) {
        if (account.isLoggedIn) loadNodes()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Frp 设置") },
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
            // ── 穿透服务商单选（全局，编辑共享节点跟着它显示） ──
            Text("穿透服务商", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedProvider == "openfrp",
                    onClick = { selectedProvider = "openfrp"; store.selectedProvider = "openfrp" },
                    label = { Text("OpenFrp") }
                )
                FilterChip(
                    selected = selectedProvider == "mefrp",
                    onClick = { selectedProvider = "mefrp"; store.selectedProvider = "mefrp" },
                    label = { Text("mefrp 幻缘映射") }
                )
            }
            Text(
                "编辑共享里的节点与隧道都会按此服务商显示。切换后，已有隧道仍按各自服务商继续运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            // ── Login / Account ───────────────────────────────────
            Text("账户", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            if (selectedProvider == "mefrp") {
                // ── mefrp 账户（令牌 + 验证 + 账号信息） ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "mefrp 幻缘映射账户",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "在 mefrp 网站（mefrp.com）个人中心复制「访问令牌」，粘贴验证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mefrpToken,
                            onValueChange = { mefrpToken = it },
                            label = { Text("mefrp 访问令牌") },
                            placeholder = { Text("粘贴「用户 Token」") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        mefrpError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        mefrpInfo?.let { info ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "账号: ${info.displayName}" +
                                    (info.remainingProxies?.let { " · 剩余隧道: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { validateMefrpToken() },
                                enabled = !mefrpChecking && mefrpToken.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (mefrpChecking) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("验证中...")
                                } else {
                                    Text("验证令牌")
                                }
                            }
                            if (mefrpToken.isNotBlank() || mefrpInfo != null) {
                                OutlinedButton(onClick = {
                                    mefrpStore.clear()
                                    mefrpToken = ""
                                    mefrpInfo = null
                                    mefrpError = null
                                }) {
                                    Text("清除")
                                }
                            }
                        }
                    }
                }
            } else if (account.isLoggedIn) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.AccountCircle, null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        userInfo?.nickname ?: account.authorization.take(12) + "...",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "OpenFrp 用户 ID: ${userInfo?.id ?: "-"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            IconButton(onClick = { logout() }) {
                                Icon(Icons.Default.Logout, "退出登录")
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("尚未登录 OpenFrp", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "登录后可在共享配置页一键创建公网隧道。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showWebViewLogin = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("网页登录 (推荐)")
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Frpc 客户端（按当前服务商显示对应二进制状态） ────────
            val isMefrpClient = selectedProvider == "mefrp"
            val clientVersion = if (isMefrpClient) mefrpVersion else frpcVersion
            Text(
                (if (isMefrpClient) "mefrpc 客户端（幻缘映射）" else "Frpc 客户端（OpenFrp）"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (clientVersion != null)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (clientVersion != null) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (clientVersion != null)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (clientVersion != null)
                                    (if (isMefrpClient) "mefrpc 已就绪" else "frpc 已就绪")
                                else
                                    (if (isMefrpClient) "mefrpc 未检测到" else "frpc 未检测到"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (clientVersion != null) "版本: $clientVersion" else "需要重新安装应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(
                        onClick = { verifyFrpc() },
                        enabled = !verifyingFrpc
                    ) {
                        if (verifyingFrpc) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("验证中...")
                        } else {
                            Text("验证")
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Known incompatibility advisory ───────────────────
            if (frpcIncompatible) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "已知兼容性问题",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "检测到内置 frpc 为已知数据面不兼容的旧构建（SHA256 命中旧版）：隧道会显示「启动成功」，但公网地址实际无法访问（frpc 不会收到远程连接）。请重新安装已内置 OpenFrp 官方最新定制版（OF_0.68.0_37f78258_260326）的 APK。局域网访问不受影响。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Node status（按所选服务商） ──────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(
                    "节点状态",
                    if (selectedProvider == "mefrp") {
                        "mefrp 节点列表（绿=在线，负载/在线数为官网节点卡片同源数据）。仅反映管理状态，创建隧道后仍需实测数据面是否真能通。"
                    } else {
                        "各节点可用性（绿=可用 / 黄=受限 / 红=不可用）。仅反映管理状态，创建隧道后仍需实测数据面是否真能通。"
                    }
                )
                if (selectedProvider == "mefrp") {
                    IconButton(onClick = { loadMefrpNodes() }, enabled = !loadingMefrpNodes) {
                        if (loadingMefrpNodes) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新节点列表")
                        }
                    }
                } else if (account.isLoggedIn) {
                    IconButton(onClick = { loadNodes() }, enabled = !loadingNodes) {
                        if (loadingNodes) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新节点列表")
                        }
                    }
                }
            }

            if (selectedProvider == "mefrp") {
                when {
                    loadingMefrpNodes -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(strokeWidth = 2.dp) }
                    mefrpNodesError != null -> Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning, null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                mefrpNodesError!!, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    mefrpNodes.isEmpty() -> Text(
                        "暂无 mefrp 节点，点击右上角刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 在线优先 → 非 VIP 专属优先 → 负载低优先
                        mefrpNodes.sortedForPicker(mefrpNodeStatuses).forEach { node ->
                            val status = mefrpNodeStatuses[node.nodeId]
                            val load = status?.loadPercent
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (node.isOnline)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier.size(10.dp).clip(CircleShape).background(
                                                if (node.isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
                                            )
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(node.displayName, style = MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.width(6.dp))
                                                Badge { Text("mefrp", style = MaterialTheme.typography.labelSmall) }
                                                if (node.vip) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Badge(
                                                        containerColor = Color(0xFFD4A017),
                                                        contentColor = Color(0xFF212121)
                                                    ) { Text("VIP", style = MaterialTheme.typography.labelSmall) }
                                                }
                                            }
                                            Text(
                                                buildString {
                                                    node.bandwidth?.takeIf { it.isNotBlank() }?.let { append("$it ") }
                                                    append("端口 ")
                                                    append(node.portRanges.joinToString(",") { "${it.first}-${it.last}" }
                                                        .ifBlank { "不限" })
                                                    node.allowType?.takeIf { it.isNotBlank() }?.let { append(" · ${it.uppercase()}") }
                                                    if (status != null && status.isOnline) {
                                                        append(" · 客户端 ${status.onlineClient} · 隧道 ${status.onlineProxy}")
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            // 节点描述（官网卡片说明文字，如「衢州电信 复活的#9」）
                                            node.description?.takeIf { it.isNotBlank() }?.let {
                                                Text(
                                                    it, style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        // 负载 chip（官网「负载 79%」同款）+ 在线/离线
                                        Column(horizontalAlignment = Alignment.End) {
                                            if (load != null) {
                                                val (bg, fg) = when (status!!.loadLevel) {
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
                                            }
                                            Text(
                                                if (node.isOnline) "在线" else "离线",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (node.isOnline) Color(0xFF4CAF50)
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!account.isLoggedIn) {
                Text(
                    "登录后显示节点列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else when {
                loadingNodes -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(strokeWidth = 2.dp) }
                nodesError != null -> Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            nodesError!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                nodes.isEmpty() -> Text(
                    "暂无节点数据，点击右上角刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    nodes.sortedBy { it.availability.ordinal }.forEach { node ->
                        NodeStatusCard(node = node)
                    }
                }
            }

            // ── mefrp（幻缘映射）第二服务商 ─────────────────────────
            Spacer(Modifier.height(32.dp))
        }
    }

    // ── WebView Login dialog ────────────────────────────────────
    if (showWebViewLogin) {
        OpenFrpWebViewLogin(
            onLoginSuccess = { authorization, session ->
                val newAccount = OpenFrpAccount(
                    authorization = authorization,
                    session = session
                )
                store.saveAccount(newAccount)
                reloadAccount()
                showWebViewLogin = false
                scope.launch { snackbarHostState.showSnackbar("登录成功") }
            },
            onDismiss = { showWebViewLogin = false }
        )
    }
}

// ── Section Title helper ──

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NodeStatusCard(
    node: OpenFrpNode,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val avail = node.availability
    val chipContainer = when (avail) {
        NodeAvailability.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
        NodeAvailability.RESTRICTED -> MaterialTheme.colorScheme.tertiaryContainer
        NodeAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
    }
    val chipContent = when (avail) {
        NodeAvailability.AVAILABLE -> MaterialTheme.colorScheme.onPrimaryContainer
        NodeAvailability.RESTRICTED -> MaterialTheme.colorScheme.onTertiaryContainer
        NodeAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.onErrorContainer
    }
    val chipLabel = when (avail) {
        NodeAvailability.AVAILABLE -> "可用"
        NodeAvailability.RESTRICTED -> "受限"
        NodeAvailability.UNAVAILABLE -> "不可用"
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .then(
                if (selected)
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                else Modifier
            )
    ) {
        Column(Modifier.padding(12.dp)) {
            // 标题行：名称 / 地点 + （等级角标 | 状态 chip）
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(node.displayName, style = MaterialTheme.typography.titleSmall)
                    if (node.displayLocation.isNotBlank())
                        Text(
                            node.displayLocation, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    node.tierBadge?.let { badge ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                badge, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Surface(color = chipContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            chipLabel, style = MaterialTheme.typography.labelSmall,
                            color = chipContent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            // 小字：带宽 / 连接地址 / 远端端口范围
            val extra = buildList {
                node.bandwidth?.let { add("带宽 ${it}Mbps") }
                node.serverHost.takeIf { it.isNotBlank() }?.let { add("地址 $it") }
                node.allowPortHint.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (extra.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    extra.joinToString("   ·   "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 受限 / 不可用原因
            if (node.restrictionReasons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠ " + node.restrictionReasons.joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
