package com.lunashare.app.link

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.net.Uri
import android.net.http.SslError
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lunashare.app.R
import com.lunashare.app.link.LinkDownloadManager
import com.lunashare.app.link.ZcodeEventBridge
import com.lunashare.app.link.ZcodePageAdapter
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Link Tab WebView 实例常驻容器（方案 B：跨 Activity 重建不重载）。
 *
 * 背景：原实现把 WebView（经 ByWebView）放在 Composable 的 [androidx.compose.runtime.remember] 里，
 * 由于 ByWebView 用 Activity context 创建并紧绑旧 Activity，Activity 因内存回收销毁重建时，
 * 整个 Composition 连同 WebView 实例一起被销毁，重建后重新 loadUrl → 页面重载（zcode 远程桌面会重连）。
 *
 * 本注册表改用 [Context.getApplicationContext] 创建 WebView，实例随本单例（进程在即在）存活；
 * Activity 重建时只把同一实例重新挂回容器，页面状态原样保留。文件上传走
 * [WebChromeClient.onShowFileChooser] + MainActivity 注册的 ActivityResultLauncher，不依赖旧 Activity，
 * 重建后不崩。进度 / 标题 / favicon 经 StateFlow 与回调上抛给 LinkScreen 的 Compose 状态。
 *
 * 注：单例仅存活于进程内；进程被系统杀死则 WebView 实例丢失（所有 App 均如此，不可避免）。
 */
object LinkWebViewRegistry {

    /** 当前激活地址（null = 主页/连接页）；跨重建恢复用 */
    var currentUrl: String? = null
    /** 已打开地址列表（伪标签页）；跨重建恢复用 */
    val openedUrls = mutableListOf<String>()

    /** 当前 Activity（文件选择 intent 启动等需 Activity 的场景）；重建时更新 */
    @Volatile var currentActivity: Activity? = null
    /** 当前是否处于 Link Tab 激活态（供页面完成回调判定 zcode 注视状态） */
    var active: Boolean = true

    // ─────────────────────────────────────────────────────────────────────────
    //  宿主环境的两个"页面必须知道、但页面自己问不到"的状态：亮/暗 + 目标帧率
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * App 当前**生效**的深色主题（设置项优先，SYSTEM 时看系统）。由 MainActivity 解析后写入。
     *
     * 为什么必须由宿主明确告知：WebView 不会猜 App 的主题，它只读两样东西——
     *  1. 建 WebView 时所用 Context 的 `Configuration.uiMode`（据此解析到 values-night 主题）；
     *  2. 该 Theme 的 `android:isLightTheme`。
     * 二者共同决定页面里的 `prefers-color-scheme`、以及"不支持深色的页面是否被算法变暗"。
     *
     * ⚠️ 本 App 的主题是**用户设置项**（可强制亮/暗）而不是系统日夜，所以不能直接拿
     * applicationContext 建 WebView——那样页面只会跟随系统，与 App 想显示的主题不一致。
     *
     * ⚠️ uiMode 是烘进 Context 的，运行期改不了 → 主题变更只能**重建 WebView**
     * （见 [recreateAll]，LinkScreen 用 [themeVersion] 当 key）。
     */
    @Volatile
    var darkMode: Boolean = false
        private set

    /**
     * 主题版本号：每变一次自增。LinkScreen 拿它当 AndroidView 的 key，
     * 一变就重建 WebView（uiMode 无法原地改），代价是页面重新 loadUrl。
     */
    val themeVersion = MutableStateFlow(0)

    /** 目标刷新率（面板最高刷新率；≤60 表示不请求）。由 MainActivity 实测后写入。 */
    @Volatile
    var targetFrameRate: Float = 0f
        private set

