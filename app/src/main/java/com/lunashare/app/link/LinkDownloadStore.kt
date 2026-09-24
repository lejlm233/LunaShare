package com.lunashare.app.link

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Link 浏览器下载路径持久化（仿 LinkStore 风格，key 前缀 lunashare_download）。
 * 存真实文件路径（下载写文件 / 文件页浏览都走 File API，与文件页的存储权限模型一致），
 * 选择目录时同时兜底保存 SAF treeUri。
 */
class LinkDownloadStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lunashare_download", Context.MODE_PRIVATE)

    /** 默认下载目录：系统标准 Download 目录 */
    val defaultDir: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    var downloadDir: String
        get() = prefs.getString("download_dir", null) ?: defaultDir
        set(value) = prefs.edit().putString("download_dir", value).apply()

    var downloadDirUri: String?
        get() = prefs.getString("download_dir_uri", null)
        set(value) = prefs.edit().putString("download_dir_uri", value).apply()

    companion object {
        /**
         * 把 SAF 目录选择器返回的 treeUri（如 content://.../tree/primary%3ADownload）
         * 解析成真实文件路径：`primary:` 前缀 → 主存储 + 相对路径；
         * 其它 rootId（如 SD 卡）→ /storage/<rootId>/ + 相对路径。
         * 非外部存储的 provider（云盘等）解析失败返回 null。
         */
        fun treeUriToPath(context: Context, treeUri: Uri): String? {
            return try {
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val split = docId.split(":")
                if (split.size < 2) return null
                val rootId = split[0]
                val rel = split[1]
                if (rootId == "primary") {
                    File(Environment.getExternalStorageDirectory(), rel).absolutePath
                } else {
                    File("/storage/$rootId", rel).absolutePath
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}