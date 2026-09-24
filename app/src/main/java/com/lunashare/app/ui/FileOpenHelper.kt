package com.lunashare.app.ui

import android.app.AppOpsManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文件「打开方式」共享实现：FileListScreen（文件页）与 LinkScreen（下载完成）共用。
 *
 * 逻辑（与文件页一致）：
 * - 某扩展名已记忆默认应用 → openFileSilently 直接静默打开；
 * - 未记忆 → 弹 AppChooserDialog 列出可用应用，选择后可「始终用此应用打开」。
 */
data class AppInfo(val name: String, val packageName: String)

fun getFileUri(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

fun getMimeType(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "txt" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}

fun openFileSilently(context: Context, file: File, defaultApp: String?) {
    val isApk = file.extension.lowercase() == "apk"
    try {
        val uri = getFileUri(context, file)
        if (isApk) {
            // APK：先检查「安装未知应用」权限（Android 8+），未开启时系统安装界面
            // 会被直接拒绝（vivo 等 ROM 默认禁止，现象是点了安装程序没反应）。
            // 注意：canRequestPackageInstalls() 在部分 ROM（vivo）上会把默认状态误报为
            // 已授权，所以这里用 AppOps 直接读真实状态（默认/拒绝 → 一律视为未开启）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages(context)) {
                android.widget.Toast.makeText(
                    context,
                    "请先允许 LunaShare 安装未知应用",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                // 优先跳「允许安装未知应用」设置页；部分 ROM（如 vivo）不支持带包名定位
                // 或不存在该页面，逐级回退：→ 应用详情页（应用信息里可开启未知来源）→ 仅提示
                val unknownSourceOpened = runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                if (!unknownSourceOpened) {
                    val detailsOpened = runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!detailsOpened) {
                        android.widget.Toast.makeText(
                            context,
                            "请在系统设置中开启「安装未知应用」权限",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return
            }
            // 已授权 → 走标准安装 intent，直接拉起系统安装界面
            // clipData + WRITE grant：Android 12+ 规范做法，让 PackageInstaller 一定拿到 URI 授权
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    clipData = ClipData.newRawUri(null, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, getMimeType(file.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val def = defaultApp
                    if (def != null) setPackage(def)
                }
            )
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            if (isApk) {
                "无法打开安装界面: ${e.message}"
            } else {
                "打开文件失败: ${e.message}"
            },
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * 是否允许安装未知来源 APK（Android 8+）。直接读 AppOps 的真实状态：
 * 只有 MODE_ALLOWED 才算开启，默认 / 拒绝都视为未开启——
 * 因为 canRequestPackageInstalls() 在部分 ROM（vivo 等）会把默认状态误报为已授权。
 */
private fun canInstallPackages(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // OPSTR_REQUEST_INSTALL_PACKAGES 常量在该 SDK 未暴露，用其字符串字面量
        val mode = appOps.checkOpNoThrow(
            "android:request_install_packages",
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        context.packageManager.canRequestPackageInstalls()
    }
}

@Composable
fun AppChooserDialog(
    file: File,
    prefs: SharedPreferences,
    context: Context,
    onDismiss: () -> Unit
) {
    val extension = file.extension.lowercase()
    val defaultAppKey = "default_app_$extension"
    val defaultApp = prefs.getString(defaultAppKey, null)

    val availableApps = remember(file) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(getFileUri(context, file), getMimeType(file.name)) }
        pm.queryIntentActivities(intent, 0).map { resolveInfo ->
            AppInfo(
                name = resolveInfo.loadLabel(pm).toString(),
                packageName = resolveInfo.activityInfo.packageName
            )
        }.distinctBy { it.packageName }
    }

    var rememberingForThisType by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开方式") },
        text = {
            Column {
                Text("文件: ${file.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { rememberingForThisType = !rememberingForThisType }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberingForThisType, onCheckedChange = { rememberingForThisType = it })
                    Spacer(Modifier.width(8.dp))
                    Text("始终用此应用打开 .$extension 文件", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (availableApps.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(availableApps) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (rememberingForThisType) prefs.edit().putString(defaultAppKey, app.packageName).apply()
                                        openFileSilently(context, file, app.packageName)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Android, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(app.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (app.packageName == defaultApp) Icon(Icons.Default.Check, "默认", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else Text("没有可用的应用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}