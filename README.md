# LunaShare 🌙✨

**LunaShare** 是一个 Android 本地文件共享工具，支持通过 **HTTP/WebDAV**、**FTP** 和 **SMB** 协议在局域网中共享文件，并可通过 **OpenFrp 公网隧道（frpc）** 把局域网服务暴露到公网。

> 本项目由 FrpcAndroid 改造而来，frp 集成以「OpenFrp 公网隧道」的形式重新加入。

## 功能特点

- 📁 **HTTP/WebDAV 文件服务器** — 浏览器 / WebDAV 客户端访问、上传、管理文件，内置 Web 文件管理界面（HTML 生成器）；文件列表支持**按名称 / 大小 / 修改时间排序**（点列头切换升 / 降序）
- 📂 **FTP 文件服务器** — 任意 FTP 客户端访问，**PASV 被动模式**（含 EPSV，兼容 NAT/防火墙）+ PORT 主动模式，UTF-8 文件名（WinSCP 中文名不乱码）
- 🗂️ **SMB/CIFS 文件服务器** — 通过 `libsmb_engine.so` 引擎对外提供 SMB 共享（SMB2/3 + NTLM，标准客户端可用用户名/密码连接）
- 🌐 **OpenFrp 公网隧道穿透** — 集成 OpenFrp 定制版 frpc（frpc1），把本地服务映射为公网 `*.ofalias.net` 地址
- 🌐 **mefrp（幻缘映射）第二服务商** — 支持第二家 frp 穿透服务商：在「穿透服务商」页粘贴 mefrp 访问令牌验证（账号/剩余隧道数），「编辑共享」里切到 mefrp 选节点创建 TCP 隧道，与 OpenFrp 并行互不干扰
- 📋 **一键复制链接** — 弹窗同时列出局域网链接与公网访问链接，逐条复制
- 🔐 **访问认证** — 用户名 / 密码保护（HTTP/WebDAV/FTP/SMB 通用）
- 🔒 **电脑端加密传输（`tools/luna-send.py`）** — 配套 Python 脚本，在本地用 **AES-256-GCM** 端到端加密文件（**含文件名**）后上传到手机端，手机端自动解密还原原文件；加密口令独立于连接账号密码，适合经公网 / 不可信网络传输敏感文件（详见下文「电脑端加密传输」一节）
- 🏠 **mDNS 局域网名称访问** — 开共享后自动在局域网广播 `lunashare.local`，同网设备**免记 IP** 直接用名字访问（不受手机 DHCP 换 IP 影响）；可在「设置 → 局域网访问」关闭。复制链接弹窗也会额外列出「本地名称访问」项
- 🎵 **音频转换** — 内置 ffmpeg（`libffmpeg_engine.so`），FLAC → ALAC 等转码
- 📱 **文件浏览器** — 内置管理器，支持缩略图、打开、删除、重命名
- 🎨 **Material You** — Android 12+ 动态取色
- 🌙 **深色模式** — 亮 / 暗 / 跟随系统
- ⚡ **后台保活 + 开机自启** — 前台服务 + WakeLock + `BootReceiver`；顶栏电源按钮可一键干净退出（停止全部服务，二次确认）
- **退出 App** — 顶栏设置左侧红色电源按钮 → 二次确认 → 停止全部共享/隧道/SMB/mDNS、释放 WakeLock、结束进程
- 🔁 **隧道自动重试看门狗** — frpc 因网络瞬时错误退出时自动重连（最多 5 次，3s 退避），永久错误（凭据 / 禁用 / 封禁）不重试
- 🗺️ **OpenFrp 节点状态可视化** — 登录后展示各节点可用性（可用 / 受限 / 不可用，按可用性排序），编辑共享的「选择节点」弹窗也用同款卡片，选节点时直接看状态、避开数据面 relay 不通的节点
- 🔌 **ADB 端口穿透** — 在「服务」页通过右下角 `+` 的「新建共享」弹窗进入：点「ADB 穿透」打开 `AdbTunnelScreen` 配置页，配好后服务页出现 ADB 穿透卡片；把手机 `adbd` 的 TCP 5555 监听经 OpenFrp / mefrp 反向隧道映射到公网，PC 端在任意网络（含蜂窝）`adb connect` 远程控制手机；支持 root 一键开启、无 root 复制 `adb tcpip 5555` 命令、开机自启、数据面自检；服务商在 ADB 穿透页内切换（OpenFrp / mefrp），与「共享」隧道同套二进制/令牌分流架构
- 🔗 **Link 浏览器 Tab（第 3 个底部 Tab）** — 复刻 HermesMobile 的 WebView 体验：地址输入 / 扫码连接、网页标题 + favicon 展示、**多 WebView 缓存（伪标签页）**——每个打开过的链接常驻一个 WebView，侧边栏切换秒切不重新加载；右侧抽屉管理已存连接（选中高亮 / 删除二次确认 / 添加连接）；全屏沉浸浏览（无标题栏），支持不安全证书、屏幕常亮；**网页文件下载** — 点下载链接自动保存到下载目录，侧边栏抽屉顶部实时进度条（可取消 / 打开 / 重试），下载目录可在设置中修改，设置后文件页 tab 直接浏览
- ⚡ **高刷新率适配** — 启动时自动请求设备支持的最高刷新率（90/120Hz），滚动与动画更流畅
- 🎬 **Tab 切换动画** — 底部 Tab 切换带方向过渡动画（文件左滑入 / 服务淡入 / Link 右滑入）