    /** 设置生效主题；变化时同步已存在的 WebView 并把主题版本号推给 UI 层。 */
    fun setDarkMode(dark: Boolean) {
        if (darkMode == dark) return
        darkMode = dark
        // 两条路子，按"页面自己会不会读 prefers-color-scheme"分流：
        //  · DSH：色板由 `<body data-ds-dark-theme>` 驱动 → 实时下发即可，**绝不重建**。
        //    重建 = 重载页面 = 掐掉正在跑的会话（还会重连 WebSocket），代价太高。
        //  · 其他页面：prefers-color-scheme 烘在"建 WebView 时那个 Context"里，运行期改不了
        //    → 只能重建（见 [nightContext]）。
        val untouched = mutableListOf<String>()
        entries.forEach { (u, e) ->
            if (e.isDsh) {
                untouched += u
                runCatching {
                    e.webView.evaluateJavascript(DshPageAdapter.buildHostThemeScript(dark), null)
                }
            }
        }
        val rebuild = entries.keys.filter { it !in untouched }
        if (rebuild.isNotEmpty()) {
            destroyEntries(rebuild)
            // 版本号一变，LinkScreen 就用新 key 重新组合 → 非 DSH 页面按新主题重建；
            // DSH 页面因注册表里实例还在，只 re-attach，页面状态原样保留。
            themeVersion.value = themeVersion.value + 1
        }
    }

    /** 设置目标刷新率；变化时就地推给已存在的 WebView。 */
    fun setTargetFrameRate(fps: Float) {
        if (targetFrameRate == fps) return
        targetFrameRate = fps
        entries.values.forEach { e -> applyHighFrameRate(e.webView, fps) }
    }

    /**
     * 销毁指定地址的 WebView 实例（主题变更时对"非 DSH"页面用）。
     *
     * **不动 `openedUrls` / `currentUrl`**：地址列表由 UI 层（LinkScreen）持有，
     * 环境变化把 [themeVersion] 一变，UI 就会用新 key 重新组合并重新 [obtain]，
     * 届时按同一批地址建出带新 uiMode 的实例。
     */
    private fun destroyEntries(urls: List<String>) {
        urls.forEach { u ->
            entries.remove(u)?.let { e ->
                (e.webView.parent as? ViewGroup)?.removeView(e.webView)
                runCatching { e.webView.destroy() }
            }
        }
    }

    /**
     * 造一个"告诉 WebView 现在该用亮色还是暗色"的 Context。
     *
     * 两件事**都要做**，缺一不可（实测 Android 16 / Chrome 138 WebView）：
     *
     *  1. **uiMode 覆盖**：`prefers-color-scheme` 与系统日夜的感知走 Context 的
     *     `Configuration.uiMode`。本 App 的主题是用户设置项（可强制亮/暗），可能与系统不一致，
     *     所以要显式覆盖成生效主题。
     *  2. **显式主题**：WebView 判定亮/暗读的是 Theme 的 `android:isLightTheme`。
     *     这里用 [ContextThemeWrapper] 直接指定 [R.style.Theme_LunaShare_Dark] /
     *     `Theme.LunaShare`，**不依赖 values-night 解析**——因为 isLightTheme 是 API 29
     *     属性，写进 values-night 会被 aapt2 的版本限定符处理弄丢（见 themes.xml 注释）。
     *
     * ⚠️ uiMode 与 Theme 都烘在 Context 里，运行期改不了 → 主题变更只能重建 WebView
     * （见 [recreateAll]，LinkScreen 用 [themeVersion] 当 key）。
     */
    private fun nightContext(base: Context, dark: Boolean): Context {
        return try {
            val themeRes = if (dark) R.style.Theme_LunaShare_Dark else R.style.Theme_LunaShare
            if (base is Activity) {
                // base 是 Activity：保留它的 window token——chromium 内部的 <select> 下拉
                // 弹窗（PopupWindow/Dialog）用 WebView 的 context 创建，在部分 ROM
                // （荣耀/华为魔改内核）上非 Activity context 会创建失败且被静默吞掉，
                // 表现为「点下拉框没反应」。uiMode 覆盖在这条路上让位给 token：
                // 主题仍由 ContextThemeWrapper 显式指定（isLightTheme 决定算法变暗），
                // App 主题与系统日夜不一致时 prefers-color-scheme 可能跟系统走，可接受。
                ContextThemeWrapper(base, themeRes)
            } else {
                val cfg = Configuration(base.resources.configuration)
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                ContextThemeWrapper(base.createConfigurationContext(cfg), themeRes)
            }
        } catch (_: Exception) {
            base
        }
    }

