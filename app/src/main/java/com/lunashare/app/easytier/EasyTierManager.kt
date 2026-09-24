package com.lunashare.app.easytier

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.easytier.jni.EasyTierJNI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * EasyTier 组网生命周期管理（对标官方 easytier-gui mobile_vpn.ts 时序）：
 *
 * start: 生成/导入 TOML（配置不带 no_tun，核心挂起等 fd，控制面照常）
 *        → 轮询 collectNetworkInfos 拿 DHCP 分配的虚拟 IP
 *        → startService(LunaVpnService)：establish + setTunFd 注入
 * stop:  停 VpnService → stopAllInstances
 *
 * 附加能力：
 * - 断线自动重连：轮询发现实例消失/running=false 连续若干轮 → 用缓存 TOML 重启核心；
 *   用户手动停止后不重连；连续失败超限则放弃并提示。
 * - 多配置档案：配置按档案名存取（SharedPreferences JSON），连接/保存作用于当前档案。
 * - 开机自动连接：bootAutoStart 开关由 BootReceiver 读取。
 * - 会话统计：连接时长 + tun0 收发字节（/sys/class/net/tun0/statistics）。
 */
object EasyTierManager {

    private const val TAG = "EasyTierManager"
    const val INSTANCE_NAME = "lunashare_et"
    private const val PREFS = "easytier_cfg"
    private const val MONITOR_INTERVAL_MS = 3000L

    // 断线重连参数
    private const val BAD_ROUNDS_TO_RECONNECT = 3      // 连续异常 3 轮（约 9s）判定断线
    private const val MAX_RECONNECT_ATTEMPTS = 5       // 单会话最多自动重连 5 次

    private var monitorScope: CoroutineScope? = null
    private var lastIpv4: String? = null
    private var lastProxyCidrs: List<String> = emptyList()
    /** 诊断轮次：IP 未分配期间周期性 dump 核心原始状态，避免刷屏。 */
    private var diagRound = 0

    // 断线重连状态
    /** 最近一次启动使用的 TOML（自动重连时复用）。 */
    private var lastToml: String? = null
    /** 用户是否主动停止过（true 期间不做自动重连）。 */
    private var userStopped = true
    /** 本会话已自动重连次数。 */
    private var reconnectCount = 0
    /** 连续异常轮数。 */
    private var badRounds = 0
    /** 会话开始时间（elapsedRealtime）。 */
    private var sessionStartMs = 0L
    /** 流量基线（TrafficStats uid 口径，start 时记录，统计=当前值-基线）。 */
    private var baseRx = 0L
    private var baseTx = 0L

    // ---------------- 配置持久化（多档案） ----------------

    data class EasyTierConfig(
        val networkName: String = "",
        val networkSecret: String = "",
        val hostname: String = "lunashare",
        /** 对等节点 URI 列表（tcp/udp/wss://...），公共节点可多个。 */
        val peers: List<String> = emptyList(),
        /** 自定义虚拟 IP（空 = DHCP 自动分配）。 */
        val virtualIp: String = ""
    )

