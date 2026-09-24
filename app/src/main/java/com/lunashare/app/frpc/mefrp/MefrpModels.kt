package com.lunashare.app.frpc.mefrp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * mefrp（幻缘映射）API 响应信封：{code, data, message}。
 * 与 OpenFrp 的 flag/msg 不同——mefrp 用 HTTP 语义的 code（200 = 成功）。
 */
@Serializable
data class MefrpApiResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null
) {
    val isSuccess: Boolean get() = code == 200
    val displayMessage: String get() = message.ifBlank { "code=$code" }
}

/**
 * mefrp 用户信息（GET /auth/user/info）。
 */
@Serializable
data class MefrpUserInfo(
    val userId: Int? = null,
    val username: String? = null,
    val email: String? = null,
    val group: String? = null,
    val friendlyGroup: String? = null,
    val isRealname: Boolean? = null,
    val maxProxies: Int? = null,
    val usedProxies: Int? = null,
    val traffic: Long? = null,
    val raw: JsonElement? = null
) {
    val displayName: String
        get() = username?.ifBlank { null } ?: email ?: "mefrp 用户"

    val remainingProxies: Int?
        get() = if (maxProxies != null && usedProxies != null) (maxProxies - usedProxies) else null
}

/**
 * mefrp 节点（GET /auth/node/list）。
 */
