package com.lunashare.app.remote

import android.content.Context
import android.util.Log
import com.lunashare.app.config.RemoteConnectionStore
import com.lunashare.app.model.RemoteConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程共享连接管理：单例持有每个 RemoteConnection 的客户端实例与状态。
 *
 * 状态机：idle → connecting → connected / error；断开回 idle。
 * 连接为纯客户端出站连接，无前台服务依赖；app 进程存活期间保持。
 */
object RemoteConnectionManager {

    private const val TAG = "RemoteConnMgr"

    data class RemoteState(
        val connecting: Boolean = false,
        val connected: Boolean = false,
        val error: String? = null,
        val logs: List<String> = emptyList(),
    ) {
        val isBusy: Boolean get() = connecting || connected
    }

    private val _states = MutableStateFlow<Map<String, RemoteState>>(emptyMap())
    val states: StateFlow<Map<String, RemoteState>> = _states.asStateFlow()

    private val clients = ConcurrentHashMap<String, RemoteFileClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getState(id: String): RemoteState = _states.value[id] ?: RemoteState()

    /** 是否有连接中/已连接的远程连接（前台保活判断用）。 */
    fun hasActive(): Boolean = _states.value.values.any { it.connecting || it.connected }

    private fun activeCount(): Int = _states.value.values.count { it.connected }

    /** 连接活跃时把 ShareService 拉成前台（聚合通知+进程保活，防荣耀杀后台断连）。 */
    private fun keepAlive(context: Context) {
        val active = activeCount()
        if (active > 0) {
            com.lunashare.app.service.FgNotificationHelper.updateRemote("远程: $active 个连接")
        } else {
            com.lunashare.app.service.FgNotificationHelper.clearRemote()
        }
        runCatching {
            context.startService(
                android.content.Intent(context, com.lunashare.app.service.ShareService::class.java)
                    .setAction(com.lunashare.app.service.ShareService.ACTION_KEEPALIVE)
            )
        }
    }

    fun addLog(id: String, line: String) {
        Log.d(TAG, "[$id] $line")
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _states.value = _states.value.toMutableMap().apply {
            val st = this[id] ?: RemoteState()
            this[id] = st.copy(logs = (st.logs + "[$ts] $line").takeLast(200))
        }
    }

    private fun setState(id: String, transform: (RemoteState) -> RemoteState) {
        _states.value = _states.value.toMutableMap().apply {
            this[id] = transform(this[id] ?: RemoteState())
        }
    }

    /** 连接（异步）。重复调用幂等：已在连接/已连接时忽略。 */
    fun connect(context: Context, cfg: RemoteConnection) {
        val st = getState(cfg.id)
        if (st.connecting || st.connected) return
        setState(cfg.id) { it.copy(connecting = true, error = null) }
        addLog(cfg.id, "正在连接 ${cfg.displayAddress()} (${RemoteConnection.protocolLabel(cfg.protocol)})...")
        val appContext = context.applicationContext
        scope.launch {
            try {
                val client = withContext(Dispatchers.IO) { RemoteClientFactory.create(cfg) }
                withContext(Dispatchers.IO) { client.connect() }
                clients[cfg.id]?.runCatching { disconnect() }
                clients[cfg.id] = client
                setState(cfg.id) { it.copy(connecting = false, connected = true, error = null) }
                addLog(cfg.id, "已连接")
                keepAlive(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed: ${cfg.name}", e)
                setState(cfg.id) { it.copy(connecting = false, connected = false, error = e.message ?: "连接失败") }
                addLog(cfg.id, "连接失败: ${e.message}")
            }
        }
    }

    /** 断开。 */
    fun disconnect(id: String) {
        setState(id) { it.copy(connecting = false, connected = false, error = null) }
        scope.launch(Dispatchers.IO) {
            clients.remove(id)?.runCatching { disconnect() }
        }
        addLog(id, "已断开")
    }

    /** 断开并通知前台服务刷新保活状态（UI 层调用）。 */
    fun disconnect(context: Context, id: String) {
        disconnect(id)
        keepAlive(context.applicationContext)
    }

    /** 删除配置时清理。 */
    fun remove(context: Context, id: String) {
        disconnect(context, id)
        _states.value = _states.value.toMutableMap().apply { remove(id) }
    }

    /** 取活跃客户端（文件浏览用）；未连接返回 null。 */
    fun clientOf(id: String): RemoteFileClient? =
        if (getState(id).connected) clients[id] else null

    /** app 启动时恢复 autoConnect 的连接（MainActivity 调用）。 */
    fun autoConnectAll(context: Context) {
        val store = RemoteConnectionStore(context)
        store.listConfigs().filter { it.autoConnect }.forEach { connect(context, it) }
    }

    /** 断开全部远程连接（退出 app 用）——防止进程缓存存活导致重开后"假自动连接"。 */
    fun disconnectAll(context: Context) {
        _states.value.keys.toList().forEach { disconnect(context, it) }
    }
}