## 快速开始

### 前置要求

- Android Studio Hedgehog (2024.3+) 或更新
- Android SDK（编译目标 API 36）
- JDK 17+（推荐用 Android Studio 自带 JBR：`Android Studio/jbr`）
- 一台 arm64-v8a 设备（frpc / smb / ffmpeg 原生库均为 arm64 构建）

### 构建运行

```bash
# 使用 Gradle wrapper
./gradlew assembleDebug

# 或直接在 Android Studio 中打开项目运行
```

> ⚠️ **Windows 构建提示**：本机非管理员时，Windows Defender 实时扫描会偶发抢锁，导致 Gradle 非确定性 `AccessDeniedException`（每次卡在不同 `.lock`）。若该问题反复出现，绕过 wrapper 直接调用已解压的 Gradle 二进制、并在每轮构建前清理 `.lock` 即可。

### 在设备上使用

1. 打开 App，进入「服务」标签页
2. 点击 `+` 创建共享，选择要共享的文件夹
3. 选择要启用的服务（HTTP/WebDAV、FTP、SMB）
4. 设置访问用户名 / 密码（可选）
5. 保存并启动；同一局域网内设备即可通过链接访问
6. 需要公网访问时，在「OpenFrp」页登录并创建隧道（见下节）

### Link Tab（浏览器 / 远程连接）

第 3 个底部 Tab「Link」是一个轻量 WebView 浏览器（内核为系统 Chromium WebView），常用于连接 ZCode、HermesMobile 等设备管理页面：

1. 切到「Link」Tab → 主页输入访问地址（自动补 `http://`）或点相机图标扫码连接
2. 连接后全屏浏览网页：底部栏左侧「返回」按钮回到进入前的 Tab，右侧「侧边栏」打开连接管理抽屉
3. 侧边栏（屏幕 80% 宽，右侧滑出）：已存连接以卡片展示（网页标题 + 地址 + favicon，当前激活高亮，删除需二次确认），底部「添加连接」回到主页；链接较多时列表内滚动
4. **多链接秒切**：打开过的链接会常驻 WebView（不重新加载），在侧边栏点其它已开链接即瞬间切换，像浏览器标签页一样保留页面状态
5. **网页下载**：网页里点下载链接会自动开始下载，侧边栏抽屉顶部「下载」区实时显示进度条（可取消 / 打开 / 重试），带页面 Cookie 与登录态，文件保存到下载目录
6. 主页底部开关可设置「允许不安全证书」与「保持屏幕常亮」；连接页同样通过「添加连接」进入
7. **下载目录设置**：默认系统 Download 目录，可在「设置」→「下载设置」里「选择文件夹」改到任意外部存储目录或以「恢复默认」重置；设置后「文件」页顶部会出现对应目录名的 tab，点进去直接浏览已下载文件

## 电脑端加密传输（`tools/luna-send.py`）