@Serializable
data class MefrpNode(
    val nodeId: Int = 0,
    val name: String = "",
    val hostname: String? = null,     // 节点连接地址（可能为空）
    val description: String? = null,
    val token: String? = null,        // 节点 Token
    val servicePort: Int? = null,     // 实际连接端口
    val adminPort: Int? = null,
    val allowGroup: String? = null,
    val allowPort: String? = null,    // 允许的端口范围，如 "10000-20000,30000-40000"
    val allowType: String? = null,
    val region: String? = null,
    val bandwidth: String? = null,
    val isOnline: Boolean = false,
    val isDisabled: Boolean = false,
    /** 是否为 VIP 专属节点（普通用户无法创建隧道）。由 [MefrpApiClient.detectVip] 从 API 原始 JSON 容错探测。 */
    val vip: Boolean = false
) {
    val displayName: String
        get() = name.ifBlank { "节点 #$nodeId" }

    /** 可用的远程端口区间（解析 allowPort：「a-b」或逗号分隔多个区段）。 */
    val portRanges: List<IntRange>
        get() = parsePortRanges(allowPort)

    private fun parsePortRanges(raw: String?): List<IntRange> {
        if (raw.isNullOrBlank()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        raw.split(',', '，', ';').forEach { part ->
            val p = part.trim().removePrefix("(").removeSuffix(")")
            val nums = p.split('-')
            if (nums.size == 2) {
                val a = nums[0].trim().toIntOrNull()
                val b = nums[1].trim().toIntOrNull()
                if (a != null && b != null && a > 0 && b >= a) ranges += a..b
            } else if (p.isNotBlank()) {
                p.toIntOrNull()?.takeIf { it > 0 }?.let { ranges += it..it }
            }
        }
        return ranges
    }

    /** 建议的远程端口：取第一个端口区间的随机值（无区间时 null）。 */
    fun suggestedRemotePort(): Int? {
        val r = portRanges.firstOrNull() ?: return null
        return r.random()
    }
}

/**
 * 由节点 allowGroup 派生「VIP 专属节点」标识。
 *
 * mefrp 节点 allowGroup 形如 "admin;sponsor;vip;default"，用 ';' 分隔该节点允许的
 * 用户组。普通用户 group=default，只有 allowGroup 同时含 "default" 才能创建隧道；
 * 若仅含 "vip"（不含 "default"，如长沙①、广州②）则为 VIP 专属节点，普通用户无法创建。
 *
 * 重要：vip 字段不写入缓存（kotlinx.serialization 默认值 false 不序列化），故每次从
 * SharedPreferences 读取节点时都应重新调用本函数派生，否则旧缓存会一律显示 vip=false。
 */
fun MefrpNode.deriveVip(): MefrpNode {
    val groups = (allowGroup ?: "").split(';').map { it.trim().lowercase() }
    return if (groups.contains("vip") && !groups.contains("default")) copy(vip = true) else this
}

/**
 * mefrp 节点选择器排序：在线优先 → 非 VIP 专属优先 → 负载低优先。
 *
 * VIP 专属节点（allowGroup 仅含 "vip" 不含 "default"）普通用户无法创建隧道，
 * 故默认沉到列表末尾，让常用、可用的节点排在最前。
 *
 * 返回新列表，不修改原列表（List.sortedWith 本身即返回新列表）。
 */
fun List<MefrpNode>.sortedForPicker(
    statuses: Map<Int, MefrpNodeStatus>
): List<MefrpNode> = sortedWith(
    compareByDescending<MefrpNode> { it.isOnline }
        .thenBy { it.vip } // false(0) 排前 → 非 VIP 专属节点在前
        .thenBy {
            when (statuses[it.nodeId]?.loadLevel) {
                MefrpNodeStatus.LoadLevel.HIGH -> 2
                MefrpNodeStatus.LoadLevel.LOW -> 0
                else -> 1
            }
        }
)

/**
 * mefrp 隧道（GET /auth/proxy/list）。
 */
@Serializable
data class MefrpProxy(
    val proxyId: Int = 0,
    val username: String? = null,
    val proxyName: String = "",
    val proxyType: String? = null,
    val isBanned: Boolean = false,
    val isDisabled: Boolean = false,
    val localIp: String? = null,
    val localPort: Int? = null,
    val remotePort: Int? = null,
    val nodeId: Int? = null,
    val isOnline: Boolean = false,
    val domain: String? = null
) {
    val isActive: Boolean get() = isOnline && !isBanned && !isDisabled
}

/**
 * mefrp proxy/list 响应 data 里的 nodes 条目（只含该账号隧道用到的节点）。
 * 这里的 hostname 是用户真实可用的节点连接地址（如 ip.lhdyx.top）——
 * node/list 的 hostname 对普通用户恒为空，公网访问地址只能从这里（或
 * 「生成启动配置」的 serverAddr）拿。
 */
@Serializable
data class MefrpProxyListNode(
    val nodeId: Int = 0,
    val name: String = "",
    val hostname: String = "",
    val allowGroup: String? = null
)

/** GET /auth/proxy/list 的完整 data：{nodes: [...], proxies: [...]}。 */
@Serializable
data class MefrpProxyListData(
    val nodes: List<MefrpProxyListNode> = emptyList(),
    val proxies: List<MefrpProxy> = emptyList()
)

/**
 * 创建隧道请求（POST /auth/proxy/create，新版）。
 * httpUser/httpPassword 与 accessKey 不能同时存在；TCP 隧道只传核心字段。
 */
@Serializable
data class MefrpCreateProxyRequest(
    val nodeId: Int,
    val proxyName: String,
    val proxyType: String,
    val localIp: String,
    val localPort: Int,
    val remotePort: Int,
    val domain: String = "",
    val useEncryption: Boolean = false,
    val useCompression: Boolean = false
)

/** 创建隧道附加约束（GET /auth/createProxyData）。宽松解析，仅取可用字段。 */
@Serializable
data class MefrpCreateProxyData(
    val allowPort: String? = null,
    val proxyNumber: Int? = null,
    val maxProxyNumber: Int? = null,
    val raw: JsonElement? = null
)

/**
 * mefrp 节点运行状态（GET /auth/node/status）——官网节点卡片的数据来源。
 * 负载 loadPercent 即官网显示的「负载 79%」；按 nodeId 与 node/list 的节点 join。
 */
@Serializable
data class MefrpNodeStatus(
    val nodeId: Int = 0,
    val name: String = "",
    val totalTrafficIn: Long = 0,
    val totalTrafficOut: Long = 0,
    val onlineClient: Int = 0,
    val onlineProxy: Int = 0,
    val isOnline: Boolean = false,
    val version: String? = null,
    val uptime: Long = 0,
    val curConns: Int = 0,
    val loadPercent: Int? = null
) {
    /** 负载分级（对齐官网展示口径：<60 低 / 60-85 中 / >85 高）。 */
    val loadLevel: LoadLevel
        get() = when {
            loadPercent == null -> LoadLevel.UNKNOWN
            loadPercent < 60 -> LoadLevel.LOW
            loadPercent <= 85 -> LoadLevel.MEDIUM
            else -> LoadLevel.HIGH
        }

    enum class LoadLevel { LOW, MEDIUM, HIGH, UNKNOWN }
}