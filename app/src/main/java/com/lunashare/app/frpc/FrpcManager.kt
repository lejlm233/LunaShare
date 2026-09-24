package com.lunashare.app.frpc

import android.content.Context
import android.util.Log
import com.lunashare.app.frpc.model.OpenFrpProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import com.lunashare.app.frpc.mefrp.MefrpProxyGateway

/**
 * Manages the local frpc (frp client) binary process on the Android device.
 *
 * Responsibilities:
 *   1. Locates / validates the frpc binary (arm64-v8a).
 *   2. Generates TOML config files for a given OpenFrp proxy.
 *   3. Spawns frpc subprocesses and tracks their state per proxy ID.
 *   4. Collects stdout/stderr logs and exposes them via StateFlow.
 *
 * NOTE: The actual network tunnel is established by the frpc binary talking
 * to the OpenFrp cloud node. Our job is to babysit the process.
 */
class FrpcManager(private val context: Context) {

    companion object {
        private const val TAG = "FrpcManager"

        /** Standard OpenFrp server ports (not user configurable) */
        internal const val DEFAULT_FRPS_PORT = 7000
        internal const val DEFAULT_FRPS_KCP_PORT = 7001

        /** Directory inside app files/ where we write TOMLs and drop logs */
        private const val FRPC_SUBDIR = "frpc"

        /**
         * 进程扫描清理所有遗留 frpc 进程（不依赖内存 Map）。
         * 供 VPN 被系统收回(onRevoke)时调用：组网下 frpc 本应已暂停，
         * 此调用清掉任何残留，防止孤儿隧道继续占用端口/服务器注册。
         */
        fun killAllStaleProcesses(filesDir: File) {
            val marker = File(filesDir, FRPC_SUBDIR).absolutePath
            runCatching {
                Runtime.getRuntime().exec(arrayOf("pkill", "-9", "-f", marker)).waitFor()
            }.onFailure { Log.w("FrpcManager", "killAllStaleProcesses 失败(可忽略): ${it.message}") }
        }
    }

    // ── Public state ──────────────────────────────────────────────

    data class TunnelRuntimeState(
        val running: Boolean = false,
        val pid: Int? = null,
        val logs: List<String> = emptyList(),
        val exitCode: Int? = null,
        val lastError: String? = null
    )

    private val _tunnelStates = MutableStateFlow<Map<Int, TunnelRuntimeState>>(emptyMap())
    val tunnelStates: StateFlow<Map<Int, TunnelRuntimeState>> = _tunnelStates.asStateFlow()

    fun getTunnelState(proxyId: Int): TunnelRuntimeState =
        _tunnelStates.value[proxyId] ?: TunnelRuntimeState()

    // ── Private plumbing ──────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableMapOf<Int, Process>()  // proxyId → Process
    private val jobs = mutableMapOf<Int, Job>()           // proxyId → log-collection job

    /** Auto-retry budget per proxy (handles transient OpenFrp API slow-node timeouts). */
    private val retriesRemaining = mutableMapOf<Int, Int>()
    /** Set when the user explicitly stops a tunnel, to suppress auto-retry. */
    private val userStopped = mutableMapOf<Int, Boolean>()
    /** Pending retry jobs, so stopTunnel can cancel them. */
    private val watchdogJobs = mutableMapOf<Int, Job>()
    /** Last launch params, so the watchdog can relaunch after a transient failure. */
    private val launchParams = mutableMapOf<Int, Pair<Triple<String, String, String>, List<String>>>()
    private val MAX_AUTO_RETRIES = 5
    private val RETRY_BACKOFF_MS = 3000L

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val workDir: File by lazy {
        File(context.filesDir, FRPC_SUBDIR).apply { mkdirs() }
    }

    private val configsDir: File by lazy {
        File(workDir, "configs").apply { mkdirs() }
    }

    private val logsDir: File by lazy {
        File(workDir, "logs").apply { mkdirs() }
    }

    /** Combined system CA certs so the Go-based frpc can verify HTTPS. */
    private val caBundleFile: File by lazy {
        File(workDir, "ca-bundle.pem")
    }