配套命令行脚本，用于 **电脑 → 手机** 之间的端到端加密文件传输。文件在电脑本地用 **AES-256-GCM（AEAD）** 加密，**文件名也一并加密**（手机端还原为原名称），经 HTTP `POST /_encupload` 上传，手机端收到后自动解密落盘。

- **加密口令**独立于共享的连接账号/密码，存于共享配置的「文件加密口令」字段；电脑端脚本与手机端填入**同一个口令**即可互通。口令经 **PBKDF2-HMAC-SHA256（20 万次迭代，256-bit）** 派生密钥，无需任何证书、无需 HTTPS。
- 超过 **400 MB** 的文件脚本不加密并给出提示（避免一次性读入内存造成压力）。
- 配置文件（服务器地址 / 账号 / 密码 / 加密口令）以**明文**保存在脚本所在目录的 `lunarc` 文件，便于自用维护。

### 使用步骤

1. **手机端设置口令**：编辑共享 → 「文件加密口令」卡片，点「生成加密口令并复制」生成随机口令（或自行填写），保存到共享配置（务必记住 / 复制该口令）。
2. **电脑端配置**：运行 `python tools/luna-send.py config`，交互填入手机端地址（如公网 `http://<公网地址>:<映射端口>` 或局域网 `http://手机IP:8080`）、连接账号/密码、以及**与手机端完全一致的加密口令**，保存到 `tools/lunarc`。
3. **发送**：`python tools/luna-send.py send 文件1.zip 照片.jpg`（可一次多个，也可把文件直接拖入命令行窗口）；脚本本地加密后上传，手机端自动解密并还原原文件名落盘。
4. **仅本地加密（不发送）**：`python tools/luna-send.py encrypt 文件` → 输出 `文件.lunaenc`（文件名与内容均已加密，可用于离线交付 / 二次传输）。
5. **连接测试**：`python tools/luna-send.py test` 验证与手机端服务器连通（复用 Basic Auth，不真正上传）。

> 依赖：`pip install cryptography`。经公网地址使用时需手机端已创建对应 TCP 隧道（OpenFrp / mefrp），详见下一节「公网访问」。

## 公网访问（OpenFrp 隧道）

LunaShare 在 `frpc/` 模块实现了完整的 OpenFrp 集成；同时支持 **mefrp（幻缘映射）** 作为第二穿透服务商（独立 mefrpc 二进制 `libmefrpc.so`（0.67.0）+ 同款看门狗/数据面探测架构，隧道按 `provider` 字段分流）：

- **登录**：支持远程登录 / OAuth / WebView 登录（`OpenFrpScreen`）
- **隧道管理**：在共享配置中一键创建 TCP 隧道，frpc 用 `frpc -n -u <OpenFrp 访问令牌> -p <隧道 ID>` 拉起，客户端自动从 OpenFrp API 拉取完整配置
- **节点状态**：OpenFrp 页展示节点列表（可用性 chip + 带宽 / 主机 / 端口范围），编辑共享的「选择节点」弹窗也用同样的卡片，选节点时直接看状态；普通用户可用节点（`group` 含 `normal`）会排在前且不显示 VIP/SVIP 角标
- **链接展示**：`ServiceListScreen` 的复制链接弹窗会列出公网地址 `http://<节点域名>:<remotePort>`
- **自动重试**：`FrpcManager` 的看门狗在瞬时网络错误后自动重连

### 配置步骤

1. 在 OpenFrp 后台注册账号，获取 frpc 用户密钥
2. App 内「OpenFrp」页登录
3. 编辑某个共享 → 创建隧道：在「选择节点」卡片列表里挑一个**可用**的节点（绿标），远程端口会自动在该节点的端口范围内随机建议，本地端口填服务端口（如 HTTP 用 `8080`）
4. 启动共享，隧道自动拉起
5. 点「复制链接」→ 选公网访问链接，发给外网设备

### 当前状态（重要）

**局域网访问完全正常。** 公网 TCP 隧道能否真正连通，**取决于所选 OpenFrp 节点的数据面 relay 是否可用**，与 frpc 二进制版本无关：

