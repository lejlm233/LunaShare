# luna-send 使用说明

电脑端加密发送脚本：把文件在本地用 **AES-256-GCM** 加密后，传到手机端 LunaShare，手机端自动解密并还原**原文件名**（文件名也一并加密）。适合经公网 / 不可信网络传输敏感文件。

**大文件分包发送**：经内网穿透隧道（OpenFrp / mefrp）传输大文件时，单条大请求常因隧道超时中断。发送端会把加密后的整包切成多包（默认 **512KB/包**）逐包 `POST /_encupload_chunk`（带 `X-Luna-Session` / `X-Luna-Chunk-Index` / `X-Luna-Chunk-Total`），**单包失败可独立重试**；手机端收齐所有分包后按序合并、再整体解密还原原文件。整包单发路径 `POST /_encupload` 仍保留兼容。分包临时文件存放在手机端共享根目录下的隐藏目录 `.luna_enc_tmp/`，合并且解密后立即删除，且对外不可通过 GET 访问。

**断点续传**：传输中断（掉线 / 超时 / 手动停止）后，**重新运行同一条 `send` 命令即可从断点继续**，已传过的包不会重传。原理：加密产物缓存在本目录 `.luna_send_cache/<session>.blob`（内容本身已加密），逐包确认状态记录在 `luna-send-state.json`；续传时脚本先向手机端 `POST /_encstatus` 查询该会话已收到的分包序号，只补缺的包。服务端分包会话保留 **24 小时**（期间可续传，超时自动清理），若查询发现上次其实已传完会直接跳过。传输成功后缓存与状态自动清理；也可随时手动删除 `.luna_send_cache/` 与 `luna-send-state.json`（下次传输从头开始）。

## 安装依赖

```bash
pip install cryptography
```

## 配置

```bash
python luna-send.py config
```

交互填入：手机端地址（如 `http://<公网地址>:<映射端口>` 或局域网 `http://手机IP:8080`）、连接账号 / 密码、以及和手机端一致的**加密口令**。配置明文保存到本目录 `lunarc`。

## 使用

```bash
python luna-send.py send 文件1.zip 照片.jpg   # 加密发送（可多个，也可把文件直接拖入窗口）
python luna-send.py encrypt 文件              # 仅本地加密，输出 文件.lunaenc（不发送，可离线交付）
python luna-send.py test                      # 测试与手机端服务器连通（不真正上传）
```

## 说明

- **加密口令**独立于共享的连接账号 / 密码，两端填同一个即可互通；口令经 PBKDF2-HMAC-SHA256（20 万次迭代）派生 256-bit 密钥，无需证书、无需 HTTPS。
- **排障：上传报 `解密失败：...BAD_DECRYPT`** = 手机端共享里配置的「文件加密口令」与脚本 `lunarc` 里的 `enc_password` **不一致**（AES-GCM 认证失败，密文/文件名都会解不开）。请两端重新核对，或直接在手机端点「生成加密口令并复制」再粘到脚本配置里。排查时可用 `python luna-send.py config` 重填口令。
- 超过 **400MB** 的文件不加密并提示（避免一次性读入内存）。
- 经公网地址使用时，需手机端已创建对应 TCP 隧道（OpenFrp / mefrp）。
