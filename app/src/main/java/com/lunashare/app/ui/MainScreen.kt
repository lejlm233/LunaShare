package com.lunashare.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.easytier.NetworkScreen
import com.lunashare.app.model.RemoteConnection
import com.lunashare.app.model.ShareConfig
import com.lunashare.app.service.ShareStateHolder
import com.lunashare.app.ui.theme.StatusRunning
import com.lunashare.app.ui.theme.StatusStopped
import com.lunashare.app.ui.theme.StatusBarOverride
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    configStore: ShareConfigStore,
    onEditShare: (ShareConfig) -> Unit,
    onCreateNew: () -> Unit,
    onStartStopShare: (ShareConfig) -> Unit,
    onDeleteShare: (ShareConfig) -> Unit,
    onSettings: () -> Unit = {},
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    shareListRefresh: Int = 0,
    onOpenAdbTunnel: () -> Unit = {},
    onStartStopAdb: () -> Unit = {},
    onDeleteAdb: () -> Unit = {},
    onEditRemote: (RemoteConnection) -> Unit = {},
    linkFullscreen: Boolean = false,
    onLinkFullscreenChanged: (Boolean) -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    val tabs = listOf("文件", "服务", "组网", "Link")
    val shareStates by ShareStateHolder.shareStates.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 退出 App 二次确认弹窗（电源按钮触发）
    var showExitConfirm by remember { mutableStateOf(false) }

    // 记录进入 Link Tab 前的 Tab：底部「返回」按钮回到这里（0=文件 / 1=服务）
    var lastNonLinkTab by remember { mutableIntStateOf(0) }
    // Link 侧边栏开关状态（底部栏按钮 toggle，LinkScreen 同步上报）
    var linkDrawerOpen by remember { mutableStateOf(false) }
    // Link 是否处于 WebView 页（非主页/添加连接页）：仅 WebView 页显示底部「侧边栏」按钮
    var linkOnWebPage by remember { mutableStateOf(false) }
    // Link 当前是否为 zcode 网页页：仅 zcode 页底部栏提供「缩放复位」按钮
    var linkIsZcode by remember { mutableStateOf(false) }
    // Link 当前是否为 DSH 移动端页面（dsh-bridge 皮肤）：这类页面的顶栏已注入宿主按钮，
    // 不再叠加右侧悬浮胶囊面板（避免两套重复按钮 + 遮挡页面）
    var linkIsDsh by remember { mutableStateOf(false) }
    // Link 当前是否为 zcode 工作台：标题栏右侧已注入宿主按钮，同样不再叠加悬浮面板
    var linkIsZcodeTitlebar by remember { mutableStateOf(false) }
    // 缩放复位信号：点击底部栏「缩放复位」自增，LinkScreen 监听后复位当前 WebView 缩放
    var linkResetZoomSignal by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedTab) {
        if (selectedTab != 3) lastNonLinkTab = selectedTab
    }

    // Count running servers
    val httpRunningCount = shareStates.count { it.value.httpRunning }
    val ftpRunningCount = shareStates.count { it.value.ftpRunning }
    val frpcTunnelCount = shareStates.values.sumOf { s ->
        (if (s.frpcHttpRunning) 1 else 0) + (if (s.frpcFtpRunning) 1 else 0)
    }
    val totalRunning = httpRunningCount + ftpRunningCount

    val statusColor = if (totalRunning > 0) StatusRunning else StatusStopped
    val statusText = buildString {
        if (totalRunning > 0) {
            append("运行中:")
            if (httpRunningCount > 0) append(" HTTP $httpRunningCount")
            if (ftpRunningCount > 0) append(" · FTP $ftpRunningCount")
            if (frpcTunnelCount > 0) append(" · 穿透 $frpcTunnelCount")
        } else {
            append("已停止")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // ── 状态栏自适应：把 Scaffold 的容器色当成"状态栏色带"来用 ──
        // 实测（Android 16 / SER-AN00，用 onGloballyPositioned 量得 LinkScreen 根节点 y=125）
        // 本 App 在窗口层面是 edge-to-edge：状态栏那 125px 不属于内容区，而是 Scaffold 的
        // containerColor 铺出来的；Material3 又把 body 内容整体下移到状态栏之下。
        // 同时 `window.statusBarColor` 在本系统上已是**空操作**（写成品红后读回仍是 0，
        // 截图上那条照旧）——所以想染状态栏，唯一有效的落点就是这里。
        // DSH 页面在 Link Tab 打开时会把页面真实底色写进 StatusBarOverride；无覆盖
        // （非 DSH 页面 / 其它 Tab）时用主题背景，与从前完全一致。
        containerColor = StatusBarOverride.color?.let { Color(it) }
            ?: MaterialTheme.colorScheme.background,
        topBar = {
            // Link Tab（主页或 WebView 页）及 WebView 全屏时隐藏 LunaShare 标题栏与设置按钮
            if (selectedTab != 3 && !linkFullscreen) {
                TopAppBar(
                    title = { Text("LunaShare") },
                    actions = {
                        // 红色电源按钮：退出 App（停止全部共享/隧道/服务并结束进程）
                        IconButton(onClick = { showExitConfirm = true }) {
                            Icon(Icons.Default.PowerSettingsNew,
                                contentDescription = "退出 App",
                                tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column {
                    // 状态条（运行中/已停止）：仅在服务页显示
                    if (selectedTab == 1) {
                        Surface(
                            color = statusColor.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(modifier = Modifier.size(10.dp)) {
                                    drawCircle(statusColor)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = statusColor
                                )
                            }
                        }
                    }

                    if (selectedTab != 3) {
                        NavigationBar {
                            tabs.forEachIndexed { index, title ->
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            when (index) {
                                                0 -> Icons.Default.Folder
                                                1 -> Icons.Default.FolderShared
                                                2 -> Icons.Default.DeviceHub
                                                else -> Icons.Default.Link
                                            },
                                            contentDescription = title
                                        )
                                    },
                                    label = { Text(title) },
                                    selected = selectedTab == index,
                                    onClick = { onTabSelected(index) }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // 三个 Tab 常驻组合：切 Tab 不销毁组件（Link 的 WebView 保持，不重新加载）
        // Link Tab 不吃 Scaffold 的顶部 padding，由 LinkScreen 自己 statusBarsPadding，
        // 让主页内容紧贴状态栏下方、WebView 页全屏沉浸
        Box(modifier = Modifier.fillMaxSize()) {
            TabLayer(visible = selectedTab == 0, index = 0, modifier = Modifier.padding(padding)) {
                FileListScreen(
                    configStore = configStore
                )
            }
            TabLayer(visible = selectedTab == 1, index = 1, modifier = Modifier.padding(padding)) {
                ServiceListScreen(
                    configStore = configStore,
                    onEditShare = onEditShare,
                    onCreateNew = onCreateNew,
                    onStartStop = onStartStopShare,
                    onDeleteShare = onDeleteShare,
                    snackbarHostState = snackbarHostState,
                    refreshTrigger = shareListRefresh,
                    onOpenAdbTunnel = onOpenAdbTunnel,
                    onStartStopAdb = onStartStopAdb,
                    onDeleteAdb = onDeleteAdb,
                    onEditRemote = onEditRemote
                )
            }
            TabLayer(visible = selectedTab == 2, index = 2, modifier = Modifier.padding(padding)) {
                NetworkScreen()
            }
            TabLayer(visible = selectedTab == 3, index = 3) {
                LinkScreen(
                    active = selectedTab == 3,
                    drawerOpen = linkDrawerOpen,
                    onDrawerChanged = { linkDrawerOpen = it },
                    onFullscreenChanged = onLinkFullscreenChanged,
                    onWebPageChanged = { linkOnWebPage = it },
                    onZcodeChanged = { linkIsZcode = it },
                    resetZoomSignal = linkResetZoomSignal,
                    // DSH 移动端页面顶栏注入的「回主页」按钮：与悬浮面板「主页」同义
                    onGoHome = { onTabSelected(lastNonLinkTab) },
                    onDshPageChanged = { linkIsDsh = it },
                    onZcodeTitlebarChanged = { linkIsZcodeTitlebar = it }
                )
            }
            // Link Tab 右侧悬浮胶囊面板（沉浸化替代旧底部文字栏），仅 Link Tab 显示。
            // DSH 移动端页面 / zcode 工作台隐藏：按钮已注入页面自身顶栏，避免重复且不遮挡内容。
            LinkFloatingPanel(
                visible = selectedTab == 3 && !linkIsDsh && !linkIsZcodeTitlebar,
                showSidebar = linkOnWebPage,
                showZoomReset = linkOnWebPage,
                onBack = { onTabSelected(lastNonLinkTab) },
                onToggleSidebar = { linkDrawerOpen = !linkDrawerOpen },
                onZoomReset = { linkResetZoomSignal++ }
            )
        }
    }

    // 退出 App 二次确认弹窗：红色「退出」执行，取消可反悔
    if (showExitConfirm) {
        val totalRunning = shareStates.count { it.value.isAnyRunning }
        val etState by com.lunashare.app.easytier.EasyTierStateHolder.state.collectAsState()
        val vpnRunning = etState.coreRunning
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            icon = {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("退出 LunaShare") },
            text = {
                Text(
                    when {
                        totalRunning > 0 && vpnRunning ->
                            "有 $totalRunning 个共享正在运行，且组网（VPN）已连接。退出将停止全部共享、隧道与组网，完全退出不留后台，确定吗？"
                        totalRunning > 0 ->
                            "有 $totalRunning 个共享正在运行。退出将停止全部共享、隧道与前台服务，确定退出吗？"
                        vpnRunning ->
                            "组网（VPN）已连接。退出将断开组网并完全退出 App，确定吗？"
                        else ->
                            "将停止全部服务并退出 App，确定吗？"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        onExitApp()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
            }
        )
    }
}

/**
 * Tab 容器：三个 Tab 常驻组合，非当前 Tab 透明显示 + 拦截点击（防止误触下层页面），
 * 当前 Tab 置顶（zIndex 1）可正常交互。切换 Tab 时页面状态/WebView 不会销毁重建。
 * 切换动画：**只做新 Tab 的进入动画**（淡入 + 按位置方向滑入），旧 Tab 立即隐藏——
 * 避免新旧两个页面半透明叠加造成"闪一下"的叠影。
 */
@Composable
private fun TabLayer(
    visible: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 进入动画进度：0 → 1（仅对可见 Tab 生效；非可见 Tab 直接隐藏不走动画）
    val enterProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "tabEnter"
    )
    // 方向：0(文件) 从左侧滑入，3(Link) 从右侧滑入，其余仅淡入
    val direction = when (index) {
        0 -> -1f
        3 -> 1f
        else -> 0f
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .graphicsLayer {
                if (visible) {
                    alpha = enterProgress
                    translationX = direction * (1f - enterProgress) * 30.dp.toPx()
                } else {
                    // 旧 Tab 立即隐藏：无淡出，避免与新 Tab 叠加闪烁
                    alpha = 0f
                    translationX = 0f
                }
            }
            .then(
                if (!visible) Modifier.clickable(
                    enabled = true,
                    indication = null,
                    interactionSource = interactionSource
                ) {}
                else Modifier
            )
    ) { content() }
}

@Composable
private fun Canvas(
    modifier: Modifier,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    androidx.compose.foundation.Canvas(modifier = modifier, onDraw = draw)
}
