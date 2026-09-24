package com.lunashare.app.easytier

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 组网（EasyTier）全局运行时状态，被 [EasyTierManager]（写）与组网页 UI（读）共享。
 * 模式与 AdbTunnelStateHolder 一致：全局单例功能，不按 shareId 拆分。
 */
object EasyTierStateHolder {

    private val _state = MutableStateFlow(EasyTierState())
    val state: StateFlow<EasyTierState> = _state.asStateFlow()

    /** 组网设备列表行（来自 peer_route_pairs，官方 GUI 设备页同源）。 */
    data class PeerRow(
        val hostname: String,
        val virtualIp: String,
        /** 是否本机节点。 */
        val isLocal: Boolean = false,
        /** cost==1 = P2P 直连；>1 = 经 n-1 跳中继。 */
        val cost: Int = 0,
        /** 端到端延迟（ms），null = 暂不可知。 */
        val latencyMs: Int? = null,
        /** 通道类型（如 Udp/Tcp/WsTls），本机节点为 null。 */
        val tunnelType: String? = null
    )

    /** 本会话统计（轮询采样，UI 只读）。 */
    data class EtStats(
        /** 会话时长 ms（从 start 起算）。 */
        val sessionMs: Long = 0,
        /** tun0 接收字节数（系统接口累计）。 */
        val rxBytes: Long = 0,
        /** tun0 发送字节数。 */
        val txBytes: Long = 0,
        /** 本会话自动重连次数。 */
        val reconnects: Int = 0
    )

    data class EasyTierState(
        /** 核心实例（runNetworkInstance）是否已启动。 */
        val coreRunning: Boolean = false,
        /** tun 是否已建立且 fd 注入成功（即 VPN 数据面工作正常）。 */
        val vpnRunning: Boolean = false,
        /** 本机虚拟 IP（如 10.126.126.2/24），null = 尚未分配。 */
        val virtualIp: String? = null,
        /** 对端 proxy_cidrs 列表。 */
        val proxyCidrs: List<String> = emptyList(),
        /** 连接方式摘要（如「P2P 直连」/「中转」），由节点信息推断。 */
        val connSummary: String = "",
        /** 组网内设备列表（轮询刷新）。 */
        val peers: List<PeerRow> = emptyList(),
        /** 导入的原始 TOML（非空时优先于表单字段生成）。 */
        val importedToml: String? = null,
        /** 本会话统计。 */
        val stats: EtStats = EtStats(),
        val lastError: String? = null,
        val logs: List<String> = emptyList()
    )

    private fun update(transform: EasyTierState.() -> EasyTierState) {
        _state.value = _state.value.transform()
    }

    fun get(): EasyTierState = _state.value

    fun setCoreRunning(v: Boolean) = update { copy(coreRunning = v) }
    fun setVpnRunning(v: Boolean) = update { copy(vpnRunning = v) }
    fun setNetInfo(ip: String?, cidrs: List<String>) = update { copy(virtualIp = ip, proxyCidrs = cidrs) }
    fun setConnSummary(s: String) = update { copy(connSummary = s) }
    fun setPeers(peers: List<PeerRow>) = update { copy(peers = peers) }
    fun clearPeers() = update { copy(peers = emptyList()) }
    fun setImportedToml(t: String?) = update { copy(importedToml = t) }
    fun setStats(s: EtStats) = update { copy(stats = s) }
    fun setError(e: String?) = update { copy(lastError = e) }

    fun addLog(line: String) = update {
        copy(logs = (logs + "[组网] $line").takeLast(300))
    }

    fun clearLogs() = update { copy(logs = emptyList(), lastError = null) }
}