- ✅ **海外节点数据面正常**：实测节点 4（韩国-首尔）、节点 29（香港-4）relay 可正常转发流量，公网地址可达。
- ❌ **国内节点数据面多不通**：实测节点 45（北京移动）、节点 32（宁德电信）frpc 控制面（`ESTABLISHED`）正常、TCP 也能连，但远程连接被 relay 丢弃（HTTP 返回 0 字节），属节点侧合规 / ICP 限流，**App 与 frpc 均无法修复**。账号 `realname=True` 已排除「未实名」误判。
- 内置 frpc 已替换为 OpenFrp 官方最新定制版（`OF_0.68.0_37f78258_260326`，arm64）；该替换消除了旧 build 潜在的协议兼容隐患，但**不能让国内节点「数据面不通」变成「通」**——国内节点 relay 坏是平台侧问题。

> 结论：公网可用性的瓶颈在**节点**，不在客户端。LunaShare 的做法是**把节点状态如实展示出来**（OpenFrp 页节点列表 + 编辑共享的节点卡片），让你优先选海外 / 可用节点，并保留 `ShareService.probeDataPlane` 在隧道「启动成功」后主动探测公网地址可达性、把结果写进 frpc 日志，避免被「启动成功」误导。

> 二进制获取方式：OpenFrp 软件分发 API `GET https://api.openfrp.net/commonQuery/get?key=software`（须带 UA）→ 取 `data.latest_full` 与 `data.source` 拼接 `frpc_android_arm64.tar.gz`（Android 仅 arm64，tar.gz 打包）。

## ADB 端口穿透（公网远程控制）

LunaShare 在「服务」页通过右下角 `+` 按钮打开「新建共享」弹窗，点其中的「ADB 穿透」即可进入 `AdbTunnelScreen` 配置页；配置完成后「服务」页内会出现一张 ADB 穿透卡片，可把手机 `adbd` 的 TCP 5555 监听经 OpenFrp 反向隧道映射到公网，PC 端在**任意网络（含蜂窝数据）**都能 `adb connect` 远程控制手机（调试、截屏、安装、文件推送等）。

### 工作原理

```
手机本地 adbd 监听 TCP 5555
   (root 一键开启 或 电脑 `adb tcpip 5555`)
        │
        ▼
frpc 反向隧道：127.0.0.1:5555  →  OpenFrp / mefrp 公网节点 <host>:<remotePort>
        │
        ▼
PC 端（任意网络）：adb connect <host>:<remotePort>
```

- **开启 5555**：`adb tcpip 5555` 让 adbd 在 TCP 5555 监听并绑定所有网卡；`adb tcpip 0` 关闭。
- **公网映射**：复用 OpenFrp frpc（`ShareService` 内的 `startAdbTunnel` / `stopAdbTunnel`），把 `127.0.0.1:5555` 反向映射为 `*.ofalias.net:<remotePort>`。
- **数据面自检**：隧道启动后 `ShareService.probeAdbDataPlane` 从设备自身向公网地址发 TCP 探测，验证 PC 端 `adb connect` 能真正落到本地 5555，结果写入日志（避免被「启动成功」误导）。

### 使用步骤

1. **开启手机 5555（第一步）**
   - 有 root：点「一键开启 5555（root 模式）」，执行 `setprop persist.adb.tcp.port 5555 && restart adbd`，**永久生效、开机自启**。
   - 无 root：用数据线或无线调试把手机连上电脑，在电脑终端执行 App 内提供的 `adb tcpip 5555`（含一键复制按钮）；关闭用 `adb tcpip 0`。回到 App 点「刷新状态」，待「手机 5555 端口」显示「已在监听」即可建隧道。
   - > 设计取舍：本 App **不**通过 Shizuku 自动开启 5555。部分 ROM（实测 vivo）限制 shell 设系统属性 / 重启 adbd 的权限，故回归由用户在 PC 上执行 `adb tcpip` 命令的最稳方案，App 仅做探测 + 引导 + 建隧道。
2. **创建公网隧道（第二步）**：先在「OpenFrp」或「Frp 设置（mefrp）」页登录/验证令牌 → 在 ADB 穿透配置页顶部切到对应**服务商**（OpenFrp / mefrp）→ 点「选择节点创建公网隧道」→ 挑一个**可用**节点 + 填远程端口（在节点端口范围内）→ 创建后 frpc 自动拉起。mefrp 隧道用独立 mefrpc 二进制（`-t` 启动令牌），节点公网地址从 proxy/list 回填，与「共享」隧道完全一致。
3. **连接**：隧道卡片里给出 `adb connect <host>:<remotePort>` 命令，一键复制；PC 端执行即可远程控制。
4. **开机自启（可选）**：隧道卡片里开「开机自动启动此 ADB 穿透」。重启后由 `BootReceiver` 拉起 `ShareService` 并自动启动该隧道（前提：手机已开启 5555——root 模式可完全自启；无 root 需先连电脑 `adb tcpip 5555`）。

