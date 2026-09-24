package com.lunashare.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lunashare.app.adb.AdbTunnelStateHolder
import com.lunashare.app.adb.AdbTunnelStore
import com.lunashare.app.remote.RemoteConnectionManager
import com.lunashare.app.config.RemoteConnectionStore
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.easytier.EasyTierManager
import com.lunashare.app.easytier.EasyTierStateHolder
import com.lunashare.app.frpc.OpenFrpApiClient
import com.lunashare.app.frpc.OpenFrpConfigStore
import com.lunashare.app.model.RemoteConnection
import com.lunashare.app.model.ShareConfig
import com.lunashare.app.link.LinkWebViewRegistry
import com.lunashare.app.service.ShareService
import com.lunashare.app.service.TRANSFER_EXTRA_OPEN_FILES_TAB
import com.lunashare.app.ui.AdbTunnelScreen
import com.lunashare.app.ui.MainScreen
import com.lunashare.app.ui.OpenFrpScreen
import com.lunashare.app.ui.RemoteConfigScreen
import com.lunashare.app.ui.SettingsScreen
import com.lunashare.app.ui.ServiceConfigScreen
import com.lunashare.app.ui.theme.LunaShareTheme
import com.lunashare.app.ui.theme.StatusBarOverride
import com.lunashare.app.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var configStore: ShareConfigStore
    private lateinit var themeManager: ThemeManager
    private var editingShare: ShareConfig? = null

    /**
     * 跨组件导航信号：通知点进来时要求 MainContent 切到指定底部 Tab（如 Link Tab）。
     * 用 Channel 中转，MainActivity 在 onCreate/onNewIntent 发射，MainContent 在 LaunchedEffect 收集。
     */
    internal val navToTabChannel = Channel<Int>(Channel.BUFFERED)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user may accept or deny */ }

    /**
     * Link Tab WebView 文件选择（onShowFileChooser）结果回传 → 常驻注册表 consumeFileChooser。
     * 用 Activity Result API 替代旧的 startActivityForResult，跨 Activity 重建不崩。
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        LinkWebViewRegistry.consumeFileChooser(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 适配高刷新率屏幕：默认 Android 普通 App 只跑 60Hz（省电策略），
        // 这里主动请求设备支持的最高刷新率（如 90/120Hz），Compose 动画/滚动更流畅
        enableHighRefreshRate()

        configStore = ShareConfigStore(this)
        themeManager = ThemeManager(this)
        requestNotificationPermission()

        // 组网「启动自动连接」：开关打开且组网未运行时，app 启动即以当前档案发起组网。
        // 走 app 启动链路（比 boot 直拉可靠）；VPN 授权 appops 已持久化，establish 静默通过。
        if (EasyTierManager.isBootAutoStart(this) && !EasyTierStateHolder.get().coreRunning) {
            runCatching { EasyTierManager.startFromSavedConfig(this) }
                .onFailure { Log.w("MainActivity", "mesh auto-connect failed", it) }
        }

        // 远程连接「app 启动时自动连接」
        runCatching { RemoteConnectionManager.autoConnectAll(this) }
            .onFailure { Log.w("MainActivity", "remote auto-connect failed", it) }

        // 「生效主题」必须在**任何 WebView 被创建之前**就定下来：WebView(Chromium) 判定
        // prefers-color-scheme 读的是应用主题（isLightTheme），而主题是烘在 Context 里的、
        // 运行期改不了。放在 setContent 之前，Link Tab 首次建 WebView 时就已经是对的那一份。
        applyEffectiveDarkMode()

        // Auto-start shares marked "start on app launch"
        autoStartSharesOnLaunch()

        Log.d(TAG, "setContent starting...")
        setContent {
            val darkOverride = themeManager.isDarkTheme()
            val effectiveDark = darkOverride ?: isSystemInDarkTheme()
            // 设置项改了主题（或系统日夜变了 → Activity 重建/重组）→ 同步给 WebView 层。
            // DSH 页面就地换色板（不重载、不掐会话）；其他页面才重建（uiMode 烘在 Context 里）。
            SideEffect { applyDarkModeIfChanged(effectiveDark) }
            LunaShareTheme(
                darkTheme = darkOverride
            ) {
                MainContent(
                    context = this,
                    configStore = configStore,
                    themeManager = themeManager
                )
            }
        }
        Log.d(TAG, "setContent done")

        // Link Tab WebView 文件选择：把 launcher 接入常驻注册表（跨 Activity 重建有效）
        LinkWebViewRegistry.requestFileChooser = { intent -> fileChooserLauncher.launch(intent) }
        LinkWebViewRegistry.currentActivity = this

        // 冷启动（App 已退出）：通知携带 extra 拉起时，直接落到目标 Tab
        handleOpenLinkTabIntent(intent)
    }

    /**
     * 若启动/唤醒 Intent 携带 [ZCODE_EXTRA_OPEN_LINK_TAB]，发射切换 Link Tab 的信号。
     * 冷启动在 onCreate 调用；热启动（App 在前台/后台存活）在 onNewIntent 调用。
     */
    private fun handleOpenLinkTabIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(
                com.lunashare.app.link.ZCODE_EXTRA_OPEN_LINK_TAB, false
            ) == true
        ) {
            // 你已从这条通知进了 App，它的使命完成 → 显式撤销。
            // 不能只依赖系统 setAutoCancel：部分 ROM 在「Activity 存活、被 SINGLE_TOP 唤到前台」时
            // 不触发它，通知会继续挂在通知中心 —— 这才是「进 App 后通知还在」的根因。
            val notifId = intent.getIntExtra(com.lunashare.app.link.ZCODE_EXTRA_NOTIF_ID, 0)
            if (notifId != 0) com.lunashare.app.link.ZcodeNotifier.cancel(this, notifId)
            navToTabChannel.trySend(3) // 3 = Link Tab
            Log.d(TAG, "open link tab requested via notification (cancelled notif=$notifId)")
        }
    }

    /**
     * 若启动/唤醒 Intent 携带 [TRANSFER_EXTRA_OPEN_FILES_TAB]（传输完成通知点进来），
     * 发射切换「文件」Tab（index 0）的信号——刚接收的文件就在文件列表里。
     */
    private fun handleOpenFilesTabIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(TRANSFER_EXTRA_OPEN_FILES_TAB, false) == true) {
            navToTabChannel.trySend(0) // 0 = 文件 Tab
            Log.d(TAG, "open files tab requested via transfer notification")
        }
    }

    /**
     * 把「App 生效主题」告诉 WebView 层（Link Tab 的页面据此定 prefers-color-scheme）。
     *
     * 生效主题 = 设置项优先；设置为「跟随系统」时看系统日夜。
     */
    private fun applyEffectiveDarkMode() {
        val forced = themeManager.isDarkTheme()
        val dark = forced ?: (
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            )
        applyDarkModeIfChanged(dark)
    }

    /**
     * 幂等地把某个明暗状态落实到「进程级」：
     *  1. 通知 Link WebView 注册表（含页面级处理）；
     *  2. 切 [applicationContext] 的主题。
     *
     * 第 2 步为什么必须在 applicationContext 上而不是 WebView 自己的 Context：
     * 实测给 WebView 单独套 `Theme.LunaShare.Dark` **不影响** 页面的
     * `prefers-color-scheme`；Chromium 读的是**应用主题**的 `android:isLightTheme`。
     * 于是"App 强制亮色而系统深色"这种不一致也能对上。
     *
     * 用 [appliedDark] 去重：Compose 每次重组都会走到这里，重复 setTheme 会反复触发
     * 资源重解析。
     */
    private fun applyDarkModeIfChanged(dark: Boolean) {
        if (appliedDark == dark) return
        appliedDark = dark
        LinkWebViewRegistry.setDarkMode(dark)
        runCatching {
            applicationContext.setTheme(
                if (dark) R.style.Theme_LunaShare_Dark else R.style.Theme_LunaShare
            )
        }
    }

    /** 已经落实过的生效主题（null = 尚未落实）；见 [applyDarkModeIfChanged] */
    @Volatile
    private var appliedDark: Boolean? = null

    /**
     * 适配高刷新率：把面板带到设备支持的最高刷新率（120/90Hz）。
     *
     * ⚠️ 实测教训（HONOR SER-AN00 / MagicOS / Android 16 / 120Hz 面板）：
     *
     *  - **系统本来就会给普通 App 高刷**：桌面、系统设置处于前台时 `dumpsys display`
     *    显示 `mActiveModeId=1`（120Hz）。也就是说"不请求"反而能拿到 120Hz。
     *  - **反而是本 App 自己把面板压到了 60Hz**：只要本 App 在前台，`mActiveModeId` 就掉到 3
     *    （60Hz）。旧实现里唯一"主动干预显示"的动作就是
     *    `preferredDisplayModeId = 最高刷新率模式` —— 所以它就是元凶：
     *    在 Android 14+ 这个字段已不推荐，本机（MagicOS）上的实际效果是把显示**钉在**
     *    一个非预期模式上，而不是"提升到"最高模式。
     *
     * 因此策略改成：**默认什么都不请求（FPS_AUTO）**，让系统自适应调度；同时留一个
     * 免重编译的逃生门，便于在真机上 A/B：
     *
     *     adb shell settings put global lunashare_fps_mode <0..6>
     *     0 什么都不请求（默认）   1 仅 preferredDisplayModeId    2 仅 API35 窗口开关
     *     3 仅 WebView 帧率提示     4 全部                          5 = 1+3
     *     6 = 2+3
     *
     * 改完 force-stop 重开 App 生效（这些开关只能在窗口创建前设置）。
     */
    @Suppress("DEPRECATION")
    private fun enableHighRefreshRate() {
        val mode = try {
            android.provider.Settings.Global.getInt(contentResolver, "lunashare_fps_mode", FPS_AUTO)
        } catch (_: Exception) {
            FPS_AUTO
        }
        val display = window.windowManager.defaultDisplay ?: return
        val best: android.view.Display.Mode = try {
            display.supportedModes.maxByOrNull { it.refreshRate }
        } catch (_: Exception) {
            null
        } ?: return
        if (best.refreshRate <= 60f) return

        val wantLegacy = mode == FPS_LEGACY || mode == FPS_ALL || mode == FPS_LEGACY_HINT
        val wantKnobs = mode == FPS_KNOBS || mode == FPS_ALL || mode == FPS_KNOBS_HINT
        val wantHint = mode == FPS_HINT || mode == FPS_ALL ||
            mode == FPS_LEGACY_HINT || mode == FPS_KNOBS_HINT

        if (wantLegacy) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = best.modeId
                }
            } else {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = best.refreshRate
                }
            }
        }
        if (wantKnobs && Build.VERSION.SDK_INT >= 35) {
            runCatching { window.setFrameRateBoostOnTouchEnabled(true) }
            runCatching { window.setFrameRatePowerSavingsBalanced(false) }
        }
        // 传给 WebView 层：之后创建的每个 WebView 都会对自身请求这个帧率
        LinkWebViewRegistry.setTargetFrameRate(if (wantHint) best.refreshRate else 0f)
    }

    /** Start all shares that have "start on app launch" enabled. */
    private fun autoStartSharesOnLaunch() {
        val autoStartConfigs = configStore.listConfigs().filter { it.autoStart }
        if (autoStartConfigs.isEmpty()) return
        Log.i(TAG, "Auto-starting ${autoStartConfigs.size} share(s) on app launch")
        for (config in autoStartConfigs) {
            startShare(config.id)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent: ${intent.action}")
        // 热启动（App 存活）：通知点进来 → 切到对应 Tab（Link / 文件）
        handleOpenLinkTabIntent(intent)
        handleOpenFilesTabIntent(intent)
    }

    // Link Tab 文件选择由 fileChooserLauncher（ActivityResultLauncher）处理，结果经
    // LinkWebViewRegistry.consumeFileChooser 回传给 WebView 的 onShowFileChooser 回调。

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun startShare(shareId: String) {
        val intent = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_START_SHARE
            putExtra(ShareService.EXTRA_SHARE_ID, shareId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    fun stopShare(shareId: String) {
        val intent = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_STOP_SHARE
            putExtra(ShareService.EXTRA_SHARE_ID, shareId)
        }
        startService(intent)
    }

    fun startAdbTunnel() {
        val intent = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_START_ADB_TUNNEL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    fun stopAdbTunnel() {
        val intent = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_STOP_ADB_TUNNEL
        }
        startService(intent)
    }

    private fun stopAllShares() {
        val intent = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_STOP_ALL
        }
        startService(intent)
    }

    /**
     * 主动退出 App（电源按钮）：停止全部共享 / 隧道 / SMB / mDNS / 前台服务
     * （ACTION_STOP_ALL 内部会 stopSelf 并在 onDestroy 释放 WakeLock），**并停止
     * 组网（EasyTier 核心 + VPN 服务 + 常驻通知）**，然后结束所有 Activity。
     * 实现完全退出、不留任何后台。
     */
    fun exitApp() {
        EasyTierManager.stop(this)
        // 远程连接一并断开：否则进程被缓存存活，重开 app 会带着"已连接"状态，
        // 看起来就像没勾自启却自动连上了。
        RemoteConnectionManager.disconnectAll(this)
        stopAllShares()
        finishAffinity()
    }

    /** 删除 ADB 穿透：停隧道 + 清本地配置 + 重置状态 + 在 OpenFrp 删除代理。 */
    suspend fun deleteAdbTunnel() {
        withContext(Dispatchers.IO) {
            val store = AdbTunnelStore(this@MainActivity)
            val cfg = store.loadConfig() ?: return@withContext
            val openFrpStore = OpenFrpConfigStore(this@MainActivity)
            val account = openFrpStore.loadAccount()
            if (account.isLoggedIn) {
                val api = OpenFrpApiClient()
                val creds = OpenFrpApiClient.Credentials(account.authorization, account.session)
                val realId = api.getUserProxies(creds).getOrNull()
                    ?.find { it.name == cfg.name || it.proxyName == cfg.name }?.proxyIdValue
                    ?: cfg.proxyId.takeIf { it != 0 }
                realId?.let { api.removeProxy(creds, it) }
            }
            store.clear()
        }
        AdbTunnelStateHolder.setEnabled(false)
        AdbTunnelStateHolder.setTunnelRunning(false)
        AdbTunnelStateHolder.setConnectInfo("", null)
        AdbTunnelStateHolder.addLog("ADB 隧道已删除")
        stopAdbTunnel()
    }

    companion object {
        private const val TAG = "MainActivity"

        /** 高刷策略（见 [enableHighRefreshRate]）；可用 settings global 覆盖做真机 A/B */
        const val FPS_AUTO = 0          // 不请求，交给系统自适应（产品默认）
        const val FPS_LEGACY = 1        // 仅 preferredDisplayModeId
        const val FPS_KNOBS = 2         // 仅 API35 窗口级开关
        const val FPS_HINT = 3          // 仅 WebView 的 setRequestedFrameRate
        const val FPS_ALL = 4           // 全部
        const val FPS_LEGACY_HINT = 5   // 1 + 3
        const val FPS_KNOBS_HINT = 6    // 2 + 3
    }
}