    /**
     * 亮/暗就位：决定"页面自带深色主题能不能生效"与"不支持深色的页面要不要被算法变暗"。
     *
     * - API 33+：`setAlgorithmicDarkeningAllowed(true)`。传 true 是**恒开**，不是"强制变暗"——
     *   Chromium 只在页面自身没有深色方案时才做算法变暗（策略 prefer-web-theme），
     *   所以像 DSH 这种自带深色主题的页面会用它自己的深色，不会被二次变暗。
     *   也正因为是"允许"而非"强制"，宿主为亮色时该开关不起作用（preferred scheme 是 light）。
     * - API 29~32：退回旧的 `setForceDark`。
     * - API < 29：无此能力（页面仍可按 prefers-color-scheme 走自己的深色方案）。
     */
    @Suppress("DEPRECATION")
    private fun applyDarkMode(wv: WebView, dark: Boolean) {
        runCatching {
            val s = wv.settings
            if (Build.VERSION.SDK_INT >= 33) {
                s.setAlgorithmicDarkeningAllowed(true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                s.forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
        // 画布底色跟着主题：页面从 loadUrl 到首帧之间 WebView 会先铺自己的底。
        // 不设的话深色主题下每次加载都先闪一下白，非常刺眼（页面 body 透明时也会露出白底）。
        runCatching {
            wv.setBackgroundColor(if (dark) 0xFF151517.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    /**
     * 让 WebView 也参与高刷新率请求。
     *
     * 实测（华为 SER-AN00 / Android 16 / 120Hz 面板）：只改
     * `window.attributes.preferredDisplayModeId` **不会**让面板真的切到高刷——
     * `dumpsys display` 里 `mActiveModeId` 一直停在 60Hz、`appRequest` 恒为
     * `(0.0 Infinity)`，说明请求没落到 SurfaceFlinger 的帧率投票上。
     * API 35+ 的 `View.setRequestedFrameRate` 会把请求上抛到最近一个"有 Surface 的祖先"
     * （也就是窗口 Surface），SF 的帧率投票才会把面板切上去。
     */
    fun applyHighFrameRate(wv: WebView, fps: Float) {
        if (fps <= 60f) return
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching { wv.setRequestedFrameRate(fps) }
        }
    }
    /** 当前 App 是否在前台 */
    var appInForeground: Boolean = true

    /** 文件选择请求转发给 MainActivity 注册的 launcher */
    var requestFileChooser: ((Intent) -> Unit)? = null
    /** 当前挂起的文件选择回调（onShowFileChooser 设置，launcher 结果回来 consume） */
    @Volatile private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val entries = mutableMapOf<String, Entry>()

    /** 单个地址的 WebView 持有条目 */
    class Entry(
        val webView: WebView,
        /** 本 WebView 的一次性口令：页面适配脚本回报时必须携带，防第三方页面伪造 */
        val nonce: String,
        var title: String? = null,
        var favicon: Bitmap? = null,
        val progress: MutableStateFlow<Int> = MutableStateFlow(0),
        /**
         * 页面已辨识为 DSH 控制台（由页面侧上报）。
         * 决定切主题时是"实时下发"还是"必须重建"——DSH 靠属性切色板，重建等于掐会话。
         */
        var isDsh: Boolean = false,
        /**
         * 页面已辨识为 zcode 工作台（标题栏存在，由页面侧上报）。
         * 决定原生右侧悬浮胶囊面板是否隐藏——按钮已注入 zcode 标题栏右侧。
         */
        var isZcodeTitlebar: Boolean = false,
    )

    fun getEntry(url: String?): Entry? = entries[url]
    fun getWebView(url: String?): WebView? = entries[url]?.webView
    fun contains(url: String): Boolean = entries.containsKey(url)

    /**
     * 取或创建某地址的 WebView 实例（跨重建复用）。
     * 已存在 → 直接返回（**不重新 loadUrl**，页面状态保留）；不存在 → 创建并 loadUrl。
     */
    fun obtain(context: Context, url: String, allowInsecure: Boolean): Entry {
        entries[url]?.let { return it }
        val nonce = DshPageAdapter.newNonce()
        // base 优先用当前 Activity（而非 applicationContext）：chromium 的 <select> 原生
        // 下拉弹窗等内部 UI 需要带 window token 的 Activity context，applicationContext
        // 会让弹窗创建失败（静默，页面表现为点下拉无反应）。WebView 实例本身仍常驻
        // 本注册表、跨 Activity 重建存活（Activity 销毁只多留一个引用，不触发销毁）。
        val base = currentActivity ?: context.applicationContext
        // 再包一层 [nightContext] 把生效主题烘进去（isLightTheme/算法变暗 + token 保留）
        val wv = WebView(nightContext(base, darkMode))
        configure(wv, url, allowInsecure, nonce)
        val entry = Entry(webView = wv, nonce = nonce)
        entries[url] = entry
        if (!openedUrls.contains(url)) openedUrls.add(url)
        wv.loadUrl(url)
        return entry
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView, url: String, allowInsecure: Boolean, nonce: String) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // 支持 http 页面的混合内容（zcode 远程桌面可能 http 资源），复刻 ByWebView 默认行为
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
            defaultTextEncodingName = "UTF-8"
        }
        // 亮/暗跟宿主走（页面 prefers-color-scheme + 算法变暗）
        applyDarkMode(wv, darkMode)
        // 高刷新率：window 侧属性对 WebView 的独立 Surface 不一定生效，这里再对 View 请求一次
        applyHighFrameRate(wv, targetFrameRate)
        // 渲染进程别被系统降级（WebView 可见时默认就是 IMPORTANT，显式声明避免后台切换后掉优先级）
        runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
        // 仅 debug 包打开 WebView 远程调试（chrome://inspect / CDP），release 保持关闭
        if ((wv.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
        wv.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                if (handler == null) return
                // 设置里开了「允许不安全证书」→ 一律放行（沿用旧行为）
                if (allowInsecure) {
                    handler.proceed()
                    return
                }
                // 会话内用户已对该主机选择「继续访问」→ 直接放行（含子资源，避免反复弹窗）
                val host = hostOf(error?.url)
                if (host != null && sslProceedHosts.contains(host)) {
                    handler.proceed()
                    return
                }
                val activity = currentActivity
                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    // 无可用 Activity（后台/重建间隙）→ 安全兜底：取消加载
                    handler.cancel()
                    return
                }
                showInsecureCertPrompt(activity, handler, error, host)
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                super.onPageFinished(view, loadedUrl)
                onPageFinishedListener?.invoke(url, view)
                // 页面适配层（自辨识：非 DSH 页面静默不动作）。页面重载会重置内联的
                // 安全区变量与注入按钮，故每次加载完成都要重新注入整段脚本。
                // 同时把「宿主当前明暗」带进去：DSH 色板不听 prefers-color-scheme，
                // 只认 <body data-ds-dark-theme>，必须由宿主显式贴上去。
                entries[url]?.let { e ->
                    try {
                        view?.evaluateJavascript(
                            DshPageAdapter.buildInjectScript(e.nonce, statusBarTopPx, darkMode), null
                        )
                    } catch (_: Exception) {
                    }
                    // zcode 工作台：把「主页 / 缩放复位 / 侧边栏」注入标题栏右侧，
                    // 让原生悬浮胶囊面板可以隐藏（与 DSH 移动端页同理）。
                    if (isZcode(url)) {
                        try {
                            view?.evaluateJavascript(
                                ZcodePageAdapter.buildInjectScript(e.nonce), null
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                entries[url]?.progress?.value = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    entries[url]?.title = title
                    onTitleUpdated?.invoke(url, title)
                }
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null) {
                    entries[url]?.favicon = icon
                    onFaviconUpdated?.invoke(url, icon)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                pendingFileCallback = filePathCallback
                requestFileChooser?.invoke(fileChooserParams.createIntent())
                return true
            }

            /**
             * 页面 → 宿主 的控制台消息通道（见 [DshPageAdapter]）。
             * 只认「前缀 + 一次性口令」都匹配的消息：命中则上抛并吞掉（不污染 Logcat），
             * 其余一律原样放行，不影响页面自身的 console 输出。
             */
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val text = msg?.message() ?: return false
                if (text.startsWith(DshPageAdapter.MSG_PREFIX)) {
                    val nonce = entries[url]?.nonce ?: return false
                    val parsed = DshPageAdapter.parse(text, nonce) ?: return false
                    parsed.capability?.let { cap -> entries[url]?.isDsh = (cap == DshPageAdapter.CAP_DSH) }
                    onPageMessage?.invoke(url, parsed)
                    return true
                }
                if (text.startsWith(ZcodePageAdapter.MSG_PREFIX)) {
                    val nonce = entries[url]?.nonce ?: return false
                    val parsed = ZcodePageAdapter.parse(text, nonce) ?: return false
                    parsed.capability?.let { cap ->
                        entries[url]?.isZcodeTitlebar = (cap == ZcodePageAdapter.CAP_ZCODE)
                    }
                    onZcodePageMessage?.invoke(url, parsed)
                    return true
                }
                return false
            }
        }
        wv.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, contentLength ->
            LinkDownloadManager.start(wv.context, dlUrl, userAgent, contentDisposition, mimeType, contentLength)
        }
        // 布局/窗口变化（旋转、分屏、状态栏显隐）会改变「状态栏压住页面的高度」→ 重测并同步给页面
        wv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            refreshStatusBarTopPx(url)
        }
        if (isZcode(url)) {
            // 仅 zcode 页面注入 JS 桥（持有 applicationContext，避免 Activity 泄漏）
            wv.addJavascriptInterface(ZcodeEventBridge(wv.context, url), "LunaZcode")
        }
    }

    /** MainActivity 文件选择结果回来时调用（Activity Result API） */
    fun consumeFileChooser(resultCode: Int, data: Intent?) {
        val cb = pendingFileCallback ?: return
        pendingFileCallback = null
        if (resultCode == Activity.RESULT_OK && data != null) {
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        } else {
            cb.onReceiveValue(null)
        }
    }

    /** 删除连接：从容器 detach → destroy WebView → 移除注册表项 */
    fun destroy(url: String) {
        entries.remove(url)?.let { e ->
            (e.webView.parent as? ViewGroup)?.removeView(e.webView)
            e.webView.destroy()
        }
        openedUrls.remove(url)
        if (currentUrl == url) currentUrl = null
    }

    // ── UI 回调（由 LinkScreen 在组合时设置，页面事件上抛给 Compose 状态）──
    var onTitleUpdated: ((String, String) -> Unit)? = null
    var onFaviconUpdated: ((String, Bitmap) -> Unit)? = null
    var onPageFinishedListener: ((String, WebView?) -> Unit)? = null

    /** 页面适配层回传的消息（见 [DshPageAdapter]）；参数为发出消息的 WebView 地址 */
    var onPageMessage: ((String, DshPageAdapter.PageMessage) -> Unit)? = null

    /** zcode 页面适配层回传的消息（见 [ZcodePageAdapter]）；参数为发出消息的 WebView 地址 */
    var onZcodePageMessage: ((String, ZcodePageAdapter.PageMessage) -> Unit)? = null

    /**
     * 当前需要为页面补偿的状态栏高度（px，0 = 无需补偿）。
     *
     * 含义：**状态栏下沿**与**WebView 可视区顶边**之差——即页面被状态栏压住的高度。
     * WebView 内 `env(safe-area-inset-top)` 在 Android WebView 上恒为 0，所以只能由宿主实测
     * 后写进页面 `--dsh-mobile-safe-top`（插件顶栏总高按 header-h + safe-top 计算）。
     *
     * ⚠️ 必须用 [android.view.View.getLocationOnScreen]（**绝对屏幕坐标**），
     * 不能用 `getLocationInWindow` + `insetTop` 相减：本机实测（Android 16 / 华为 SER-AN00，
     * 状态栏 125px）内容层在 in-window 坐标系里的 y 恒为 0，而 WebView 实际落在屏幕 y=125
     * （uiautomator 实测 `bounds=[0,125][1200,2640]`）。用 in-window 坐标会算成「需要补偿 125px」，
     * 页面于是**额外**补了一次安全区，顶栏被整体下推 38 CSS px —— 这正是「顶栏占了屏幕一大截」
     * 的成因（页面自己已经不在状态栏下面了，不该再补）。
     */
    @Volatile
    var statusBarTopPx: Int = 0

    /**
     * 实测某地址 WebView 需要补偿的状态栏高度；量不到（未挂载/未布局）时回退到上次值。
     */
    fun measureStatusBarTopPx(url: String?): Int {
        val wv = getWebView(url) ?: return statusBarTopPx
        if (wv.height <= 0 || !wv.isAttachedToWindow) return statusBarTopPx
        val insetTop = runCatching {
            ViewCompat.getRootWindowInsets(wv)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }.getOrDefault(0)
        if (insetTop <= 0) return 0
        val loc = IntArray(2)
        runCatching { wv.getLocationOnScreen(loc) }
        return (insetTop - loc[1]).coerceAtLeast(0)
    }

    /**
     * 重新测量并（变化时）把新值推给页面。返回最新值。
     *
     * 窗口/布局变化（旋转、分屏、状态栏显隐）都会改变这个差值，所以除了 onPageFinished，
     * 还挂了 layout 变化监听兜底。
     */
    fun refreshStatusBarTopPx(url: String?): Int {
        val measured = measureStatusBarTopPx(url)
        if (measured != statusBarTopPx) {
            statusBarTopPx = measured
            getWebView(url)?.let { wv ->
                wv.post {
                    runCatching {
                        wv.evaluateJavascript(DshPageAdapter.buildSafeAreaScript(measured), null)
                    }
                }
            }
        }
        return measured
    }

    // ── SSL 证书错误：浏览器式「不安全连接」确认弹窗 ─────────────────────────────
    // 会话内用户已选择「继续访问」的主机（进程存活期间有效，跨 WebView 实例共享）
    private val sslProceedHosts = mutableSetOf<String>()
    /** 当前是否已有 SSL 确认弹窗（同一页面的重定向链可能连续触发多次回调） */
    @Volatile private var sslDialogShowing = false

    /** 从 URL 提取主机名；解析失败返回 null */
    private fun hostOf(url: String?): String? = runCatching {
        url?.let { android.net.Uri.parse(it).host }
    }.getOrNull()

    /** 把 SslError 的错误码翻成人类可读的原因说明 */
    private fun sslReasonOf(error: SslError?): String = when (error?.primaryError) {
        SslError.SSL_NOTYETVALID -> "证书尚未生效"
        SslError.SSL_EXPIRED -> "证书已过期"
        SslError.SSL_IDMISMATCH -> "证书与站点域名不匹配"
        SslError.SSL_UNTRUSTED -> "证书由不受信任的机构颁发（自签名）"
        SslError.SSL_DATE_INVALID -> "证书日期无效"
        SslError.SSL_INVALID -> "证书无效"
        else -> "证书校验失败"
    }

    /**
     * 弹出浏览器风格的确认框：继续访问（并记住该主机）或返回（取消加载）。
     *
     * 必须保证 [SslErrorHandler.proceed]/[SslErrorHandler.cancel] 二者恰被调用一次：
     *  - 按钮点击里先置 [settled] 再调用对应方法；
     *  - 用户按返回键关掉对话框走 OnCancelListener → 兜底 cancel；
     *  - OnDismissListener 只负责清「弹窗展示中」标志（proceed 后 dismiss 也会走，不能 cancel）。
     * handler 允许在回调返回之后异步调用，弹窗挂起期间加载自然暂停。
     */
    private fun showInsecureCertPrompt(
        activity: Activity,
        handler: SslErrorHandler,
        error: SslError?,
        host: String?,
    ) {
        if (sslDialogShowing) {
            // 已有一个确认框在等待用户决策，后续回调（子资源/重定向）一律先取消
            handler.cancel()
            return
        }
        sslDialogShowing = true
        val site = host ?: hostOf(error?.url) ?: "未知站点"
        var settled = false
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("不安全的连接")
            .setMessage(
                "「$site」的安全证书存在问题（${sslReasonOf(error)}）。\n\n" +
                    "继续访问可能存在信息泄露风险，请确认这是你信任的站点。"
            )
            .setNegativeButton("返回") { _, _ ->
                settled = true
                handler.cancel()
            }
            .setPositiveButton("继续访问") { _, _ ->
                settled = true
                if (host != null) sslProceedHosts.add(host)
                handler.proceed()
            }
            .setOnCancelListener {
                // 用户按返回键 / 点外部关闭：必须取消加载，否则加载流程挂死
                if (!settled) handler.cancel()
            }
            .show()
        dialog.setOnDismissListener { sslDialogShowing = false }
    }

    /** 判断地址是否为 zcode 远程桌面/开发页面（与 LinkScreen.isZcode 保持一致） */
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
}
