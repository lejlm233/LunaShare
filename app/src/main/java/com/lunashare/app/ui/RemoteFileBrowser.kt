package com.lunashare.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunashare.app.model.RemoteConnection
import com.lunashare.app.remote.RemoteConnectionManager
import com.lunashare.app.remote.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程共享文件浏览：已连接的远程连接在文件 Tab 占一个标签，
 * 展示远程目录树，点文件可下载到本地（目录跟随设置页的下载目录）。
 */
@Composable
fun RemoteFileBrowser(
    cfg: RemoteConnection,
    tick: Int,
    onDirty: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = RemoteConnectionManager.clientOf(cfg.id)
    // 下载目录跟随设置页（Link 浏览器/设置页共用同一份），未初始化时兜底读持久化
    val downloadDirState by com.lunashare.app.link.LinkDownloadManager.downloadDir.collectAsState()

    var dirPath by remember(cfg.id) { mutableStateOf(cfg.remotePath) }
    var subDirs by remember(cfg.id) { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember(cfg.id) { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var error by remember(cfg.id) { mutableStateOf<String?>(null) }
    var loading by remember(cfg.id) { mutableStateOf(true) }
    var downloadingName by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }

    fun currentRemotePath(): String {
        if (subDirs.isEmpty()) return dirPath
        return dirPath.trimEnd('/') + "/" + subDirs.joinToString("/")
    }

    suspend fun load() {
        val c = client ?: run { error = "连接已断开"; loading = false; return }
        loading = true
        try {
            withContext(Dispatchers.IO) { c.list(currentRemotePath()) }.let {
                entries = it
                error = null
            }
        } catch (e: Exception) {
            entries = emptyList()
            error = "读取失败: ${e.message}"
        }
        loading = false
    }

    // 连接断开时也能感知（断开后 clientOf 返回 null）
    LaunchedEffect(cfg.id, tick, client) { load() }
    // 3s 轻轮询保持新鲜
    LaunchedEffect(cfg.id, client) {
        while (true) {
            delay(3000)
            load()
        }
    }

    fun goUp() {
        if (subDirs.isNotEmpty()) subDirs = subDirs.dropLast(1)
    }

    BackHandler(enabled = subDirs.isNotEmpty()) { goUp() }

    // 上传文件选择器：选中后拷到缓存再走 client.upload，成功后刷新列表
    val uploadPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val c = client ?: run {
            android.widget.Toast.makeText(context, "连接已断开", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        uploading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 从 content uri 取真实文件名（客户端以 localFile.name 作为远端文件名，
                    // 所以缓存临时文件必须叫原名，否则上传后变成 tmp 文件名）
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                        val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
                    }?.takeUnless { it.isBlank() }
                        ?: "upload_${System.currentTimeMillis()}.bin"
                    val safeName = name.replace('/', '_').replace('\\', '_')
                    val tmp = File(context.cacheDir, safeName)
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
                    }
                    try {
                        c.upload(tmp, currentRemotePath())
                    } finally {
                        tmp.delete()
                    }
                    safeName
                }.let { name ->
                    android.widget.Toast.makeText(
                        context, "已上传 $name", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                onDirty()
            } catch (ex: Exception) {
                android.widget.Toast.makeText(
                    context, "上传失败: ${ex.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
            uploading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 标题/路径栏
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { goUp() }, enabled = subDirs.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上级")
                }
                Text(
                    text = "${cfg.name}: " + (dirPath.trimEnd('/') + if (subDirs.isNotEmpty()) "/" + subDirs.joinToString("/") else ""),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { uploadPicker.launch("*/*") },
                    enabled = client != null && !uploading
                ) {
                    Icon(Icons.Default.CloudUpload, "上传文件")
                }
                IconButton(onClick = { onDirty() }) {
                    Icon(Icons.Default.Refresh, "刷新")
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                client == null -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("连接已断开，请在「服务」页重新连接",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                loading && entries.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在读取远程目录…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(error ?: "", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                entries.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("空目录", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(entries, key = { it.path }) { e ->
                            RemoteEntryRow(
                                entry = e,
                                downloading = downloadingName == e.name,
                                onClick = {
                                    if (e.isDirectory) {
                                        subDirs = subDirs + e.name
                                    } else {
                                        // 下载
                                        val c = client ?: return@RemoteEntryRow
                                        downloadingName = e.name
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    // 下载目录跟随设置页；StateFlow 还没初始化时兜底读持久化
                                                    val dirPath = downloadDirState.ifBlank {
                                                        com.lunashare.app.link.LinkDownloadStore(context).downloadDir
                                                    }
                                                    val destDir = File(dirPath)
                                                    destDir.mkdirs()
                                                    val dest = File(destDir, e.name)
                                                    // 先下到临时文件，成功后才替换目标——
                                                    // 失败绝不碰已有文件（否则会把原文件截成 0B）
                                                    val tmp = File(destDir, e.name + ".lunapart")
                                                    try {
                                                        c.download(e.path, tmp)
                                                        if (dest.exists() && !dest.delete()) {
                                                            throw java.io.IOException("无法覆盖旧文件")
                                                        }
                                                        if (!tmp.renameTo(dest)) {
                                                            throw java.io.IOException("写入下载目录失败")
                                                        }
                                                    } finally {
                                                        if (tmp.exists()) tmp.delete()
                                                    }
                                                }
                                                android.widget.Toast.makeText(
                                                    context, "已下载到 ${downloadDirState.ifBlank { com.lunashare.app.link.LinkDownloadStore(context).downloadDir }}/${e.name}",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            } catch (ex: Exception) {
                                                android.widget.Toast.makeText(
                                                    context, "下载失败: ${ex.message}",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                            downloadingName = null
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 底部统计
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
            val folderCount = entries.count { it.isDirectory }
            val fileCount = entries.count { !it.isDirectory }
            Text(
                "远程 · $folderCount 个文件夹, $fileCount 个文件",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun RemoteEntryRow(
    entry: RemoteEntry,
    downloading: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!entry.isDirectory) {
                Text(
                    formatRemoteSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (downloading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("下载中", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!entry.isDirectory) {
            Icon(Icons.Default.CloudDownload, "下载",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

internal fun formatRemoteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
