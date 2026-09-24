package com.lunashare.app.frpc

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Locates the bundled frpc executable.
 *
 * The frpc binary ships as `libfrpc.so` inside the APK's native library
 * directory. It is packed that way (rather than under assets/ or the app's
 * data dir) because on modern Android (API 29+) the app's SELinux domain
 * cannot exec files from its own writable data directory, while native-lib
 * files (apk_data_file) remain executable. `useLegacyPackaging = true` in
 * build.gradle.kts ensures the .so is extracted to disk at install time.
 *
 * The path is queried fresh from PackageManager on every call: after an
 * in-place update the running process may still cache the OLD code path,
 * whose lib dir no longer exists.
 */
class FrpcBinaryManager(private val context: Context) {

    companion object {
        private const val TAG = "FrpcBinaryMgr"
        private const val LIB_NAME = "libfrpc.so"
        private const val MEFRPC_LIB_NAME = "libmefrpc.so"

        /**
         * 已知「公网数据面不兼容」的 frpc 构建的 SHA-256 集合。
         *
         * 命中即说明该二进制是旧的坏构建（控制面可连接、隧道显示启动成功，
         * 但 frpc 与服务端 frps 的数据面协议不兼容，远程连接无法转发）。
         * 此前内置的是 OF_0.68.0 旧 build（编译于 2026-03，哈希见下），
         * 已替换为 OpenFrp 后台分发的最新官方定制版
         * （OF_0.68.0_37f78258_260326，SHA256 47482d31…da59e2c6），该版不再命中。
         *
         * 注意：不能用版本号（如 "0.68"）判定，因为官方最新版同为 0.68.0 大版本，
         * 按版本号会误伤正常构建。改用哈希精确识别旧坏包。
         */
        private val KNOWN_BAD_SHA256 = setOf(
            "2ca505e7fe3a9cd10f797395fe3b2663c39d9727217961527a373f752969a23c" // 旧坏 build (OF_0.68.0, 2026-03)
        )
    }

    /**
     * Absolute path to the usable frpc binary, or null if not bundled.
     */
    fun getBinaryPath(): String? {
        val candidates = mutableListOf<String>()
        runCatching {
            val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
            candidates += File(ai.nativeLibraryDir, LIB_NAME).absolutePath
        }
        candidates += File(context.applicationInfo.nativeLibraryDir, LIB_NAME).absolutePath

        for (p in candidates.distinct()) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return p
        }
        Log.w(TAG, "frpc binary not found (tried: $candidates)")
        return null
    }

    /** Absolute path to the bundled mefrpc (mefrp custom frpc) binary, or null. */
    fun getMefrpBinaryPath(): String? {
        val candidates = mutableListOf<String>()
        runCatching {
            val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
            candidates += File(ai.nativeLibraryDir, MEFRPC_LIB_NAME).absolutePath
        }
        candidates += File(context.applicationInfo.nativeLibraryDir, MEFRPC_LIB_NAME).absolutePath
        for (p in candidates.distinct()) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return p
        }
        Log.w(TAG, "mefrpc binary not found (tried: $candidates)")
        return null
    }

    /** True if the frpc binary is available. */
    fun isBinaryAvailable(): Boolean = getBinaryPath() != null

    /** Get the file size of the current binary in bytes, or -1. */
    fun getBinarySize(): Long {
        val path = getBinaryPath() ?: return -1L
        return File(path).length()
    }

    /** Get the SHA-256 hash of the current binary, or null. */
    fun getBinaryHash(): String? {
        val path = getBinaryPath() ?: return null
        return try {
            val bytes = File(path).readBytes()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Run `frpc --version` and capture the output.
     * Returns the version string, or null if the binary can't run.
     */
    fun getVersion(): String? {
        val path = getBinaryPath() ?: return null
        return try {
            val proc = ProcessBuilder(path, "--version")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            Log.d(TAG, "frpc --version → exit=$code, output='$output'")
            if (output.isNotBlank()) output else null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get frpc version: ${e.message}", e)
            null
        }
    }

    /**
     * Run `mefrpc --version` (fallback `-v` — 定制版可能不认 --version) and
     * capture the output. Returns the version string, or null if the binary
     * can't run.
     */
    fun getMefrpVersion(): String? {
        val path = getMefrpBinaryPath() ?: return null
        return try {
            var output = runVersion(path, "--version")
            if (output.isNullOrBlank()) output = runVersion(path, "-v")
            if (output.isNullOrBlank()) null else output
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get mefrpc version: ${e.message}", e)
            null
        }
    }

    private fun runVersion(path: String, flag: String): String? {
        return try {
            val proc = ProcessBuilder(path, flag).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            Log.d(TAG, "mefrpc $flag → exit=$code, output='$output'")
            if (output.isNotBlank()) output else null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot run mefrpc $flag: ${e.message}", e)
            null
        }
    }

    /**
     * Detect whether the bundled frpc is a known build with the public-network
     * data-plane forwarding bug (control plane connects, tunnel reports
     * "started", but remote traffic is never delivered to the local service).
     *
     * Heuristic only: when the version can't be determined (binary can't run
     * `--version`) we return false to avoid false alarms. Pair this with a
     * runtime data-plane reachability probe (see ShareService) for a definitive
     * signal.
     */
    fun isKnownIncompatible(): Boolean {
        val hash = getBinaryHash() ?: return false
        return hash in KNOWN_BAD_SHA256
    }

    /** User-facing explanation when [isKnownIncompatible] is true, else null. */
    fun incompatibilityReason(): String? =
        if (isKnownIncompatible())
            "检测到内置 frpc 为已知数据面不兼容的旧构建（SHA256 命中旧版）：" +
            "隧道会显示「启动成功」但公网地址实际无法访问。请重新安装已内置 OpenFrp " +
            "官方最新定制版（OF_0.68.0_37f78258_260326）的 APK。局域网访问不受影响。"
        else null
}
