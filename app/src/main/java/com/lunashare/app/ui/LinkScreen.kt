package com.lunashare.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lunashare.app.PortraitCaptureActivity
import com.lunashare.app.link.DshPageAdapter
import com.lunashare.app.link.ZcodePageAdapter
import com.lunashare.app.link.DownloadStatus
import com.lunashare.app.link.DownloadTask
import com.lunashare.app.link.LinkDownloadManager
import com.lunashare.app.link.LinkStore
import com.lunashare.app.link.LinkWebViewRegistry
import com.lunashare.app.link.NotifySettingsStore
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lunashare.app.link.ZcodeEventBridge
import com.lunashare.app.link.ZcodeNotifier
import com.lunashare.app.link.ZCODE_TICK_JS
import com.lunashare.app.link.ZCODE_WATCHER_JS
import kotlinx.coroutines.delay
import com.lunashare.app.ui.theme.StatusBarOverride
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import android.os.Build

/**
 * 判断地址是否为 zcode 远程桌面/开发页面（zcode.z.ai 域名或含 /remote/ 路径）。
 * Link 页对 zcode 启用「远程模式」：WebView 双指/双击缩放 + 底部栏「缩放复位」按钮
 * （缩放复位由 MainScreen 底部栏提供，仅在 zcode 网页页显示；按需求不含「断开连接」）。
 */
private fun isZcode(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return try {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return false
        host == "zcode.z.ai" || host.endsWith(".zcode.z.ai") ||
            (uri.path ?: "").contains("/remote/")
    } catch (_: Exception) {
        false
    }
}

/** 缩放复位：循环缩小到最小（overview 适配宽度），即「铺满屏幕」状态 */
private fun resetWebViewZoom(wv: WebView) {
    try {
        var guard = 0
        while (wv.zoomOut() && guard < 50) guard++
    } catch (_: Exception) {
    }
}

/**
 * 对 zcode 页面注入的保守优化 CSS：仅去掉干扰项（页面橡皮筋回弹、点击高亮），
 * 不改动其自带移动端布局，避免过度侵入导致 zcode 改版时大面积失效。
 */
private const val ZCODE_OPT_CSS =
    "(function(){try{var s=document.getElementById('luna-zcode-opt');if(!s){s=document.createElement('style');s.id='luna-zcode-opt';(document.head||document.documentElement).appendChild(s);}s.textContent='html,body{overscroll-behavior:none;-webkit-tap-highlight-color:transparent;}';}catch(e){}})();"

