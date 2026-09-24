package com.lunashare.app.frpc.model

import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Base response wrapper for all OpenFrp API endpoints.
 *
 * All OpenFrp API responses contain at least `flag` (success indicator)
 * and `msg` (human-readable message) at the top level.
 */
@Serializable
data class OpenFrpApiResponse<T>(
    val flag: Boolean = false,
    val msg: String = "",
    val data: T? = null,

    // Some endpoints use alternative naming
    val status: Int? = null,
    val success: Boolean? = null,
    val message: String? = null
) {
    val isSuccess: Boolean get() = flag || (success == true) || (status == 200)
    val displayMessage: String get() = msg.ifBlank { message ?: "" }
}

/**
 * Authenticated user information returned by getUserInfo.
 */
@Serializable
data class OpenFrpUserInfo(
    val id: Int? = null,
    val username: String? = null,
    val email: String? = null,
    val nickname: String? = null,
    val avatar: String? = null,

    // frpc login token (user secret, 32 chars) — THIS is what frpc uses to
    // connect to frps, NOT the API `authorization`. Null until fetched.
    val token: String? = null,

    // Quota / usage fields
    val bandwidth: Long? = null,
    val trafficInbound: Long? = null,
    val trafficOutbound: Long? = null,
    val trafficUsed: Long? = null,
    val trafficRemaining: Long? = null,

    // For raw passthrough
    val raw: JsonElement? = null
) {
    val displayName: String
        get() = nickname?.ifBlank { null } ?: username ?: email ?: "OpenFrp 用户"
}

/**
 * Coarse health/usability of an OpenFrp node for the current (assumed normal-tier)
 * account. NOTE: this reflects the node's *administrative* status from the API.
 * It CANNOT guarantee the data-plane (relay) actually works — some "online" nodes
 * still fail to forward traffic (e.g. the previously-broken 北京移动 node 45).
 * Always verify after creating a tunnel.
 */
enum class NodeAvailability { AVAILABLE, RESTRICTED, UNAVAILABLE }

/**
 * An OpenFrp network node (server) that can host proxies.
 */
