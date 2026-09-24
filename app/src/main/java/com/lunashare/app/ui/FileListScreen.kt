package com.lunashare.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.model.RemoteConnection
import com.lunashare.app.link.LinkDownloadManager
import com.lunashare.app.util.MusicConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(
    configStore: ShareConfigStore
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("file_open_prefs", Context.MODE_PRIVATE) }

    // Storage permission
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val manageStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasPermission = Environment.isExternalStorageManager()
    }

    LaunchedEffect(Unit) {
        // 初始化下载目录（从持久化读取），供「下载」tab 使用
        LinkDownloadManager.init(context)
        if (!hasPermission) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    var refreshEpoch by remember { mutableIntStateOf(0) }

    // Get shares that have localDir set
    val shares = remember(refreshEpoch) { configStore.listConfigs().filter { it.localDir.isNotBlank() } }

    var selectedShareIndex by remember { mutableIntStateOf(0) }
    var currentPath by remember { mutableStateOf<String?>(null) }
    var subDirStack by remember { mutableStateOf<List<String>>(emptyList()) }

    // ── 连接共享：已连接的远程连接各占一个 Tab ──
    val remoteStates by com.lunashare.app.remote.RemoteConnectionManager.states.collectAsState()
    val connectedRemotes = remember(remoteStates) {
        com.lunashare.app.config.RemoteConnectionStore(context).listConfigs()
            .filter { remoteStates[it.id]?.connected == true }
    }
    // 当前激活的远程 Tab（null = 本地文件模式）；存 id 避免配置对象变化重建
    var activeRemoteId by remember { mutableStateOf<String?>(null) }
    val activeRemote = connectedRemotes.firstOrNull { it.id == activeRemoteId }
    var remoteTick by remember { mutableIntStateOf(0) }

    // 下载目录（Link 浏览器下载 / 设置页可改）：设置后文件页出现「下载」tab 指向该路径
    val downloadDirState by LinkDownloadManager.downloadDir.collectAsState()
    val hasDownloadTab = downloadDirState.isNotBlank()
    var fileList by remember { mutableStateOf<List<File>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAppChooser by remember { mutableStateOf<File?>(null) }

    // Selection
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedFiles.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var showOpenAsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Music conversion
    var showConvertDialog by remember { mutableStateOf(false) }
    var showConvertConfirm by remember { mutableStateOf(false) }
    var pendingMusicFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var convertProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var convertCurrentFile by remember { mutableStateOf("") }
    var convertResults by remember { mutableStateOf<List<MusicConverter.ConvertResult>?>(null) }
    var convertOutDirName by remember { mutableStateOf("") }

    fun resolvePath(): String? {
        val root = currentPath ?: return null
        return subDirStack.fold(root) { acc, seg -> File(acc, seg).absolutePath }
    }

    LaunchedEffect(shares, downloadDirState) {
        if (currentPath == null) {
            // 首次进入：优先第一个共享目录；无共享但设置了下载目录时直接展示下载目录
            currentPath = if (shares.isNotEmpty()) shares[0].localDir
                else if (hasDownloadTab) downloadDirState
                else null
            subDirStack = emptyList()
        }
        // 共享列表变化后防止选中索引越界（删除共享 / 取消下载目录等）
        val maxTab = shares.size + if (hasDownloadTab) 1 else 0
        if (selectedShareIndex >= maxTab && maxTab > 0) {
            selectedShareIndex = maxTab - 1
        }
    }

    LaunchedEffect(refreshEpoch) {
        snapshotFlow { Triple(resolvePath(), selectedShareIndex, shares) }
            .collectLatest { (path, _, _) ->
                while (true) {
                    path?.let { p ->
                        try {
                            val dir = File(p)
                            if (dir.exists() && dir.isDirectory) {
                                fileList = dir.listFiles()?.sortedWith(
                                    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
                                ) ?: emptyList()
                                errorMessage = null
                            } else {
                                fileList = emptyList()
                                errorMessage = "目录不存在或无法访问"
                            }
                        } catch (e: Exception) {
                            fileList = emptyList()
                            errorMessage = "读取目录失败: ${e.message}"
                        }
                    }
                    delay(2000)
                }
            }
    }

    fun doRefresh() {
        refreshEpoch++
        remoteTick++
    }

    // 修复乱码文件名：扫描当前目录（含子目录）中「Latin-1 化 GBK」的可逆乱码名并重命名
    var fixEncodingRunning by remember { mutableStateOf(false) }
    var showFixEncodingResult by remember { mutableStateOf<Pair<Int, Int>?>(null) } // fixed / scanned
    fun fixEncodingNow() {
        val rootPath = resolvePath() ?: return
        fixEncodingRunning = true
        scope.launch {
            var fixed = 0
            var scanned = 0
            withContext(Dispatchers.IO) {
                try {
                    val root = File(rootPath)
                    if (root.isDirectory) {
                        root.walkTopDown().forEach { f ->
                            scanned++
                            val fixedName = com.lunashare.app.util.EncodingFixer.fixFileName(f.name) ?: return@forEach
                            try {
                                if (f.renameTo(File(f.parentFile, fixedName))) fixed++
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            fixEncodingRunning = false
            showFixEncodingResult = fixed to scanned
            if (fixed > 0) doRefresh()
        }
    }

    fun goUp() {
        if (subDirStack.isNotEmpty()) {
            subDirStack = subDirStack.dropLast(1)
            selectedFiles = emptySet()
        }
    }

    /**
     * First step of music conversion: filter the selection and show a
     * confirmation dialog before doing anything (avoid accidental conversion).
     */
    fun requestConvert() {
        val musicFiles = selectedFiles
            .map { File(it) }
            .filter { !it.isDirectory && it.extension.lowercase() in MusicConverter.AUDIO_EXTS }
        if (musicFiles.isEmpty()) {
            android.widget.Toast.makeText(context, "所选文件中没有音乐文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pendingMusicFiles = musicFiles
        showConvertConfirm = true
    }

    /**
     * Actually convert the confirmed files:
     * FLAC → ALAC (via FFmpeg), other audio copied as-is,
     * all saved into a new folder in the current directory.
     */
    fun doConvert() {
        showConvertConfirm = false
        val musicFiles = pendingMusicFiles
        if (musicFiles.isEmpty()) return
        val currentDir = resolvePath() ?: return
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(currentDir, "转换文件_$ts")
        if (!outDir.mkdirs()) {
            android.widget.Toast.makeText(context, "创建输出文件夹失败", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        convertOutDirName = outDir.name
        showConvertDialog = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val ffmpegPath = MusicConverter.prepareFfmpeg(context)
                if (ffmpegPath == null) {
                    listOf(MusicConverter.ConvertResult("ffmpeg", false, "无法提取转码引擎"))
                } else {
                    val libDir = context.applicationInfo.nativeLibraryDir
                    // Snapshot state writes are thread-safe; Compose picks them up next frame
                    MusicConverter.convert(ffmpegPath, libDir, musicFiles, outDir) { done, total, name ->
                        convertProgress = done to total
                        convertCurrentFile = name
                    }
                }
            }
            showConvertDialog = false
            convertProgress = null
            convertResults = results
            selectedFiles = emptySet()
            doRefresh()
        }
    }

    BackHandler(enabled = selectionMode || (subDirStack.isNotEmpty() && activeRemote == null)) {
        if (selectionMode) {
            selectedFiles = emptySet()
        } else {
            goUp()
        }
    }

    key(refreshEpoch) {
    Scaffold(
        floatingActionButton = {
            // 刷新按钮：与服务页「新建共享」FAB 一致（Scaffold 槽位，默认右下角位置/样式）
            if (!selectionMode) {
                FloatingActionButton(onClick = { doRefresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 共享数 > 1 / 设置了下载目录 / 有已连接的远程连接时显示 tab 栏
                if (shares.size > 1 || hasDownloadTab || connectedRemotes.isNotEmpty()) {
                    // 选中索引：远程 Tab 也按真实位置编入（本地共享 + 下载 + 远程顺延）。
                    // 不能传 -1：material3 1.3 的 ScrollableTabRow 默认指示器直接
                    // tabPositions[selectedTabIndex]，-1 会 IndexOutOfBoundsException（真机已踩）。
                    val remoteBase = shares.size + if (hasDownloadTab) 1 else 0
                    val selectedTabIndex = when (val activeId = activeRemoteId) {
                        null -> selectedShareIndex
                        else -> remoteBase + connectedRemotes.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
                    }
                    ScrollableTabRow(selectedTabIndex = selectedTabIndex, edgePadding = 8.dp) {
                        shares.forEachIndexed { index, share ->
                            Tab(
                                selected = activeRemote == null && selectedShareIndex == index,
                                onClick = { activeRemoteId = null; selectedShareIndex = index; currentPath = share.localDir; subDirStack = emptyList() },
                                text = { Text(share.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                        if (hasDownloadTab) {
                            Tab(
                                selected = activeRemote == null && selectedShareIndex == shares.size,
                                onClick = {
                                    activeRemoteId = null
                                    selectedShareIndex = shares.size
                                    currentPath = downloadDirState
                                    subDirStack = emptyList()
                                },
                                // tab 上显示下载目录名（如 Download），完整路径在标题栏
                                text = {
                                    Text(
                                        File(downloadDirState).name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                        // 远程连接 Tab（仅已连接显示；连接断开 Tab 自动消失）
                        connectedRemotes.forEach { rc ->
                            Tab(
                                selected = activeRemoteId == rc.id,
                                onClick = { activeRemoteId = rc.id },
                                text = {
                                    Text(
                                        RemoteConnection.protocolLabel(rc.protocol) + "·" + rc.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (selectionMode) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedFiles.size == fileList.size && fileList.isNotEmpty(),
                                onCheckedChange = {
                                    selectedFiles = if (it) fileList.map { f -> f.absolutePath }.toSet() else emptySet()
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (selectedFiles.size == fileList.size && fileList.isNotEmpty()) "取消全选" else "全选",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { requestConvert() }
                            ) {
                                Icon(Icons.Default.MusicNote, "格式转换",
                                    tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(
                                onClick = {
                                    if (selectedFiles.size == 1) {
                                        fileToRename = fileList.firstOrNull { it.absolutePath in selectedFiles }
                                        if (fileToRename != null) showRenameDialog = true
                                    }
                                },
                                enabled = selectedFiles.size == 1
                            ) {
                                Icon(Icons.Default.Edit, "重命名",
                                    tint = if (selectedFiles.size == 1) MaterialTheme.colorScheme.onSurface
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            }
                            IconButton(
                                onClick = { if (selectedFiles.size == 1) showOpenAsDialog = true },
                                enabled = selectedFiles.size == 1
                            ) {
                                Icon(Icons.Default.OpenInNew, "打开为",
                                    tint = if (selectedFiles.size == 1) MaterialTheme.colorScheme.onSurface
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { selectedFiles = emptySet() }) {
                                Icon(Icons.Default.Close, "关闭选择", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
                    val folderCount = fileList.count { it.isDirectory }
                    val fileCount = fileList.count { !it.isDirectory }
                    Text(
                        "$folderCount 个文件夹, $fileCount 个文件",
                        // 与服务页「运行中/已停止」条条一致：labelLarge + vertical 8dp
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (activeRemote == null) {
            // File browser header
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val path = currentPath
                    if (path != null && subDirStack.isNotEmpty()) {
                        IconButton(onClick = { goUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上级")
                        }
                    }
                    val showingDownloadRoot = hasDownloadTab && selectedShareIndex == shares.size && subDirStack.isEmpty()
                    Text(
                        text = when {
                            subDirStack.isNotEmpty() -> subDirStack.joinToString("/")
                            showingDownloadRoot -> path ?: "下载"
                            else -> path?.let { File(it).name } ?: "文件"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 修复乱码文件名（GBK 客户端上传 / 老站点下载留下的可逆乱码）
                    if (path != null && !fixEncodingRunning) {
                        IconButton(onClick = { fixEncodingNow() }) {
                            Icon(Icons.Default.Spellcheck, contentDescription = "修复乱码文件名",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (fixEncodingRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            } // end if (activeRemote == null) — 远程模式用 RemoteFileBrowser 自带标题栏

            // Content area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    activeRemote != null -> RemoteFileBrowser(
                        cfg = activeRemote,
                        tick = remoteTick,
                        onDirty = { remoteTick++ }
                    )

                    !hasPermission -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Lock, null, Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(16.dp))
                            Text("需要存储权限", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                } else {
                                    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            }) { Text("授予权限") }
                        }
                    }

                    shares.isEmpty() && !hasDownloadTab -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.FolderOff, null, Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(16.dp))
                            Text("没有配置文件夹", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("请先在「服务」标签页中创建共享并设置文件夹",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }

                    errorMessage != null -> Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error)

                    fileList.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(16.dp))
                            Text("空文件夹", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(fileList, key = { it.absolutePath }) { file ->
                                FileListItem(
                                    file = file,
                                    isSelected = file.absolutePath in selectedFiles,
                                    onRowClick = {
                                        if (file.isDirectory) {
                                            subDirStack = subDirStack + file.name
                                            selectedFiles = emptySet()
                                        } else {
                                            val extension = file.extension.lowercase()
                                            val defaultApp = prefs.getString("default_app_$extension", null)
                                            if (defaultApp != null) {
                                                openFileSilently(context, file, defaultApp)
                                            } else {
                                                showAppChooser = file
                                            }
                                        }
                                    },
                                    onCheckboxClick = {
                                        selectedFiles = if (file.absolutePath in selectedFiles) {
                                            selectedFiles - file.absolutePath
                                        } else {
                                            selectedFiles + file.absolutePath
                                        }
                                    },
                                    onLongClick = {
                                        selectedFiles = if (file.absolutePath in selectedFiles) {
                                            selectedFiles - file.absolutePath
                                        } else {
                                            selectedFiles + file.absolutePath
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
    } // close key(refreshEpoch)

    // App chooser dialog
    showAppChooser?.let { file ->
        AppChooserDialog(file = file, prefs = prefs, context = context, onDismiss = { showAppChooser = null })
    }

    if (showOpenAsDialog && selectedFiles.size == 1) {
        val file = fileList.firstOrNull { it.absolutePath in selectedFiles }
        if (file != null) {
            AppChooserDialog(file = file, prefs = prefs, context = context, onDismiss = { showOpenAsDialog = false })
        }
    }

    // Fix encoding result dialog
    showFixEncodingResult?.let { (fixed, scanned) ->
        AlertDialog(
            onDismissRequest = { showFixEncodingResult = null },
            title = { Text("修复乱码文件名") },
            text = {
                Text(if (fixed > 0) "已修复 $fixed 个乱码文件名（共扫描 $scanned 项）。\n注意：含 \"�\" 的文件名已不可逆损坏，无法自动修复。"
                else "扫描了 $scanned 项，没有发现可自动修复的乱码文件名。")
            },
            confirmButton = {
                TextButton(onClick = { showFixEncodingResult = null }) { Text("确定") }
            }
        )
    }

    // Delete confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedFiles.size} 个项目吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedFiles.forEach { path -> try { File(path).deleteRecursively() } catch (_: Exception) {} }
                        selectedFiles = emptySet()
                        showDeleteConfirm = false
                        doRefresh()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    // Rename dialog
    if (showRenameDialog && fileToRename != null) {
        var newName by remember(fileToRename) { mutableStateOf(fileToRename!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank() && newName != fileToRename!!.name) {
                        try {
                            File(fileToRename!!.parent, newName).let { fileToRename!!.renameTo(it) }
                            doRefresh()
                        } catch (_: Exception) {}
                    }
                    showRenameDialog = false
                    fileToRename = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

    // Music conversion confirmation dialog (avoid accidental conversion)
    if (showConvertConfirm) {
        val files = pendingMusicFiles
        AlertDialog(
            onDismissRequest = { showConvertConfirm = false },
            title = { Text("确认格式转换") },
            text = {
                Column {
                    Text("将处理 ${files.size} 个音乐文件：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("🎵 FLAC → ALAC 无损转码", style = MaterialTheme.typography.bodySmall)
                    Text("📋 其他音乐格式直接复制", style = MaterialTheme.typography.bodySmall)
                    Text("📂 结果保存到当前文件夹的「转换文件_时间戳」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text("文件列表:", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    files.take(6).forEach { f ->
                        Text("• ${f.name}", style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (files.size > 6) {
                        Text("…等 ${files.size} 个文件", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { doConvert() }) { Text("开始转换") }
            },
            dismissButton = {
                TextButton(onClick = { showConvertConfirm = false }) { Text("取消") }
            }
        )
    }

    // Music conversion progress dialog (non-dismissible while running)
    if (showConvertDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("格式转换") },
            text = {
                Column {
                    Text("FLAC 将转码为 ALAC，其他音乐文件直接复制", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    val p = convertProgress
                    if (p != null) {
                        LinearProgressIndicator(
                            progress = { if (p.second > 0) p.first.toFloat() / p.second else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("(${p.first}/${p.second}) ${convertCurrentFile}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // Conversion result dialog
    convertResults?.let { results ->
        val successCount = results.count { it.success }
        val failedCount = results.count { !it.success }
        AlertDialog(
            onDismissRequest = { convertResults = null },
            title = { Text("转换完成") },
            text = {
                Column {
                    Text("成功: $successCount 个，失败: $failedCount 个", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("已保存到文件夹「$convertOutDirName」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val failed = results.filter { !it.success }
                    if (failed.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("失败项:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        failed.take(5).forEach { r ->
                            Text("• ${r.fileName}: ${r.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (failed.size > 5) {
                            Text("…等 ${failed.size} 个", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { convertResults = null }) { Text("确定") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    file: File,
    isSelected: Boolean,
    onRowClick: () -> Unit,
    onCheckboxClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbnail = remember(file.absolutePath) { getThumbnail(context, file) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface)
            .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onCheckboxClick() }
        )

        Spacer(Modifier.width(8.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = onRowClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    thumbnail != null -> Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    file.isDirectory -> Icon(Icons.Default.Folder, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    else -> Icon(getFileIcon(file.name), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!file.isDirectory) {
                    Text(text = formatFileSize(file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun getThumbnail(context: Context, file: File): Bitmap? {
    if (file.isDirectory) return null
    val ext = file.extension.lowercase()
    return try {
        when {
            ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.absolutePath, this)
                    inSampleSize = calculateInSampleSize(this, 80, 80)
                    inJustDecodeBounds = false
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
            ext == "apk" -> {
                val pm = context.packageManager
                val pi = pm.getPackageArchiveInfo(file.absolutePath, 0)
                pi?.applicationInfo?.let { ai ->
                    ai.sourceDir = file.absolutePath
                    ai.publicSourceDir = file.absolutePath
                    drawableToBitmap(ai.loadIcon(pm))
                }
            }
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
    val h = options.outHeight; val w = options.outWidth
    var sz = 1
    if (h > reqH || w > reqW) {
        val h2 = h / 2; val w2 = w / 2
        while (h2 / sz >= reqH && w2 / sz >= reqW) sz *= 2
    }
    return sz
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
    val bmp = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}

private fun getFileIcon(fileName: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp3", "wav", "flac", "aac", "ogg", "wma" -> Icons.Default.MusicNote
        "mp4", "mkv", "avi", "mov", "wmv", "flv" -> Icons.Default.Movie
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Default.Image
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx" -> Icons.Default.Description
        "xls", "xlsx" -> Icons.Default.TableChart
        "ppt", "pptx" -> Icons.Default.Slideshow
        "zip", "rar", "7z", "tar", "gz" -> Icons.Default.Archive
        "apk" -> Icons.Default.Android
        "txt" -> Icons.Default.TextSnippet
        "html", "htm", "xml", "json" -> Icons.Default.Code
        else -> Icons.Default.InsertDriveFile
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
