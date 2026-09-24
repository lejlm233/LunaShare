package com.lunashare.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lunashare.app.config.RemoteConnectionStore
import com.lunashare.app.model.RemoteConnection
import kotlinx.coroutines.launch

/**
 * 远程连接（连接共享）编辑器：与 [ServiceConfigScreen] 同模式的全屏表单。
 *
 * 「添加」弹窗选「连接共享」→ 传 [isNew]=true 新建；服务面板卡片「编辑」→ 编辑已有配置。
 * 保存动作通过 [onSaved] 回调宿主（落盘后的重连、刷新、提示都在宿主做）；
 * 复制/删除等操作在服务面板的连接卡片上完成，编辑器只负责表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteConfigScreen(
    connection: RemoteConnection,
    store: RemoteConnectionStore,
    isNew: Boolean,
    onSaved: (RemoteConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    // 系统返回键 → 丢弃修改
    BackHandler { onDismiss() }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun msg(s: String) { scope.launch { snackbar.showSnackbar(s) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建连接" else "编辑连接") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RemoteEditForm(
                initial = connection,
                onSave = { cfg ->
                    if (cfg.name.isBlank()) { msg("名称不能为空"); return@RemoteEditForm }
                    if (cfg.host.isBlank()) { msg("地址不能为空"); return@RemoteEditForm }
                    store.saveConfig(cfg)
                    onSaved(cfg)
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 新建/编辑表单，样式对齐 ServiceConfigScreen（OutlinedTextField + 分节标题）。 */
@Composable
private fun RemoteEditForm(
    initial: RemoteConnection,
    onSave: (RemoteConnection) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.port == 0) "" else initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var anonymous by remember { mutableStateOf(initial.anonymous) }
    var remotePath by remember { mutableStateOf(initial.remotePath) }
    var autoConnect by remember { mutableStateOf(initial.autoConnect) }
    var pwVisible by remember { mutableStateOf(false) }

    fun defaultPort(): String = when (protocol) {
        "webdav" -> "80"
        "smb" -> "445"
        else -> "21"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("基本信息", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("名称") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // 协议三选一
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteConnection.PROTOCOLS.forEach { p ->
                FilterChip(
                    selected = protocol == p,
                    onClick = {
                        protocol = p
                        if (port.isBlank() || port.toIntOrNull() in listOf(21, 80, 443, 445)) port = ""
                    },
                    label = { Text(RemoteConnection.protocolLabel(p)) }
                )
            }
        }

        Text("服务器", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = host, onValueChange = { host = it.trim() },
            label = { Text("地址（IP 或域名）") }, singleLine = true,
            supportingText = { Text("如 192.168.1.100；WebDAV 可填 https:// 开头走加密") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("端口（留空用默认 ${defaultPort()}）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = remotePath, onValueChange = { remotePath = it },
            label = { Text("初始目录") }, singleLine = true,
            supportingText = {
                Text(when (protocol) {
                    "smb" -> "SMB 填共享名，如 /Share"
                    "webdav" -> "WebDAV 服务器上的路径，如 /dav"
                    else -> "FTP 登录后的起始目录"
                })
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text("账号", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = anonymous, onCheckedChange = { anonymous = it })
            Text("匿名访问", style = MaterialTheme.typography.bodyMedium)
        }
        if (!anonymous) {
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("用户名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("密码") }, singleLine = true,
                visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { pwVisible = !pwVisible }) {
                        Text(if (pwVisible) "隐藏" else "显示", fontSize = 12.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = autoConnect, onCheckedChange = { autoConnect = it })
            Text("app 启动时自动连接", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                onSave(initial.copy(
                    name = name.trim(),
                    protocol = protocol,
                    host = host.trim(),
                    port = port.toIntOrNull() ?: 0,
                    username = if (anonymous) "" else username.trim(),
                    password = if (anonymous) "" else password,
                    anonymous = anonymous,
                    remotePath = remotePath.trim().ifBlank { "/" },
                    autoConnect = autoConnect,
                ))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}
