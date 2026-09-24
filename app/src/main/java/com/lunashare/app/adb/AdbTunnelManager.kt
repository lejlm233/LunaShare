package com.lunashare.app.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 设备侧（纯 Kotlin，无 frpc）的 ADB 穿透辅助函数。
 *
 * 负责两件事：
 *  1. [isAdbdListening] —— 探测手机本地 `127.0.0.1:5555` 是否有 adbd 在监听；
 *  2. [enableAdbdPort]  —— 尝试把 adbd 切到 TCP 5555 监听：
 *       • root/su：执行 `setprop persist.adb.tcp.port 5555` + 重启 adbd（开机自启、永久）；
 *       • 无 root：返回 `adb tcpip 5555` 命令，引导用户在 PC 上执行。
 *
 * 设计说明（重要）：
 *  本 App 不再尝试通过 Shizuku 自动开启 5555（部分 ROM 限制 setprop / 重启 adbd
 *  的权限，实测在 vivo 上 `ShizukuSystemProperties.set` 与 `setprop` 均被拒）。
 *  改为由用户在 PC 上执行 `adb tcpip 5555` 命令开启，App 仅负责：
 *    - 探测 5555 是否已监听（[isAdbdListening]）；
 *    - 把 5555 通过 OpenFrp 反向隧道映射到公网（frpc，由 ShareService 负责）。
 *
 * 本类的角色是「尽力开启 + 如实反馈」：每次开启后都用 [isAdbdListening] 复核。
 */
object AdbTunnelManager {

    private const val TAG = "AdbTunnelManager"
    const val ADBD_PORT = 5555

    /** 探测手机本地 `127.0.0.1:5555` 是否有 adbd 在监听。 */
    suspend fun isAdbdListening(port: Int = ADBD_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 1500)
                Log.i(TAG, "adbd listening on 127.0.0.1:$port")
                true
            }
        } catch (e: Exception) {
            Log.i(TAG, "adbd NOT listening on 127.0.0.1:$port (${e.message})")
            false
        }
    }

    // ── root/su ───────────────────────────────────────────────

    /** 设备是否有 root（su 可执行）。 */
    fun isRootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        code == 0 && out.contains("uid=0")
    } catch (t: Throwable) {
        Log.d(TAG, "isRootAvailable: false (${t.message})")
        false
    }

    /** 通过 su 执行命令。 */
    fun runViaSu(command: String): ShellResult = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = p.inputStream.bufferedReader().readText()
        val stderr = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        ShellResult(code, stdout, stderr)
    } catch (t: Throwable) {
        Log.e(TAG, "runViaSu failed: ${t.message}", t)
        ShellResult(-1, "", t.message ?: "unknown error")
    }

    // ── 统一入口：按优先级尝试开启 5555 ───────────────────────────

    /**
     * 开启 adbd 的 TCP 5555 监听。按优先级尝试：
     *   1. root（persist 属性，开机自启、永久）
     *   2. 失败 → 返回 [EnableResult.fallbackCommand]（`adb tcpip 5555`）供 UI 复制引导。
     *
     * @param persistIfRoot 仅当走 root 档时是否写 persist 属性（开机自启）。
     */
    suspend fun enableAdbdPort(
        port: Int = ADBD_PORT,
        persistIfRoot: Boolean = true
    ): EnableResult = withContext(Dispatchers.IO) {

        // 先复核：已经监听就直接返回成功，省得重复操作。
        if (isAdbdListening(port)) {
            return@withContext EnableResult(
                method = EnableMethod.ALREADY_OPEN,
                success = true,
                listening = true,
                message = "5555 端口已在监听"
            )
        }

        // ── root 档 ──
        if (isRootAvailable()) {
            Log.i(TAG, "enableAdbdPort via root")
            val prop = if (persistIfRoot) "persist.adb.tcp.port" else "service.adb.tcp.port"
            val cmd = "setprop $prop $port && setprop ctl.restart adbd && stop adbd && start adbd"
            val r = runViaSu(cmd)
            delayMs(3000)
            if (isAdbdListening(port)) {
                return@withContext EnableResult(
                    method = if (persistIfRoot) EnableMethod.ROOT_PERSIST else EnableMethod.ROOT,
                    success = true,
                    listening = true,
                    message = if (persistIfRoot)
                        "已通过 root 永久开启 5555（persist 属性，开机自启）"
                    else
                        "已通过 root 开启 5555（重启后失效）"
                )
            }
            return@withContext EnableResult(
                method = if (persistIfRoot) EnableMethod.ROOT_PERSIST else EnableMethod.ROOT,
                success = false,
                listening = false,
                message = "root 命令已执行但 5555 仍未监听（${r.stderr.ifBlank { r.stdout }}）"
            )
        }

        // ── 无 root：USB / 无线调试 兜底 ──
        EnableResult(
            method = EnableMethod.USB_FALLBACK,
            success = false,
            listening = false,
            message = "本机无 root。请用数据线或无线调试把手机连上电脑，执行下方命令开启 5555。",
            fallbackCommand = "adb tcpip $port"
        )
    }

    /**
     * 关闭 TCP 5555 监听（切回仅 USB）。root 清掉 persist 才会开机也不自动开。
     */
    suspend fun disableAdbdPort(
        port: Int = ADBD_PORT,
        clearPersist: Boolean = true
    ): EnableResult = withContext(Dispatchers.IO) {
        if (isRootAvailable()) {
            val cmd = buildString {
                if (clearPersist) append("setprop persist.adb.tcp.port \"\"; ")
                append("setprop service.adb.tcp.port \"\" && setprop ctl.restart adbd && stop adbd && start adbd")
            }
            runViaSu(cmd)
            delayMs(2000)
            return@withContext EnableResult(
                method = EnableMethod.ROOT,
                success = !isAdbdListening(port),
                listening = isAdbdListening(port),
                message = if (!isAdbdListening(port)) "已关闭 5555（TCP 监听已停）" else "已执行关闭命令但 5555 仍在监听"
            )
        }
        EnableResult(
            method = EnableMethod.USB_FALLBACK,
            success = false,
            listening = isAdbdListening(port),
            message = "无 root 权限，无法远程关闭。可在 PC 执行 `adb usb` 切回仅 USB。",
            fallbackCommand = "adb usb"
        )
    }

    /** PC 端连接命令（隧道建好后展示给用户复制）。 */
    fun buildConnectCommand(host: String, remotePort: Int): String =
        "adb connect $host:$remotePort"

    private fun delayMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    // ── 结果类型 ──

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val succeeded: Boolean get() = exitCode == 0
    }

    enum class EnableMethod {
        /** 已经监听，无需操作。 */
        ALREADY_OPEN,
        /** root（运行时生效，重启失效）。 */
        ROOT,
        /** root（persist 属性，开机自启永久）。 */
        ROOT_PERSIST,
        /** 无 root，需用户用 USB / 无线调试 在 PC 执行命令。 */
        USB_FALLBACK
    }

    data class EnableResult(
        val method: EnableMethod,
        val success: Boolean,
        val listening: Boolean,
        val message: String,
        val fallbackCommand: String? = null
    )
}
