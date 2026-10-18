# Codex Remote

从 Android 手机使用服务器上的 Codex，访问对话、终端和文件。

Codex Remote 复用官方 VS Code 插件：手机显示官方聊天界面，Agent 在服务器上运行官方 `extension.js`，实际任务由服务器上的 Codex App Server 执行。App 提供服务器切换、工作目录选择和手机端交互。

[下载 Android APK](https://github.com/chensunlai/codex-remote/releases/latest/download/codex-remote-android.apk) · [所有版本](https://github.com/chensunlai/codex-remote/releases)

## 目录

- [功能](#功能)
- [架构](#架构)
- [运行要求](#运行要求)
- [安装与连接](#安装与连接)
- [使用说明](#使用说明)
- [更新](#更新)
- [配置与数据位置](#配置与数据位置)
- [常见问题](#常见问题)
- [从源码构建](#从源码构建)

## 功能

| 功能 | 说明 |
| --- | --- |
| 官方聊天界面 | 会话、流式回复、模型选项和审批由官方插件处理 |
| 多服务器 | 在同一 Gateway 下切换 Agent，分别保存工作目录 |
| 远端执行 | 手机断网或关闭后，服务器继续执行已接受的任务 |
| 聊天附件 | 从手机选择图片和文件，加入官方输入框 |
| 文件与终端 | 浏览服务器文件、预览和下载文件，使用远端终端 |
| 鼠标模式 | 在触屏上移动指针、悬停、点击和切换键盘焦点 |
| 外观 | 黑色、白色、Anthropic 暖色主题，以及字体缩放 |
| Codex 管理 | 查看 CLI 版本，在 App 中更新或重启服务器上的 Codex |
| Gateway 管理 | 管理员在 App 中查看服务、管理 Token、修改传输限制、更新或重启 Gateway |
| 版本检查 | 在 App 中检查 Android App 和 Agent 是否有新版本 |

官方前端与 `extension.js` 保持原样；主题通过宿主提供的 VS Code CSS 变量和主题元数据适配。模型列表、推理档位和功能开关由官方插件及后端决定。

## 架构

```text
Android 手机
  原生 App + 官方 WebView
            ↕ HTTPS / WSS
Gateway 服务器
  认证、服务发现、消息与资源转发
            ↕ WSS（Agent 主动连接）
Agent 服务器
  Node.js 宿主 → 官方 extension.js → Codex App Server
```

| 组件 | 安装位置与职责 |
| --- | --- |
| Android App | 安装在手机，显示界面并提供文件选择、预览、剪贴板等能力 |
| Gateway | 部署在手机和 Agent 均可访问的服务器，默认监听 `6767` 端口 |
| Agent | 部署在实际运行 Codex 的服务器，运行插件宿主、访问文件并执行命令 |

Gateway 与 Agent 可以部署在同一台机器上，也可以分开部署。Agent 主动连接 Gateway，无需为 Agent 单独开放入站端口。**App 和 Agent 必须使用同一个 Gateway 访问令牌**，App 才能看到对应服务器。

官方 WebView 和 `extension.js` 来自同一份 `OpenAI.chatgpt` 插件。宿主适配层实现所需的公开 VS Code API，Gateway 不解释官方聊天消息内容。本项目不读取官方前端私有导出，也不自行解析 Codex 会话文件。

## 运行要求

| 环境 | 要求 |
| --- | --- |
| Gateway / Agent | glibc Linux，支持 `x86_64` 和 `arm64` |
| Agent 所在服务器 | 已安装并登录 Codex CLI，启动 Agent 的用户可以运行它 |
| Android | Android 8.0（API 26）及以上，系统 WebView 需支持当前官方前端 |
| Gateway 地址 | Release APK 使用 HTTPS 地址 |

Agent 安装包附带扩展宿主和独立的 Node.js 24.18.0 运行时，无需另装系统 Node.js。**Codex CLI 需自行安装**；Agent 使用系统中的 `codex` 或配置时指定的可执行文件。官方插件在首次使用时下载。

当前移动宿主不提供代码编辑、编辑器上下文、代码跳转、登录回调或 Windows/WSL 支持。请先在 Agent 服务器完成 Codex 登录。

## 安装与连接

`install.sh` 根据命令参数下载并安装程序。交互式界面在程序启动后提供：Gateway 打开管理界面，Agent 首次启动填写连接配置。

### 1. 安装 Gateway

在 Gateway 服务器执行：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | sudo bash -s -- gateway --systemd
```

其中 `gateway` 选择安装 Gateway，`--systemd` 表示同时创建并启动 systemd 服务。默认安装到 `/opt/codex-remote`。

systemd 安装需要服务器提供 `curl` 和 `flock`；安装器会先检查这些依赖。

查看状态和首次生成的访问令牌：

```bash
sudo systemctl status codex-remote-gateway
sudo cat /opt/codex-remote/data/api-token
```

打开交互式管理界面，可创建或撤销带 tag 的令牌、管理服务记录：

```bash
sudo /opt/codex-remote/codex-remote-gateway
```

首次自动生成的管理员令牌保存在 `data/api-token`；在管理界面创建的新令牌只显示一次，请在创建时保存。这里的访问令牌也就是 App 中填写的 Gateway Key，与 OpenAI API Key 不同。使用管理员令牌连接 App 后，可直接在手机上管理 Gateway。

不使用 systemd 时，安装命令省略 `--systemd`，然后运行 `sudo /opt/codex-remote/codex-remote-gateway start` 启动服务。直接运行二进制打开的是管理界面。

### 2. 安装 Agent

在运行 Codex 的服务器上，以已登录 Codex 的用户执行：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | bash -s -- agent

~/.local/codex-remote/codex-remote-agent
```

**Agent 默认安装到 `~/.local/codex-remote`**，不需要先创建目录或切换当前目录。首次启动进入配置界面：

| 配置项 | 填写内容 |
| --- | --- |
| Gateway 地址 | 例如 `https://codex.example.com` |
| 服务名称 | 在 App 中显示的名称，例如 `开发服务器` |
| 访问令牌 | Gateway 创建的令牌，与 App 使用的令牌一致 |
| Codex 路径 | 默认 `codex`，也可填写可执行文件的绝对路径 |

配置保存在 `~/.local/codex-remote/agent.json`。后续直接运行即可连接，重新配置使用：

```bash
~/.local/codex-remote/codex-remote-agent --configure
```

安装器不会为 Agent 配置后台服务。需要断开 SSH 后继续运行时，可以用 tmux 启动；避免同时启动同一份配置的多个 Agent：

```bash
tmux new -s codex-remote-agent "$HOME/.local/codex-remote/codex-remote-agent"
```

按 `Ctrl+B`，再按 `D` 离开 tmux；通过 `tmux attach -t codex-remote-agent` 返回。tmux 会话不会在服务器重启后自动恢复。

### 3. 连接 Android App

下载并安装 [Release APK](https://github.com/chensunlai/codex-remote/releases/latest/download/codex-remote-android.apk)：

1. 输入 Gateway 的 HTTPS 地址和访问令牌。
2. 在“服务器”页面选择在线 Agent。
3. 首次进入聊天页，点击 **“检查并下载官方前端”**，等待下载和初始化完成。
4. 点击右上角文件夹按钮，选择服务器上的工作目录。
5. 在官方界面打开已有会话，或创建新会话。

工作目录按服务器保存，默认是 Agent 用户的主目录。切换工作目录会重新加载官方页面。

### 自定义安装位置

安装器支持通过 `--install-dir` 指定位置。例如：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | bash -s -- agent --install-dir "$HOME/tools/codex-remote"
```

默认目录变更不会搬迁已有安装。**升级旧 Agent 时，如果原目录不同，请继续用 `--install-dir` 指定原目录**，以使用已有配置和数据。

## 使用说明

### 页面切换与断线恢复

切换 App 底部页面时保留聊天 WebView。短暂断线后，能够恢复原页面时会保留草稿；系统回收页面或宿主要求重新同步时，需要重新加载。结果不明的已发送操作不会自动重发，以免重复执行。

官方插件支持历史分页，但多端同步仍可能产生较大的状态快照。Agent ↔ Gateway ↔ App 在传输层透明分块：Agent 与 Gateway 在 `32 MiB` 以内直接发送；手机上行为 `8 MiB`，以适应 Android 网络库的发送队列限制。超过阈值才拆成二进制分片，收齐后交给原有逻辑。单条 WebSocket 消息（含分片头）上限为 `32 MiB`。官方插件资源保持原样。

管理员可在 App 的 Gateway 管理 → 参数中选择完整会话同步消息上限：`64 / 128 / 256 / 512 MiB`，默认 `128 MiB`。该设置保存于 Gateway 的 `gateway-settings.json`（`maxMessageBytes`），对新连接生效；调整后需要重连 App 和 Agent，实际链路按两端协商的较小上限执行。较高上限会增加手机重组及 WebView 解析时的内存占用。App 使用系统允许的大堆额度；配置上限不保证所有手机都能处理同等大小的消息，内存不足时会停止本次传输并提示。

会话 WebSocket 链路启用标准 `permessage-deflate` 压缩，支持压缩的 Agent 和 App 自动协商；小于 `1 KiB` 的下发消息不压缩。分块阈值和消息上限仍按解压后的字节数计算。无需修改官方插件；不支持压缩的客户端仍可正常连接。

Agent 保留最多 `48 MiB` 的未确认事件用于断线恢复，超过缓存范围时重新同步。中断的分片会被丢弃，未确认的用户操作不会自动重发。上述限制独立于文件上传、预览和下载限制。

分块会话要求 App、Gateway、Agent 均升级至 `2.2.0` 或更新版本。旧连接入口保留；新版客户端遇到旧组件会提示升级。新 WebSocket 入口为 `/agent/v2/connect` 与 `/api/v4/services/:serviceId/extension`，现有 HTTP 资源接口沿用。

手机进入后台、断网或彻底关闭，都不会主动结束 Agent 上的官方扩展。**后端已接受的任务继续执行**；需要审批或补充信息时，等待用户恢复连接。Agent、扩展宿主和 App Server 必须持续运行，重启服务器或相关进程可能中断任务。

官方设置等独立面板显示在聊天页上层。App 返回箭头和 Android 返回操作可关闭上层面板；键盘、原生弹窗和未保存设置的确认窗口优先处理返回。

### 图片、文件与链接

- **添加聊天附件**：点击官方输入框的 **＋ → Files and folders（文件和文件夹）**；若出现“选择文件或目录”，选择 **“手机文件或图片”**，再选择附件，可多选。“服务器目录”用于选择远端目录。附件加入输入框后，点击发送才会提交给 Codex。
- **查看和下载**：聊天中的本地文件链接与文件页共用原生预览器。图片支持双指缩放，文本最多预览 256 KiB，其他二进制文件提示下载。所有原生文件预览均提供下载按钮。
- **打开网页**：网页链接提供“在浏览器打开”操作。
- **清理附件**：上传文件保存在 Agent 用户的 `~/.codex-remote-uploads/<随机目录>/原文件名`，供会话后续引用，可在文件页手动清理。

Gateway 默认上传、预览和下载上限均为 **10 MiB**。管理员可在 App 中分别修改上传和下载上限，预览使用下载上限。图片预览还受 App 的 20 MiB 数据上限约束，并会缩小过大的位图以控制内存。

### 鼠标模式

点击聊天页右上角的指针按钮切换鼠标模式，切换时不会重新加载前端：

| 操作 | 效果 |
| --- | --- |
| 单指滑动 | 移动指针 |
| 松手 | 保持悬停 |
| 轻点 | 在指针位置点击 |
| 双指滑动 | 滚动页面 |
| 工具条上一项 / 下一项 | 发送 `Shift+Tab` / `Tab`，切换键盘焦点 |

鼠标模式开启时，宿主会临时适配官方页面中要求设备支持悬停的样式，让图片附件等控件响应指针悬停；关闭模式或页面失去交互资格时恢复原样。官方插件文件不变，也不会调用其私有接口。若某个控件仍未显示，可用焦点切换定位后点击；实体鼠标可直接使用。

### 主题与字体

在右上角菜单的 **“App 设置”** 中选择黑色、白色或 Anthropic 暖色主题，并调整字体大小。外观设置应用于原生界面、聊天和终端。

### Codex 更新与重启

选中服务器后，进入 **“App 设置 → Codex CLI”**，可查看配置的可执行文件、磁盘安装版本、实际运行版本和最新稳定版。

| 按钮 | 行为 | 高亮条件 |
| --- | --- | --- |
| 更新 | 执行所配置 Codex 的 `update` 命令，不自动重启已有 App Server | 检测到新版本 |
| 重启 | 重建该 Agent 管理的扩展宿主和 App Server，必要时先确认 | 磁盘版本与运行版本不同 |

更新和重启互斥，页面显示执行状态及输出。CLI 不支持 `update` 时按钮禁用，请按原安装方式在服务器更新，再点击“重启”。版本查询失败会显示未知，不据此判断有更新。

### 手机端 Gateway 管理

使用管理员密钥连接 Gateway 后，App 设置中显示管理入口，提供与 Gateway 管理 TUI 对应的概览、Token 和服务管理能力：

| 管理项目 | 操作 |
| --- | --- |
| 网关概览 | 查看 Gateway 版本、监听信息、服务数量和传输限制 |
| Token | 列出令牌，创建普通或管理员令牌，撤销不再使用的令牌 |
| 已注册服务 | 查看全部服务的在线状态、主机、平台、Agent 版本和连接时间；删除离线记录 |
| 传输限制 | 分别修改上传和下载大小上限，保存后对新请求生效，重启后保留 |
| 更新与重启 | 检查 Gateway 新版本，确认后更新或重启，查看执行结果 |

管理员身份由 Gateway 校验，Token 的名称不决定权限。普通令牌继续只访问自己绑定的服务。新建 Token 的明文仅在创建时显示；撤销后该令牌不能继续访问 Gateway。

Gateway 的远程更新和重启需要安装器配置的 systemd 管理组件。已有 systemd 安装需重新执行本版本安装命令以补齐组件；手动启动的 Gateway 可检查更新，App 会提示不支持远程更新或重启。更新包通过校验后替换程序；启动检查失败时回退到原程序。

### App 与 Agent 检查更新

App 设置中可检查 Android App 和 Agent 的版本，分别显示当前版本、最新正式版和更新入口。App 更新通过浏览器下载官方 Release APK；Agent 检查提供 Release 信息，安装仍使用原目录的安装命令。检查更新不会自动安装或重启程序。

## 更新

项目程序、官方插件和 Codex CLI 分别更新：

| 更新对象 | 更新方式 |
| --- | --- |
| Android App | App 设置中检查更新，从 Releases 下载新 APK 并安装 |
| Gateway | 管理员在 App 中检查并更新，或重新运行安装器 |
| Agent | App 中检查更新，重新运行安装器并指定原安装目录 |
| 官方插件 | 聊天页右上角菜单 → 检查并更新前端 |
| Codex CLI | App 设置 → Codex CLI → 更新，或在服务器按原安装方式更新 |

### 更新 Gateway 和 Agent

Gateway 默认安装：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | sudo bash -s -- gateway --systemd
```

Agent 默认安装：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | bash -s -- agent
```

安装器保留配置和数据。Gateway 使用 `--systemd` 时自动重启；Agent 安装后需自行重启进程以运行新版本，请在任务结束后操作。Agent 的 `runtime/` 与二进制配套更新；手动下载部署时，也要安装相同版本、相同架构的 runtime 压缩包。

已有 `gateway.env` 中设置的传输限制会保留。需要将旧安装的上传限制调整为 10 MiB，可在 App 管理页面修改并保存；App 保存的限制优先于环境变量。

安装器默认下载最新 Release。需要指定项目版本时，添加 `--version vX.Y.Z`；需要使用旧安装目录时，添加 `--install-dir /原安装目录`。

### 更新官方插件

点击 **“检查并更新前端”** 后，Agent 从官方 Marketplace 检查并下载最新正式版的完整插件，App 展示进度、总大小和下载速度。手机加载该插件对应的 WebView 资源。

只提供最新版本，不提供插件版本选择。更新由用户手动触发，日常启动使用已安装版本。新包会先校验并验证扩展生命周期；验证失败时保留当前版本。应用更新可能重启宿主并中断任务，选择“稍后”会保留待应用包。

首次进入需要传输资源并初始化官方页面。后续启动优先使用 App 的资源缓存，但仍有官方模块初始化开销。

## 配置与数据位置

以下为默认安装位置；使用自定义目录时相应变化：

| 内容 | 默认位置 |
| --- | --- |
| Gateway 程序 | `/opt/codex-remote/codex-remote-gateway` |
| Gateway 数据和令牌 | `/opt/codex-remote/data/` |
| App 保存的 Gateway 传输限制 | `/opt/codex-remote/data/gateway-settings.json` |
| Gateway systemd 环境配置 | `/opt/codex-remote/gateway.env` |
| Agent 程序 | `~/.local/codex-remote/codex-remote-agent` |
| Agent 连接配置 | `~/.local/codex-remote/agent.json` |
| Agent 插件和宿主状态 | `~/.local/codex-remote/agent-data/` |
| Agent 自带 Node.js 与宿主代码 | `~/.local/codex-remote/runtime/` |
| 聊天上传附件 | Agent 用户的 `~/.codex-remote-uploads/` |

这里的 `~` 指运行 Agent 的用户主目录。使用 `--config` 指定其他配置文件时，`agent-data/` 跟随配置文件所在目录。Codex 自身的登录、模型和会话配置仍由所配置的 CLI 和官方插件管理。

### Gateway 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `6767` | 监听端口 |
| `CODEX_REMOTE_DATA_DIR` | 二进制旁的 `data/` | 数据和令牌目录 |
| `CODEX_REMOTE_MAX_DOWNLOAD_BYTES` | `10485760` | 预览与下载上限，10 MiB |
| `CODEX_REMOTE_MAX_UPLOAD_BYTES` | `10485760` | 上传上限，10 MiB |
| `LOG_LEVEL` | `info` | 日志级别 |

通过安装器配置的 systemd 服务读取 `gateway.env`；修改后执行 `sudo systemctl restart codex-remote-gateway` 生效。直接运行管理命令不会自动读取此文件，使用自定义数据目录时，需要给管理命令设置同样的 `CODEX_REMOTE_DATA_DIR`。

在 App 中保存上传或下载限制后，Gateway 优先使用 `gateway-settings.json` 中的值。初始配置中的令牌作为管理员导入；已有手动创建的普通令牌不会因为名字包含 `admin` 而获得管理员权限。

### Agent 参数

| 参数 | 用途 |
| --- | --- |
| `--gateway <url>` | Gateway 地址，省略协议时使用 HTTPS |
| `--token <token>` | Gateway 访问令牌 |
| `--name <name>` | App 中显示的服务器名称 |
| `--codex <path>` | Codex 可执行文件，默认 `codex` |
| `--config <path>` | 指定 Agent 配置文件 |
| `--configure` | 重新输入连接配置 |

运行 `~/.local/codex-remote/codex-remote-agent --help` 可查看参数说明。

## 常见问题

**App 中看不到服务器？**

确认 Agent 正在运行，App 与 Agent 连接同一个 Gateway，并使用同一个访问令牌。Gateway 地址填写 HTTPS 地址；Agent 连接失败时会在终端输出原因并自动重试。

**退出手机 App 后，任务还会执行吗？**

后端已经接受的任务会继续，前提是服务器上的 Agent、扩展宿主和 App Server 仍在运行。尚未发出的草稿不算已提交任务。

**官方页面长时间加载或断线后没有恢复？**

先确认 App、Gateway、Agent 及 Agent runtime 已配套更新，Agent 在线，系统 WebView 可用。首次下载、资源传输和官方模块初始化都可能耗时。仍未恢复时，可使用聊天页菜单的“重新加载前端”；手动重载前请保存草稿。

**提示 `already has an active writer`？**

会话可能被其他客户端占用。客户端协调使用官方扩展自身的实现；未参与该协调的 CLI 仍可能冲突。App 不会强制抢占写入者。

**某个模型、推理档位或桌面功能没有出现？**

可用选项由官方插件和后端决定。移动宿主只提供已支持的宿主能力，不保证完整 VS Code 的编辑器功能都可用。

**旧 Agent 安装在别处，重新安装后要求配置？**

默认安装目录变更不会自动迁移旧配置。重新运行安装器时，用 `--install-dir` 指定原安装目录，再启动该目录内的 Agent。

## 从源码构建

开发与打包环境使用 Node.js 24.18.0+：

```bash
npm ci
npm test
npm run typecheck
npm run build
npm run package:binaries
```

打包产物位于 `release/`。Agent 打包过程会下载并校验对应架构的 Node.js 运行时，输出独立的 `codex-remote-agent-runtime-linux-<架构>.tar.gz`；部署时需与 Agent 二进制一起安装。开发环境可用 `CODEX_REMOTE_NODE_PATH` 指定宿主 Node.js。

构建 Android Debug APK，需要 JDK 17 或更高版本，以及 Android SDK（当前编译目标为 API 37）：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

产物为 `android/app/build/outputs/apk/debug/app-debug.apk`。连接设备或模拟器后，可运行 `./gradlew :app:connectedDebugAndroidTest` 执行设备测试。

验证完整官方扩展与真实页面，需要安装 Playwright，也可用 `CODEX_PLAYWRIGHT_MODULE` 指定其入口：

```bash
npm run frontend:smoke -- --extension /path/to/openai.chatgpt-extension
```

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 原生 App |
| `server/` | Gateway |
| `agent/` | Agent、官方扩展宿主与服务器能力 |
| `extension-webview-host/` | 当前 WebView 桥接与主题适配 |
| `scripts/` | 打包、资源同步和验证脚本 |
| `frontend-host/` | 旧链路回归代码，不打包进当前 Android App |