### 核心文件

```
app/src/main/java/com/lunashare/app/
├── adb/                          # ⭐ ADB 端口穿透模块
│   ├── AdbTunnelManager.kt       # 探测 5555 / root 开启 / USB 兜底命令
│   ├── AdbTunnelConfig.kt        # 穿透配置（proxyId / nodeHost / remotePort / bootAutoStart）
│   ├── AdbTunnelStateHolder.kt   # 全局单例响应式状态（StateFlow）
│   └── AdbTunnelStore.kt         # SharedPreferences 持久化
└── ui/
    └── AdbTunnelScreen.kt        # 服务页 ADB 卡片对应的全屏配置页（开启 5555 / 建公网隧道 / adb connect）
```

## 项目结构

```
LunaShare/
├── app/src/main/
│   ├── java/com/lunashare/app/
│   │   ├── MainActivity.kt           # 主入口
│   │   ├── BootReceiver.kt            # 开机自启广播接收器
│   │   ├── PortraitCaptureActivity.kt # 竖屏扫码页（Link Tab 扫码，ZXing）
│   │   ├── config/
│   │   │   └── ShareConfigStore.kt    # 共享配置存储（SharedPreferences JSON）
│   │   ├── link/                      # ⭐ Link Tab 模块（WebView 浏览器）
│   │   │   ├── LinkStore.kt           # 连接地址/标题/favicon 持久化（SharedPreferences）
│   │   │   ├── LinkWebViewHolder.kt   # 当前激活 WebView 单例（文件选择结果转发）
│   │   │   ├── LinkDownloadManager.kt # 下载管理（OkHttp 流式下载 + 进度 StateFlow，带 Cookie/UA）
│   │   │   ├── LinkDownloadStore.kt   # 下载路径持久化（SharedPreferences / SAF treeUri）
│   │   │   └── DownloadTask.kt        # 下载任务模型（状态/进度）
│   │   ├── model/
│   │   │   ├── ShareConfig.kt         # 配置模型（含 http/ftp/smb TunnelConfig）
│   │   │   └── ShareServiceType.kt    # 服务类型枚举
│   │   ├── service/
│   │   │   ├── ShareService.kt        # 前台服务（共享管理核心）
│   │   │   ├── MdnsBroadcaster.kt     # mDNS 局域网名称广播（lunashare.local → 手机 IPv4，jmDNS）
│   │   │   ├── ShareStateHolder.kt    # 响应式状态管理（StateFlow）
│   │   │   ├── FileServer.kt          # HTTP/WebDAV 文件服务器（原始 Socket）
│   │   │   ├── WebUi.kt               # Web 文件管理界面（HTML 模板 / JSON）
│   │   │   ├── FtpServer.kt           # FTP 服务器包装
│   │   │   ├── FtpHandler.kt          # FTP 协议处理器
│   │   │   ├── SmbServerManager.kt    # SMB 服务（libsmb_engine.so）
│   │   │   └── NanoFileServer.kt      # NanoHTTPD 备用服务器
│   │   ├── frpc/                      # ⭐ OpenFrp / frpc1 集成
│   │   │   ├── FrpcManager.kt         # frpc 进程管理 + 自动重试看门狗
│   │   │   ├── FrpcBinaryManager.kt   # 原生二进制解压 / 版本校验
│   │   │   ├── OpenFrpApiClient.kt    # OpenFrp API 客户端
│   │   │   ├── mefrp/                 # ⭐ mefrp（幻缘映射）第二服务商
│   │   │   │   ├── MefrpApiClient.kt    # mefrp API 客户端（Bearer 令牌，https://api.mefrp.com/api）
│   │   │   │   ├── MefrpConfigStore.kt  # 访问令牌/frpcToken/节点 持久化
│   │   │   │   └── MefrpModels.kt       # mefrp 数据模型（code/data/message 信封）
│   │   │   ├── OpenFrpConfigStore.kt  # OpenFrp 凭证存储
│   │   │   ├── OpenFrpRemoteLogin.kt  # 远程登录
│   │   │   ├── OpenFrpOAuthLogin.kt   # OAuth 登录
│   │   │   ├── model/OpenFrpModels.kt # OpenFrp 数据模型
│   │   │   └── OpenFrpWebViewLogin.kt # WebView 登录页
│   │   ├── adb/                       # ⭐ ADB 端口穿透模块（全局单例功能）
│   │   │   ├── AdbTunnelManager.kt    # 探测 5555 / root 开启 / USB 兜底命令
│   │   │   ├── AdbTunnelConfig.kt     # 穿透配置（proxyId / nodeHost / remotePort / bootAutoStart）
│   │   │   ├── AdbTunnelStateHolder.kt# 全局单例响应式状态（StateFlow）
│   │   │   └── AdbTunnelStore.kt      # SharedPreferences 持久化
│   │   ├── ui/
│   │   │   ├── MainScreen.kt          # 主界面（文件 | 服务 | Link，三 Tab 常驻 + 切换动画）
│   │   │   ├── LinkScreen.kt          # Link Tab（登录页 + 多 WebView 缓存 + 右侧抽屉）
│   │   │   ├── AdbTunnelScreen.kt     # ADB 穿透全屏配置页（从服务页卡片进入：开启 5555 / 建公网隧道 / adb connect）
│   │   │   ├── FileListScreen.kt      # 文件浏览器
│   │   │   ├── FileOpenHelper.kt      # 文件「打开方式」共享实现（AppChooserDialog / FileProvider / APK 安装）
│   │   │   ├── ServiceListScreen.kt    # 服务列表 + 复制链接弹窗
│   │   │   ├── ServiceConfigScreen.kt  # 共享配置编辑器（含隧道创建）
│   │   │   ├── OpenFrpScreen.kt        # OpenFrp 登录 / 隧道管理
│   │   │   ├── SettingsScreen.kt       # 设置页
│   │   │   └── theme/                  # Material You 主题
│   │   └── util/
│   │       └── MusicConverter.kt       # ffmpeg 音频转码（libffmpeg_engine.so）
│   ├── jniLibs/arm64-v8a/
│   │   ├── libfrpc.so                 # OpenFrp 官方定制版 frpc（OF_0.68.0_37f78258_260326, arm64）
│   │   ├── libsmb_engine.so           # SMB 引擎
│   │   └── libffmpeg_engine.so        # ffmpeg 引擎
│   └── res/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── tools/
    └── luna-send.py            # ⭐ 电脑端加密传输脚本（AES-256-GCM 端到端加密上传，含 config/send/encrypt/test 子命令）
```