/**
 * 复刻 HermesMobile 的「主页(连接) + WebActivity(WebView)」逻辑，作为一个 link Tab。
 *
 * WebView 实例由 LinkWebViewRegistry（Application 级单例）常驻管理：自带进度条（StateFlow）/ 上传 / 下载 / JS / 混合内容 / 错误页，跨 Activity 重建不重载。
 *
 * 关键设计：
 * - **多 WebView 缓存（伪标签页）**：每个连接过的地址一个常驻 WebView（openedUrls 循环 + key(u) 稳定插槽），
 *   隐藏不销毁；切换链接只改 container visibility（INVISIBLE/VISIBLE）——**不用 zIndex/alpha 动态切换**
 *   （那会触发 ComposeView 重排子 View → WebView detach/attach → 被系统重新加载）。
 *   Android 系统 WebView 本身即 Chromium（Chrome）内核，多 WebView 叠放即浏览器标签页效果；
 * - 默认（无上次连接）显示连接/主页页；有 lastAddress 则自动进入 WebView；
 * - WebView 页全屏无标题栏；底部栏由 MainScreen 提供（左「返回」+ 右「侧边栏」），
 *   主页/添加连接页通过 onWebPageChanged(false) 隐藏侧边栏按钮；
 * - 左侧抽屉（80% 宽左滑入）：已存地址圆角卡片（x 删除二次确认）、添加连接、断开连接；
 *   与 DSH 会话侧边栏（页面自管、右滑入）分居两侧；
 * - 抽屉开关状态由 MainScreen 持有（drawerOpen/onDrawerChanged）toggle；
 * - 系统返回键：抽屉开→关抽屉；WebView 可回退→回退；无回退→断开回主页；主页(pendingReturnUrl)→回 WebView；
 * - 键盘：WebView 层 imePadding() + padding(bottom = max(0, 44dp - ime))，主页 Column 单独 imePadding；
 * - 文件选择经 WebChromeClient.onShowFileChooser + MainActivity 的 ActivityResultLauncher，
 *   Activity 重建后不崩（不再依赖紧绑旧 Activity 的 ByWebView）；
 * - 常驻组合：切其他 Tab 不销毁，active=false 时隐藏全屏态/清屏幕常亮/关抽屉。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LinkScreen(
    onFullscreenChanged: (Boolean) -> Unit = {},
    onWebPageChanged: (Boolean) -> Unit = {},
    active: Boolean = true,
    drawerOpen: Boolean = false,
    onDrawerChanged: (Boolean) -> Unit = {},
    // 上报当前是否处于 zcode 网页页（MainScreen 据此在底部栏显示「缩放复位」按钮）
    onZcodeChanged: (Boolean) -> Unit = {},
    // 缩放复位信号：MainScreen 底部栏按钮每次点击自增，这里 LaunchedEffect 触发当前 WebView 复位
    resetZoomSignal: Int = 0,
    // 页面内「回主页」按钮触发（DSH 移动端页面顶栏注入的宿主按钮），与悬浮面板「主页」同义
    onGoHome: () -> Unit = {},
    // 上报当前页是否为 DSH 移动端页面（MainScreen 据此隐藏右侧悬浮胶囊面板：按钮已注入页面顶栏）
    onDshPageChanged: (Boolean) -> Unit = {},
    // 上报当前页是否为 zcode 工作台（MainScreen 据此隐藏右侧悬浮胶囊面板：按钮已注入标题栏右侧）
    onZcodeTitlebarChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { LinkStore(context) }
    val notifyStore = remember { NotifySettingsStore(context) }
    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    // 当前激活的 WebView 地址：null = 主页/连接页；非 null = 该地址的 WebView 可见
    val lastAddress = store.getLastAddress()
    var currentUrl by remember { mutableStateOf(lastAddress) }
    // 已打开的地址（每个对应一个常驻 WebView，隐藏不销毁 → 切换不重新加载）
    val openedUrls = remember {
        mutableStateListOf<String>().apply { lastAddress?.let { add(it) } }
    }
    // WebView 实例由 LinkWebViewRegistry（Application 级单例）常驻管理，跨 Activity 重建不重载，
    // 这里不再持有 ByWebView map；地址 → WebView 统一走 LinkWebViewRegistry.getWebView(url)。
    // 地址 → 显示标题（页面加载完成后由 onPageFinished 更新为网页标题，如"百度一下，你就知道"）
    val linkTitles = remember { mutableStateMapOf<String, String>() }
    // 地址 → 网页 favicon 图标（onPageFinished 后延迟读取 WebView.favicon）
    val linkFavicons = remember { mutableStateMapOf<String, Bitmap>() }
    // 从抽屉「添加连接」进入主页页时记住来源，主页页的返回按钮可回到 WebView
    var pendingReturnUrl by remember { mutableStateOf<String?>(null) }
    // 待删除的连接（弹二次确认对话框）
    var pendingDeleteAddress by remember { mutableStateOf<String?>(null) }
    // 长按侧边栏项目 → 弹出「通知行为 / 重命名」操作菜单的目标地址
    var actionSheetAddress by remember { mutableStateOf<String?>(null) }
    // 正在配置通知行为的页面地址（弹 NotifySettingsDialog）
    var notifyConfigAddress by remember { mutableStateOf<String?>(null) }
    // 正在重命名的页面地址（弹 RenameDialog）
    var renameAddress by remember { mutableStateOf<String?>(null) }
    // 通知配置版本号：保存配置后自增，驱动 LaunchedEffect 把新配置推给已打开的 zcode 页面
    var notifyCfgVersion by remember { mutableIntStateOf(0) }

    // ── DSH 移动端页面适配（dsh-bridge 注入的移动端皮肤）──
    /** 地址 → 是否 DSH 移动端页面（由页面适配脚本自辨识后经控制台通道回报） */
    val dshPages = remember { mutableStateMapOf<String, Boolean>() }
    /** 当前 DSH 页面的主题是否深色（决定状态栏图标明暗；null = 当前页不是 DSH 页面） */
    var dshPageDark by remember { mutableStateOf<Boolean?>(null) }
    /** 当前 DSH 页面的真实底色 "r,g,b"（决定状态栏背景色；null = 未上报/非 DSH 页面） */
    var dshPageBg by remember { mutableStateOf<String?>(null) }
    /** 页面内宿主按钮的待处理动作（home / zoom / luna_sidebar） */
    var pendingPageAction by remember { mutableStateOf<String?>(null) }
    /** 当前页是否 DSH 移动端页面 */
    val currentIsDsh = currentUrl?.let { dshPages[it] == true } ?: false

    // ── zcode 工作台页面适配（标题栏右侧注入宿主按钮）──
    /** 地址 → 是否 zcode 工作台（标题栏存在，由页面适配脚本自辨识后经控制台通道回报） */
    val zcodeTitlebarPages = remember { mutableStateMapOf<String, Boolean>() }
    /** 当前页是否为 zcode 工作台（按钮已注入标题栏右侧） */
    val currentIsZcodeTitlebar = currentUrl?.let { zcodeTitlebarPages[it] == true } ?: false
    /** 供长生命周期回调读取最新地址（避免闭包捕获首次组合的旧值） */
    val currentUrlState = rememberUpdatedState(currentUrl)

    /**
     * 主题版本号：App 亮/暗一变就自增（见 [LinkWebViewRegistry.setDarkMode]）。
     *
     * 为什么要拿它当 WebView 的 key：页面里的 `prefers-color-scheme` 来自建 WebView 时那个
     * Context 的 `uiMode`，运行期无法原地修改 —— 换主题只能把 WebView 重建一遍（= 页面重新
     * loadUrl）。这是"换主题后网页跟着变"的唯一可靠办法。
     */
    val themeVersion by LinkWebViewRegistry.themeVersion.collectAsState()

    var addressInput by remember { mutableStateOf(lastAddress ?: "") }
    val allowInsecureState = remember { mutableStateOf(store.allowInsecureCert) }
    var keepScreenOn by remember { mutableStateOf(store.keepScreenOn) }

    val addresses = remember { mutableStateListOf<String>() }
    fun refreshAddresses() {
        addresses.clear()
        addresses.addAll(store.getAddresses())
    }
    LaunchedEffect(Unit) { refreshAddresses() }

    // 右侧抽屉开关状态由外部（MainScreen）持有，这里只上报
    fun openDrawer() { onDrawerChanged(true) }
    fun closeDrawer() { onDrawerChanged(false) }

    // 释放 Link 内焦点并隐藏输入法（切换 Tab / App 退后台时调用，
    // 防止回前台时 Android 为持焦的输入框/WebView 恢复输入法，键盘意外弹出）
    fun releaseFocusAndHideIme() {
        // 注意：Compose 焦点在 WebView 原生焦点持有/快速切换时可能处于不一致状态，
        // 普通 clearFocus() 会抛 ActiveParent with no focused child 崩溃——用 force=true + try-catch 兜底
        try {
            focusManager.clearFocus(force = true)
        } catch (_: Exception) {
        }
        LinkWebViewRegistry.openedUrls.forEach { u ->
            try { LinkWebViewRegistry.getWebView(u)?.clearFocus() } catch (_: Exception) {}
        }
        if (view.isAttachedToWindow) {
            val ime = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            ime?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // 切到其他 Tab：收起抽屉 + 释放焦点/隐藏输入法（Link 是常驻组合，输入框/WebView 会一直持焦，
    // 若不清理，切到服务页后再回前台会概率性弹出键盘）
    LaunchedEffect(active) {
        if (!active) {
            closeDrawer()
            releaseFocusAndHideIme()
        }
    }

    // App 退到后台：释放焦点 + 隐藏输入法，防止再次打开 App 时键盘意外弹出
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                releaseFocusAndHideIme()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // WebView 页 → 全屏（隐藏顶部标题栏），并上报是否处于 WebView 页（控制底部侧边栏按钮显隐）
    LaunchedEffect(active, currentUrl == null) {
        onFullscreenChanged(active && currentUrl != null)
        onWebPageChanged(currentUrl != null)
    }
    // 上报当前是否处于 zcode 网页页：仅 zcode 页面才在底部栏提供「缩放复位」（其它网页无需缩放）
    LaunchedEffect(active, currentUrl) {
        onZcodeChanged(active && currentUrl != null && isZcode(currentUrl))
    }

    // 「用户是否正注视 zcode 页」→ 写入页面 window.__lunaLinkVisible，供注入脚本做静音判断。
    // 判定 = Link Tab 激活 && 当前激活链接是 zcode && App 在前台（ON_START 之后/ON_STOP 之前）。
    // 不用 document.visibilityState：本 App 切到「文件」Tab 只是把 zcode WebView 移出屏幕
    // （translationX=10000），document 仍可能报 visible，会让完成/失败通知永远静音。
    val lifecycleOwnerForVisible = LocalLifecycleOwner.current
    var appInForeground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwnerForVisible) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appInForeground = true
                Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> {}
            }
        }
        lifecycleOwnerForVisible.lifecycle.addObserver(obs)
        appInForeground =
            lifecycleOwnerForVisible.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwnerForVisible.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(active, currentUrl, appInForeground) {
        // 同步激活态到常驻注册表，供 onPageFinished 重新注入「注视标志」时取值正确
        //（实时变化由下方 evaluateJavascript 即时推送，这里是页面（重）加载后的回填兜底）
        LinkWebViewRegistry.active = active && currentUrl != null
        LinkWebViewRegistry.currentUrl = currentUrl
        LinkWebViewRegistry.appInForeground = appInForeground
        val looking =
            active && appInForeground && currentUrl != null && isZcode(currentUrl) &&
                LinkWebViewRegistry.getEntry(currentUrl) != null
        LinkWebViewRegistry.getWebView(currentUrl)?.let { wv ->
            wv.post {
                try {
                    wv.evaluateJavascript(
                        "window.__lunaLinkVisible = $looking;",
                        null
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    // zcode 通知检测「心跳」：宿主每 2s 主动让页面跑一次检测（页面脚本暴露 window.__lunaZcodeTick）。
    // 页面内的 setInterval 在 App 退到后台会被 WebView 节流到 ≥1 分钟，而「待确认」往往正好发生在
    // 你切走之后（Agent 在等你回答）；宿主 evaluateJavascript 不受节流影响，是及时性的主要保证。
    // 该协程随组合存活，Activity 进后台（ON_STOP）时仍继续运行。
    LaunchedEffect(active, currentUrl) {
        val url = currentUrl ?: return@LaunchedEffect
        if (!active || !isZcode(url)) return@LaunchedEffect
        while (true) {
            delay(2000)
            val wv = LinkWebViewRegistry.getWebView(url) ?: continue
            wv.post {
                try {
                    wv.evaluateJavascript(ZCODE_TICK_JS, null)
                } catch (_: Exception) {
                }
            }
        }
    }

    // 把页面事件回调挂到常驻注册表（Activity 重建后重新设置，指向新组合的闭包）
    DisposableEffect(Unit) {
        LinkWebViewRegistry.onTitleUpdated = { url, title ->
            if (!store.isRenamed(url)) {
                store.setTitle(url, title)
                linkTitles[url] = title
            }
        }
        LinkWebViewRegistry.onFaviconUpdated = { url, icon -> linkFavicons[url] = icon }
        LinkWebViewRegistry.onPageFinishedListener = { url, view ->
            // 标题兜底（onReceivedTitle 可能未触发）
            val t = view?.title
            if (!t.isNullOrBlank() && !store.isRenamed(url)) {
                store.setTitle(url, t)
                linkTitles[url] = t
            }
            // zcode 远程模式：注入保守优化 CSS（去 overscroll / 点击高亮），
            // 让其自带移动端页面在手机上更跟手，不改动其自有布局
            if (isZcode(url)) {
                view?.evaluateJavascript(ZCODE_OPT_CSS, null)
                view?.evaluateJavascript(ZCODE_WATCHER_JS, null)
                // 页面（重）加载会重置 window，需立刻回填「是否正被注视」标志
                // 与用户的通知行为配置（二者均存于 window，重载即丢失）
                val looking = LinkWebViewRegistry.active && LinkWebViewRegistry.appInForeground &&
                    url == LinkWebViewRegistry.currentUrl && isZcode(url)
                val cfgJs = notifyStore.getOrDefault(NotifySettingsStore.keyOf(url)).toJsLiteral()
                view?.evaluateJavascript(
                    "window.__lunaLinkVisible = $looking;" +
                        "window.__lunaNotifyCfg = $cfgJs;",
                    null
                )
            }
            // favicon 通常比标题稍晚就绪，延迟一小段再读（WebView 可能已销毁，异常兜底）
            view?.postDelayed({
                try {
                    val icon = view.favicon
                    if (icon != null) linkFavicons[url] = icon
                } catch (_: Exception) {
                }
            }, 500)
        }
        onDispose {
            LinkWebViewRegistry.onTitleUpdated = null
            LinkWebViewRegistry.onFaviconUpdated = null
            LinkWebViewRegistry.onPageFinishedListener = null
        }
    }

    // 通知行为配置变更（notifyCfgVersion 自增）→ 把新配置推给所有已打开的 zcode 页面。
    // 只改配置不重载页面：脚本读取 window.__lunaNotifyCfg 是即时的，改完下一次事件就生效。
    LaunchedEffect(notifyCfgVersion) {
        if (notifyCfgVersion == 0) return@LaunchedEffect
        openedUrls.forEach { url ->
            if (!isZcode(url)) return@forEach
            LinkWebViewRegistry.getWebView(url)?.let { wv ->
                wv.post {
                    try {
                        val cfgJs = notifyStore.getOrDefault(NotifySettingsStore.keyOf(url)).toJsLiteral()
                        wv.evaluateJavascript("window.__lunaNotifyCfg = $cfgJs;", null)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // ────────── DSH 移动端页面适配（dsh-bridge 注入的移动端皮肤）──────────

    // 1) 接收页面适配层消息：页面能力辨识 / 主题明暗 / 顶栏宿主按钮动作。
    //    走控制台通道（前缀 + 每个 WebView 独立的一次性口令），页面上的普通脚本无法伪造，
    //    因此不必给任意网页暴露 JS 接口。
    DisposableEffect(Unit) {
        LinkWebViewRegistry.onPageMessage = { url, msg ->
            msg.capability?.let { cap ->
                val isDsh = cap == DshPageAdapter.CAP_DSH
                if (dshPages[url] != isDsh) dshPages[url] = isDsh
                // 落盘：DSH 页面可能挂在任意 origin，只能靠"页面自己承认"，
                // 抽屉长按菜单的「通知行为」入口等需要跨会话知道它。
                if (isDsh) store.setDshPage(url) else store.clearDshPage(url)
            }
            msg.dark?.let { dark -> if (url == currentUrlState.value) dshPageDark = dark }
            msg.bg?.let { bg -> if (url == currentUrlState.value) dshPageBg = bg }
            msg.action?.let { action -> pendingPageAction = action }
        }
        LinkWebViewRegistry.onZcodePageMessage = { url, msg ->
            msg.capability?.let { cap ->
                val isZcode = cap == ZcodePageAdapter.CAP_ZCODE
                if (zcodeTitlebarPages[url] != isZcode) zcodeTitlebarPages[url] = isZcode
            }
            // 动作与 DSH 同义（home / zoom / luna_sidebar），复用同一套原生行为分发
            msg.action?.let { action -> pendingPageAction = action }
        }
        onDispose {
            LinkWebViewRegistry.onPageMessage = null
            LinkWebViewRegistry.onZcodePageMessage = null
        }
    }

    // 2) 页面内顶栏按钮（主页 / 缩放复位 / 连接）→ 与悬浮胶囊面板同义的原生行为。
    //    ⚠️ 页面顶栏右插槽的「会话侧边栏」不走这里：那是 DSH 自己的抽屉，纯页面行为，
    //    由注入脚本直接切 body 上的 dsh-drawer-open（面板从右滑出），不绕原生。
    LaunchedEffect(pendingPageAction) {
        when (pendingPageAction) {
            DshPageAdapter.ACTION_HOME -> onGoHome()
            DshPageAdapter.ACTION_ZOOM ->
                currentUrl?.let { u ->
                    LinkWebViewRegistry.getWebView(u)?.let { wv -> resetWebViewZoom(wv) }
                }
            // 「连接列表」= LunaShare 自己的连接抽屉（左滑入），与 DSH 会话侧边栏（右侧）分居两侧
            DshPageAdapter.ACTION_LUNA_SIDEBAR -> onDrawerChanged(!drawerOpen)
            else -> return@LaunchedEffect
        }
        pendingPageAction = null
    }

    // 3) 安全区补偿：量出「状态栏下沿 → WebView 可视区顶边」的差（= 页面被状态栏压住的高度）。
    //    实测这里 WebView **已经在状态栏下方**（uiautomator: bounds=[0,125][1200,2640]），
    //    所以差值是 0、页面不需要补偿；真正的坑是以前用 in-window 坐标算成了「要补 125px」，
    //    页面于是多补一次安全区、顶栏被整体下推一大截。测量与踩坑细节见
    //    [LinkWebViewRegistry.measureStatusBarTopPx]，这里只负责触发重测。
    LaunchedEffect(active, currentUrl, currentIsDsh) {
        if (!active) return@LaunchedEffect
        LinkWebViewRegistry.refreshStatusBarTopPx(currentUrl)
    }

    // 3b) 主动要求页面重报一次（切回该页时）。
    //     页面侧上报是按"变化"去重的：页面在别的 Tab 停留一段时间后切回来，主题没变就不会重发，
    //     宿主手里的 dshPageDark 可能过期 → 状态栏图标会闪回 App 主题。
    //     只对"已知 DSH 页面"ping；未辨识过的页面脚本可能还没装上，ping 也无副作用。
    LaunchedEffect(active, currentUrl) {
        val url = currentUrl ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        if (dshPages[url] != true && !store.isDshPage(url)) return@LaunchedEffect
        val wv = LinkWebViewRegistry.getWebView(url) ?: return@LaunchedEffect
        wv.post { runCatching { wv.evaluateJavascript(DshPageAdapter.buildPingScript(), null) } }
    }

    // 3c) zcode 工作台页面：切回该页/Tab 时主动要求重报一次（cap 去重同 DSH）。
    //     用 host 侧 isZcode(url) 判定（而非 zcodeTitlebarPages[url]），否则当 map 已被误置
    //     false 时反而不会 ping，悬浮面板卡在"显示"态。ping 会重置脚本内 lastCap 强制重报，
    //     让 isZcodeTitlebar 与「按钮已注入标题栏」状态及时复位。
    LaunchedEffect(active, currentUrl) {
        val url = currentUrl ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        if (!isZcode(url)) return@LaunchedEffect
        val wv = LinkWebViewRegistry.getWebView(url) ?: return@LaunchedEffect
        wv.post { runCatching { wv.evaluateJavascript(ZcodePageAdapter.buildPingScript(), null) } }
    }

    // 4) 状态栏自适应：**底色 + 图标明暗**都跟着 DSH 页面走，离开该页 / 离开 Link Tab 还原 App 主题。
    //
    //    为什么必须染底色：本 App 不是 edge-to-edge（实测窗口被状态栏裁掉，WebView 从状态栏下沿
    //    才开始），状态栏是 App 用 `window.statusBarColor` 实绘的一条不透明色带，页面颜色**不会**
    //    自己透上来。「App 深色主题 + 页面浅色」时那条带子就是一条割裂的黑边——这正是用户反馈的
    //    「状态栏还是黑色」。所以这里主动把状态栏染成页面底色。
    //
    //    覆盖值写进 StatusBarOverride 而不是在这里直接 setColor：LunaShareTheme 的 SideEffect
    //    每次重组都会刷状态栏，两边直接抢会互相打架、随机闪烁（见 StatusBarOverride 注释）。
    LaunchedEffect(active, currentUrl, currentIsDsh, dshPageDark, dshPageBg) {
        if (!active || !currentIsDsh) {
            StatusBarOverride.clear()
            return@LaunchedEffect
        }
        val reportedColor = DshPageAdapter.parseBg(dshPageBg)
        if (reportedColor == null && dshPageDark == null) {
            // 页面还没回报任何外观信息：先维持 App 主题色，别瞎猜（免得先闪一个错色）
            StatusBarOverride.clear()
            return@LaunchedEffect
        }
        // 页面底色优先；未回报底色时按明暗兜底（与注入脚本的兜底色保持一致）
        val pageColor = reportedColor
            ?: if (dshPageDark == false) 0xFFFFFFFF.toInt() else 0xFF1B1B1C.toInt()
        val pageDark = dshPageDark ?: (Color(pageColor).luminance() < 0.5f)
        StatusBarOverride.apply(color = pageColor, darkPage = pageDark)
    }
    // 组合销毁兜底清空：避免覆盖值泄漏到其它 Tab（LinkScreen 是常驻组合，但 Activity 重建时会重建）
    DisposableEffect(Unit) { onDispose { StatusBarOverride.clear() } }

    // 5) 上报「当前是否 DSH 页面」：MainScreen 据此隐藏悬浮胶囊面板（按钮已注入页面顶栏）
    LaunchedEffect(active, currentUrl, currentIsDsh) {
        onDshPageChanged(active && currentUrl != null && currentIsDsh)
    }
    // 5b) 上报「当前是否 zcode 工作台」：MainScreen 据此隐藏悬浮胶囊面板（按钮已注入标题栏右侧）
    LaunchedEffect(active, currentUrl, currentIsZcodeTitlebar) {
        onZcodeTitlebarChanged(active && currentUrl != null && currentIsZcodeTitlebar)
    }
    // 打开 Link Tab 即申请通知权限（targetSdk 33+ 需运行时授权，否则系统通知不显示）。
    // 不只卡在 zcode 页：只要 Link Tab 处于激活态且尚未授权就弹窗，覆盖用户"打开 link 没申请"的反馈。
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权结果仅影响后续通知是否展示，无需额外处理 */ }
    LaunchedEffect(active) {
        if (active && Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // 缩放复位信号（来自 MainScreen 底部栏按钮）：每次自增触发一次，把当前 WebView 缩回 fit 屏幕
    LaunchedEffect(resetZoomSignal) {
        if (resetZoomSignal > 0) {
            currentUrl?.let { LinkWebViewRegistry.getWebView(it)?.let { wv -> resetWebViewZoom(wv) } }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            onFullscreenChanged(false)
            onWebPageChanged(false)
        }
    }

    // 打开/切换到指定地址：已开过 → 只切显示层不重载；未开过 → 加入 openedUrls，由 AndroidView 创建并加载
    fun openUrl(target: String) {
        val u = LinkStore.normalizeUrl(target)
        if (u.isEmpty()) return
        store.setLastAddress(u)
        store.addAddress(u)
        refreshAddresses()
        pendingReturnUrl = null
        if (!openedUrls.contains(u)) openedUrls.add(u)
        LinkWebViewRegistry.currentUrl = u
        currentUrl = u
    }
    fun doConnect(target: String) = openUrl(target)

    // ── 扫码连接（ZXing Android Embedded，竖屏扫码页参考 HermesMobile）── 扫到即填入并连接
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            val u = LinkStore.normalizeUrl(contents)
            addressInput = contents.trim()
            if (u.isNotEmpty()) {
                doConnect(u)
            } else {
                Toast.makeText(context, "二维码内容不是有效地址", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "已取消扫码", Toast.LENGTH_SHORT).show()
        }
    }

    // 侧边栏宽度 = 屏幕 80%
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val drawerWidth = (screenWidthDp * 0.8f).dp

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 多 WebView 层：每个已打开地址一个常驻组合（key 稳定插槽，隐藏不销毁，切换不重新加载）──
        // 关键：**不能**用 zIndex/alpha 动态切换——那会触发 ComposeView 重排子 View（detach/attach），
        // WebView 在 attach 后会被系统重新加载（表现为进度条重现 + 页面卡住）。
        // 只靠 AndroidView update 里的 visibility（INVISIBLE/VISIBLE）控制显示：View 树顺序固定、
        // 始终 attached，切换只改 visibility，页面状态原样保留。
        val progressColor = MaterialTheme.colorScheme.primary.toArgb()
        openedUrls.forEach { u ->
            // key 里带 themeVersion：App 亮/暗一变，**非 DSH** 页面要重建整个 WebView
            // （prefers-color-scheme 来自建 WebView 时的 Context，运行期改不了）。
            // DSH 页面不重建——它靠 <body data-ds-dark-theme> 切色板，宿主实时下发即可，
            // 重建会重载页面、掐掉正在跑的会话。注册表里实例还在，这里只会 re-attach。
            key(u, themeVersion) {
                val isActive = u == currentUrl
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .navigationBarsPadding()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val container = android.widget.FrameLayout(ctx)
                            // 从常驻注册表取（或创建）WebView 实例；已存在则复用、不重载
                            val entry = LinkWebViewRegistry.obtain(ctx, u, allowInsecureState.value)
                            val wv = entry.webView
                            // 从旧容器 detach（Activity 重建时旧 container 已销毁，父节点可能失效），
                            // 再挂到本次组合的新容器
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            container.addView(
                                wv,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                            container
                        },
                        update = { container ->
                            // 显隐条件：必须同时看 active（当前是否在 Link Tab）和 isActive（是否为当前激活链接）。
                            // 只判断 isActive 不够——在「文件/服务」页时只要之前打开过链接 currentUrl 就非空，
                            // 容器会是 VISIBLE，而 TabLayer 靠祖先 graphicsLayer{alpha=0f} 隐藏非当前 Tab，
                            // 但 WebView 渲染到独立 Surface，祖先 graphicsLayer 的 alpha 盖不住它，
                            // 于是该 Surface 会穿透显示在顶层（App 从后台返回、Surface 重建后尤其明显，
                            // 表现为「网页强行跑到前台 / 闪一下」）。用 View 自身 visibility 控制最可靠：
                            // 保持 attached 不触发 detach/attach 重排 → 切回时不重新加载页面。
                            val show = active && isActive
                            // 不能用 INVISIBLE 隐藏 WebView 容器：当网页里的链接让 WebView 持焦后，
                            // setVisibility(INVISIBLE) 会触发 Android 自动清焦点，撞上 Compose focus 校验
                            // 抛 IllegalArgumentException("ActiveParent with no focused child") → 闪退。
                            // 改用 translationX 把容器整体移出屏幕：WebView 独立 Surface 随之移走、不再
                            // 穿透显示在其它 Tab（问题1 同样解决），且不触发清焦点，不会崩；保持 attached
                            // 不触发 detach/attach 重排 → 切回 Link 时页面不重新加载。
                            container.translationX = if (show) 0f else 10000f
                            container.visibility = android.view.View.VISIBLE
                        }
                    )
                    // 进度条（每个 WebView 自带，覆盖在内容区顶部；从注册表进度流观测）
                    LinkWebViewRegistry.getEntry(u)?.let { entry ->
                        val progress by entry.progress.collectAsState()
                        if (isActive && progress in 1..99) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .align(Alignment.TopCenter),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                    DisposableEffect(u) {
                        onDispose {
                            // 实例常驻：组合消失（Tab 切换 / Activity 重建）**不销毁** WebView，
                            // 仅由 AndroidView 自动 detach；真正删除连接时由 LinkWebViewRegistry.destroy(u) 销毁。
                            // 若此处调用 destroy，Activity 重建会杀掉存活的 WebView → 回到前台重新加载。
                        }
                    }
                }
            }
        }

        // 切换激活链接（或切 Tab）：暂停后台 WebView、恢复前台 WebView。
        // 若恢复时页面内容已被系统回收（webView.url 为空），重新 loadUrl 保证能正常显示
        LaunchedEffect(currentUrl, active) {
            openedUrls.forEach { url ->
                val wv = LinkWebViewRegistry.getWebView(url) ?: return@forEach
                if (active && url == currentUrl) {
                    wv.onResume()
                    if (wv.url.isNullOrEmpty()) {
                        wv.loadUrl(url)
                    }
                } else {
                    // 只暂停渲染（webView.onPause），**不要** pauseTimers()：
                    // 它会暂停所有 WebView 共享的 JS 定时器——依赖心跳定时器的
                    // 页面（如 zcode 的 WebSocket）在恢复时会误判断线触发重连
                    wv.onPause()
                }
            }
        }

        // ── 主页 / 连接页（currentUrl == null 时显示；标题栏 + 内容从顶部排列）──
        if (currentUrl == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                // ── 标题栏（与设置页一致：TopAppBar，返回按钮在标题栏位置）──
                TopAppBar(
                    title = { Text("连接设备") },
                    navigationIcon = {
                        if (pendingReturnUrl != null) {
                            IconButton(onClick = {
                                currentUrl = pendingReturnUrl
                                pendingReturnUrl = null
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                            }
                        }
                    }
                )
                // ── 内容区：从标题栏下方开始 ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(8.dp))
                    // 连接图标（登录页头部）
                    Icon(
                        Icons.Default.Link,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "输入访问地址或扫码，连接你的设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                // ── 表单卡片 ──
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            label = { Text("地址") },
                            placeholder = { Text("http://192.168.1.10:8080") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { doConnect(addressInput) }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scanLauncher.launch(
                                        ScanOptions()
                                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                            .setPrompt("将二维码对入框中扫码连接")
                                            .setBeepEnabled(true)
                                            .setOrientationLocked(false)
                                            .setCaptureActivity(PortraitCaptureActivity::class.java)
                                    )
                                },
                                modifier = Modifier.weight(0.3f)
                            ) {
                                Icon(Icons.Default.PhotoCamera, null)
                            }
                            Button(
                                onClick = { doConnect(addressInput) },
                                modifier = Modifier.weight(0.7f)
                            ) {
                                Text("连接")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // ── 选项开关 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("允许不安全证书 (http/自签名)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = allowInsecureState.value, onCheckedChange = {
                        allowInsecureState.value = it
                        store.allowInsecureCert = it
                    })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保持屏幕常亮", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = keepScreenOn, onCheckedChange = {
                        keepScreenOn = it
                        store.keepScreenOn = it
                    })
                }
                    // ── 已保存连接：固定高度 + 超出滚动 + 卡片间隙 ──
                    if (addresses.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "已保存的连接",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(addresses) { addr ->
                                // 自定义圆角卡片（与侧边栏一致）：Row 垂直居中，favicon + 标题/地址 + 删除
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { doConnect(addr) }
                                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // favicon 网页图标（无则默认 Link 图标）
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            linkFavicons[addr]?.let { bmp ->
                                                Image(
                                                    bitmap = bmp.asImageBitmap(),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .clip(CircleShape)
                                                )
                                            } ?: Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                linkTitles[addr] ?: store.getTitle(addr),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                addr,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = { pendingDeleteAddress = addr }) {
                                            Icon(
                                                Icons.Default.Close,
                                                "删除连接",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 左侧抽屉（scrim + 左滑入面板，屏幕 80% 宽）──
        // 方向约定：LunaShare 自己的「连接」抽屉从**左**滑入；DSH 会话侧边栏（页面自管）
        // 从**右**滑入。两者分居两侧，互不抢位，也避免"按钮在右、面板从左出"的错位感。
        val scrimAlpha by animateFloatAsState(
            targetValue = if (drawerOpen) 0.5f else 0f,
            label = "drawerScrim"
        )
        if (scrimAlpha > 0f || drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                    .clickable(enabled = drawerOpen) { closeDrawer() }
            )
        }
        AnimatedVisibility(
            visible = drawerOpen,
            modifier = Modifier.fillMaxSize().zIndex(2f),
            enter = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(250)) +
                fadeIn(animationSpec = tween(250)),
            exit = slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(250)) +
                fadeOut(animationSpec = tween(250))
        ) {
            // 注意：align 必须由真正的 Box 父级承载，AnimatedVisibility 内部不是 Box，
            // 所以外层包 Box + contentAlignment 才能让抽屉面板真正贴左停靠
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart
            ) {
                Surface(
                    shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "连接",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Divider()
                        // ── 下载区：侧边栏固定区块，展示下载任务与进度条 ──
                        DownloadSection()
                        Divider()
                        LazyColumn(
                            contentPadding = PaddingValues(8.dp),
                            // 固定占满剩余高度，链接超出时列表内滚动（不会撑破抽屉/顶掉底部按钮）
                            modifier = Modifier.weight(1f)
                        ) {
                            items(addresses) { addr ->
                                // 圆角矩形卡片（仿 HermesMobile address_card：12dp 圆角 + 删除按钮）
                                // 当前激活的链接高亮（primaryContainer），其余 surfaceVariant
                                val selected = addr == currentUrl
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // 点击：打开连接；长按：弹出「通知行为 / 重命名」菜单
                                            .combinedClickable(
                                                onClick = {
                                                    openUrl(addr)
                                                    closeDrawer()
                                                },
                                                onLongClick = { actionSheetAddress = addr }
                                            )
                                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // favicon 网页图标（无则用默认 Link 图标）
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            linkFavicons[addr]?.let { bmp ->
                                                Image(
                                                    bitmap = bmp.asImageBitmap(),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .clip(CircleShape)
                                                )
                                            } ?: Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            // 标题（网页标题，如"百度一下，你就知道"）+ 地址两行显示
                                            Text(
                                                linkTitles[addr] ?: store.getTitle(addr),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                        else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                addr,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        // 「通知行为」入口：该页面支持通知（已有配置，或本身是 zcode 这类已知支持
                                        // 通知的页面）时才显示。完全不支持通知的普通网页这里留空。
                                        val notifyKey = NotifySettingsStore.keyOf(addr)
                                        if (notifyStore.hasConfig(notifyKey) || isZcode(addr)) {
                                            IconButton(
                                                onClick = { notifyConfigAddress = addr }
                                            ) {
                                                Icon(
                                                    Icons.Default.Notifications,
                                                    "通知行为",
                                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                           else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        IconButton(onClick = { pendingDeleteAddress = addr }) {
                                            Icon(
                                                Icons.Default.Close,
                                                "删除连接",
                                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                       else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Divider()
                        // 底部操作按钮：导航栏 inset 避让（旧底部栏已移除，仅留导航栏安全区）
                        Column(
                            Modifier
                                .navigationBarsPadding()
                                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                        ) {
                            Button(
                                onClick = {
                                    pendingReturnUrl = currentUrl
                                    currentUrl = null
                                    closeDrawer()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(6.dp))
                                Text("添加连接")
                            }
                        }
                    }
                }
            }
        }

        // 屏幕常亮：仅 Link Tab 可见时生效
        if (active) {            DisposableEffect(keepScreenOn) {
                val act = context as? Activity
                if (keepScreenOn) {
                    act?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    act?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        // 系统返回：抽屉开→关抽屉；WebView 可回退→回退；WebView 无回退→断开回主页；主页(添加连接)→回 WebView
        val activeWeb = currentUrl?.let { LinkWebViewRegistry.getWebView(it) }
        BackHandler(enabled = active && (drawerOpen || currentUrl != null || pendingReturnUrl != null)) {
            when {
                drawerOpen -> closeDrawer()
                currentUrl != null && activeWeb?.canGoBack() == true ->
                    activeWeb?.goBack()
                currentUrl != null -> {
                    currentUrl = null
                    pendingReturnUrl = null
                }
                pendingReturnUrl != null -> {
                    currentUrl = pendingReturnUrl
                    pendingReturnUrl = null
                }
            }
        }

        // 删除连接二次确认（侧边栏卡片 x / 主页已存连接 x 共用）
        pendingDeleteAddress?.let { addr ->
            AlertDialog(
                onDismissRequest = { pendingDeleteAddress = null },
                title = { Text("删除连接") },
                text = { Text("确定删除「$addr」吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        store.removeAddress(addr)
                        refreshAddresses()
                        if (currentUrl == addr) {
                            currentUrl = null
                            pendingReturnUrl = null
                        }
                        openedUrls.remove(addr)
                        // 真正销毁 WebView 实例（detach + destroy + 移除注册表项），避免常驻单例内存泄漏
                        LinkWebViewRegistry.destroy(addr)
                        linkTitles.remove(addr)
                        linkFavicons.remove(addr)
                        pendingDeleteAddress = null
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteAddress = null }) {
                        Text("取消")
                    }
                }
            )
        }

        // ── 长按侧边栏项目 → 操作菜单（通知行为 / 重命名）──
        actionSheetAddress?.let { addr ->
            ModalBottomSheet(onDismissRequest = { actionSheetAddress = null }) {
                Column(Modifier.navigationBarsPadding()) {
                    ListItem(
                        headlineContent = { Text(linkTitles[addr] ?: store.getTitle(addr)) },
                        supportingContent = { Text(addr, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("通知行为") },
                        supportingContent = { Text("配置该页面的通知时机与方式") },
                        leadingContent = { Icon(Icons.Default.Notifications, null) },
                        modifier = Modifier.clickable {
                            notifyConfigAddress = addr
                            actionSheetAddress = null
                        }
                    )
                    ListItem(
                        headlineContent = { Text("重命名") },
                        leadingContent = { Icon(Icons.Default.Edit, null) },
                        modifier = Modifier.clickable {
                            renameAddress = addr
                            actionSheetAddress = null
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ── 通知行为配置弹窗（按页面维度；无配置时以默认值打开，保存后即产生配置）──
        notifyConfigAddress?.let { addr ->
            val key = NotifySettingsStore.keyOf(addr)
            val hasCfg = notifyStore.hasConfig(key)
            NotifySettingsDialog(
                pageLabel = linkTitles[addr] ?: store.getTitle(addr),
                settings = notifyStore.getOrDefault(key),
                hasConfig = hasCfg,
                onSave = { newCfg ->
                    val oldCfg = notifyStore.getOrDefault(key)
                    notifyStore.save(key, newCfg)
                    // 关掉「需要确认」提醒（或总开关）→ 已经挂在通知中心的那几条待确认也一并收走，
                    // 否则用户会以为开关没生效（通知还挂在那儿）。
                    if ((oldCfg.enabled && !newCfg.enabled) ||
                        (oldCfg.notifyOnConfirm && !newCfg.notifyOnConfirm)
                    ) {
                        ZcodeNotifier.clearPendingAsks(context)
                    }
                    notifyCfgVersion++
                    notifyConfigAddress = null
                },
                onDismiss = { notifyConfigAddress = null },
                // 清除配置：删除该页面的配置，回到「无配置」空状态（zcode 页仍会显示入口，可重新配置）
                onClear = {
                    notifyStore.clear(key)
                    notifyCfgVersion++
                    notifyConfigAddress = null
                }
            )
        }

        // ── 重命名弹窗：自定义显示名称（存 LinkStore.title_<url>；网页标题加载完不再覆盖它）──
        renameAddress?.let { addr ->
            RenameDialog(
                original = addr,
                currentName = linkTitles[addr] ?: store.getTitle(addr),
                onSave = { newName ->
                    store.setCustomTitle(addr, newName)
                    linkTitles[addr] = newName
                    renameAddress = null
                },
                onDismiss = { renameAddress = null }
            )
        }

        // 注：zcode 远程模式的「缩放复位」、返回、侧边栏按钮已统一收到右侧悬浮胶囊面板
        // （LinkFloatingPanel，纯图标、可拖动、默认吸右），不再用底部文字栏/悬浮工具栏
        // （按需求去掉「断开连接」按钮，避免遮挡 zcode 自带交互，也更沉浸）。
    }
}

// ── 侧边栏抽屉「下载」区：显示下载路径 + 任务列表（进度条 / 取消 / 打开 / 重试）──
@Composable
private fun DownloadSection() {
    val context = LocalContext.current
    val downloadTasks by LinkDownloadManager.tasks.collectAsState()
    val downloadDirState by LinkDownloadManager.downloadDir.collectAsState()
    LaunchedEffect(Unit) { LinkDownloadManager.init(context) }

    // 打开文件与文件页一致：默认应用记忆存于同一 prefs（file_open_prefs）
    val openPrefs = remember { context.getSharedPreferences("file_open_prefs", Context.MODE_PRIVATE) }
    // 未记忆默认应用时弹「打开方式」选择器（同文件页 AppChooserDialog）
    var openChooserFile by remember { mutableStateOf<File?>(null) }

    val runningCount = downloadTasks.count { it.status == DownloadStatus.RUNNING }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("下载", style = MaterialTheme.typography.titleSmall)
            Text(
                if (runningCount > 0) "$runningCount 个进行中" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            downloadDirState,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        if (downloadTasks.isEmpty()) {
            Text(
                "暂无下载，网页里点下载链接会出现在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(downloadTasks, key = { it.id }) { task ->
                    DownloadTaskRow(
                        task = task,
                        onClickOpen = {
                            val path = task.outPath
                            if (path != null) {
                                val file = File(path)
                                if (file.exists()) {
                                    // 与文件页一致：已记忆默认应用直接打开，否则弹「打开方式」选择器
                                    val extension = file.extension.lowercase()
                                    val defaultApp = openPrefs.getString("default_app_$extension", null)
                                    if (defaultApp != null) openFileSilently(context, file, defaultApp)
                                    else openChooserFile = file
                                }
                            }
                        },
                        onCancel = { LinkDownloadManager.cancel(task.id) },
                        onRetry = {
                            // 重试 = 用原 URL / UA / MIME 重新发一个下载任务（文件名自动去重 (1)(2)）
                            LinkDownloadManager.start(
                                context, task.url, task.userAgent, null, task.mimeType, -1
                            )
                        },
                        onRemove = { LinkDownloadManager.remove(task.id) }
                    )
                }
            }
        }
    }

    // 未记忆默认应用时弹「打开方式」选择器（与文件页共用实现）
    openChooserFile?.let { file ->
        AppChooserDialog(
            file = file,
            prefs = openPrefs,
            context = context,
            onDismiss = { openChooserFile = null }
        )
    }
}

// ── 单个下载任务行：文件名 + 状态/大小 + 进度条 + 右侧操作按钮 ──
// 完成后无按钮：点击整个条目行打开文件（打开方式与文件页一致）
@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onClickOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val rowModifier =
        if (task.status == DownloadStatus.COMPLETED) Modifier.clickable(onClick = onClickOpen)
        else Modifier
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).then(rowModifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (task.status) {
                    DownloadStatus.RUNNING -> Icons.Default.Download
                    DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
                    DownloadStatus.FAILED -> Icons.Default.Error
                    DownloadStatus.CANCELED -> Icons.Default.Cancel
                },
                null,
                tint = when (task.status) {
                    DownloadStatus.RUNNING, DownloadStatus.COMPLETED ->
                        MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    downloadStatusText(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (task.status) {
                DownloadStatus.RUNNING -> IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "取消下载", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 完成后不再放按钮：点击整个条目行打开文件（见 DownloadTaskRow 的 rowModifier）
                DownloadStatus.COMPLETED -> {}
                else -> Row {
                    if (task.status == DownloadStatus.FAILED) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, "重试", Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "移除", Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 仅下载中的任务显示进度条：长度未知时走不确定模式
        if (task.status == DownloadStatus.RUNNING) {
            if (task.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

private fun downloadStatusText(task: DownloadTask): String {
    val size = formatDownloadSize(task.downloadedBytes)
    return when (task.status) {
        DownloadStatus.RUNNING ->
            if (task.totalBytes > 0) "$size / ${formatDownloadSize(task.totalBytes)}" else size
        DownloadStatus.COMPLETED -> "已完成 · $size"
        DownloadStatus.FAILED -> "失败：${task.error ?: "未知错误"}"
        DownloadStatus.CANCELED -> "已取消"
    }
}

private fun formatDownloadSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
