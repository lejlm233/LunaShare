package com.lunashare.app.link

/** 下载任务状态 */
enum class DownloadStatus {
    /** 下载中 */
    RUNNING,
    /** 已完成 */
    COMPLETED,
    /** 下载失败（网络错误 / 目录不可写 / 权限不足等） */
    FAILED,
    /** 用户手动取消 */
    CANCELED
}

/**
 * Link 浏览器的一个下载任务。
 * 进度更新时整体替换（LinkDownloadManager 的 StateFlow 驱动 Compose 抽屉 UI）。
 */
data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String,
    /** 总字节数，-1 = Content-Length 缺失（进度条走不确定模式） */
    val totalBytes: Long,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.RUNNING,
    val error: String? = null,
    /** 下载完成后的本地文件路径 */
    val outPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 发起下载时页面提供的 User-Agent（失败后重试时复用） */
    val userAgent: String = ""
)