## 配置说明

### 共享配置项（`ShareConfig`）

| 配置项 | 说明 | 示例 |
|--------|------|------|
| 名称 | 共享名称 | `我的文档` |
| 文件夹路径 | 要共享的目录 | `/storage/emulated/0/Documents` |
| 用户名 / 密码 | 访问认证（可选） | `your_username` / `your_password` |
| HTTP/WebDAV | 是否启用 HTTP 服务 | 开关 |
| HTTP 端口 | HTTP 服务端口 | `8080` |
| FTP | 是否启用 FTP 服务 | 开关 |
| FTP 端口 | FTP 服务端口 | `8021` |
| SMB | 是否启用 SMB 服务 | 开关 |
| SMB 端口 | SMB 服务端口 | `8445` |
| 开机自启 | 开机自动启动 | 开关 |
| OpenFrp 用户密钥 | frpc 登录密钥（32 位） | `fb9a…` |
| OpenFrp 隧道 | 各服务的 `TunnelConfig`（proxyId / 节点 / 远程端口） | — |

### `TunnelConfig`（单条隧道）

| 字段 | 说明 |
|------|------|
| `enabled` | 是否启用该隧道 |
| `proxyId` | OpenFrp 代理 ID（数字） |
| `nodeId` / `nodeHost` / `nodePort` | OpenFrp 节点信息 |
| `remotePort` | OpenFrp 分配的公网远程端口 |
| `localPort` | 本地映射端口（如 HTTP 用 `8080`） |
| `type` | 隧道类型（当前 `tcp`） |

