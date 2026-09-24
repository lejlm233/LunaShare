package com.lunashare.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Music file conversion utility used by the file browser.
 *
 * - FLAC files are transcoded to ALAC (Apple Lossless) via FFmpeg, saved as .m4a
 * - Other audio files are copied as-is
 * - All outputs go into a fresh folder created next to the source files
 *
 * ALAC encoding is not supported by Android's MediaCodec, so FFmpeg is used.
 * The ffmpeg executable ships in app assets (extracted from JavaCPP's
 * android-arm64 build); its dependency .so files live in lib/ where the
 * Android linker can find them.
 */
object MusicConverter {

    private const val TAG = "MusicConverter"

    /** Extensions treated as music files (case-insensitive). */
    val AUDIO_EXTS: Set<String> = setOf(
        "flac", "mp3", "m4a", "aac", "wav", "ogg", "opus",
        "wma", "ape", "aiff", "aif", "alac", "amr", "mid", "midi"
    )

    /** Result of converting one file. */
    data class ConvertResult(
        val fileName: String,
        val success: Boolean,
        val message: String
    )

    /** Progress callback: (done, total, currentFileName). */
    fun interface ProgressListener {
        fun onProgress(done: Int, total: Int, fileName: String)
    }

    /**
     * Locate the bundled ffmpeg executable.
     *
     * It ships as `libffmpeg_engine.so` in the app's native library dir —
     * packed that way (instead of app assets / data dir) because on modern
     * Android the app's SELinux domain cannot exec files from its own data
     * directory, while native-lib files (apk_data_file) remain executable.
     *
     * The path is queried fresh from PackageManager: after an in-place update
     * the running process may still cache the OLD code path, whose lib dir
     * no longer exists.
     */
    fun prepareFfmpeg(context: Context): String? {
        val candidates = mutableListOf<String>()
        runCatching {
            val ai = context.packageManager.getApplicationInfo(context.packageName, 0)
            candidates += File(ai.nativeLibraryDir, "libffmpeg_engine.so").absolutePath
        }
        candidates += File(context.applicationInfo.nativeLibraryDir, "libffmpeg_engine.so").absolutePath

        for (p in candidates.distinct()) {
            if (File(p).exists()) return p
        }
        Log.e(TAG, "libffmpeg_engine.so not found (tried: $candidates)")
        return null
    }

    /**
     * Convert [inputs] into [outputDir] using the ffmpeg binary at [ffmpegPath].
     * [libDir] is the app's nativeLibraryDir — the ffmpeg binary links against
     * libav*.so there, and the Android linker needs LD_LIBRARY_PATH to find them.
     * Returns per-file results. Never throws — each file is handled defensively.
     */
    fun convert(
        ffmpegPath: String,
        libDir: String,
        inputs: List<File>,
        outputDir: File,
        onProgress: ProgressListener? = null
    ): List<ConvertResult> {
        val results = mutableListOf<ConvertResult>()
        val total = inputs.size
        var done = 0

        for (input in inputs) {
            done++
            onProgress?.onProgress(done, total, input.name)
            val result = try {
                convertOne(ffmpegPath, libDir, input, outputDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert ${input.name}", e)
                ConvertResult(input.name, false, e.message ?: "未知错误")
            }
            results.add(result)
        }
        return results
    }

    private fun convertOne(ffmpegPath: String, libDir: String, input: File, outputDir: File): ConvertResult {
        val ext = input.extension.lowercase()
        val baseName = input.nameWithoutExtension
        val output = if (ext == "flac") {
            File(outputDir, "$baseName.m4a")   // ALAC in MP4/M4A container
        } else {
            File(outputDir, input.name)
        }

        if (output.exists()) {
            return ConvertResult(input.name, false, "目标文件已存在: ${output.name}")
        }

        return if (ext == "flac") {
            transcodeFlacToAlac(ffmpegPath, libDir, input, output)
        } else {
            copyFile(input, output)
        }
    }

    /** FLAC → ALAC via ffmpeg CLI: `ffmpeg -nostdin -y -i in.flac -map 0:a:0 -c:a alac out.m4a`. */
    private fun transcodeFlacToAlac(ffmpegPath: String, libDir: String, input: File, output: File): ConvertResult {
        return try {
            val cmd = listOf(
                ffmpegPath, "-nostdin", "-y",
                "-i", input.absolutePath,
                "-map", "0:a:0",
                "-c:a", "alac",
                output.absolutePath
            )
            Log.i(TAG, "FFmpeg: ${cmd.joinToString(" ")}")

            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply {
                    // ffmpeg links against libav*.so in the app's native lib dir —
                    // point the child linker there.
                    environment()["LD_LIBRARY_PATH"] = libDir
                }
                .start()
            // Drain output so the child never blocks on a full pipe
            val logTail = proc.inputStream.bufferedReader().use { reader ->
                val lines = mutableListOf<String>()
                reader.forEachLine { if (lines.size < 50) lines.add(it) }
                lines.takeLast(5).joinToString("\n")
            }
            val exit = proc.waitFor()

            if (exit == 0 && output.exists() && output.length() > 0) {
                ConvertResult(input.name, true, "FLAC → ALAC 转换完成")
            } else {
                Log.e(TAG, "FFmpeg failed (exit=$exit):\n$logTail")
                ConvertResult(input.name, false, "转码失败 (exit=$exit)")
            }
        } catch (e: Throwable) {
            // Note: catch Throwable, not Exception — some failures surface as Errors
            val msg = "转码异常 [${e.javaClass.simpleName}] ${e.message}\n${e.stackTraceToString()}"
            Log.e(TAG, "FFmpeg exception", e)
            writeDebugLog(msg)
            ConvertResult(input.name, false, "转码异常 [${e.javaClass.simpleName}]")
        }
    }

    /** Fallback diagnostics file — some OEMs hide app logcat output. */
    private fun writeDebugLog(msg: String) {
        try {
            File("/sdcard/lunashare_debug.log").appendText("$msg\n\n")
        } catch (_: Exception) {}
    }

    /** Plain file copy for non-FLAC audio. */
    private fun copyFile(input: File, output: File): ConvertResult {
        return try {
            FileInputStream(input).use { fis ->
                FileOutputStream(output).use { fos ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
            if (output.exists() && output.length() == input.length()) {
                ConvertResult(input.name, true, "已复制")
            } else {
                ConvertResult(input.name, false, "复制不完整")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Copy failed for ${input.name}", e)
            ConvertResult(input.name, false, e.message ?: "复制失败")
        }
    }
}