    /** 命名配置档案。 */
    data class Profile(val id: String, val name: String, val cfg: EasyTierConfig)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部档案（按保存顺序）。 */
    fun loadProfiles(context: Context): List<Profile> {
        // 首次：迁移旧版单配置为「默认」档案
        val sp = prefs(context)
        val raw = sp.getString("profiles", null)
        if (raw == null) {
            val legacy = sp.getString("cfg", null)
            val first = if (legacy != null) runCatching { jsonToConfig(JSONObject(legacy)) }.getOrNull()
            else EasyTierConfig(networkName = "")
            val p = Profile(id = newId(), name = "默认配置", cfg = first ?: EasyTierConfig())
            saveProfiles(context, listOf(p))
            setCurrentProfile(context, p.id)
            // 旧键保留（启动流程 startWithConsent 仍单独存 cfg 作为 boot 备份），此处不再读
            return listOf(p)
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Profile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    cfg = jsonToConfig(o.optJSONObject("cfg") ?: JSONObject())
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("cfg", configToJson(p.cfg)))
        }
        prefs(context).edit().putString("profiles", arr.toString()).apply()
    }

    fun currentProfileId(context: Context): String = prefs(context).getString("currentProfile", null) ?: ""

    fun setCurrentProfile(context: Context, id: String) {
        prefs(context).edit().putString("currentProfile", id).apply()
    }

    /** 当前档案配置（无档案时返回空配置）。 */
    fun loadConfig(context: Context): EasyTierConfig {
        val profiles = loadProfiles(context)
        val cur = profiles.find { it.id == currentProfileId(context) } ?: profiles.firstOrNull()
        return cur?.cfg ?: EasyTierConfig()
    }

    /** 保存到当前档案（无档案则建「默认配置」）。 */
    fun saveConfig(context: Context, cfg: EasyTierConfig) {
        val profiles = loadProfiles(context).toMutableList()
        val curId = currentProfileId(context)
        val idx = profiles.indexOfFirst { it.id == curId }
        if (idx >= 0) profiles[idx] = profiles[idx].copy(cfg = cfg)
        else profiles.add(Profile(id = curId.ifBlank { newId() }.also { setCurrentProfile(context, it) }, name = "默认配置", cfg = cfg))
        saveProfiles(context, profiles)
    }

    /** 新建档案并设为当前。 */
    fun addProfile(context: Context, name: String, cfg: EasyTierConfig): Profile {
        val profiles = loadProfiles(context).toMutableList()
        var n = 1
        var unique = name
        while (profiles.any { it.name == unique }) unique = "$name(${++n})"
        val p = Profile(id = newId(), name = unique, cfg = cfg)
        profiles.add(p)
        saveProfiles(context, profiles)
        setCurrentProfile(context, p.id)
        return p
    }

    /** 删除档案；若删的是当前档案则切到相邻档案。返回删除后的当前配置。 */
    fun deleteProfile(context: Context, id: String): EasyTierConfig {
        val profiles = loadProfiles(context).toMutableList()
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx >= 0) profiles.removeAt(idx)
        saveProfiles(context, profiles)
        if (currentProfileId(context) == id) {
            val next = profiles.getOrNull(idx.coerceAtMost(profiles.size - 1))
            setCurrentProfile(context, next?.id ?: "")
            return next?.cfg ?: EasyTierConfig()
        }
        return profiles.find { it.id == currentProfileId(context) }?.cfg ?: EasyTierConfig()
    }

    /** 重命名档案。 */
    fun renameProfile(context: Context, id: String, name: String) {
        val profiles = loadProfiles(context).toMutableList()
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            profiles[idx] = profiles[idx].copy(name = name)
            saveProfiles(context, profiles)
        }
    }

    private fun newId() = "p${System.currentTimeMillis()}_${(0..999).random()}"

    private fun configToJson(c: EasyTierConfig): JSONObject = JSONObject()
        .put("networkName", c.networkName)
        .put("networkSecret", c.networkSecret)
        .put("hostname", c.hostname)
        .put("peers", JSONArray(c.peers))
        .put("virtualIp", c.virtualIp)

    private fun jsonToConfig(o: JSONObject): EasyTierConfig = EasyTierConfig(
        networkName = o.optString("networkName"),
        networkSecret = o.optString("networkSecret"),
        hostname = o.optString("hostname", "lunashare").ifBlank { "lunashare" },
        peers = o.optJSONArray("peers")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList(),
        virtualIp = o.optString("virtualIp")
    )

    // ---------------- 开机自动连接 ----------------

    fun isBootAutoStart(context: Context): Boolean = prefs(context).getBoolean("bootAutoStart", false)

    fun setBootAutoStart(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean("bootAutoStart", v).apply()
    }

    /** 开机/外部触发：用当前档案配置直接启动组网（无有效配置则忽略）。 */
    fun startFromSavedConfig(context: Context) {
        val cfg = loadConfig(context)
        if (cfg.networkName.isBlank()) {
            Log.w(TAG, "boot auto-start skipped: no network name")
            return
        }
        start(context, buildToml(cfg))
    }

    // ---------------- TOML 生成 ----------------

    /**
     * 表单字段 → EasyTier TOML。
     * 注意：官方 example_config.toml 已过时，真实 schema 以 easytier/src/common/config.rs
     * 的 `Config` 结构为准：instance_name / [network_identity] / [[peer]] / [flags]。
     */
    fun buildToml(cfg: EasyTierConfig): String {
        require(cfg.networkName.isNotBlank()) { "网络名不能为空" }
        val sb = StringBuilder()
        sb.appendLine("instance_name = \"$INSTANCE_NAME\"")
        if (cfg.hostname.isNotBlank()) sb.appendLine("hostname = \"${cfg.hostname.trim()}\"")
        if (cfg.virtualIp.isNotBlank()) {
            val vip = cfg.virtualIp.trim()
            // ipv4 字段按 Ipv4Inet 解析，缺省前缀补 /24
            sb.appendLine("ipv4 = \"${if (vip.contains('/')) vip else "$vip/24"}\"")
        } else {
            sb.appendLine("dhcp = true")
        }
        sb.appendLine("[network_identity]")
        sb.appendLine("network_name = \"${cfg.networkName.trim()}\"")
        if (cfg.networkSecret.isNotBlank()) sb.appendLine("network_secret = \"${cfg.networkSecret.trim()}\"")
        cfg.peers.filter { it.isNotBlank() }.forEach { p ->
            sb.appendLine("[[peer]]")
            sb.appendLine("uri = \"${p.trim()}\"")
        }
        return sb.toString()
    }

    /**
     * 检查配置中的 no_tun 标志（官方语义：no_tun=true = 仅组网、不建 VPN，
     * 官方 mobile_vpn.ts 在此情形下直接跳过 VPN 服务）。
     * 返回 true 表示用户显式选择了仅组网模式。
     */
    fun hasNoTun(toml: String): Boolean =
        Regex("(?m)^\\s*no_tun\\s*=\\s*true\\s*$").containsMatchIn(toml)

    // ---------------- 启动 / 停止 ----------------

    fun start(context: Context, toml: String) {
        stop(context, silent = true)
        userStopped = false
        reconnectCount = 0
        badRounds = 0
        lastToml = toml
        sessionStartMs = SystemClock.elapsedRealtime()
        // 流量基线（SELinux 禁读 /sys 与 /proc/net，改用 TrafficStats uid 口径差值）
        runCatching {
            baseRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
            baseTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        }.onFailure { baseRx = -1L; baseTx = -1L }
        EasyTierStateHolder.setStats(EasyTierStateHolder.EtStats(sessionMs = 0))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        monitorScope = scope

        scope.launch {
            try {
                // 显式预热 JNI 类初始化：dlopen 失败会在这里带完整 message 抛出（否则后续
                // 只能拿到 NoClassDefFoundError，根因丢失）
                Log.d(TAG, "trace: pre loadLibrary")
                try {
                    EasyTierJNI.getLastError()
                } catch (e: Throwable) {
                    // 类初始化失败的真实根因在 cause 链里（ExceptionInInitializerError
                    // / NoClassDefFoundError 本身不带 message），挖到底层 dlopen 报错
                    var root: Throwable = e
                    while (root.cause != null) root = root.cause!!
                    throw RuntimeException(
                        "JNI 库加载失败: ${root.javaClass.name}: ${root.message}", e
                    )
                }
                Log.d(TAG, "trace: loadLibrary ok")
                // 官方移动端语义：配置不带 no_tun，核心以 mobile cfg 启动时自建 tun 被
                // 跳过、挂起等待 LunaVpnService 注入 fd；控制面（连 peer/DHCP）照常。
                // 只有用户显式写 no_tun=true（仅组网模式）才不建 VPN。
                val finalToml = toml
                if (hasNoTun(finalToml)) {
                    EasyTierStateHolder.addLog("配置含 no_tun=true（仅组网模式），将不会建立 VPN")
                }
                EasyTierJNI.parseConfig(finalToml) // 校验，失败会抛 RuntimeException
                Log.d(TAG, "trace: parseConfig ok")
                EasyTierStateHolder.addLog("配置校验通过，启动核心...")
                val rc = EasyTierJNI.runNetworkInstance(finalToml)
                Log.d(TAG, "trace: runNetworkInstance rc=$rc")
                if (rc != 0) {
                    val err = EasyTierJNI.getLastError() ?: "rc=$rc"
                    EasyTierStateHolder.setError("核心启动失败: $err")
                    EasyTierStateHolder.addLog("runNetworkInstance 失败: $err")
                    return@launch
                }
                EasyTierStateHolder.setCoreRunning(true)
                EasyTierStateHolder.addLog("核心已启动，等待虚拟 IP 分配...")
                monitorLoop(context)
            } catch (t: Throwable) {
                // 协程被取消（点「停止」→ monitorScope.cancel）是正常流程，不当作错误
                if (t is kotlinx.coroutines.CancellationException) return@launch
                Log.e(TAG, "start failed", t)
                // 完整错误类型+信息（dlopen failed / No implementation found 等关键细节不能吞）
                val detail = buildString {
                    append(t.javaClass.name)
                    t.message?.takeIf { it.isNotBlank() }?.let { append(": "); append(it.take(300)) }
                }
                EasyTierStateHolder.setError(detail)
                EasyTierStateHolder.addLog("启动异常: $detail")
            }
        }
    }

    fun stop(context: Context, silent: Boolean = false) {
        userStopped = true
        monitorScope?.cancel()
        monitorScope = null
        // 显式 ACTION_STOP（而非裸 stopService）：与轮询协程里 startService 的竞态下，
        // 即使停止指令先到、随后 pollOnce 又拉起服务，后到的 STOP 也能兜底停干净
        runCatching {
            context.startService(
                Intent(context, LunaVpnService::class.java).setAction(LunaVpnService.ACTION_STOP)
            )
        }.onFailure { Log.w(TAG, "send VPN stop failed", it) }
        runCatching { EasyTierJNI.stopAllInstances() }.onFailure {
            Log.w(TAG, "stopAllInstances failed (JNI 未初始化?)", it)
        }
        lastIpv4 = null
        lastProxyCidrs = emptyList()
        EasyTierStateHolder.setCoreRunning(false)
        EasyTierStateHolder.setVpnRunning(false)
        EasyTierStateHolder.setNetInfo(null, emptyList())
        EasyTierStateHolder.setConnSummary("")
        EasyTierStateHolder.setError(null)
        EasyTierStateHolder.clearPeers()
        EasyTierStateHolder.setStats(EasyTierStateHolder.EtStats())
        if (!silent) EasyTierStateHolder.addLog("已停止")
    }

    // ---------------- 状态轮询 ----------------

    private suspend fun monitorLoop(context: Context) {
        val scope = monitorScope ?: return
        while (scope.isActive && EasyTierStateHolder.get().coreRunning) {
            delay(MONITOR_INTERVAL_MS)
            runCatching { pollOnce(context) }.onFailure {
                Log.w(TAG, "monitor error", it)
                // collectNetworkInfos 抛异常（核心线程崩了等）也计入坏轮
                if (++badRounds >= BAD_ROUNDS_TO_RECONNECT) tryReconnect(context)
            }
        }
    }

    /** 断线自动重连：用缓存的 TOML 重启核心（用户手动停止后不走到这里）。 */
    private fun tryReconnect(context: Context) {
        val toml = lastToml ?: return
        if (userStopped) return
        if (reconnectCount >= MAX_RECONNECT_ATTEMPTS) {
            EasyTierStateHolder.addLog("自动重连已连续失败 $reconnectCount 次，停止重试；请手动重新连接")
            stop(context, silent = true)
            EasyTierStateHolder.addLog("已停止")
            return
        }
        reconnectCount++
        badRounds = 0
        EasyTierStateHolder.addLog("检测到断线，自动重连（第 $reconnectCount/$MAX_RECONNECT_ATTEMPTS 次）...")
        // 注意：此刻正跑在旧 monitorLoop 协程里，不能直接 start()（start 会 cancel 掉
        // monitorScope=当前协程自身，后续代码不会执行）——先摘掉旧 scope，另起新协程启动
        monitorScope?.cancel()
        monitorScope = null
        CoroutineScope(Dispatchers.IO).launch { start(context, toml) }
    }

    private fun pollOnce(context: Context) {
        // scope 已被取消（stop 已发）：立即退出，避免竞态下再 startService 拉起 VPN
        if (monitorScope?.isActive != true) return
        // 会话统计：时长 + 进程网络流量（TrafficStats 差值，与 IP 分配无关）
        updateSessionStats()

        val json = try {
            EasyTierJNI.collectNetworkInfos(10)
        } catch (t: Throwable) {
            if (++badRounds >= BAD_ROUNDS_TO_RECONNECT) tryReconnect(context)
            return
        }
        if (json == null || json.isBlank() || json == "{}") {
            // 核心没上报任何实例信息：连续多轮视为断线
            if (++badRounds >= BAD_ROUNDS_TO_RECONNECT) tryReconnect(context)
            return
        }

        val root = JSONObject(json)
        // JNI 层序列化 prost 的 NetworkInstanceRunningInfoMap，实例表嵌在 "map" 字段下：
        // {"map": {"lunashare_et": {...}}}；兼容直接顶层为实例表的形式
        val instances = root.optJSONObject("map") ?: root
        val info = instances.optJSONObject(INSTANCE_NAME)
        if (info == null) {
            if (++badRounds >= BAD_ROUNDS_TO_RECONNECT) tryReconnect(context)
            return
        }
        badRounds = 0
        if (!info.optBoolean("running")) {
            val em = info.optString("error_msg")
            if (em.isNotBlank()) EasyTierStateHolder.setError("核心异常: $em")
            if (++badRounds >= BAD_ROUNDS_TO_RECONNECT) tryReconnect(context)
            return
        }
        EasyTierStateHolder.setError(null)

        // 虚拟 IP：my_node_info.virtual_ipv4 = {address:{addr}, network_length}
        // prost 序列化 Ipv4Addr.addr 为 u32 整数（如 176061955=10.126.126.3），
        // 兼容 [a,b,c,d] 数组形式
        val myNode = info.optJSONObject("my_node_info")
        val v4 = myNode?.optJSONObject("virtual_ipv4")
        val ip = formatV4(v4?.optJSONObject("address"))
        if (ip == null) {
            // IP 未到手：每 5 轮 dump 核心状态摘要，便于定位（连不上 peer / DHCP 未完成等）
            if (++diagRound % 5 == 1) {
                val peerCnt = info.optJSONArray("peer_route_pairs")?.length() ?: -1
                val routesCnt = info.optJSONArray("routes")?.length() ?: -1
                EasyTierStateHolder.addLog(
                    "诊断: my_node_info=${if (myNode == null) "缺失" else "无IP"} " +
                        "peers=$peerCnt routes=$routesCnt " +
                        "json=${json.take(300)}"
                )
            }
            return
        }
        val prefix = v4?.optInt("network_length", 24) ?: 24
        val ipv4WithPrefix = "$ip/$prefix"

        // proxy_cidrs 汇总
        val cidrs = mutableListOf<String>()
        info.optJSONArray("routes")?.let { routes ->
            for (i in 0 until routes.length()) {
                routes.optJSONObject(i)?.optJSONArray("proxy_cidrs")?.let { pc ->
                    for (j in 0 until pc.length()) cidrs.add(pc.getString(j))
                }
            }
        }

        // 连接方式摘要：按 peer_route_pairs 统计 P2P/中继
        val summary = "运行中"
        EasyTierStateHolder.setConnSummary(summary)

        // 组网设备列表：peer_route_pairs（官方 GUI 设备页同源）
        val myPeerId = myNode.optInt("peer_id", -1)
        val rows = mutableListOf<EasyTierStateHolder.PeerRow>()
        info.optJSONArray("peer_route_pairs")?.let { pairs ->
            for (i in 0 until pairs.length()) {
                val p = pairs.optJSONObject(i) ?: continue
                val route = p.optJSONObject("route") ?: continue
                val peerId = route.optInt("peer_id", -1)
                val host = route.optString("hostname").ifBlank { "节点 $peerId" }
                val peerIp = route.optJSONObject("ipv4_addr")?.let { v ->
                    formatV4(v.optJSONObject("address"))?.let { "$it/${v.optInt("network_length", 24)}" }
                } ?: ""
                val cost = route.optInt("cost", 0)
                val lat = route.optInt("path_latency", 0)
                val tunnel = p.optJSONObject("peer")?.optJSONArray("conns")?.optJSONObject(0)
                    ?.optJSONObject("tunnel")?.optString("tunnel_type")?.takeIf { it.isNotBlank() }
                rows.add(
                    EasyTierStateHolder.PeerRow(
                        hostname = host, virtualIp = peerIp,
                        isLocal = peerId == myPeerId,
                        cost = cost,
                        latencyMs = lat.takeIf { it > 0 },
                        tunnelType = tunnel
                    )
                )
            }
        }
        EasyTierStateHolder.setPeers(rows)

        if (ipv4WithPrefix != lastIpv4 || cidrs != lastProxyCidrs) {
            val changed = lastIpv4 != null && ipv4WithPrefix != lastIpv4
            lastIpv4 = ipv4WithPrefix
            lastProxyCidrs = cidrs
            EasyTierStateHolder.setNetInfo(ipv4WithPrefix, cidrs)
            if (!changed) EasyTierStateHolder.addLog("虚拟 IP 已分配: $ipv4WithPrefix")
            // 启动/重建 tun（IP 变化时重建）
            val intent = Intent(context, LunaVpnService::class.java)
                .putExtra(LunaVpnService.EXTRA_IPV4, ipv4WithPrefix)
                .putExtra(LunaVpnService.EXTRA_PROXY_CIDRS, ArrayList(cidrs))
                .putExtra(LunaVpnService.EXTRA_INSTANCE, INSTANCE_NAME)
            context.startService(intent)
        }
    }

    /** 会话统计：时长 + 本进程网络流量（TrafficStats uid 口径，含 tun 转发）。 */
    private fun updateSessionStats() {
        val prev = EasyTierStateHolder.get().stats
        val sessionMs = SystemClock.elapsedRealtime() - sessionStartMs
        val (rx, tx) = runCatching {
            val cur = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
            val cut = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
            // UNSUPPORTED(-1) 或异常基线 → 显示 0
            val rx = if (baseRx >= 0 && cur > baseRx) cur - baseRx else 0L
            val tx = if (baseTx >= 0 && cut > baseTx) cut - baseTx else 0L
            rx to tx
        }.getOrDefault(0L to 0L)
        EasyTierStateHolder.setStats(
            prev.copy(sessionMs = sessionMs, rxBytes = rx, txBytes = tx, reconnects = reconnectCount)
        )
    }

    /**
     * prost 的 Ipv4Addr：{"addr": <u32>}（大端，176061955=10.126.126.3），
     * 兼容数组形式 [a,b,c,d]。无效返回 null。
     */
    private fun formatV4(address: org.json.JSONObject?): String? {
        if (address == null) return null
        address.optJSONArray("addr")?.let { a ->
            if (a.length() >= 4) {
                return "${a.getInt(0)}.${a.getInt(1)}.${a.getInt(2)}.${a.getInt(3)}"
            }
        }
        if (address.has("addr")) {
            val v = address.optLong("addr", -1L)
            if (v in 0..0xFFFFFFFFL) {
                return "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
            }
        }
        return null
    }
}