@Composable
private fun MainContent(
    context: MainActivity,
    configStore: ShareConfigStore,
    themeManager: ThemeManager,
) {
    var showEditor by remember { mutableStateOf<ShareConfig?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showOpenFrp by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showAdbTunnel by remember { mutableStateOf(false) }
    // 编辑中的远程连接（null=不在编辑器）；remoteIsNew 区分新建/编辑标题
    var editingRemote by remember { mutableStateOf<RemoteConnection?>(null) }
    var remoteIsNew by remember { mutableStateOf(false) }
    val uiPrefs = context.getSharedPreferences("lunashare_ui", Context.MODE_PRIVATE)
    var selectedTab by remember { mutableIntStateOf(uiPrefs.getInt("last_selected_tab", 0)) }
    // 记住上次打开的底部 Tab：切 Tab / 编辑器返回都会自动落盘
    LaunchedEffect(selectedTab) {
        uiPrefs.edit().putInt("last_selected_tab", selectedTab).apply()
    }
    // 通知点进来 / 跨组件要求切 Tab（如跳 Link Tab）：非 Link 页时也能直接落到 Link
    LaunchedEffect(Unit) {
        context.navToTabChannel.receiveAsFlow().collect { tab ->
            selectedTab = tab
        }
    }
    var editorReturnTab by remember { mutableIntStateOf(0) }
    var shareListRefresh by remember { mutableIntStateOf(0) }
    // Link Tab WebView 页全屏（隐藏 LunaShare 顶部标题栏）
    var linkFullscreen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (showOpenFrp) {
        OpenFrpScreen(
            onDismiss = { showOpenFrp = false }
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            themeManager = themeManager,
            configStore = configStore,
            onDismiss = { showSettings = false },
            onOpenFrp = { showOpenFrp = true }
        )
        return
    }

    if (editingRemote != null) {
        RemoteConfigScreen(
            connection = editingRemote!!,
            store = RemoteConnectionStore(context),
            isNew = remoteIsNew,
            onSaved = { cfg ->
                // 修改的是正在连接/连接中的配置 → 断开后按新配置自动重连
                val wasConnected = RemoteConnectionManager.states.value[cfg.id]
                    ?.let { it.connected || it.connecting } == true
                editingRemote = null
                shareListRefresh++
                if (wasConnected) {
                    RemoteConnectionManager.disconnect(context, cfg.id)
                    RemoteConnectionManager.connect(context, cfg)
                    scope.launch { snackbarHostState.showSnackbar("连接配置已保存，正在重新连接…") }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("连接配置已保存") }
                }
            },
            onDismiss = { editingRemote = null }
        )
        return
    }

    if (showAdbTunnel) {
        AdbTunnelScreen(
            onBack = { showAdbTunnel = false },
            onStartTunnel = { context.startAdbTunnel() },
            onStopTunnel = { context.stopAdbTunnel() }
        )
        return
    }

    // Handle editor result
    if (showEditor != null) {
        ServiceConfigScreen(
            share = showEditor!!,
            configStore = configStore,
            onConfigChanged = { updated ->
                configStore.saveConfig(updated)
                // Update auto-start preference
                configStore.setAutoStart(updated.id, updated.autoStart)
                // 关键：加密口令 / 端口 / 目录等参数是在共享启动时快照进 FileServer 的，
                // 仅保存配置不会生效（改了口令不重启就会一直 BAD_DECRYPT）。
                // 故这里对「正在运行」的共享执行停止→按新配置重启，真正应用变更并重建隧道。
                val wasRunning = com.lunashare.app.service.ShareStateHolder
                    .getState(updated.id).isAnyRunning
                context.startService(
                    Intent(context, ShareService::class.java).apply {
                        action = if (wasRunning) ShareService.ACTION_RESTART_SHARE
                                 else ShareService.ACTION_START_SHARE
                        putExtra(ShareService.EXTRA_SHARE_ID, updated.id)
                    }
                )
                // SMB 是全局单实例、同样在启动时读配置，需单独重启
                if (com.lunashare.app.service.SmbServerManager.isRunning) {
                    context.startService(
                        Intent(context, ShareService::class.java).apply {
                            action = ShareService.ACTION_RESTART_SMB
                        }
                    )
                }
                shareListRefresh++
                showEditor = null
                selectedTab = editorReturnTab
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (wasRunning) "配置已保存，正在重启共享…" else "配置已保存"
                    )
                }
            },
            onDismiss = {
                showEditor = null
                selectedTab = editorReturnTab
            }
        )
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = StatusBarOverride.color?.let { Color(it) }
                ?: MaterialTheme.colorScheme.background
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                MainScreen(
                    configStore = configStore,
                    onEditShare = { share ->
                        editorReturnTab = selectedTab
                        showEditor = share
                    },
                    onCreateNew = {
                        editorReturnTab = selectedTab
                        showCreateDialog = true
                    },
                    onStartStopShare = { share ->
                        val state = com.lunashare.app.service.ShareStateHolder.getState(share.id)
                        if (state.isAnyRunning) {
                            context.stopShare(share.id)
                        } else {
                            configStore.saveConfig(share)
                            shareListRefresh++
                            context.startShare(share.id)
                        }
                    },
                    onDeleteShare = { share ->
                        configStore.deleteConfig(share.id)
                        com.lunashare.app.service.ShareStateHolder.removeShare(share.id)
                        shareListRefresh++
                    },
                    onSettings = { showSettings = true },
                    selectedTab = selectedTab,
                    onTabSelected = { i ->
                        if (i != 3) linkFullscreen = false
                        selectedTab = i
                    },
                    shareListRefresh = shareListRefresh,
                    onOpenAdbTunnel = { showAdbTunnel = true },
                    onEditRemote = { rc ->
                        remoteIsNew = false
                        editingRemote = rc
                    },
                    onStartStopAdb = {
                        if (AdbTunnelStateHolder.get().tunnelRunning) context.stopAdbTunnel()
                        else context.startAdbTunnel()
                    },
                    onDeleteAdb = { scope.launch { context.deleteAdbTunnel() } },
                    linkFullscreen = linkFullscreen,
                    onLinkFullscreenChanged = { linkFullscreen = it },
                    onExitApp = { context.exitApp() }
                )
            }
        }

        // Create new share dialog
        if (showCreateDialog) {
            BackHandler { showCreateDialog = false }
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                icon = {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null
                    )
                },
                title = { Text("添加") },
                text = {
                    // 竖排全宽选项：三个按钮横排在窄屏会溢出，把「创建共享」挤出去
                    Column {
                        Text("创建新的文件共享、连接别人的共享，或配置 ADB 穿透。")
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val newShare = ShareConfig()
                                configStore.saveConfig(newShare)
                                showCreateDialog = false
                                showEditor = newShare
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("创建共享")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                showCreateDialog = false
                                remoteIsNew = true
                                editingRemote = RemoteConnection()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Cloud, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("连接共享")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                showCreateDialog = false
                                showAdbTunnel = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Router, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ADB 穿透")
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        showCreateDialog = false
                    }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}
