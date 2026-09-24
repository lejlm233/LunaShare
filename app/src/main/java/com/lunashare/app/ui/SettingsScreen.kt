package com.lunashare.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunashare.app.config.ShareConfigStore
import com.lunashare.app.link.LinkDownloadManager
import com.lunashare.app.link.LinkDownloadStore
import com.lunashare.app.ui.theme.ThemeManager
import com.lunashare.app.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeManager: ThemeManager,
    configStore: ShareConfigStore,
    onDismiss: () -> Unit,
    onOpenFrp: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentMode = themeManager.themeMode
    var bootAutoStart by remember { mutableStateOf(configStore.isBootAutoStart()) }
    var batteryWhitelist by remember { mutableStateOf(configStore.isBatteryWhitelist()) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var mdnsEnabled by remember { mutableStateOf(configStore.isMdnsEnabled()) }

    // Re-check system battery-optimization status whenever the switch or page state changes
    LaunchedEffect(batteryWhitelist) {
        batteryExempt = isBatteryExempt(context)
    }

    BackHandler { onDismiss() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // App info section
            Text(
                text = "LunaShare",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                text = "本地文件共享工具 v1.0.11",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))

            // Auto-start section
            Text(
                text = "启动设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("开机自启", style = MaterialTheme.typography.bodyLarge)
                            Text("设备开机后自动启动 LunaShare",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = bootAutoStart,
                            onCheckedChange = {
                                bootAutoStart = it
                                configStore.setBootAutoStart(it)
                            }
                        )
                    }

                    // Some ROMs (vivo, Xiaomi, Huawei...) block boot broadcasts by default —
                    // guide the user to allow auto-start in system settings.
                    if (bootAutoStart) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("部分系统需在「系统设置」中允许自启动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { openAutoStartSettings(context) }) {
                                Text("去开启")
                                Icon(Icons.Default.OpenInNew, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // ── Battery whitelist: keep service alive in background / screen off ──
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("电池白名单", style = MaterialTheme.typography.bodyLarge)
                            Text("后台与息屏时保持共享服务不被系统清理",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = batteryWhitelist,
                            onCheckedChange = {
                                batteryWhitelist = it
                                configStore.setBatteryWhitelist(it)
                                // Ask the system right away when the user opts in
                                if (it && !batteryExempt) {
                                    openBatterySettings(context)
                                }
                            }
                        )
                    }

                    if (batteryWhitelist) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        if (batteryExempt) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("✅ 已加入系统电池白名单",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("系统未允许忽略电池优化，服务可能被清理",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { openBatterySettings(context) }) {
                                    Text("去开启")
                                    Icon(Icons.Default.OpenInNew, contentDescription = null,
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 局域网访问（mDNS 广播）──
            Text(
                text = "局域网访问",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("mDNS 广播", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "开共享后用 lunashare.local 在同网设备访问本机（免记 IP）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = mdnsEnabled,
                        onCheckedChange = {
                            mdnsEnabled = it
                            configStore.setMdnsEnabled(it)
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Theme section
            Text(
                text = "主题设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column {
                    ThemeOptionItem(
                        label = "亮色模式",
                        isSelected = currentMode == ThemeMode.LIGHT,
                        onClick = { themeManager.updateThemeMode(ThemeMode.LIGHT) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ThemeOptionItem(
                        label = "暗色模式",
                        isSelected = currentMode == ThemeMode.DARK,
                        onClick = { themeManager.updateThemeMode(ThemeMode.DARK) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ThemeOptionItem(
                        label = "跟随设备",
                        isSelected = currentMode == ThemeMode.SYSTEM,
                        onClick = { themeManager.updateThemeMode(ThemeMode.SYSTEM) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "切换应用主题，跟随设备将使用系统当前的主题设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(24.dp))

            // ── 下载设置 section ─────────────────────────────────────
            Text(
                text = "下载设置",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Link 浏览器下载目录：系统文件夹选择器选目录 → 解析真实路径保存；
            // 设置后文件页 tab 会显示该路径，侧边栏下载区写入这里
            val downloadStore = remember { LinkDownloadStore(context) }
            var downloadDirPath by remember { mutableStateOf(downloadStore.downloadDir) }
            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    val path = LinkDownloadStore.treeUriToPath(context, uri)
                    if (path != null) {
                        downloadStore.downloadDirUri = uri.toString()
                        downloadStore.downloadDir = path
                        downloadDirPath = path
                        LinkDownloadManager.refreshDir(context)
                        android.widget.Toast.makeText(
                            context, "下载路径已设置为 $path", android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context, "请选择外部存储上的文件夹", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("下载路径", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                downloadDirPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("选择文件夹")
                        }
                        OutlinedButton(onClick = {
                            downloadStore.downloadDirUri = null
                            downloadStore.downloadDir = downloadStore.defaultDir
                            downloadDirPath = downloadStore.defaultDir
                            LinkDownloadManager.refreshDir(context)
                        }) {
                            Text("恢复默认")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── OpenFrp / frpc1 section ──────────────────────────────
            Text(
                text = "公网穿透",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable(onClick = onOpenFrp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Frp 穿透", style = MaterialTheme.typography.bodyLarge)
                            Text("配置 Frp 客户端、管理节点和隧道（OpenFrp / mefrp 双服务商）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "进入",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // About
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LunaShare 是一个本地文件共享工具，支持通过 HTTP/WebDAV 和 FTP 协议在局域网中共享文件。",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("基于 FrpcAndroid 改造，移除了端口映射功能，专注于本地文件共享。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ThemeOptionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Helper: open system auto-start settings ──

/** Open the OEM auto-start permission page, falling back to app details. */
private fun openAutoStartSettings(context: android.content.Context) {
    // vivo / OriginOS
    val vivoIntent = Intent().apply {
        component = ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    if (runCatching { context.startActivity(vivoIntent) }.isSuccess) return

    // Xiaomi / HyperOS
    val miIntent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    if (runCatching { context.startActivity(miIntent) }.isSuccess) return

    // Fallback: app details page
    runCatching {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

// ── Helper: battery optimization whitelist ──

/** Whether the app is already exempt from battery optimizations. */
private fun isBatteryExempt(context: Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

/**
 * Open the battery optimization whitelist flow.
 * First tries the direct "ignore battery optimizations" request (system dialog);
 * falls back to the battery optimization settings list.
 */
private fun openBatterySettings(context: Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        // Direct request — shows a system dialog asking the user to allow
        val direct = runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess
        if (direct) return
    }

    // Fallback: battery optimization settings list
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