## 技术原理

- **HTTP/WebDAV 服务器** — 基于原始 Socket 的自定义实现，支持完整 WebDAV 协议（PROPFIND / PUT / DELETE / MKCOL / COPY / MOVE / LOCK / UNLOCK），`WebUi` 提供文件列表 HTML 与 JSON 模板
- **FTP 服务器** — 自定义 FTP 协议，**PASV 被动模式（EPSV 亦支持）+ PORT 主动模式**，声明 `FEAT UTF8` 支持 UTF-8 文件名（解决 WinSCP 等客户端中文名乱码），上传下载均支持
- **SMB 服务器** — 通过 `libsmb_engine.so` 原生引擎（go-smbserver fork）对外提供 SMB2/3 共享，NTLM 认证，标准客户端（Windows 资源管理器 / WinSCP）用用户名/密码即可连接
- **OpenFrp 隧道** — `FrpcManager` 拉起 `libfrpc.so`，用 `-n -u <token> -p <proxyId>` 启动；客户端自拉配置，无需本地 TOML；Go 静态二进制缺 Android CA，启动时注入 `SSL_CERT_FILE` 指向合并的系统证书包
- **音频转换** — `MusicConverter` 调用 `libffmpeg_engine.so` 完成 FLAC → ALAC 等转码
- **mDNS 局域网名称广播** — 开共享时 `MdnsBroadcaster` 用 jmDNS（`javax.jmdns`）向组播宣告 `lunashare.local → 手机 WiFi IPv4`，并注册 `_http._tcp` / `_ftp._tcp` 服务（DNS-SD）；同网设备（Windows 装 Bonjour / iTunes、macOS / Linux 原生）可免记 IP 直接用名字访问。需 `CHANGE_WIFI_MULTICAST_STATE` 组播锁，依赖本地 jar `libs/jmdns-3.5.9.jar` + `libs/slf4j-api-1.7.36.jar`（runtime 仅 warning 不报错）
- **前台服务 + 状态管理** — Foreground Service 保活，Kotlin StateFlow 驱动响应式 UI

## 开源许可

本项目由 [AceDroidX/frp-Android](https://github.com/AceDroidX/frp-Android)（FrpcAndroid）改造而来，保留了其 frp 设计思路，并以 OpenFrp 公网隧道的形式重新集成了 frpc 穿透能力，同时扩展为 HTTP/WebDAV + FTP + SMB 多协议本地文件共享工具。

## 构建签名（Signing）

本项目完全开源、无任何商业计划，因此发布签名密钥信息公开如下，方便任何人 clone 后直接编译出**签名一致**的安装包（避免不同设备 / 机器编译出现签名不一致导致无法覆盖安装的情况）。

- **Keystore 文件**：`app/keystore/lunashare-release.jks`（已纳入仓库，`.gitignore` 白名单例外）
- **别名（keyAlias）**：`lunashare`
- **Keystore 密码 / Key 密码**：`ad6061a7383916a03868ab57618960bffe1c`
- **有效期**：10000 天（约 27 年）
- **证书指纹（固定值，所有构建一致）**：
  - **SHA1**：`2C:EB:2E:4F:9F:1B:C5:A9:4A:A2:1F:B8:46:8B:CA:59:B1:88:15:F1`
  - **SHA256**：`2F:92:84:2E:08:52:68:74:E6:C2:72:7A:67:74:43:28:2A:AC:33:85:36:E6:32:06:E4:C3:4F:DB:8C:F5:D3:0A`

`release` 与 `debug` 构建类型**共用同一签名**（`app/build.gradle.kts` 的 `signingConfigs.release`），无需任何额外配置，clone 后即可 `./gradlew assembleDebug` / `assembleRelease`。

> 重新生成命令（仅维护者需要时执行，生成后请同步更新上面密码与指纹）：
> ```bash
> keytool -genkeypair -v \
>   -keystore app/keystore/lunashare-release.jks \
>   -alias lunashare -keyalg RSA -keysize 2048 -validity 10000 \
>   -dname "CN=luxi2035, OU=LunaShare, O=LunaShare, L=Shanghai, ST=Shanghai, C=CN"
> ```
