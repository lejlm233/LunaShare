package com.lunashare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lunashare.app.R
import com.lunashare.app.adb.AdbTunnelConfig
import com.lunashare.app.adb.AdbTunnelManager
import com.lunashare.app.adb.AdbTunnelStateHolder
import com.lunashare.app.adb.AdbTunnelStore
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.frpc.FrpcBinaryManager
import com.lunashare.app.frpc.FrpcManager
import com.lunashare.app.frpc.OpenFrpApiClient
import com.lunashare.app.frpc.OpenFrpConfigStore
import com.lunashare.app.frpc.mefrp.MefrpApiClient
import com.lunashare.app.frpc.mefrp.MefrpConfigStore
import com.lunashare.app.frpc.mefrp.MefrpDnsHelper
import com.lunashare.app.frpc.model.OpenFrpProxy
import com.lunashare.app.model.ShareConfig
import com.lunashare.app.easytier.EasyTierStateHolder
import com.lunashare.app.MainActivity
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that manages file sharing services.
 *
 * For each share configuration, this service starts:
 *   - FileServer (HTTP/WebDAV) if httpEnabled
 *   - FtpServer (FTP) if ftpEnabled
 */
class ShareService : Service() {

    companion object {
        private const val TAG = "ShareService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "lunashare_service_channel"

        const val ACTION_START_SHARE = "com.lunashare.app.action.START_SHARE"
        const val ACTION_STOP_SHARE = "com.lunashare.app.action.STOP_SHARE"
        const val ACTION_STOP_ALL = "com.lunashare.app.action.STOP_ALL"
        const val ACTION_RESTART_SHARE = "com.lunashare.app.action.RESTART_SHARE"
        const val ACTION_RESTART_SMB = "com.lunashare.app.action.RESTART_SMB"
        // ── ADB 端口穿透（全局单例功能，独立于文件共享） ──
        const val ACTION_START_ADB_TUNNEL = "com.lunashare.app.action.START_ADB_TUNNEL"
        const val ACTION_STOP_ADB_TUNNEL = "com.lunashare.app.action.STOP_ADB_TUNNEL"
        // 连接共享（远程客户端）保活：有活跃远程连接时让服务保持前台，防止进程被杀断连
        const val ACTION_KEEPALIVE = "com.lunashare.app.action.REMOTE_KEEPALIVE"
        const val EXTRA_SHARE_ID = "share_id"
    }

    /** Tracks running HTTP server instances per share ID. */
    private val httpServers = ConcurrentHashMap<String, FileServer>()
    /** Tracks running FTP server instances per share ID. */
    private val ftpServers = ConcurrentHashMap<String, FtpServer>()

    /**
     * 「重启共享」进行中标志：重启必然经过"先全停"的瞬间，此时 httpServers/ftpServers 会短暂清空。
     * 若不拦住 stopSelfIfIdle() 的自杀逻辑，Service 会在 stopShare() 结束的瞬间停掉自己——
     * onDestroy 再停一遍刚起的 FileServer 并 cancel(scope)，表现为「只停止、没重启」。
     */
    private var restartingShares = java.util.concurrent.atomic.AtomicInteger(0)

    /** frpc tunnel manager for OpenFrp remote access. */
    private lateinit var frpcManager: FrpcManager
    /** OpenFrp account / binary config store. */
    private lateinit var openFrpStore: OpenFrpConfigStore
    /** OpenFrp REST client (used to self-heal tunnels whose proxyId was
     *  never persisted, e.g. a 0-id tunnel saved by an older build). */
    private val frpcApi = OpenFrpApiClient()
    /** mefrp（幻缘映射）凭证存储 + API 客户端（provider=mefrp 的隧道用）。 */
    private lateinit var mefrpStore: MefrpConfigStore
    private val mefrpApi = MefrpApiClient()
    /** 用系统 DNS 解析 mefrp 域名 → IP，注入 mefrpc 以绕过其内置 8.8.8.8 拦截。 */
    private val mefrpDns = MefrpDnsHelper
    /** frpc binary extractor / importer. */
    private lateinit var frpcBinaryManager: FrpcBinaryManager
    /** Observe frpc process state per proxyId and bridge into ShareStateHolder. */
    private var frpcObserverJob: Job? = null

    /** ADB 穿透配置持久化。 */
    private lateinit var adbTunnelStore: AdbTunnelStore
    /** ADB 隧道（frpc）是否在运行：阻止服务在无文件共享时误退出。 */
    private var adbTunnelRunning = false
    /** 观察 ADB 隧道 proxyId 的 frpc 状态，桥接到 AdbTunnelStateHolder。 */
    private var adbObserverJob: Job? = null

    /** 观察组网核心激活状态，驱动「组网开→暂停共享 frpc / 组网关→恢复」。 */
    private var networkObserverJob: Job? = null
    /** 被组网暂停、待组网关闭后恢复的共享 frpc 隧道 proxyId 集合（线程安全）。 */
    private val networkPausedProxyIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var configStore: ShareConfigStore

