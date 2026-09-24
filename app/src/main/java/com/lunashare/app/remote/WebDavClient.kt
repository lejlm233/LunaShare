package com.lunashare.app.remote

import android.util.Log
import com.lunashare.app.model.RemoteConnection
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端：PROPFIND 列目录 / GET 下载 / PUT 上传 / DELETE / MKCOL。
 * 语义与自家 FileServer（服务端）对齐。
 */
class WebDavClient(private val cfg: RemoteConnection) : RemoteFileClient {

    companion object {
        private const val TAG = "WebDavClient"
        private const val NS_DAV = "DAV:"
        private val DATE_FMT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val baseUrl: String

    init {
        // host 允许直接填 http(s):// 开头，也允许纯 IP/域名
        val scheme = if (cfg.host.startsWith("https://") || cfg.host.startsWith("http://")) ""
        else "http://"
        val hostPart = cfg.host.removePrefix("https://").removePrefix("http://")
        val root = "${scheme}${hostPart}:${cfg.effectivePort()}"
        baseUrl = root.trimEnd('/') + normalizeDir(cfg.remotePath)
        Log.i(TAG, "WebDAV base=$baseUrl")
    }

    private fun auth(): String? =
        if (cfg.anonymous || cfg.username.isBlank()) null
        else Credentials.basic(cfg.username, cfg.password)

    private fun req(url: String, method: String, body: okhttp3.RequestBody? = null): Request.Builder {
        val b = Request.Builder().url(url).method(method, body)
        auth()?.let { b.header("Authorization", it) }
        return b
    }

    private fun normalizeDir(p: String): String {
        val s = if (p.isBlank()) "/" else p
        return if (s.endsWith("/")) s else "$s/"
    }

    override fun connect() {
        // PROPFIND 根目录 depth 0 验证可达 + 认证
        val url = baseUrl
        val resp = client.newCall(
            req(url, "PROPFIND",
                """<?xml version="1.0"?><d:propfind xmlns:d="$NS_DAV"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
                    .toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0").build()
        ).execute()
        resp.use {
            when (it.code) {
                207 -> return
                401 -> throw IOException("认证失败：用户名或密码错误（HTTP 401）")
                404 -> throw IOException("服务器上不存在路径 ${cfg.remotePath}（HTTP 404）")
                else -> throw IOException("连接失败：HTTP ${it.code}")
            }
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = normalizeDir(if (path.isBlank()) cfg.remotePath else path)
        val url = baseUrl.ensureBase(dir)
        val resp = client.newCall(
            req(url, "PROPFIND",
                """<?xml version="1.0"?><d:propfind xmlns:d="$NS_DAV"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
                    .toRequestBody("application/xml".toMediaType()))
                .header("Depth", "1").build()
        ).execute()
        if (resp.code != 207) {
            resp.close()
            throw IOException("列目录失败：HTTP ${resp.code}")
        }
        val body = resp.body?.string() ?: throw IOException("列目录失败：空响应")
        return parseMultiStatus(body, dir)
    }

    /** 解析 207 Multi-Status（用 XmlPullParser 流式解析，避免整树 DOM 依赖差异）。 */
    private fun parseMultiStatus(xml: String, currentDir: String): List<RemoteEntry> {
        val entries = mutableListOf<RemoteEntry>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var href: String? = null
        var isDir = false
        var size = 0L
        var mtime = 0L
        var inProp = false

        fun flush() {
            val h = href ?: return
            href = null
            // href 形如 /dav/xxx/ 或完整 URL；取 path 部分解码
            val path = try {
                val u = URL(if (h.startsWith("http")) h else "http://placeholder${h}")
                u.path?.decodeUnicode() ?: return
            } catch (e: Exception) {
                h.decodeUnicode()
            }
            // 跳过当前目录自身（href 以当前目录结尾）
            val cur = currentDir.trimEnd('/')
            if (path.trimEnd('/') == cur) return
            // 子路径 = 相对当前目录
            val rel = path.removePrefix(if (cur.isEmpty()) "/" else "$cur/")
            if (rel.isBlank()) return
            val name = rel.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) return
            entries.add(RemoteEntry(name, path, isDir, size, mtime))
            isDir = false; size = 0L; mtime = 0L
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name?.lowercase() ?: ""
                    when {
                        local == "response" -> { href = null; isDir = false; size = 0L; mtime = 0L }
                        local == "prop" -> inProp = true
                        local == "href" -> href = parser.nextText()
                        inProp && local == "resourcetype" -> {
                            // 内含 <d:collection/> 即目录
                            var depth = 1
                            while (depth > 0) {
                                val e2 = parser.next()
                                if (e2 == XmlPullParser.START_TAG) depth++
                                else if (e2 == XmlPullParser.END_TAG) depth--
                                else if (e2 == XmlPullParser.END_DOCUMENT) break
                                if (e2 == XmlPullParser.START_TAG && parser.name?.lowercase() == "collection") isDir = true
                            }
                        }
                        inProp && local == "getcontentlength" -> size = parser.nextText().toLongOrNull() ?: 0L
                        inProp && local == "getlastmodified" -> mtime = runCatching {
                            DATE_FMT.parse(parser.nextText())?.time ?: 0L
                        }.getOrDefault(0L)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val local = parser.name?.lowercase() ?: ""
                    if (local == "response") flush()
                    if (local == "prop") inProp = false
                }
            }
            event = parser.next()
        }
        flush()
        return entries.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun String.decodeUnicode(): String {
        // href 可能含 %XX 与 Unicode 转义；URLDecoder 处理 %XX，+ 不该变空格（路径场景）
        return java.net.URLDecoder.decode(this, "UTF-8")
    }