@Serializable
data class OpenFrpNode(
    val id: Int = 0,
    val name: String = "",
    val node: String? = null,       // Alternative name field
    val area: String? = null,       // Region / location
    val location: String? = null,   // Alternative region field
    val bandwidth: Int? = null,     // Mbps
    val bandwidthUp: Int? = null,
    val bandwidthDown: Int? = null,
    val protocol: String? = null,   // e.g. "kcp", "tcp"
    val types: List<String>? = null, // Supported proxy types (tcp, udp, http, https)
    val status: Int? = null,        // 1 = online
    val online: Boolean? = null,
    val hostname: String? = null,    // Server hostname for frpc connection
    val hostnameExt: String? = null,  // Alternative hostname
    val allowPort: String? = null,    // Remote-port range hint, e.g. "(50000,60000)"; null/"" = no limit
    val description: String? = null,  // Node description / small-print text
    val fullyLoaded: Boolean? = null, // 节点是否已满载（满载则不可建新隧道）
    val needRealname: Boolean? = null, // 是否需要实名认证
    val group: String? = null,        // 可用账号等级，如 "normal,svip"
    val comments: String? = null,     // 备注，如 "调整中,暂不接受新站创建"
    // frps server port (e.g. 7000, 8120). Kept as raw JsonElement because OpenFrp
    // returns a STRING placeholder "您无权查询此节点的地址" for nodes the account
    // has no access to — decoding into Int? would crash the WHOLE node list.
    val port: JsonElement? = null
) {
    val displayName: String
        get() = name.ifBlank { node ?: "节点 #$id" }

    val displayLocation: String
        get() = area ?: location ?: ""

    /**
     * 节点是否在线。OpenFrp API 实测返回 status=200 表示在线（并非 1），
     * 因此同时兼容 status==200 与 status==1 两种取值。
     */
    val isOnline: Boolean
        get() = (status == 200) || (status == 1) || (online == true)

    /**
     * 当前（假定 normal 普通等级）账号在该节点的可用性。
     * 仅反映节点*管理面*状态，不保证数据面 relay 真能通：
     *  - AVAILABLE:   可建隧道
     *  - RESTRICTED:  受限（需实名 / 维护调整中 / 等级不符），可能建不了或体验差
     *  - UNAVAILABLE: 不可用（离线 / 已满载）
     */
    val availability: NodeAvailability
        get() {
            if (!isOnline) return NodeAvailability.UNAVAILABLE
            if (fullyLoaded == true) return NodeAvailability.UNAVAILABLE
            if (needRealname == true) return NodeAvailability.RESTRICTED
            val c = comments.orEmpty()
            if (c.contains("维护") || c.contains("调整") || c.contains("不接受新站") || c.contains("暂不"))
                return NodeAvailability.RESTRICTED
            val g = group?.trim().orEmpty()
            if (g.isNotEmpty() && !g.split(",").any { it.trim() == "normal" })
                return NodeAvailability.RESTRICTED
            return NodeAvailability.AVAILABLE
        }

    /** 不可用 / 受限的具体原因，用于 UI 展示（不含等级限制——等级改用右上角角标）。 */
    val restrictionReasons: List<String>
        get() {
            val r = mutableListOf<String>()
            if (!isOnline) r += "节点离线"
            if (fullyLoaded == true) r += "节点已满载"
            if (needRealname == true) r += "需要实名认证"
            comments?.takeIf { it.isNotBlank() }?.let { r += "备注: $it" }
            return r
        }

    /**
     * 节点要求的付费等级角标（仅 VIP / SVIP）。普通 normal 等级不显示。
     * 用于卡片右上角、状态 chip 的左侧，提示「为何受限」。
     */
    val tierBadge: String?
        get() {
            val g = group?.lowercase().orEmpty()
            // 普通用户(normal)可用的节点不显示 VIP/SVIP 角标，避免误导「需要付费」
            if (g.contains("normal")) return null
            return when {
                g.contains("svip") -> "SVIP"
                g.contains("vip") -> "VIP"
                else -> null
            }
        }

    val serverHost: String
        get() = hostnameExt?.ifBlank { null } ?: hostname ?: ""

    /** Resolved numeric frps port. Null when the node hides it behind
     *  the "您无权查询此节点的地址" string placeholder (account lacks access). */
    val portValue: Int?
        get() = port?.toString()?.trim('"')?.toIntOrNull()

    /**
     * Parsed remote-port range from [allowPort], e.g. "(50000,60000)" -> 50000..60000.
     * Returns null when the node imposes no port restriction.
     */
    val allowPortRange: IntRange?
        get() {
            val raw = allowPort?.takeIf { it.isNotBlank() } ?: return null
            val m = Regex("\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(raw) ?: return null
            val (a, b) = m.destructured
            val lo = a.toIntOrNull() ?: return null
            val hi = b.toIntOrNull() ?: return null
            if (hi < lo) return null
            return lo..hi
        }

    /** Human-readable small-print hint mirroring the official web "小字". */
    val allowPortHint: String
        get() = allowPortRange?.let { "远端端口范围: ${it.first} - ${it.last}" }
            ?: "远端端口: 无限制（请自行填写 1-65535）"

    /** 默认远程端口：在允许范围内随机选一个，避免每次都填同一个固定端口；无范围限制则用本地端口。 */
    fun suggestedRemotePort(localPort: Int): Int {
        val range = allowPortRange ?: return localPort
        if (range.isEmpty()) return localPort
        return Random.nextInt(range.first, range.last + 1)
    }
}

/**
 * A single proxy (tunnel) configuration owned by the user.
 */
@Serializable
data class OpenFrpProxy(
    val id: Int = 0,
    val proxyId: Int? = null,       // Alternative id field
    val proxy_id: Int? = null,      // OpenFrp API actually returns this snake_case field
    val name: String = "",
    val proxyName: String? = null,  // Alternative name field

    val nodeId: Int? = null,
    val node_id: Int? = null,
    val nodeName: String? = null,

    // Connection config
    val type: String = "tcp",       // tcp, udp, http, https, stcp, xtcp
    val proxyType: String? = null,

    val localAddr: String? = null,
    val local_addr: String? = null,
    val localPort: Int? = null,
    val local_port: Int? = null,

    val remotePort: Int? = null,
    val remote_port: Int? = null,

    // HTTP/HTTPS specific
    val domain: String? = null,
    val domainBind: String? = null,
    val domain_bind: String? = null,
    val hostRewrite: String? = null,
    val host_rewrite: String? = null,
    val urlRoute: String? = null,
    val url_route: String? = null,

    // Options
    val dataGzip: Boolean? = null,
    val data_gzip: Boolean? = null,
    val dataEncrypt: Boolean? = null,
    val data_encrypt: Boolean? = null,

    // Runtime status
    val status: Boolean? = null,    // API actually returns a boolean (true = running);
                                     // some docs show 1/0, so keep tolerant.
    val running: Boolean? = null,
    val online: Boolean? = null,

    // Timestamps
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val proxyIdValue: Int
        get() = proxy_id ?: proxyId ?: id

    val displayName: String
        get() = name.ifBlank { proxyName ?: "隧道 #$proxyIdValue" }

    /** Server indicates this proxy is currently running/online (status may be
     *  boolean true or 1; null/0 means unknown or offline). */
    val isActive: Boolean
        get() = status == true || running == true || online == true

    val nodeIdValue: Int?
        get() = node_id ?: nodeId

    val localAddrValue: String
        get() = local_addr ?: localAddr ?: "127.0.0.1"

    val localPortValue: Int
        get() = local_port ?: localPort ?: 0

    val remotePortValue: Int?
        get() = remote_port ?: remotePort

    val proxyTypeValue: String
        get() = proxyType ?: type

    val isRunning: Boolean
        get() = (running == true) || (online == true) || (status == true)
}

/**
 * Request body for creating a new proxy via newProxy API.
 *
 * Fields match the OpenFrp API documentation exactly.
 */
@Serializable
data class NewProxyRequest(
    val node_id: Int,
    val type: String,
    val local_port: String,
    val local_addr: String = "127.0.0.1",
    val name: String,
    val remote_port: Int? = null,
    val domain_bind: String = "",
    val dataEncrypt: Boolean = false,
    val dataGzip: Boolean = false,
    val autoTls: String = "false",
    val forceHttps: Boolean = false,
    val proxyProtocolVersion: Boolean = false,
    val custom: String = ""
)

/**
 * Request body for removing a proxy via removeProxy API.
 */
@Serializable
data class RemoveProxyRequest(
    val proxy_id: Int
)

/**
 * A user-created OpenFrp tunnel (proxy) with all info needed to
 * auto-populate a ShareConfig for frpc punching.
 *
 * Created via the in-app "Create Tunnel" dialog.
 */
@Serializable
data class OpenFrpTunnelInfo(
    val proxyId: Int,
    val nodeId: Int,
    val nodeHost: String,
    val remotePort: Int? = null,
    val type: String = "tcp",
    val name: String = "",
    val localPort: Int = 8080,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Persisted OpenFrp account credentials stored on device.
 *
 * Uses the Security Login (Authorization header) approach — users paste
 * a token obtained from the OpenFrp dashboard.
 */
@Serializable
data class OpenFrpAccount(
    val authorization: String = "",
    val session: String = "",
    val frpToken: String = "",      // frpc login token from getUserInfo().token
    val userInfo: OpenFrpUserInfo? = null,
    val lastLoginAt: Long = 0L
) {
    val isLoggedIn: Boolean get() = authorization.isNotBlank()
}
