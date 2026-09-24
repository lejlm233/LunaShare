package com.lunashare.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds per-share runtime state, shared between the foreground service
 * (which writes) and the Activity (which observes).
 */
object ShareStateHolder {

    private val _shareStates = MutableStateFlow<Map<String, ShareState>>(emptyMap())
    val shareStates: StateFlow<Map<String, ShareState>> = _shareStates.asStateFlow()

    data class ShareState(
        val httpRunning: Boolean = false,
        val ftpRunning: Boolean = false,
        val smbRunning: Boolean = false,
        val httpLogs: List<String> = emptyList(),
        val ftpLogs: List<String> = emptyList(),
        val smbLogs: List<String> = emptyList(),
        val combinedLogs: List<String> = emptyList(),
        val lastError: String? = null,

        // ── OpenFrp / frpc1 穿透状态 ───────────────────────────────
        /** frpc tunnel running for the HTTP proxy. */
        val frpcHttpRunning: Boolean = false,
        /** frpc tunnel running for the FTP proxy. */
        val frpcFtpRunning: Boolean = false,
        /** OpenFrp HTTP tunnel logs (frpc stdout/stderr). */
        val frpcHttpLogs: List<String> = emptyList(),
        /** OpenFrp FTP tunnel logs (frpc stdout/stderr). */
        val frpcFtpLogs: List<String> = emptyList()
    ) {
        val isAnyRunning: Boolean get() = httpRunning || ftpRunning || smbRunning

        /** True if any OpenFrp tunnel for this share is up. */
        val isAnyFrpcRunning: Boolean get() = frpcHttpRunning || frpcFtpRunning
    }

    /** Get current state for a share. */
    fun getState(shareId: String): ShareState =
        _shareStates.value[shareId] ?: ShareState()

    /** Set HTTP running state. */
    fun setHttpRunning(shareId: String, running: Boolean) {
        update(shareId) { copy(httpRunning = running) }
    }

    /** Set FTP running state. */
    fun setFtpRunning(shareId: String, running: Boolean) {
        update(shareId) { copy(ftpRunning = running) }
    }

    /** Set SMB running state. */
    fun setSmbRunning(shareId: String, running: Boolean) {
        update(shareId) { copy(smbRunning = running) }
    }

    /** Record an error. */
    fun setError(shareId: String, error: String) {
        update(shareId) { copy(lastError = error) }
    }

    /** Append a log line for HTTP service. */
    fun addHttpLog(shareId: String, line: String) {
        update(shareId) {
            val max = 200
            val newLogs = (httpLogs + line).takeLast(max)
            val newCombined = (combinedLogs + "[HTTP] $line").takeLast(max)
            copy(httpLogs = newLogs, combinedLogs = newCombined)
        }
    }

    /** Append a log line for FTP service. */
    fun addFtpLog(shareId: String, line: String) {
        update(shareId) {
            val max = 200
            val newLogs = (ftpLogs + line).takeLast(max)
            val newCombined = (combinedLogs + "[FTP] $line").takeLast(max)
            copy(ftpLogs = newLogs, combinedLogs = newCombined)
        }
    }

    /** Append a log line for SMB service. */
    fun addSmbLog(shareId: String, line: String) {
        update(shareId) {
            val max = 200
            val newLogs = (smbLogs + line).takeLast(max)
            val newCombined = (combinedLogs + "[SMB] $line").takeLast(max)
            copy(smbLogs = newLogs, combinedLogs = newCombined)
        }
    }

    // ── OpenFrp helpers ──────────────────────────────────────────

    fun setFrpcHttpRunning(shareId: String, running: Boolean) {
        update(shareId) { copy(frpcHttpRunning = running) }
    }

    fun setFrpcFtpRunning(shareId: String, running: Boolean) {
        update(shareId) { copy(frpcFtpRunning = running) }
    }

    fun addFrpcHttpLog(shareId: String, line: String) {
        update(shareId) {
            val max = 300
            val newLogs = (frpcHttpLogs + line).takeLast(max)
            val newCombined = (combinedLogs + "[FRPC-HTTP] $line").takeLast(max)
            copy(frpcHttpLogs = newLogs, combinedLogs = newCombined)
        }
    }

    fun addFrpcFtpLog(shareId: String, line: String) {
        update(shareId) {
            val max = 300
            val newLogs = (frpcFtpLogs + line).takeLast(max)
            val newCombined = (combinedLogs + "[FRPC-FTP] $line").takeLast(max)
            copy(frpcFtpLogs = newLogs, combinedLogs = newCombined)
        }
    }

    /** Append a log line for encrypted upload (shown in combined logs with [ENC] prefix). */
    fun addEncLog(shareId: String, line: String) {
        update(shareId) {
            val max = 200
            val newCombined = (combinedLogs + "[ENC] $line").takeLast(max)
            copy(combinedLogs = newCombined)
        }
    }

    /** Clear all logs for a share. */
    fun clearLogs(shareId: String) {
        update(shareId) {
            copy(
                httpLogs = emptyList(),
                ftpLogs = emptyList(),
                smbLogs = emptyList(),
                frpcHttpLogs = emptyList(),
                frpcFtpLogs = emptyList(),
                // 清空后保留一条可见确认行，避免「清了没反应」的观感（后续新日志会追加其后）
                combinedLogs = listOf("[系统] 日志已清空")
            )
        }
    }

    /** Remove a share from state entirely (when config is deleted). */
    fun removeShare(shareId: String) {
        val map = _shareStates.value.toMutableMap()
        map.remove(shareId)
        _shareStates.value = map
    }

    /** Clear all logs across all shares. */
    fun clearAll() {
        val map = _shareStates.value.mapValues { (_, state) ->
            state.copy(
                httpLogs = emptyList(),
                ftpLogs = emptyList(),
                smbLogs = emptyList(),
                frpcHttpLogs = emptyList(),
                frpcFtpLogs = emptyList(),
                combinedLogs = emptyList()
            )
        }
        _shareStates.value = map
    }

    // ── Helpers ──

    private fun update(shareId: String, transform: ShareState.() -> ShareState) {
        val map = _shareStates.value.toMutableMap()
        val current = map[shareId] ?: ShareState()
        map[shareId] = current.transform()
        _shareStates.value = map
    }
}