    override fun download(remotePath: String, localFile: File) {
        val url = baseUrl.ensureBase(remotePath)
        client.newCall(req(url, "GET").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
            resp.body!!.byteStream().use { input ->
                localFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
    }

    override fun upload(localFile: File, remoteDir: String) {
        val dir = normalizeDir(if (remoteDir.isBlank()) cfg.remotePath else remoteDir)
        val url = baseUrl.ensureBase(dir + localFile.name)
        val resp = client.newCall(
            req(url, "PUT", localFile.asRequestBody("application/octet-stream".toMediaType())).build()
        ).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("上传失败：HTTP ${it.code}")
        }
    }

    override fun delete(remotePath: String, isDirectory: Boolean) {
        val url = baseUrl.ensureBase(remotePath)
        client.newCall(req(url, "DELETE").build()).execute().use {
            if (!it.isSuccessful) throw IOException("删除失败：HTTP ${it.code}")
        }
    }

    override fun mkdir(parentPath: String, name: String) {
        val dir = normalizeDir(if (parentPath.isBlank()) cfg.remotePath else parentPath)
        val url = baseUrl.ensureBase(dir + name + "/")
        client.newCall(req(url, "MKCOL").build()).execute().use {
            if (!it.isSuccessful) throw IOException("新建目录失败：HTTP ${it.code}")
        }
    }

    override fun disconnect() { /* OkHttp 无状态，无需处理 */ }

    /** base 已经含初始目录；target 为绝对路径时替换，为相对时拼接。 */
    private fun String.ensureBase(target: String): String {
        if (target.isBlank()) return this
        val t = if (target.startsWith("/")) target else "/$target"
        // root = 协议+host+port 部分。注意 remotePath 为 "/" 时 trimEnd('/') 是空串，
        // substringBefore("") 返回 ""（indexOf("")==0），会拼出无 scheme 的 "/" URL —— 特判。
        val basePart = cfg.remotePath.trimEnd('/')
        val root = if (basePart.isEmpty()) this.trimEnd('/')
                   else this.substringBefore(basePart).trimEnd('/')
        // target 是「服务器绝对路径」：root(协议+host+port) + target
        return root + t
    }
}