    // ── Public API: Process management ────────────────────────────

    /**
     * Start a frpc process for the given proxy.
     *
     * @param proxyId OpenFrp proxy ID (used as the stable key).
     * @param userToken OpenFrp 32-char user key (passed via -u; the client
     *        then pulls the full tunnel config from the OpenFrp server).
     * @param binaryPath Absolute path to frpc executable on device.
     * @return true if the process was spawned (doesn't mean connect success —
     *         watch [tunnelStates] for runtime status).
     */
    /**
     * Start a frpc process for the given proxy, with automatic retry on
     * transient failures (e.g. OpenFrp API slow-node [getproxy] timeouts).
     * The actual spawn logic lives in [doLaunchOnce]; this wrapper just
     * (re)arms the retry budget so a manual re-enable starts fresh.
     */
    suspend fun startTunnel(
        proxyId: Int,
        userToken: String,
        binaryPath: String,
        maxRetries: Int = MAX_AUTO_RETRIES,
        tokenFlag: String = "-u",
        extraArgs: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        userStopped.remove(proxyId)
        retriesRemaining[proxyId] = maxRetries
        launchParams[proxyId] = Triple(userToken, binaryPath, tokenFlag) to extraArgs
        doLaunchOnce(proxyId, userToken, binaryPath, tokenFlag, extraArgs)
    }

