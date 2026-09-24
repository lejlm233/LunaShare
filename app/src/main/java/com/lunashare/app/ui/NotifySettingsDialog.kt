package com.lunashare.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lunashare.app.link.NotifySettings

/**
 * 「通知行为」配置弹窗（复刻 ZCode 远程任务通知扩展 options 页的核心项）。
 *
 * 按页面维度打开：调用方传入该页面当前的 [settings] 与 [hasConfig]，
 * 保存时回写 [onSave]。所有开关即时作用于下一次页面事件（zcode 页会重新注入脚本）。
 */
@Composable
fun NotifySettingsDialog(
    pageLabel: String,
    settings: NotifySettings,
    hasConfig: Boolean,
    onSave: (NotifySettings) -> Unit,
    onDismiss: () -> Unit,
    /** 已有的配置被主动清除时回调（该页面回到「无通知配置」状态） */
    onClear: () -> Unit = {},
) {
    var s by remember(settings) { mutableStateOf(settings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("通知行为")
                Text(
                    pageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 总开关
                SwitchRow(
                    title = "通知总开关",
                    subtitle = if (hasConfig) "关闭后本页面不再发送任何通知" else "首次配置，默认开启",
                    checked = s.enabled,
                    onCheckedChange = { s = s.copy(enabled = it) }
                )

                SectionLabel("通知哪些事件")
                SwitchRow(
                    title = "需要确认时通知",
                    subtitle = "任务在等你回答 / 授权 / 批准；每件待确认各占一条通知，可同时存在多条",
                    checked = s.notifyOnConfirm,
                    onCheckedChange = { s = s.copy(notifyOnConfirm = it) },
                    enabled = s.enabled
                )
                SwitchRow(
                    title = "任务失败时通知",
                    checked = s.notifyOnFailed,
                    onCheckedChange = { s = s.copy(notifyOnFailed = it) },
                    enabled = s.enabled
                )
                SwitchRow(
                    title = "任务完成时通知",
                    checked = s.notifyOnCompleted,
                    onCheckedChange = { s = s.copy(notifyOnCompleted = it) },
                    enabled = s.enabled
                )

                SectionLabel("提醒方式")
                SwitchRow(
                    title = "「需要确认」通知常驻",
                    subtitle = "开启：一直保留到你点掉或从它进入 App；关闭：10 分钟后自动撤销",
                    checked = s.requireInteractionConfirm,
                    onCheckedChange = { s = s.copy(requireInteractionConfirm = it) },
                    enabled = s.enabled
                )
                SwitchRow(
                    title = "页面正被查看时也通知",
                    subtitle = "默认关闭：你盯着页面时保持安静，切走后才提醒",
                    checked = s.notifyWhenVisible,
                    onCheckedChange = { s = s.copy(notifyWhenVisible = it) },
                    enabled = s.enabled
                )
                SwitchRow(
                    title = "页面刚打开时提醒已存在的「等待确认」",
                    checked = s.checkOnOpen,
                    onCheckedChange = { s = s.copy(checkOnOpen = it) },
                    enabled = s.enabled
                )
                SwitchRow(
                    title = "严格模式",
                    subtitle = "完成/失败仅在观察到「运行中 → 终态」转变时通知；关闭更灵敏但刷新页面可能误报历史任务",
                    checked = s.strictRunning,
                    onCheckedChange = { s = s.copy(strictRunning = it) },
                    enabled = s.enabled
                )
                SwitchRow(
                    title = "通知声音",
                    subtitle = "关闭后通知静音（不响铃/不振动）",
                    checked = s.sound,
                    onCheckedChange = { s = s.copy(sound = it) },
                    enabled = s.enabled
                )

                SectionLabel("防打扰")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("同一任务同一事件冷却时间", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "防止页面刷新后重复提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = s.cooldownSec.toString(),
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.take(4)
                            s = s.copy(cooldownSec = (n.toIntOrNull() ?: 0).coerceIn(0, 3600))
                        },
                        singleLine = true,
                        enabled = s.enabled,
                        suffix = { Text("秒") },
                        modifier = Modifier.width(120.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(s) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                // 已有配置时提供「清除配置」——清除后该页面恢复「没有通知配置」的空状态
                if (hasConfig) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Default.Restore, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清除配置")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/** 重命名弹窗（侧边栏项目长按 / 菜单入口） */
@Composable
fun RenameDialog(
    original: String,
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                Text(
                    original,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    label = { Text("显示名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
