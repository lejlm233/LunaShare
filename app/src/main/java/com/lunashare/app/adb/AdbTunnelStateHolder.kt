package com.lunashare.app.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局单例的 ADB 穿透运行时状态，被 [com.lunashare.app.service.ShareService]
 * （写）与 ADB 穿透界面（读）共享。
 *
 * 与 ShareStateHolder 不同：ADB 穿透是全局单例功能，不按 shareId 拆分。
 */
object AdbTunnelStateHolder {

    private val _state = MutableStateFlow(AdbTunnelState())
    val state: StateFlow<AdbTunnelState> = _state.asStateFlow()

    data class AdbTunnelState(
        /** 手机本地 127.0.0.1:5555 是否已有 adbd 监听。 */
        val adbdListening: Boolean = false,
        /** 设备是否有 root。 */
        val rootAvailable: Boolean = false,

        /** 用户是否开启了 ADB 穿透（总开关）。 */
        val enabled: Boolean = false,
        /** frpc 反向隧道是否在运行。 */
        val tunnelRunning: Boolean = false,

        /** 公网节点域名/IP（合成 `adb connect` 用）。 */
        val nodeHost: String = "",
        /** 公网远程端口（合成 `adb connect` 用）。 */
        val remotePort: Int? = null,
        /** PC 端连接命令，供复制。 */
        val connectCommand: String = "",

        val lastError: String? = null,
        val logs: List<String> = emptyList()
    )

    private fun update(transform: AdbTunnelState.() -> AdbTunnelState) {
        _state.value = _state.value.transform()
    }

    fun get(): AdbTunnelState = _state.value

    fun setAdbdListening(v: Boolean) = update { copy(adbdListening = v) }
    fun setRootAvailable(v: Boolean) = update { copy(rootAvailable = v) }

    fun setEnabled(v: Boolean) = update { copy(enabled = v) }
    fun setTunnelRunning(v: Boolean) = update { copy(tunnelRunning = v) }

    fun setConnectInfo(host: String, remotePort: Int?) {
        update {
            copy(
                nodeHost = host,
                remotePort = remotePort,
                connectCommand = if (host.isNotBlank() && remotePort != null && remotePort > 0)
                    AdbTunnelManager.buildConnectCommand(host, remotePort)
                else ""
            )
        }
    }

    fun setError(e: String?) = update { copy(lastError = e) }

    fun addLog(line: String) = update {
        val max = 300
        copy(logs = (logs + "[ADB] $line").takeLast(max))
    }

    fun clearLogs() = update { copy(logs = emptyList(), lastError = null) }
}
