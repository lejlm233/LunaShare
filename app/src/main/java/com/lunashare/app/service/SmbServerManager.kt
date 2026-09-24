package com.lunashare.app.service

import android.content.Context
import android.util.Log
import com.lunashare.app.model.ShareConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Manages the SMB server process (sambam, statically compiled for Android).
 *
 * All shares with SMB enabled are served by ONE sambam process using
 * multiple `-n name:path` flags, so a single port (445 by default) is used.
 *
 * The engine ships as `libsmb_engine.so` in the native lib dir (a static
 * ELF binary with no dynamic dependencies — the .so name only exists to get
 * past the APK installer's native-lib checks).
 */
object SmbServerManager {

    private const val TAG = "SmbServerManager"
    private const val ENGINE_NAME = "libsmb_engine.so"

    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /** Stop any running instance and clear state. */
    fun stop() {
        val p = process ?: return
        process = null
        try { p.destroy() } catch (_: Exception) {}
        try { if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
        Log.i(TAG, "SMB 服务已停止")
    }

    /**
     * Start (or restart) the SMB server for [shares].
     * @return the bound port on success.
     */
    fun start(context: Context, shares: List<ShareConfig>): Result<Int> {
        stop()
        if (shares.isEmpty()) return Result.failure(IllegalArgumentException("没有启用 SMB 的共享"))

        val engine = File(context.applicationInfo.nativeLibraryDir, ENGINE_NAME)
        if (!engine.exists()) return Result.failure(IOException("SMB 引擎不存在"))

        val port = shares.first().smbPort.coerceIn(1, 65535)
        val args = mutableListOf(engine.absolutePath)

        // Share specs: -share "name:path" (repeatable; name cannot contain ':')
        var auth: String? = null
        for (s in shares) {
            val shareName = s.name.replace(":", "_").ifBlank { "share" }
            args += "-share"
            args += "$shareName:${s.localDir}"
            if (auth == null && s.username.isNotBlank()) {
                auth = "${s.username}:${s.password}"
            }
        }
        args += "-listen"; args += "0.0.0.0:$port"
        if (auth != null) { args += "-user"; args += auth }

        Log.i(TAG, "启动 SMB: ${args.joinToString(" ")}")
        return try {
            val proc = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            process = proc

            // Drain output to logcat + a file (some OEMs hide logcat output)
            thread(name = "smb-log", isDaemon = true) {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, "sambam: $line")
                        try {
                            File("/sdcard/lunashare_smb.log").appendText("$line\n")
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            // Give it a moment to bind; fail fast if it exited
            Thread.sleep(1200)
            if (!proc.isAlive) {
                process = null
                return Result.failure(IOException("SMB 服务启动失败 (exit=${proc.exitValue()})"))
            }

            Log.i(TAG, "SMB 服务已启动: 端口 $port, ${shares.size} 个共享")
            Result.success(port)
        } catch (e: Exception) {
            Log.e(TAG, "SMB 启动异常", e)
            Result.failure(e)
        }
    }
}