    /** mDNS 广播器：开共享时让同网设备用 lunashare.local 访问本机（开关见 ShareConfigStore.isMdnsEnabled）。 */
    private lateinit var mdnsBroadcaster: MdnsBroadcaster
    /** 是否正在广播 mDNS（原子，避免多 share 并发启停错乱）。 */
    private val mdnsRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        configStore = ShareConfigStore(this)
        openFrpStore = OpenFrpConfigStore(this)
        mefrpStore = MefrpConfigStore(this)
        adbTunnelStore = AdbTunnelStore(this)
        frpcBinaryManager = FrpcBinaryManager(this)
        frpcManager = FrpcManager(this)
        createNotificationChannel()
        mdnsBroadcaster = MdnsBroadcaster(this)
        acquireWakeLock()
        startFrpcStateObserver()
        startAdbTunnelStateObserver()
        startNetworkObserver()
        Log.d(TAG, "ShareService created")
    }

    // ── Frpc state bridge: FrpcManager → ShareStateHolder ────────

    private fun startFrpcStateObserver() {
        frpcObserverJob = scope.launch(Dispatchers.Default) {
            frpcManager.tunnelStates.collect { tunnelStates ->
                val configs = configStore.listConfigs()
                for (config in configs) {
                    val shareId = config.id

                    // Collect all enabled tunnels for this share
                    val tunnels = buildList {
                        config.httpTunnel?.takeIf { it.enabled }?.let { add("http" to it) }
                        config.ftpTunnel?.takeIf { it.enabled }?.let { add("ftp" to it) }
                        config.smbTunnel?.takeIf { it.enabled }?.let { add("smb" to it) }
                    }

                    for ((service, tunnel) in tunnels) {
                        val ts = tunnelStates[tunnel.proxyId]
                        if (ts != null) {
                            val state = ShareStateHolder.getState(shareId)
                            val wasRunning = when (service) {
                                "http" -> state.frpcHttpRunning
                                "ftp" -> state.frpcFtpRunning
                                "smb" -> state.frpcHttpRunning // reuse for smb
                                else -> false
                            }
                            if (ts.running != wasRunning) {
                                when (service) {
                                    "http" -> ShareStateHolder.setFrpcHttpRunning(shareId, ts.running)
                                    "ftp" -> ShareStateHolder.setFrpcFtpRunning(shareId, ts.running)
                                    "smb" -> ShareStateHolder.setFrpcHttpRunning(shareId, ts.running)
                                }
                            }
                            ts.logs.lastOrNull()?.let { lastLine ->
                                val state = ShareStateHolder.getState(shareId)
                                val existingLogs = when (service) {
                                    "http" -> state.frpcHttpLogs
                                    "ftp" -> state.frpcFtpLogs
                                    "smb" -> state.frpcHttpLogs
                                    else -> emptyList()
                                }
                                if (existingLogs.isEmpty() || existingLogs.last() != lastLine) {
                                    val toAppend = ts.logs.drop(existingLogs.size)
                                    when (service) {
                                        "http", "smb" -> toAppend.forEach { ShareStateHolder.addFrpcHttpLog(shareId, it) }
                                        "ftp" -> toAppend.forEach { ShareStateHolder.addFrpcFtpLog(shareId, it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LunaShare::WakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "WakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SHARE -> {
                val shareId = intent.getStringExtra(EXTRA_SHARE_ID) ?: return START_NOT_STICKY
                Log.d(TAG, "Received START_SHARE: $shareId")
                ensureForeground("正在启动共享服务...")
                scope.launch { startShare(shareId) }
            }
            ACTION_STOP_SHARE -> {
                val shareId = intent.getStringExtra(EXTRA_SHARE_ID) ?: return START_NOT_STICKY
                Log.d(TAG, "Received STOP_SHARE: $shareId")
                scope.launch { stopShare(shareId) }
            }
            ACTION_STOP_ALL -> {
                Log.d(TAG, "Received STOP_ALL")
                scope.launch { stopAll() }
            }
            ACTION_RESTART_SHARE -> {
                val shareId = intent.getStringExtra(EXTRA_SHARE_ID) ?: return START_NOT_STICKY
                Log.d(TAG, "Received RESTART_SHARE: $shareId")
                ensureForeground("正在应用配置并重启共享...")
                scope.launch { restartShare(shareId) }
            }
            ACTION_RESTART_SMB -> {
                Log.d(TAG, "Received RESTART_SMB (config changed)")
                scope.launch { restartSmb() }
            }
            ACTION_START_ADB_TUNNEL -> {
                Log.d(TAG, "Received START_ADB_TUNNEL")
                ensureForeground("正在启动 ADB 穿透隧道...")
                scope.launch { startAdbTunnel() }
            }
            ACTION_STOP_ADB_TUNNEL -> {
                Log.d(TAG, "Received STOP_ADB_TUNNEL")
                scope.launch { stopAdbTunnel() }
            }
            ACTION_KEEPALIVE -> {
                // 连接共享保活：有任何活跃组件（远程连接 / 正在运行的共享 / ADB 隧道）
                // 都必须保持前台。只有真正全空闲才撤通知退前台停服务——
                // ⚠️ 不能只看远程连接：这里 stopSelf() 会走 onDestroy 把还在运行的
                // FileServer 全部停掉，而 UI 状态残留「运行中」（实测环回远程连接
                // 一断开，本机共享端口就悄悄死亡、之后永远连接被拒）。
                val hasShareServers = httpServers.isNotEmpty() || ftpServers.isNotEmpty()
                if (com.lunashare.app.remote.RemoteConnectionManager.hasActive() ||
                    hasShareServers || adbTunnelRunning
                ) {
                    ensureForeground(FgNotificationHelper.combinedText())
                } else {
                    FgNotificationHelper.refresh(this)
                    runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
                    stopSelf()
                }
            }
            else -> {
                if (intent == null) {
                    Log.d(TAG, "Service restarted by system")
                    // Auto-start shares marked for auto-start
                    ensureForeground("正在恢复共享服务...")
                    scope.launch { startAutoStartShares() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startAutoStartShares() {
        val autoStartIds = configStore.getAutoStartIds()
        for (id in autoStartIds) {
            startShare(id)
        }
        // ADB 穿透是独立功能，可单独自启（即使没有任何文件共享）。
        // 用独立的 bootAutoStart 开关控制，避免每次开机都强制拉起用户未勾选自启的隧道。
        if (adbTunnelStore.loadConfig()?.bootAutoStart == true) {
            Log.i(TAG, "Auto-starting ADB tunnel (bootAutoStart enabled in config)")
            startAdbTunnel()
        }
        stopSelfIfIdle()
    }

    private fun ensureForeground(text: String) {
        FgNotificationHelper.updateShare(text)
        val notification = FgNotificationHelper.buildNotification(this)
        try {
            startForeground(FgNotificationHelper.NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    // ── Share lifecycle ──

    private suspend fun startShare(shareId: String) {
        val config = configStore.loadConfig(shareId) ?: run {
            Log.w(TAG, "Share config not found: $shareId")
            return
        }

        if (!config.hasAnyService()) {
            ShareStateHolder.setError(shareId, "没有启用任何服务或未设置文件夹路径")
            return
        }

        val rootDir = File(config.localDir)
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        // Start HTTP/WebDAV server
        if (config.httpEnabled && !httpServers.containsKey(shareId)) {
            Log.d(TAG, "Starting HTTP server for [$shareId] on port ${config.httpPort}")
            try {
                val fs = FileServer(
                    port = config.httpPort,
                    rootDir = config.localDir,
                    username = config.username,
                    password = config.password,
                    encPassword = config.encPassword,
                    shareId = config.id,
                    onUploadComplete = { fileName, size -> onFileTransferred(fileName, size) }
                )
                val result = fs.start()
                if (result.isSuccess) {
                    httpServers[shareId] = fs
                    ShareStateHolder.setHttpRunning(shareId, true)
                    ShareStateHolder.addHttpLog(shareId, "HTTP/WebDAV 服务器已启动 (端口 ${config.httpPort})")
                    Log.i(TAG, "HTTP server started for [$shareId] on port ${config.httpPort}")
                } else {
                    val err = result.exceptionOrNull()
                    ShareStateHolder.addHttpLog(shareId, "ERROR: HTTP 服务器启动失败: ${err?.message}")
                    Log.e(TAG, "HTTP server FAILED for [$shareId]: ${err?.message}")
                }
            } catch (e: Exception) {
                ShareStateHolder.addHttpLog(shareId, "ERROR: HTTP 服务器异常: ${e.message}")
                Log.e(TAG, "HTTP server exception for [$shareId]", e)
            }
        }

        // Start FTP server
        if (config.ftpEnabled && !ftpServers.containsKey(shareId)) {
            Log.d(TAG, "Starting FTP server for [$shareId] on port ${config.ftpPort}")
            try {
                val ftp = FtpServer(
                    port = config.ftpPort,
                    rootDir = rootDir,
                    username = config.username,
                    password = config.password
                )
                val result = ftp.start()
                if (result.isSuccess) {
                    ftpServers[shareId] = ftp
                    ShareStateHolder.setFtpRunning(shareId, true)
                    ShareStateHolder.addFtpLog(shareId, "FTP 服务器已启动 (端口 ${config.ftpPort})")
                    Log.i(TAG, "FTP server started for [$shareId] on port ${config.ftpPort}")
                } else {
                    val err = result.exceptionOrNull()
                    ShareStateHolder.addFtpLog(shareId, "ERROR: FTP 服务器启动失败: ${err?.message}")
                    Log.e(TAG, "FTP server FAILED for [$shareId]: ${err?.message}")
                }
            } catch (e: Exception) {
                ShareStateHolder.addFtpLog(shareId, "ERROR: FTP 服务器异常: ${e.message}")
                Log.e(TAG, "FTP server exception for [$shareId]", e)
            }
        }

        // Start SMB service (single global process for all SMB-enabled shares)
        if (config.smbEnabled) {
            startSmbIfNeeded()
        }

        // ── OpenFrp / frpc1: start tunnels for exposed services ────
        // 启动门槛按服务商：OpenFrp 隧道要求共享配置里的 openFrpFrpcToken（-u）非空；
        // mefrp 隧道的启动令牌来自 MefrpConfigStore（resolveFrpcToken → -t frpToken），
        // 与 openFrpFrpcToken 无关——只配了 mefrp 的共享该字段为空，若在此整体拦截，
        // mefrp 隧道会永远不启动（令牌缺失由 startFrpcTunnels 循环内逐隧道检查并如实报错）。
        val activeTunnels = config.activeTunnels()
        if (activeTunnels.isNotEmpty()) {
            if (EasyTierStateHolder.get().coreRunning) {
                // 组网已开启：公网隧道无意义且可能与组网冲突，跳过启动并登记待恢复
                activeTunnels.forEach { (_, t) -> networkPausedProxyIds.add(t.proxyId) }
                ShareStateHolder.addFrpcHttpLog(
                    shareId,
                    "组网已开启，已跳过公网隧道（关闭组网后自动恢复）"
                )
                Log.i(TAG, "组网已开启，跳过共享 frpc 隧道 [$shareId]（关闭组网后恢复）")
            } else {
                startFrpcTunnels(shareId, config, activeTunnels)
            }
        }

        // ── mDNS 广播（开共享后用 lunashare.local 让同网设备访问本机）──
        if (configStore.isMdnsEnabled()) maybeStartMdns(config)

        updateNotification()
    }

    // ── OpenFrp tunnel lifecycle ──────────────────────────────────

    private suspend fun startFrpcTunnels(
        shareId: String,
        config: ShareConfig,
        tunnels: List<Pair<String, com.lunashare.app.model.TunnelConfig>>
    ) {
        // Working copy we may repair in place when a tunnel was persisted with an
        // invalid (0) proxy id. Repairs are written back through configStore so the
        // fix survives the next restart (this is what makes autoStart self-heal).
        var workingConfig = config

        // mefrp proxy/list 的 data.nodes（nodeId → 真实连接地址 hostname）。
        // node/list 的 hostname 对普通用户恒为空，公网访问地址（如 ip.lhdyx.top）
        // 只能从 proxy/list 拿——首次拉取后缓存给下面所有 mefrp 隧道回填 nodeHost。
        var mefrpNodeHosts: Map<Int, String>? = null
        suspend fun mefrpNodeHost(nodeId: Int): String? {
            if (mefrpNodeHosts == null) {
                val access = mefrpStore.accessToken
                mefrpNodeHosts = if (access.isBlank()) emptyMap() else {
                    withContext(Dispatchers.IO) {
                        mefrpApi.getProxyListWithNodes(access).getOrNull()
                    }?.nodes?.filter { it.hostname.isNotBlank() }
                        ?.associate { it.nodeId to it.hostname }
                        ?: emptyMap()
                }
            }
            return mefrpNodeHosts?.get(nodeId)
        }

        for ((service, tunnel) in tunnels) {
            // 二进制按服务商：mefrp 隧道必须用 mefrpc（OpenFrp 版 frpc 无法连 mefrp 服务端）；
            // 逐隧道选择，OpenFrp / mefrp 混配时各用各的二进制。
            val binaryPath = if (tunnel.provider == "mefrp")
                frpcBinaryManager.getMefrpBinaryPath()
            else
                frpcBinaryManager.getBinaryPath()
            if (binaryPath == null) {
                Log.w(TAG, "frpc binary not available — skipping $service tunnel for $shareId (provider=${tunnel.provider})")
                continue
            }

            var proxyId = tunnel.proxyId

            // Self-heal: an invalid proxy id (0) means the real id was never saved
            // (older build / API field mismatch). Resolve it from the server by
            // tunnel name and persist the corrected config, so the tunnel comes
            // online without the user manually re-creating it.
            if (proxyId <= 0) {
                val resolved = resolveRealProxyId(tunnel)
                if (resolved != null && resolved > 0) {
                    Log.i(TAG, "Self-healing $service tunnel '${tunnel.name}': proxyId 0 -> $resolved")
                    ShareStateHolder.addFrpcHttpLog(shareId, "自动修复 $service 隧道代理 ID -> $resolved")
                    workingConfig = when (service) {
                        "http" -> workingConfig.copy(httpTunnel = tunnel.copy(proxyId = resolved))
                        "ftp"  -> workingConfig.copy(ftpTunnel = tunnel.copy(proxyId = resolved))
                        "smb"  -> workingConfig.copy(smbTunnel = tunnel.copy(proxyId = resolved))
                        else   -> workingConfig
                    }
                    configStore.saveConfig(workingConfig)
                    proxyId = resolved
                }
            }

            if (frpcManager.isRunning(proxyId)) continue

            // mefrp：启动前回填 nodeHost（公网访问地址的 host）。
            // node/list 的 hostname 对普通用户恒为空，创建隧道时存不下真实地址；
            // proxy/list 的 data.nodes 才带「nodeId → 真实连接地址」。启动前回填，
            // 数据面探测与复制链接弹窗都直接拿到真实地址，无需等启动后再自愈。
            var effTunnel = tunnel
            if (tunnel.provider == "mefrp" && tunnel.nodeHost.isBlank()) {
                val realHost = mefrpNodeHost(tunnel.nodeId)
                if (!realHost.isNullOrBlank()) {
                    effTunnel = tunnel.copy(nodeHost = realHost)
                    workingConfig = when (service) {
                        "http" -> workingConfig.copy(httpTunnel = effTunnel)
                        "ftp"  -> workingConfig.copy(ftpTunnel = effTunnel)
                        "smb"  -> workingConfig.copy(smbTunnel = effTunnel)
                        else   -> workingConfig
                    }
                    configStore.saveConfig(workingConfig)
                    Log.i(TAG, "mefrp: backfilled nodeHost=$realHost for $service tunnel $proxyId (pre-launch)")
                    ShareStateHolder.addFrpcHttpLog(shareId, "mefrp 节点地址: $realHost（已同步，复制链接可获取公网访问链接）")
                }
            }

            // frpc 登录令牌按服务商取：mefrp 用「生成启动配置」的 frpToken（-t 语义），
            // OpenFrp 用共享配置 frpToken（-u 语义）。
            // mefrp 的 -t 必须传启动令牌 frpToken（GET /auth/user/frpToken，MefrpConfigStore 已缓存）——
            // 传「用户访问令牌」会被 API 拒绝：「请使用生成启动配置功能获取最新启动命令」。
            val token = if (effTunnel.provider == "mefrp") resolveFrpcToken(config, effTunnel) else config.openFrpFrpcToken
            if (token.isBlank()) {
                Log.w(TAG, "frpc token missing (provider=${tunnel.provider}) for $service tunnel '${tunnel.name}'")
                ShareStateHolder.addFrpcHttpLog(shareId, "ERROR: $service 穿透令牌缺失（${tunnel.provider}），请先在对应设置页配置")
                continue
            }

            // Launch mode: `... -t|-u <启动令牌> -p <proxy id>` pulls the full tunnel config
            // (node / TLS / ports) from the server itself, so no TOML is generated here.
            val tokenFlag = if (effTunnel.provider == "mefrp") "-t" else "-u"

            // ── mefrp 根治 DNS 回退 8.8.8.8 被墙拦截 ──────────────────────────
            // mefrpc 是纯 Go 编译（CGO_ENABLED=0）：不调系统 getaddrinfo、只读不存在的
            // /etc/resolv.conf，回退到硬编码 8.8.8.8:53。墙内/部分运营商网络下 8.8.8.8
            // 不可达 → api.mefrp.com 解析超时 → 隧道起不来（日志 `lookup api.mefrp.com
            // ... 8.8.8.8:53: i/o timeout`）。也没有 --server-addr/--dns-server 可覆盖。
            // 根治（App 层无 root、不改系统 DNS）：App 内起本地 CONNECT 代理，对 CONNECT
            // 目标用 Android 系统 DNS 解析，再让 mefrpc 经 HTTPS_PROXY 走此代理
            // （FrpcManager 注入环境变量）。mefrpc 发出的 Host/SNI 仍是 api.mefrp.com，
            // 代理只做 TCP 隧道，既绕过 8.8.8.8 又不被 CDN 因 Host=IP 拒绝。--skip-cert-verify
            // 用于跳过 TLS 证书校验（ca.pem 未必含公网证书链）。
            val extraArgs = if (effTunnel.provider == "mefrp") {
                ShareStateHolder.addFrpcHttpLog(shareId, "mefrp 走本地代理（系统 DNS 解析，绕过 8.8.8.8 拦截）")
                listOf("--skip-cert-verify")
            } else emptyList()

            val ok = frpcManager.startTunnel(proxyId, token, binaryPath, tokenFlag = tokenFlag, extraArgs = extraArgs)
            if (ok) {
                ShareStateHolder.addFrpcHttpLog(shareId, "$service 隧道已启动 (代理 ID: $proxyId, 本地端口: ${effTunnel.localPort})")
                Log.i(TAG, "Started $service frpc tunnel for [$shareId] (proxyId=$proxyId)")
                // ── 数据面自检 ───────────────────────────────────────
                // 已知问题：frpc 可能显示「启动成功」但公网地址实际不可达
                // （内置 frpc 与服务端数据面协议不兼容）。隧道注册需要一点时间，
                // 延迟后主动探测公网地址，把真实状态如实写进日志。
                val probeHost = effTunnel.nodeHost
                val probePort = effTunnel.remotePort ?: 0
                if (probeHost.isNotBlank() && probePort > 0) {
                    val svc = service
                    scope.launch {
                        delay(4000)
                        probeDataPlane(shareId, svc, probeHost, probePort)
                    }
                }
                // mefrp：校验服务端隧道配置的本地转发端口与 App 实际服务端口是否一致。
                // 不一致时 mefrpc 会把公网流量转发到错误的本地端口（外部表现为
                // 「公网地址能连通但访问超时/无响应」——此前隧道「启动成功却访问不了」的
                // 另一根因：服务端隧道本地端口 8886 ≠ App 服务端口，实测确认）。
                if (effTunnel.provider == "mefrp") {
                    val access = mefrpStore.accessToken
                    if (access.isNotBlank()) {
                        scope.launch {
                            val toml = withContext(Dispatchers.IO) {
                                mefrpApi.getProxyConfig(access, proxyId).getOrNull()
                            } ?: return@launch
                            // 启动前已从 proxy/list 回填过 nodeHost 时跳过；
                            // 否则用「生成启动配置」TOML 里的 serverAddr 兜底回填
                            // （同样持久化，复制链接弹窗与数据面自检才有真实地址）。
                            if (effTunnel.nodeHost.isBlank()) {
                                val serverHost = Regex("(?i)serverAddr\\s*=\\s*'([^']+)'").find(toml)
                                    ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                                if (serverHost != null) {
                                    val repaired = when (service) {
                                        "http" -> workingConfig.copy(httpTunnel = effTunnel.copy(nodeHost = serverHost))
                                        "ftp"  -> workingConfig.copy(ftpTunnel = effTunnel.copy(nodeHost = serverHost))
                                        "smb"  -> workingConfig.copy(smbTunnel = effTunnel.copy(nodeHost = serverHost))
                                        else   -> workingConfig
                                    }
                                    configStore.saveConfig(repaired)
                                    Log.i(TAG, "mefrp: backfilled nodeHost=$serverHost for $service tunnel $proxyId (post-launch)")
                                    ShareStateHolder.addFrpcHttpLog(shareId, "mefrp 节点地址: $serverHost（已同步，复制链接可获取公网访问链接）")
                                }
                            }
                            val serverLocalPort = Regex("(?i)local[_ ]?port\\s*=\\s*(\\d+)").find(toml)?.groupValues?.get(1)?.toIntOrNull()
                            if (serverLocalPort != null && serverLocalPort != effTunnel.localPort) {
                                val msg = "WARN: mefrp 服务端该隧道本地端口=$serverLocalPort，与 App 实际端口 ${effTunnel.localPort} 不一致，" +
                                        "公网流量会被转发到 $serverLocalPort（而非文件服务）。请在 mefrp 后台把该隧道本地端口改为 ${effTunnel.localPort}，或删除此隧道后重新创建。"
                                ShareStateHolder.addFrpcHttpLog(shareId, msg)
                                Log.w(TAG, "mefrp localPort mismatch: server=$serverLocalPort app=${effTunnel.localPort}")
                            }
                        }
                    }
                }
            } else {
                ShareStateHolder.addFrpcHttpLog(shareId, "ERROR: $service 穿透隧道启动失败")
                Log.e(TAG, "Failed to start $service frpc tunnel for [$shareId]")
            }
        }
    }

    /**
     * 按隧道的服务商取 frpc 登录令牌：
     * - mefrp：MefrpConfigStore 缓存 frpToken，没有则用访问令牌现拉并缓存；
     * - 其它（openfrp）：共享配置里的 openFrpFrpcToken。
     */
    private suspend fun resolveFrpcToken(
        config: ShareConfig,
        tunnel: com.lunashare.app.model.TunnelConfig
    ): String {
        if (tunnel.provider == "mefrp") {
            val cached = mefrpStore.frpcToken
            if (cached.isNotBlank()) return cached
            val access = mefrpStore.accessToken
            if (access.isBlank()) return ""
            val fetched = mefrpApi.getUserFrpToken(access).getOrNull()
            if (fetched.isNullOrBlank()) return ""
            mefrpStore.frpcToken = fetched
            return fetched
        }
        return config.openFrpFrpcToken
    }

    /**
     * Best-effort: resolve the real proxy id for a tunnel that was
     * persisted with an invalid (0) id. Looks the tunnel up by name on the
     * server (OpenFrp 或 mefrp 按 provider 分流), preferring one the server
     * reports as currently active (so we don't adopt a banned/disabled
     * duplicate). Returns null if it can't be resolved — the caller then lets
     * frpcManager reject the 0 id cleanly.
     */
    private suspend fun resolveRealProxyId(tunnel: com.lunashare.app.model.TunnelConfig): Int? =
        withContext(Dispatchers.IO) {
            if (tunnel.provider == "mefrp") {
                val access = mefrpStore.accessToken
                if (access.isBlank()) {
                    Log.w(TAG, "Self-heal skipped: mefrp access token missing")
                    return@withContext null
                }
                val proxies = mefrpApi.getProxyList(access).getOrNull()
                if (proxies.isNullOrEmpty()) {
                    Log.w(TAG, "Self-heal skipped: mefrp proxy list empty for '${tunnel.name}'")
                    return@withContext null
                }
                val matches = proxies.filter { it.proxyName == tunnel.name }
                if (matches.isEmpty()) {
                    Log.w(TAG, "Self-heal skipped: no mefrp tunnel named '${tunnel.name}'")
                    return@withContext null
                }
                val best = matches.firstOrNull { it.isActive } ?: matches.firstOrNull()
                return@withContext best?.proxyId?.takeIf { it > 0 }
            }

            val account = openFrpStore.loadAccount()
            if (!account.isLoggedIn) {
                Log.w(TAG, "Self-heal skipped: OpenFrp not logged in (authorization missing)")
                return@withContext null
            }
            val creds = OpenFrpApiClient.Credentials(account.authorization, account.session)
            val proxies = frpcApi.getUserProxies(creds).getOrNull()
            if (proxies.isNullOrEmpty()) {
                Log.w(TAG, "Self-heal skipped: getUserProxies returned nothing for '${tunnel.name}'")
                return@withContext null
            }
            val matches = proxies.filter { it.name == tunnel.name || it.proxyName == tunnel.name }
            if (matches.isEmpty()) {
                Log.w(TAG, "Self-heal skipped: no server tunnel named '${tunnel.name}'")
                return@withContext null
            }
            val best = matches.firstOrNull { it.isActive } ?: matches.firstOrNull()
            val id = best?.proxyIdValue?.takeIf { it > 0 }
            Log.d(TAG, "Self-heal lookup for '${tunnel.name}': ${matches.size} match(es), chose id=$id" +
                    " (active=${best?.isActive}, ids=${matches.joinToString { it.proxyIdValue.toString() }})")
            id
        }

    /**
     * 数据面可达性自检：从设备自身向公网隧道地址 `host:port` 发起一次 TCP 连接，
     * 验证「控制面已连接」之外，真正的用户流量能否被转发到本地服务。
     *
     * 这是排查「公网 TCP 隧道数据面转发」已知问题的关键信号：
     * 若 frpc 显示启动成功、但此处探测超时/拒绝，即可定位为数据面不通
     * （旧版 frpc 与服务端 frps 协议不兼容时尤其如此），而非配置或本地服务问题。
     * 探测结果会写入 frpc 日志，供 UI 如实展示。
     */
    private suspend fun probeDataPlane(shareId: String, service: String, host: String, port: Int) {
        if (host.isBlank() || port <= 0) return
        val result = withContext(Dispatchers.IO) {
            try {
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 5000) }
                "ok"
            } catch (e: Exception) {
                "fail:${e.message}"
            }
        }
        if (result == "ok") {
            ShareStateHolder.addFrpcHttpLog(
                shareId,
                "✓ 数据面探测成功：$host:$port 可达，公网隧道工作正常"
            )
            Log.i(TAG, "Data-plane probe OK for $service -> $host:$port")
        } else {
            ShareStateHolder.addFrpcHttpLog(
                shareId,
                "⚠️ 数据面探测失败：$host:$port 不可达（$result）。" +
                "若当前已使用 OpenFrp 官方最新 frpc 仍不可达，多为节点/网络临时问题或" +
                "隧道尚未完成注册，可稍后重试；如仍内置旧版 frpc，请更新 APK 内置的 " +
                "libfrpc.so 为官方最新定制版。局域网访问不受影响。"
            )
            Log.w(TAG, "Data-plane probe FAILED for $service -> $host:$port : $result")
        }
    }

    // ── 组网 ↔ 公网隧道互斥协调 ───────────────────────────────────
    // 用户使用场景：frpc 公网隧道与 EasyTier 组网不会同时使用。组网开启时，
    // 暂停所有正在运行的共享公网隧道（避免两路隧道并存/冲突）；组网关闭后，
    // 自动把被暂停的隧道恢复（恢复前提是共享本身仍在使用中）。

    /**
     * 观察组网核心激活状态（EasyTierStateHolder.coreRunning）的变化：
     * false→true 暂停所有共享 frpc 隧道；true→false 恢复。
     */
    private fun startNetworkObserver() {
        networkObserverJob = scope.launch(Dispatchers.Default) {
            // 以当前值作为初态，避免首帧重复触发
            var prev = EasyTierStateHolder.get().coreRunning
            EasyTierStateHolder.state.collect { st ->
                val active = st.coreRunning
                if (active == prev) return@collect
                prev = active
                if (active) pauseShareFrpcForNetwork()
                else resumeShareFrpcForNetwork()
            }
        }
    }

    /** 组网开启：暂停所有运行中的共享 frpc 隧道 + ADB 穿透隧道，并记录待恢复列表。 */
    private suspend fun pauseShareFrpcForNetwork() {
        var paused = 0
        for (config in configStore.listConfigs()) {
            for ((service, tunnel) in config.activeTunnels()) {
                if (frpcManager.isRunning(tunnel.proxyId)) {
                    networkPausedProxyIds.add(tunnel.proxyId)
                    frpcManager.stopTunnel(tunnel.proxyId)
                    paused++
                    ShareStateHolder.addFrpcHttpLog(
                        config.id,
                        "组网已开启：暂停 $service 公网隧道（proxyId=${tunnel.proxyId}，关闭组网后自动恢复）"
                    )
                }
            }
        }
        // ADB 穿透隧道同样走 frpcManager（独立 proxyId），一并暂停
        val adbCfg = adbTunnelStore.loadConfig()
        if (adbCfg != null && adbCfg.proxyId > 0 && frpcManager.isRunning(adbCfg.proxyId)) {
            networkPausedProxyIds.add(adbCfg.proxyId)
            frpcManager.stopTunnel(adbCfg.proxyId)
            paused++
            AdbTunnelStateHolder.addLog(
                "组网已开启：暂停 ADB 穿透隧道（proxyId=${adbCfg.proxyId}，关闭组网后自动恢复）"
            )
            Log.i(TAG, "组网已开启，暂停 ADB 穿透隧道 proxyId=${adbCfg.proxyId}")
        }
        if (paused > 0) {
            Log.i(TAG, "组网已开启，暂停了 $paused 条公网隧道（含共享+ADB）")
            updateNotification()
        }
    }

    /** 组网关闭：把被暂停的共享 frpc 隧道 + ADB 穿透隧道恢复（仍在使用才恢复）。 */
    private suspend fun resumeShareFrpcForNetwork() {
        val pending = networkPausedProxyIds.toList()
        networkPausedProxyIds.clear()
        for (proxyId in pending) {
            // 先尝试匹配共享配置里的隧道
            val config = configStore.listConfigs().firstOrNull { c ->
                c.activeTunnels().any { (_, t) -> t.proxyId == proxyId }
            }
            if (config != null) {
                // 共享自身已停止（用户手动停 / 删除）则不再拉起该隧道
                if (!ShareStateHolder.getState(config.id).isAnyRunning) {
                    Log.d(TAG, "组网关闭，跳过恢复 proxyId=$proxyId（共享 ${config.id} 已停止）")
                    continue
                }
                val tunnels = config.activeTunnels().filter { (_, t) -> t.proxyId == proxyId }
                startFrpcTunnels(config.id, config, tunnels)
                Log.i(TAG, "组网关闭，恢复共享公网隧道 proxyId=$proxyId（共享 ${config.id}）")
                continue
            }
            // 否则按 ADB 穿透隧道处理（proxyId 不在共享配置里）
            val adbCfg = adbTunnelStore.loadConfig()
            if (adbCfg != null && adbCfg.proxyId == proxyId) {
                // 配置仍在 → 重启（stopAdbTunnel 已清除登记，能到这说明确实仍启用）
                startAdbTunnel()
                Log.i(TAG, "组网关闭，恢复 ADB 穿透隧道 proxyId=$proxyId")
            }
        }
        if (pending.isNotEmpty()) updateNotification()
    }

    private suspend fun stopFrpcTunnelsForShare(config: ShareConfig) {
        config.activeTunnels().forEach { (_, tunnel) ->
            networkPausedProxyIds.remove(tunnel.proxyId)
            frpcManager.stopTunnel(tunnel.proxyId)
        }
    }

    // ── ADB 端口穿透隧道 ───────────────────────────────────────────

    /**
     * 启动 ADB 反向隧道：把手机本地 127.0.0.1:5555 通过 frpc 映射到 OpenFrp 公网节点。
     * 隧道元信息（proxyId / token / nodeHost / remotePort）来自 AdbTunnelStore，
     * 由 ADB 穿透界面在「创建隧道」时写入。
     */
    private suspend fun startAdbTunnel() {
        val cfg = adbTunnelStore.loadConfig()
        if (cfg == null || cfg.proxyId <= 0) {
            val msg = "ADB 隧道尚未创建（请先在「ADB 穿透」页创建公网隧道）"
            Log.w(TAG, msg)
            AdbTunnelStateHolder.addLog(msg)
            AdbTunnelStateHolder.setError(msg)
            return
        }

        // 组网已开启时，frpc 公网隧道与组网互斥：跳过 ADB 隧道启动并登记，组网关后自动恢复
        if (EasyTierStateHolder.get().coreRunning) {
            networkPausedProxyIds.add(cfg.proxyId)
            AdbTunnelStateHolder.addLog(
                "组网已开启：跳过 ADB 穿透隧道启动（proxyId=${cfg.proxyId}，关闭组网后自动恢复）"
            )
            Log.i(TAG, "组网已开启，跳过 ADB 隧道启动 proxyId=${cfg.proxyId}")
            return
        }

        val isMefrp = cfg.provider == "mefrp"

        // 二进制按服务商：mefrp 隧道必须用 mefrpc（OpenFrp 版 frpc 无法连 mefrp 服务端），
        // OpenFrp 用 frpc。与 startFrpcTunnels 的分流一致。
        val binaryPath = if (isMefrp) frpcBinaryManager.getMefrpBinaryPath() else frpcBinaryManager.getBinaryPath()
        if (binaryPath == null) {
            val msg = if (isMefrp) "mefrpc 可执行文件不可用（内置 libmefrpc.so 缺失），无法启动 ADB 隧道"
                else "frpc 可执行文件不可用，无法启动 ADB 隧道"
            Log.w(TAG, msg)
            AdbTunnelStateHolder.addLog(msg)
            AdbTunnelStateHolder.setError(msg)
            return
        }

        // 令牌按服务商取：
        // - mefrp：用 MefrpConfigStore 缓存的「启动令牌」frpToken（-t 语义），
        //   没有则现拉 GET /auth/user/frpToken 并缓存（注意 mefrp 的 -t 必须传启动令牌，
        //   不是「用户访问令牌」——传访问令牌会被 API 拒绝）；
        // - OpenFrp：优先用配置里存的 frpcToken（-u 语义），回退到账号 frpToken。
        val token = if (isMefrp) {
            val cached = mefrpStore.frpcToken
            if (cached.isNotBlank()) cached
            else {
                val fetched = mefrpStore.accessToken.takeIf { it.isNotBlank() }
                    ?.let { withContext(Dispatchers.IO) { mefrpApi.getUserFrpToken(it).getOrNull() } }
                if (!fetched.isNullOrBlank()) { mefrpStore.frpcToken = fetched; fetched } else ""
            }
        } else {
            cfg.frpcToken.takeIf { it.isNotBlank() } ?: openFrpStore.loadAccount().frpToken
        }
        if (token.isBlank()) {
            val msg = if (isMefrp) "无法获取 mefrp 启动令牌，请先在「Frp 设置」页验证访问令牌"
                else "无法获取 frpc 登录令牌，请先在 OpenFrp 设置页登录"
            Log.w(TAG, msg)
            AdbTunnelStateHolder.addLog(msg)
            AdbTunnelStateHolder.setError(msg)
            return
        }

        // mefrp：启动前回填 nodeHost（公网访问地址的 host）。
        // node/list 的 hostname 对普通用户恒为空，只能从 proxy/list 的 data.nodes 拿；
        // 启动前回填后，数据面自检与「复制连接命令」直接拿到真实地址。
        var effNodeHost = cfg.nodeHost
        if (isMefrp && cfg.nodeHost.isBlank()) {
            val realHost = withContext(Dispatchers.IO) {
                mefrpStore.accessToken.takeIf { it.isNotBlank() }
                    ?.let { mefrpApi.getProxyListWithNodes(it).getOrNull() }
                    ?.nodes?.firstOrNull { it.nodeId == cfg.nodeId }?.hostname
            }
            if (!realHost.isNullOrBlank()) {
                effNodeHost = realHost
                adbTunnelStore.saveConfig(cfg.copy(nodeHost = realHost))
                AdbTunnelStateHolder.addLog("mefrp 节点地址: $realHost（已同步）")
            }
        }

        AdbTunnelStateHolder.setEnabled(true)
        AdbTunnelStateHolder.setConnectInfo(effNodeHost, cfg.remotePort)
        AdbTunnelStateHolder.setError(null)

        if (frpcManager.isRunning(cfg.proxyId)) {
            adbTunnelRunning = true
            AdbTunnelStateHolder.setTunnelRunning(true)
            AdbTunnelStateHolder.addLog("ADB 隧道已在运行 (代理 ID: ${cfg.proxyId})")
            updateNotification()
            return
        }

        // 启动参数：mefrp 用 -t（启动令牌），OpenFrp 用 -u（用户令牌）。
        val tokenFlag = if (isMefrp) "-t" else "-u"
        val ok = frpcManager.startTunnel(cfg.proxyId, token, binaryPath, tokenFlag = tokenFlag)
        if (ok) {
            adbTunnelRunning = true
            AdbTunnelStateHolder.setTunnelRunning(true)
            AdbTunnelStateHolder.addLog(
                "ADB 隧道已启动 (provider=${cfg.provider}, 代理 ID: ${cfg.proxyId}, 本地端口: ${cfg.localPort}, 公网: $effNodeHost:${cfg.remotePort})"
            )
            Log.i(TAG, "Started ADB frpc tunnel (provider=${cfg.provider}, proxyId=${cfg.proxyId})")
            // 数据面自检：公网地址能否真正转发到本地 5555。
            val host = effNodeHost
            val port = cfg.remotePort ?: 0
            if (host.isNotBlank() && port > 0) {
                scope.launch { delay(4000); probeAdbDataPlane(host, port) }
            }
            // mefrp：启动后用「生成启动配置」TOML 的 serverAddr 兜底回填 nodeHost
            // （启动前没拿到真实地址时，复制链接/连接命令才有真实公网地址可用）。
            if (isMefrp && host.isBlank()) {
                scope.launch {
                    val serverHost = withContext(Dispatchers.IO) {
                        mefrpStore.accessToken.takeIf { it.isNotBlank() }
                            ?.let { mefrpApi.getProxyConfig(it, cfg.proxyId).getOrNull() }
                            ?.let { toml ->
                                Regex("(?i)serverAddr\\s*=\\s*'([^']+)'").find(toml)
                                    ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                            }
                    }
                    if (serverHost != null) {
                        val cur = adbTunnelStore.loadConfig() ?: return@launch
                        adbTunnelStore.saveConfig(cur.copy(nodeHost = serverHost))
                        AdbTunnelStateHolder.setConnectInfo(serverHost, cur.remotePort)
                        AdbTunnelStateHolder.addLog("mefrp 节点地址: $serverHost（已同步）")
                    }
                }
            }
        } else {
            AdbTunnelStateHolder.addLog("ERROR: ADB 穿透隧道启动失败")
            AdbTunnelStateHolder.setError("ADB 穿透隧道启动失败")
            Log.e(TAG, "Failed to start ADB frpc tunnel (provider=${cfg.provider}, proxyId=${cfg.proxyId})")
        }
        updateNotification()
    }

    private suspend fun stopAdbTunnel() {
        val cfg = adbTunnelStore.loadConfig()
        cfg?.proxyId?.takeIf { it > 0 }?.let {
            networkPausedProxyIds.remove(it) // 用户主动停 ADB：从组网待恢复列表移除，避免组网关后误恢复
            frpcManager.stopTunnel(it)
        }
        adbTunnelRunning = false
        AdbTunnelStateHolder.setTunnelRunning(false)
        AdbTunnelStateHolder.setEnabled(false)
        AdbTunnelStateHolder.addLog("ADB 隧道已停止")
        Log.i(TAG, "Stopped ADB frpc tunnel")
        updateNotification()
        stopSelfIfIdle()
    }

    /**
     * 观察 ADB 隧道 proxyId 的 frpc 状态，桥接到 AdbTunnelStateHolder。
     * 与文件共享的观察器分离，因为 ADB 是全局单例、独立 proxyId。
     */
    private fun startAdbTunnelStateObserver() {
        adbObserverJob = scope.launch(Dispatchers.Default) {
            frpcManager.tunnelStates.collect { tunnelStates ->
                val cfg = adbTunnelStore.loadConfig() ?: return@collect
                if (cfg.proxyId <= 0) return@collect
                val ts = tunnelStates[cfg.proxyId] ?: return@collect
                if (ts.running != adbTunnelRunning) {
                    adbTunnelRunning = ts.running
                    AdbTunnelStateHolder.setTunnelRunning(ts.running)
                }
                ts.logs.lastOrNull()?.let { lastLine ->
                    val existing = AdbTunnelStateHolder.get().logs
                    if (existing.isEmpty() || existing.last().removePrefix("[ADB] ") != lastLine) {
                        val toAppend = ts.logs.dropWhile { l ->
                            existing.any { it == "[ADB] $l" }
                        }
                        toAppend.forEach { AdbTunnelStateHolder.addLog(it) }
                    }
                }
            }
        }
    }

    /**
     * ADB 隧道数据面自检：从设备自身向公网隧道地址发起 TCP 连接，
     * 验证 PC 端 `adb connect` 真的能落到本地 5555。结果写入日志。
     */
    private suspend fun probeAdbDataPlane(host: String, port: Int) {
        if (host.isBlank() || port <= 0) return
        val result = withContext(Dispatchers.IO) {
            try {
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 5000) }
                "ok"
            } catch (e: Exception) {
                "fail:${e.message}"
            }
        }
        if (result == "ok") {
            AdbTunnelStateHolder.addLog("✓ 数据面探测成功：$host:$port 可达，PC 端 adb connect 可落地到本机 5555")
            Log.i(TAG, "ADB data-plane probe OK -> $host:$port")
        } else {
            AdbTunnelStateHolder.addLog(
                "⚠️ 数据面探测失败：$host:$port 不可达（$result）。" +
                "若 frpc 显示已连、但此处超时，多为节点/网络临时问题或隧道尚未完成注册，可稍后重试；" +
                "局域网/USB 不受影响。请确认手机本地 5555 已开启（见上方状态）。"
            )
            Log.w(TAG, "ADB data-plane probe FAILED -> $host:$port : $result")
        }
    }

    /** Start the global SMB server if any enabled share is running and it's not up yet. */
    private suspend fun startSmbIfNeeded() {
        if (SmbServerManager.isRunning) return
        val smbShares = configStore.listConfigs()
            .filter { it.smbEnabled && it.localDir.isNotBlank() }
        if (smbShares.isEmpty()) return

        SmbServerManager.start(this, smbShares).onSuccess { port ->
            smbShares.forEach { share ->
                ShareStateHolder.setSmbRunning(share.id, true)
                ShareStateHolder.addSmbLog(share.id, "SMB 服务已启动 (端口 $port)")
            }
            Log.i(TAG, "SMB server started on port $port for ${smbShares.size} share(s)")
        }.onFailure { e ->
            smbShares.forEach { share ->
                ShareStateHolder.addSmbLog(share.id, "ERROR: SMB 服务启动失败: ${e.message}")
            }
            Log.e(TAG, "SMB server failed to start", e)
        }
    }

    /** Stop the global SMB server if no SMB share is running anymore. */
    private suspend fun stopSmbIfIdle() {
        val anySmbRunning = ShareStateHolder.shareStates.value.values.any { it.smbRunning }
        if (anySmbRunning && SmbServerManager.isRunning) return
        SmbServerManager.stop()
        ShareStateHolder.shareStates.value.keys.forEach { shareId ->
            ShareStateHolder.setSmbRunning(shareId, false)
        }
        Log.d(TAG, "SMB server stopped (no running SMB shares)")
    }

    /** Restart the global SMB server so config changes (folder/name/port) take effect. */
    private suspend fun restartSmb() {
        SmbServerManager.stop()
        ShareStateHolder.shareStates.value.keys.forEach { shareId ->
            ShareStateHolder.setSmbRunning(shareId, false)
        }
        startSmbIfNeeded()
    }

    /**
     * 应用配置变更并重启该共享：先停（释放端口 / 回收旧配置的 FileServer），再按最新配置重启。
     *
     * 必要性：加密口令等参数是在共享启动时快照进 FileServer 构造参数的，仅保存配置不会生效——
     * 必须重启共享进程，新的加密口令才能被 2033/2035 上监听的服务加载（否则一直是 `BAD_DECRYPT`）。
     * 同时隧道配置变化也需重建。
     */
    private suspend fun restartShare(shareId: String) {
        val wasRunning = httpServers.containsKey(shareId) ||
            ftpServers.containsKey(shareId) ||
            ShareStateHolder.getState(shareId).isAnyRunning
        Log.d(TAG, "Restarting share: $shareId (wasRunning=$wasRunning)")

        // 关键：置位重启标志，挡住 stopShare() 结尾 stopSelfIfIdle() 的自杀。
        // 否则 Service 会在"已全停"的瞬间 stopSelf()，onDestroy 关掉随后拉起的
        // FileServer 并 cancel(scope)，结果就是"只停止、没重启"。
        restartingShares.incrementAndGet()
        try {
            // 先停：释放端口并丢弃持有旧口令的 FileServer 实例
            stopShare(shareId)

            if (wasRunning) {
                // 稍等端口释放，避免 TIME_WAIT / 同端口立即重绑失败
                delay(300)
            }

            startShare(shareId)

            // 按真实结果反馈，不再无条件报"已重启"
            val nowRunning = httpServers.containsKey(shareId) ||
                ftpServers.containsKey(shareId)
            if (nowRunning) {
                ShareStateHolder.addHttpLog(shareId, "配置已应用，共享已重启")
            } else {
                ShareStateHolder.addHttpLog(shareId, "ERROR: 共享重启失败，请查看上方日志")
                Log.e(TAG, "Restart finished but share is NOT running: $shareId")
            }
        } finally {
            restartingShares.decrementAndGet()
            // 重启失败（或未启用任何服务）时，这里把服务带下来，避免空转常驻
            stopSelfIfIdle()
        }

        updateNotification()
    }

    private suspend fun stopShare(shareId: String) {
        Log.d(TAG, "Stopping share: $shareId")
        val config = configStore.loadConfig(shareId)

        // Stop HTTP server
        httpServers.remove(shareId)?.let { server ->
            server.stop()
            ShareStateHolder.setHttpRunning(shareId, false)
            ShareStateHolder.addHttpLog(shareId, "HTTP 服务器已停止")
            Log.d(TAG, "HTTP server stopped for [$shareId]")
        }

        // Stop FTP server
        ftpServers.remove(shareId)?.let { server ->
            server.stop()
            ShareStateHolder.setFtpRunning(shareId, false)
            ShareStateHolder.addFtpLog(shareId, "FTP 服务器已停止")
            Log.d(TAG, "FTP server stopped for [$shareId]")
        }

        // Stop OpenFrp tunnels for this share
        config?.let { stopFrpcTunnelsForShare(it) }

        ShareStateHolder.setSmbRunning(shareId, false)
        stopSmbIfIdle()

        // 所有服务都停了（且 ADB 隧道未运行）时停止 mDNS 广播
        if (httpServers.isEmpty() && ftpServers.isEmpty() && !adbTunnelRunning) {
            maybeStopMdns()
        }

        updateNotification()
        stopSelfIfIdle()
    }

    private suspend fun stopAll() {
        Log.d(TAG, "Stopping all shares...")
        updateNotificationText("正在停止所有共享服务...")

        val httpKeys = httpServers.keys.toList()
        for (key in httpKeys) {
            httpServers.remove(key)?.stop()
            ShareStateHolder.setHttpRunning(key, false)
        }

        val ftpKeys = ftpServers.keys.toList()
        for (key in ftpKeys) {
            ftpServers.remove(key)?.stop()
            ShareStateHolder.setFtpRunning(key, false)
        }

        // Stop all OpenFrp frpc tunnels
        frpcManager.stopAll()
        networkPausedProxyIds.clear() // 全部共享已停，清空待恢复列表

        // Stop ADB tunnel (if running) and reset its state
        if (adbTunnelRunning) {
            adbTunnelRunning = false
            AdbTunnelStateHolder.setTunnelRunning(false)
            AdbTunnelStateHolder.setEnabled(false)
        }

        SmbServerManager.stop()
        ShareStateHolder.shareStates.value.keys.forEach { key ->
            ShareStateHolder.setSmbRunning(key, false)
            ShareStateHolder.setFrpcHttpRunning(key, false)
            ShareStateHolder.setFrpcFtpRunning(key, false)
        }

        httpServers.clear()
        ftpServers.clear()

        maybeStopMdns()

        // DETACH 不撤通知：组网服务可能共用同一条通知还在前台；随后按剩余状态重绘
        stopForeground(STOP_FOREGROUND_DETACH)
        FgNotificationHelper.updateShare("")
        FgNotificationHelper.refresh(this)
        stopSelf()
    }

    // ── WebDAV / 加密上传：文件传输完成 → 系统通知 ──
    // 用约 1.2s 的窗口把短时间内多个上传合并为一条通知，避免批量上传刷屏振动。
    private val transferEvents = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()
    private var transferFlushJob: Job? = null
    private val transferLock = Any()

    private fun onFileTransferred(fileName: String, sizeBytes: Long) {
        transferEvents.add(fileName to sizeBytes)
        synchronized(transferLock) {
            if (transferFlushJob?.isActive != true) {
                transferFlushJob = scope.launch {
                    delay(1200)
                    flushTransferNotifications()
                }
            }
        }
    }

    private fun flushTransferNotifications() {
        val events = ArrayList<Pair<String, Long>>().also { transferEvents.toCollection(it) }
        transferEvents.clear()
        if (events.isEmpty()) return
        val count = events.size
        val text = if (count == 1) {
            val (name, size) = events[0]
            "已接收：$name · ${formatBytes(size)}"
        } else {
            "本次已接收 $count 个文件（最新：${events[count - 1].first}）"
        }
        TransferNotifier.notifyTransferDone(this, text)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun stopSelfIfIdle() {
        // 重启进行中：此时"全停"是重启的中间态，不能当成空闲而自杀，
        // 否则 onDestroy 会关掉刚拉起的服务并 cancel(scope)。
        if (restartingShares.get() > 0) {
            Log.d(TAG, "stopSelfIfIdle skipped: ${restartingShares.get()} share(s) restarting")
            return
        }
        // ADB 隧道是独立功能：只要它还在跑，服务就不能退出。
        if (httpServers.isEmpty() && ftpServers.isEmpty() && !adbTunnelRunning) {
            // DETACH + 重绘：组网服务可能共用同一条通知还在前台，不能 REMOVE
            stopForeground(STOP_FOREGROUND_DETACH)
            FgNotificationHelper.updateShare("")
            FgNotificationHelper.refresh(this)
            stopSelf()
        }
    }

    /** Check if any share has running servers. */
    private fun hasRunningServers(): Boolean {
        return httpServers.isNotEmpty() || ftpServers.isNotEmpty()
    }

    // ── mDNS broadcaster: expose lunashare.local on the LAN while sharing ──

    private fun maybeStartMdns(config: ShareConfig) {
        if (mdnsRunning.compareAndSet(false, true)) {
            val ftpPort = if (config.ftpEnabled) config.ftpPort else 0
            mdnsBroadcaster.start(config.httpPort, ftpPort)
        }
    }

    private fun maybeStopMdns() {
        if (mdnsRunning.compareAndSet(true, false)) {
            mdnsBroadcaster.stop()
        }
    }

    // ── Notification ──

    private fun updateNotification() {
        val httpCount = httpServers.size
        val ftpCount = ftpServers.size
        val frpcCount = ShareStateHolder.shareStates.value.values
            .sumOf { (if (it.frpcHttpRunning) 1 else 0) + (if (it.frpcFtpRunning) 1 else 0) }
        val text = buildString {
            if (httpCount > 0) append("共享: HTTP $httpCount")
            if (httpCount > 0 && (ftpCount > 0 || frpcCount > 0)) append(" | ")
            if (ftpCount > 0) append("FTP: $ftpCount")
            if ((httpCount > 0 || ftpCount > 0) && frpcCount > 0) append(" | ")
            if (frpcCount > 0) append("FRPC: $frpcCount")
            if (isEmpty()) append("共享已停止")
        }
        FgNotificationHelper.updateShare(text)
        FgNotificationHelper.refresh(this)
    }

    private fun updateNotificationText(text: String) {
        FgNotificationHelper.updateShare(text)
        FgNotificationHelper.refresh(this)
    }

    private fun createNotification(text: String): Notification =
        FgNotificationHelper.buildNotification(this)

    private fun createNotificationChannel() {
        FgNotificationHelper.ensureChannel(this)
    }

    // ── Lifecycle ──

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ShareService destroyed")
        httpServers.values.forEach { it.stop() }
        ftpServers.values.forEach { it.stop() }
        httpServers.clear()
        ftpServers.clear()
        SmbServerManager.stop()
        frpcObserverJob?.cancel()
        adbObserverJob?.cancel()
        networkObserverJob?.cancel()
        networkPausedProxyIds.clear() // 服务销毁，清空待恢复列表
        adbTunnelRunning = false
        AdbTunnelStateHolder.setTunnelRunning(false)
        runBlocking { frpcManager.stopAll() }
        maybeStopMdns()
        releaseWakeLock()
        scope.cancel()
        // 服务销毁后清掉共享侧文本并重绘（组网在跑则只剩组网状态，否则撤掉残留通知）
        FgNotificationHelper.updateShare("")
        if (FgNotificationHelper.vpnText.isBlank()) {
            getSystemService(NotificationManager::class.java)
                .cancel(FgNotificationHelper.NOTIFICATION_ID)
        } else {
            FgNotificationHelper.refresh(this)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (_: Exception) {}
        wakeLock = null
    }
}