    /**
     * Spawn a single frpc attempt. If it dies on a transient failure (see
     * [maybeScheduleRetry]), the log collector's watchdog relaunches this.
     */
    private suspend fun doLaunchOnce(
        proxyId: Int,
        userToken: String,
        binaryPath: String,
        tokenFlag: String = "-u",
        extraArgs: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        if (processes.containsKey(proxyId)) {
            Log.w(TAG, "Tunnel $proxyId already running — stop it first")
            appendLog(proxyId, "WARN: 隧道已在运行，先停止再启动")
            return@withContext false
        }

        val binary = File(binaryPath)
        // 清理上次 app 被强杀遗留的、属于该 proxyId 的孤儿 frpc 进程，
        // 避免重建隧道时端口/双隧道冲突（不依赖内存 Map，靠进程扫描）。
        killStaleForProxy(proxyId)
        if (!binary.exists() || !binary.canExecute()) {
            val msg = "frpc 二进制文件不存在或无法执行: $binaryPath"
            Log.e(TAG, msg)
            setState(proxyId) { copy(lastError = msg) }
            return@withContext false
        }

        if (userToken.isBlank()) {
            val msg = "OpenFrp 用户密钥为空，无法启动隧道"
            Log.e(TAG, msg)
            setState(proxyId) { copy(lastError = msg) }
            return@withContext false
        }

        if (proxyId <= 0) {
            val msg = "代理 ID 无效 ($proxyId)，无法启动隧道（请重新创建隧道或检查账号隧道列表）"
            Log.e(TAG, msg)
            appendLog(proxyId, "ERROR: $msg")
            setState(proxyId) { copy(running = false, lastError = msg) }
            return@withContext false
        }

        // OpenFrp official launch mode: frpc -u <32-char user key> -p <proxy id>
        // The client pulls its full config (node / TLS / ports) from the
        // OpenFrp server, so no TOML file is needed.
        //   -n : disable the (useless for us) update check — it otherwise
        //        burns ~10s of frpc's API timeout budget on slow networks.
        val logFile = File(logsDir, "proxy_$proxyId.log")
        val baseCmd = arrayOf(
            binary.absolutePath,
            "-n",
            tokenFlag, userToken,
            "-p", proxyId.toString()
        )
        // mefrpc 走本地反向代理（系统 DNS 解析 api.mefrp.com，绕过其内置 8.8.8.8 被墙）：
        // Go net/http 默认 client 不读 HTTPS_PROXY 环境变量，故用 --api-root-url 显式指向
        // 127.0.0.1 本地代理（mefrpc 连 127.0.0.1 是 IP，无需 DNS 解析）。
        val apiRootArgs = if (tokenFlag == "-t") {
            val proxyPort = MefrpProxyGateway.ensureStarted(context)
            val apiRoot = "http://127.0.0.1:$proxyPort"
            Log.i(TAG, "mefrp 注入 --api-root-url=$apiRoot（本地反向代理，系统 DNS 解析 api.mefrp.com）")
            listOf("--api-root-url", apiRoot)
        } else {
            emptyList()
        }
        val cmd = baseCmd + extraArgs.toTypedArray() + apiRootArgs.toTypedArray()

        appendLog(proxyId, "正在启动 frpc 进程...")
        appendLog(proxyId, "命令: ${cmd.joinToString(" ")}")

        return@withContext try {
            val pb = ProcessBuilder(*cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)

            val env = pb.environment()
            // mefrpc 已通过 --api-root-url 指向本地反向代理，不再注入 env 代理
            // （Go net/http 默认 client 不读 HTTPS_PROXY，注入无效）。OpenFrp 直连，
            // 这里清掉可能残留的代理变量避免干扰。
            env.remove("HTTP_PROXY")
            env.remove("HTTPS_PROXY")
            // Go static binaries don't read Android's CA store; point them at
            // our bundled copy so OpenFrp API TLS verification succeeds.
            prepareCaBundle()?.let { caPath ->
                env["SSL_CERT_FILE"] = caPath
                Log.d(TAG, "SSL_CERT_FILE=$caPath")
            }

            val proc = pb.start()
            processes[proxyId] = proc
            setState(proxyId) {
                copy(
                    running = true,
                    pid = extractPid(proc),
                    exitCode = null,
                    lastError = null
                )
            }

            // Collect logs in background
            val job = scope.launch { collectLogs(proxyId, proc, logFile) }
            jobs[proxyId] = job

            // Give it a moment, then check it didn't crash immediately
            delay(800)
            val stillAlive = try { proc.isAlive } catch (_: Exception) { false }
            if (!stillAlive) {
                val code = runCatching { proc.exitValue() }.getOrNull()
                setState(proxyId) {
                    copy(
                        running = false,
                        exitCode = code,
                        lastError = "进程启动后立即退出 (code=$code)"
                    )
                }
                appendLog(proxyId, "ERROR: frpc 启动后立即退出 (exit code=$code)")
                processes.remove(proxyId)
                false
            } else {
                appendLog(proxyId, "✓ frpc 进程已启动，正在连接节点...")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to spawn frpc for $proxyId", e)
            setState(proxyId) { copy(lastError = "启动失败: ${e.message}") }
            appendLog(proxyId, "ERROR: 启动失败 — ${e.message}")
            false
        }
    }

    /**
     * 进程扫描清理：杀掉命令行中匹配 `-p <proxyId>` 的遗留 frpc 进程。
     * 用于 app 被 force-stop 后残留的孤儿 frpc——内存 Map 已随进程消失，
     * 只有扫描 /proc 才能找到并回收，防止重建隧道时端口冲突/双隧道。
     * 精确匹配 proxyId（后接非数字或行尾），避免 `-p 123` 误杀 `-p 12345`。
     */
    private fun killStaleForProxy(proxyId: Int) {
        val marker = File(context.filesDir, FRPC_SUBDIR).absolutePath
        runCatching {
            val pgrep = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", marker))
            val pids = pgrep.inputStream.bufferedReader().readLines()
                .mapNotNull { it.toIntOrNull() }
            pgrep.waitFor()
            val re = Regex("-p[ =]$proxyId(\\D|$)")
            for (pid in pids) {
                val cmdline = runCatching {
                    File("/proc/$pid/cmdline").readText().replace('\u0000', ' ')
                }.getOrNull() ?: continue
                if (re.containsMatchIn(cmdline)) {
                    Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
                    Log.i(TAG, "killStaleForProxy: 回收 proxyId=$proxyId 的孤儿 frpc (pid=$pid)")
                }
            }
        }.onFailure { Log.w(TAG, "killStaleForProxy 失败(可忽略): ${it.message}") }
    }

    /** Stop a single tunnel's frpc process. */
    suspend fun stopTunnel(proxyId: Int) = withContext(Dispatchers.IO) {
        appendLog(proxyId, "正在停止 frpc 进程...")
        // Suppress any in-flight auto-retry watchdog for this tunnel.
        userStopped[proxyId] = true
        retriesRemaining[proxyId] = 0
        watchdogJobs.remove(proxyId)?.cancel()
        launchParams.remove(proxyId)
        val proc = processes.remove(proxyId)
        jobs.remove(proxyId)?.cancel()

        if (proc == null) {
            setState(proxyId) { copy(running = false, pid = null) }
            return@withContext
        }

        try {
            // Gentle first: SIGTERM
            proc.destroy()
            val exited = runCatching {
                withTimeoutOrNull(3000) {
                    while (proc.isAlive) delay(50)
                }
            }
            if (exited == null) {
                // Still alive → force-kill
                appendLog(proxyId, "进程未响应 TERM，强制终止...")
                proc.destroyForcibly()
                runCatching { proc.waitFor() }
            }
            val code = runCatching { proc.exitValue() }.getOrNull()
            setState(proxyId) {
                copy(running = false, pid = null, exitCode = code)
            }
            appendLog(proxyId, "✓ frpc 进程已停止 (exit code=$code)")
        } catch (e: Exception) {
            Log.w(TAG, "Error while stopping tunnel $proxyId", e)
            setState(proxyId) { copy(running = false, pid = null, lastError = e.message) }
        }
    }

    /** Stop every running tunnel. */
    suspend fun stopAll() {
        val ids = processes.keys.toList()
        ids.forEach { stopTunnel(it) }
    }

    fun isRunning(proxyId: Int): Boolean =
        processes[proxyId]?.isAlive == true

    // ── Public API: Config generator ──────────────────────────────

    /**
     * Build a full frpc.toml for an OpenFrp proxy.
     *
     * The exact serverAddr for a node must come from the caller (we don't
     * keep a node→IP map here — the API client does that).
     */
    fun buildTomlConfig(
        serverAddr: String,
        serverPort: Int = DEFAULT_FRPS_PORT,
        useKcp: Boolean = false,
        authToken: String,            // OpenFrp user secret (32-char) → top-level `user`
        proxy: OpenFrpProxy,
        proxyName: String = proxy.displayName,
        tlsServerName: String? = null  // e.g. "n45d14.openfrp.net"; null → omit
    ): String {
        val transportProto = if (useKcp) "kcp" else "tcp"
        val actualPort = if (useKcp) DEFAULT_FRPS_KCP_PORT else serverPort

        // Map proxy → toml section type
        val sectionType = when (proxy.proxyTypeValue.lowercase()) {
            "tcp" -> "tcp"
            "udp" -> "udp"
            "http" -> "http"
            "https" -> "https"
            else -> "tcp"
        }

        val toml = buildString {
            // ── header ────────────────────────────────────────────
            appendLine("# OpenFrp auto-generated config for LunaShare")
            appendLine("# Proxy: $proxyName  (id=${proxy.proxyIdValue})")
            appendLine()
            appendLine("serverAddr = \"$serverAddr\"")
            appendLine("serverPort = $actualPort")
            // OpenFrp auth model (verified against the official "copy config" TOML):
            //   - the 32-char user key goes into the top-level `user` field
            //   - the login auth token is the FIXED literal "OpenFrpToken" (NOT the key)
            // Putting the user key into auth.token is what triggered
            // "token in login doesn't match token from configuration".
            appendLine("user = \"${authToken.toTomlEscaped()}\"")
            appendLine()
            appendLine("[auth]")
            appendLine("method = \"token\"")
            appendLine("token = \"OpenFrpToken\"")
            appendLine()
            appendLine("[transport]")
            appendLine("protocol = \"$transportProto\"")
            appendLine()
            appendLine("[transport.tls]")
            appendLine("enable = true")
            appendLine("disableCustomTLSFirstByte = false")
            if (!tlsServerName.isNullOrBlank()) {
                appendLine("serverName = \"${tlsServerName.toTomlEscaped()}\"")
            }
            appendLine()

            // ── [[proxies]] ───────────────────────────────────────
            appendLine("[[proxies]]")
            appendLine("name = \"${proxyName.toTomlEscaped()}\"")
            appendLine("type = \"$sectionType\"")
            appendLine("localIP = \"${proxy.localAddrValue.toTomlEscaped()}\"")
            appendLine("localPort = ${proxy.localPortValue}")
            appendLine()

            when (sectionType) {
                "tcp", "udp" -> {
                    val rp = proxy.remotePortValue
                    if (rp != null) {
                        appendLine("remotePort = $rp")
                    }
                    // Mirror the official config exactly
                    appendLine("autoTLS = \"false\"")
                }
                "http", "https" -> {
                    val domain = proxy.domain ?: proxy.domainBind ?: proxy.domain_bind
                    if (!domain.isNullOrBlank()) {
                        appendLine("customDomains = [\"${domain.toTomlEscaped()}\"]")
                    }
                    val hostRw = proxy.hostRewrite ?: proxy.host_rewrite
                    if (!hostRw.isNullOrBlank()) {
                        appendLine("hostHeaderRewrite = \"${hostRw.toTomlEscaped()}\"")
                    }
                    val route = proxy.urlRoute ?: proxy.url_route
                    if (!route.isNullOrBlank()) {
                        appendLine("locations = [\"${route.toTomlEscaped()}\"]")
                    }
                }
            }

            if (proxy.dataGzip == true || proxy.data_gzip == true) {
                appendLine("transport.useCompression = true")
            }
            if (proxy.dataEncrypt == true || proxy.data_encrypt == true) {
                appendLine("transport.encryption = true")
            }
        }

        Log.v(TAG, "Generated TOML for ${proxy.proxyIdValue}:\n$toml")
        return toml
    }

    // ── Internal helpers ──────────────────────────────────────────

    /**
     * Go static binaries (the OpenFrp frpc) do not consult Android's bundled
     * CA store at /system/etc/security/cacerts, so every HTTPS call fails with
     * "x509: certificate signed by unknown authority". We concatenate the
     * system CA certs into one PEM and expose it via SSL_CERT_FILE.
     *
     * @return absolute path to the generated bundle, or null if unavailable.
     */
    private fun prepareCaBundle(): String? {
        if (caBundleFile.exists() && caBundleFile.length() > 0) {
            return caBundleFile.absolutePath
        }
        val cacertsDir = File("/system/etc/security/cacerts")
        if (!cacertsDir.isDirectory) {
            Log.w(TAG, "系统 CA 目录不存在: $cacertsDir")
            return null
        }
        val sb = StringBuilder()
        cacertsDir.listFiles()?.forEach { cert ->
            if (!cert.isFile) return@forEach
            runCatching {
                val text = cert.readText()
                if (text.contains("BEGIN CERTIFICATE")) {
                    sb.append(text)
                    if (!text.endsWith("\n")) sb.append('\n')
                }
            }
        }
        if (sb.isEmpty()) {
            Log.w(TAG, "未能从系统 CA 目录读取任何证书")
            return null
        }
        runCatching { caBundleFile.writeText(sb.toString()) }
            .onFailure { e -> Log.e(TAG, "写入 CA bundle 失败", e) }
        if (!caBundleFile.exists()) return null
        caBundleFile.setReadable(true, false)
        Log.i(TAG, "CA bundle 已生成: ${caBundleFile.absolutePath} (${sb.length} bytes)")
        return caBundleFile.absolutePath
    }

    private fun String.toTomlEscaped(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private suspend fun collectLogs(proxyId: Int, proc: Process, logFile: File) {
        try {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val writer = logFile.bufferedWriter()
            writer.use { fileWriter ->
                var line: String?
                while (currentCoroutineContext().isActive) {
                    line = withContext(Dispatchers.IO) { reader.readLine() }
                    if (line == null) break
                    fileWriter.appendLine(line)
                    fileWriter.flush()
                    appendLog(proxyId, line)
                }
            }
        } catch (e: CancellationException) {
            // Expected on stopTunnel
        } catch (e: Exception) {
            Log.w(TAG, "Log collector for $proxyId failed", e)
            appendLog(proxyId, "WARN: 日志收集失败: ${e.message}")
        } finally {
            // Process ended — make sure the dead handle is dropped so a later
            // startTunnel isn't wrongly blocked by "already running".
            processes.remove(proxyId)
            jobs.remove(proxyId)
            val code = runCatching { proc.exitValue() }.getOrNull()
            setState(proxyId) {
                copy(
                    running = false,
                    pid = null,
                    exitCode = code
                )
            }
            appendLog(proxyId, "frpc 进程已结束 (exit code=$code)")
            // Transient failure? Relaunch automatically (handles the
            // intermittent OpenFrp API slow-node getproxy timeouts).
            maybeScheduleRetry(proxyId)
        }
    }

    /**
     * If a tunnel died on a transient failure and the user didn't explicitly
     * stop it, schedule an automatic relaunch after a short backoff. This makes
     * the intermittent OpenFrp API [getproxy] slow-node timeouts self-healing:
     * a fresh DoH resolution usually lands on a healthy EdgeOne node.
     */
    private fun maybeScheduleRetry(proxyId: Int) {
        if (userStopped[proxyId] == true) return
        val remaining = retriesRemaining[proxyId] ?: 0
        if (remaining <= 0) return
        if (!isTransientFailure(getTunnelState(proxyId).logs)) return

        retriesRemaining[proxyId] = remaining - 1
        val params = launchParams[proxyId] ?: return
        val retryTriple = params.first
        val token = retryTriple.first
        val binary = retryTriple.second
        val retryFlag = retryTriple.third
        val retryArgs = params.second
        appendLog(
            proxyId,
            "检测到隧道瞬时失败，将在 ${RETRY_BACKOFF_MS / 1000}s 后自动重试 " +
                "(剩余 ${remaining - 1} 次)..."
        )
        val job = scope.launch {
            delay(RETRY_BACKOFF_MS)
            if (userStopped[proxyId] == true) return@launch
            Log.i(TAG, "Auto-retrying tunnel $proxyId (remaining=${retriesRemaining[proxyId]})")
            doLaunchOnce(proxyId, token, binary, retryFlag, retryArgs)
        }
        watchdogJobs[proxyId] = job
    }

    /**
     * Decide whether a failure is worth retrying. We retry on network-level
     * transient errors (API timeouts, node connection failures) but NOT on
     * persistent config/auth errors that would just fail again.
     */
    private fun isTransientFailure(logs: List<String>): Boolean {
        val joined = logs.joinToString("\n")
        // Persistent errors → do NOT retry.
        if (joined.contains("token doesn't match", ignoreCase = true) ||
            joined.contains("login failed", ignoreCase = true) ||
            joined.contains("authorization", ignoreCase = true) ||
            joined.contains("unknown shorthand flag", ignoreCase = true) ||
            // OpenFrp rejected the proxy — it's disabled/banned or the id is
            // stale. Retrying only hammers the API and can worsen a throttle.
            joined.contains("403") ||
            joined.contains("Forbidden") ||
            joined.contains("disabled or banned") ||
            joined.contains("Proxy already") ||
            joined.contains("banned")
        ) {
            return false
        }
        // Transient errors → retry.
        return joined.contains("context deadline exceeded") ||
            joined.contains("API请求失败") ||
            joined.contains("i/o timeout") ||
            joined.contains("connection refused") ||
            joined.contains("connection reset") ||
            joined.contains("dial tcp") ||
            joined.contains("read tcp") ||
            joined.contains("EOF") ||
            joined.contains("tls: ")
    }

    private fun extractPid(proc: Process): Int? =
        runCatching {
            // Process.pid() available on Java 9+ / Android O+
            proc::class.java.getMethod("pid").invoke(proc) as? Int
        }.getOrNull()

    private fun appendLog(proxyId: Int, line: String) {
        setState(proxyId) {
            val max = 300
            copy(logs = (logs + line).takeLast(max))
        }
    }

    private fun setState(proxyId: Int, transform: TunnelRuntimeState.() -> TunnelRuntimeState) {
        val map = _tunnelStates.value.toMutableMap()
        val current = map[proxyId] ?: TunnelRuntimeState()
        map[proxyId] = current.transform()
        _tunnelStates.value = map
    }
}
