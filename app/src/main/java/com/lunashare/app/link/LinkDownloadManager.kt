package com.lunashare.app.link

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.lunashare.app.util.EncodingFixer

/**
 * Link 浏览器下载管理（单例，StateFlow 驱动 UI，仿 ShareStateHolder 模式）。
 * - 下载用 OkHttp 流式写入下载目录，带 WebView 的 Cookie/User-Agent（兼容登录态站点），跟随重定向；
 * - 进度每 ~200ms 刷新一次，Content-Length 缺失时进度条走不确定模式；
 * - 任务列表最多保留 20 条；完成后可经 FileProvider「打开」文件。
 */
object LinkDownloadManager {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _downloadDir = MutableStateFlow("")
    val downloadDir: StateFlow<String> = _downloadDir.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idGen = AtomicLong(0)
    private val runningCalls = ConcurrentHashMap<Long, Call>()

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 从持久化存储加载下载目录（幂等，LinkScreen / FileListScreen 首次组合时调用）。 */
    fun init(context: Context) {
        _downloadDir.value = LinkDownloadStore(context).downloadDir
    }

    /** 设置页修改下载目录后刷新全局状态。 */
    fun refreshDir(context: Context) {
        _downloadDir.value = LinkDownloadStore(context).downloadDir
    }

    /** 直接设置下载目录（含持久化）。 */
    fun setDir(context: Context, dir: String) {
        LinkDownloadStore(context).downloadDir = dir
        _downloadDir.value = dir
    }

    /**
     * WebView 下载监听回调入口：解析文件名 → 创建任务 → 后台流式下载。
     *
     * @param contentDisposition 如 `attachment; filename="a.pdf"` / `filename*=UTF-8''xx`
     * @param contentLength      Content-Length，-1 表示未知
     */
    fun start(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val dirPath = _downloadDir.value.ifBlank { LinkDownloadStore(context).downloadDir }
        _downloadDir.value = dirPath
        val fileName = uniqueFileName(dirPath, parseFileName(contentDisposition, url))
        val id = idGen.incrementAndGet()
        val task = DownloadTask(
            id = id,
            url = url,
            fileName = fileName,
            mimeType = mimeType ?: "",
            totalBytes = if (contentLength > 0) contentLength else -1,
            userAgent = userAgent
        )
        _tasks.value = (_tasks.value + task).takeLast(MAX_TASKS)

        // 带页面登录态：CookieManager 里当前域的 cookie + WebView 的 User-Agent
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent.ifBlank { "Mozilla/5.0" })
            .header("Referer", url)
            .apply { if (cookie != null) header("Cookie", cookie) }
            .build()

        scope.launch {
            var call: Call? = null
            try {
                call = httpClient.newCall(request)
                runningCalls[id] = call
                val response = call.execute()
                if (!response.isSuccessful) {
                    fail(id, "HTTP ${response.code}")
                    return@launch
                }
                // 重定向后的真实总长度（第一跳可能没有 Content-Length）
                val realTotal = response.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                if (realTotal != task.totalBytes) update(id) { copy(totalBytes = realTotal) }

                val out = File(dirPath, fileName)
                val dir = out.parentFile
                when {
                    dir == null -> {
                        fail(id, "下载目录不可写，请先在文件页开启「所有文件访问」权限")
                        return@launch
                    }
                    !dir.exists() && !dir.mkdirs() -> {
                        fail(id, "创建下载目录失败")
                        return@launch
                    }
                    !dir.isDirectory || !dir.canWrite() -> {
                        fail(id, "下载目录不可写，请先在文件页开启「所有文件访问」权限")
                        return@launch
                    }
                }

                val buffer = ByteArray(BUFFER_SIZE)
                var lastUpdate = 0L
                var downloaded = 0L
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(out).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                                lastUpdate = now
                                update(id) { copy(downloadedBytes = downloaded) }
                            }
                        }
                    }
                }
                update(id) {
                    copy(
                        downloadedBytes = downloaded,
                        status = DownloadStatus.COMPLETED,
                        outPath = out.absolutePath
                    )
                }
            } catch (e: Exception) {
                val status = get(id)?.status
                if (status != DownloadStatus.CANCELED) {
                    fail(id, e.message ?: "下载失败")
                }
            } finally {
                runningCalls.remove(id)
            }
        }
    }

    /** 取消进行中的下载。 */
    fun cancel(id: Long) {
        runningCalls.remove(id)?.cancel()
        update(id) {
            // update 的 transform 是带 receiver 的 lambda（this = DownloadTask），没有 it
            if (status == DownloadStatus.RUNNING) {
                copy(status = DownloadStatus.CANCELED, error = "已取消")
            } else this
        }
    }

    /** 从列表移除任务（失败 / 已取消等垃圾条目）。 */
    fun remove(id: Long) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    private fun get(id: Long): DownloadTask? = _tasks.value.find { it.id == id }

    private fun update(id: Long, transform: DownloadTask.() -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) it.transform() else it }
    }

    private fun fail(id: Long, message: String) {
        update(id) { copy(status = DownloadStatus.FAILED, error = message) }
    }

    /** 从 contentDisposition（filename* / filename=）或 URL 末段解析文件名，失败兜底 download。
     *  中文老站点的 filename= 常直接塞 GBK 字节（未按 RFC 编码），逐字节成串后是
     *  "ÖÐÎÄ" 式乱码——按 Latin-1 还原字节再 GBK 重解码可救回。 */
    private fun parseFileName(contentDisposition: String?, url: String): String {
        if (!contentDisposition.isNullOrBlank()) {
            // RFC 5987: filename*=UTF-8''<percent-encoded>
            val star = Regex("filename\\*\\s*=\\s*([^;]+)").find(contentDisposition)
            if (star != null) {
                val raw = star.groupValues[1].trim()
                    .removePrefix("\"").removeSuffix("\"")
                val idx = raw.indexOf("''")
                if (idx >= 0) {
                    val encoded = raw.substring(idx + 2)
                    return runCatching { URLDecoder.decode(encoded, "UTF-8") }
                        .getOrDefault(encoded)
                }
            }
            // 普通 filename="x" 或 filename=x
            val plain = Regex("filename\\s*=\\s*\"([^\"]*)\"|filename\\s*=\\s*([^;]+)")
                .find(contentDisposition)
            if (plain != null) {
                val name = plain.groupValues[1].ifBlank { plain.groupValues[2] }.trim()
                if (name.isNotBlank()) return EncodingFixer.fixFileName(name) ?: name
            }
        }
        val fromUrl = runCatching { Uri.parse(url).lastPathSegment }
            .getOrNull()?.takeIf { it.isNotBlank() && it != "/" }
            // URL 末段可能是「Latin-1 化的 GBK」（站点未按规范 percent-encode）
        return fromUrl?.let { EncodingFixer.fixFileName(it) ?: it } ?: "download"
    }

    /** 目标目录已有同名文件时自动追加 (1)(2)。 */
    private fun uniqueFileName(dir: String, rawName: String): String {
        var name = rawName.replace('/', '_').replace('\\', '_').trim()
        if (name.isBlank()) name = "download"
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var i = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base($i)$ext"
            i++
        }
        return candidate
    }

    private const val MAX_TASKS = 20
    private const val PROGRESS_INTERVAL_MS = 200L
    private const val BUFFER_SIZE = 8192
}