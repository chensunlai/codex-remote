# Codex Remote

Codex Remote 是一个自托管的 Android Codex 客户端，用于从手机访问远程服务器上的 Codex 会话、终端和文件。

## 架构

```text
Android App：服务器管理 + 官方插件 WebView + 宿主适配
                       ↕ WSS
                Gateway：认证与转发
                       ↕ WSS（Agent 主动连接）
                Agent：持有逻辑通道与进程
                       ↕ stdio
                Codex CLI app-server
```

- **Android App**：直接运行 OpenAI 官方 VS Code 插件的前端资源，由宿主适配层提供远端通信、配置与文件操作；保留服务器切换、终端和文件管理。
- **Gateway**：负责认证、服务发现和双向消息转发，默认端口为 `6767`；聊天使用 app-server 原始 JSON-RPC，不再经自定义聊天 REST 模型转换。
- **Agent**：在目标服务器启动并持有自己的 stdio app-server，保存连接恢复所需的消息和待处理请求；通过同一用户下官方插件的本地 IPC 参与会话协调，不重启已有的 VS Code/CLI 进程。

Agent 与 App 使用同一个访问令牌后，App 才能看到对应的服务。WebSocket 在建连时认证，Token 由 Android 原生网络层持有。

## 功能

官方前端负责会话、聊天、流式展示和审批交互。App 提供官方前端版本选择、服务器 Codex 管理、交互式终端和文件管理。文件预览和下载上限默认是 `10 MiB`。

退出聊天、锁屏、切换服务器或 App 被系统回收时，已经被后端接受的任务继续运行；审批和问题需要用户回来交互。重新连接时恢复当前状态与仍有效的待处理请求，提交结果不明的请求不会自动重复执行。

后台执行依赖 Agent 和其 app-server 持续运行。关闭 Agent、服务器重启或明确重启 Codex 会中断相应进程，不保证未完成任务继续；历史记录仍由 Codex 保存。无人连接且没有活动任务、未决调用或待处理请求的通道会在 60 秒后释放。

Linux 上的会话共享使用官方 owner/follower 协议：电脑端插件持有会话时，手机通过官方客户端继续输入、停止、审批并接收流式状态。需要 Gateway、Agent、App 均升级到 1.0.2 或更新版本，电脑端插件保持运行，且 Agent 与插件使用同一用户及 Codex 目录。App 内切换工作、终端、文件、服务器和设置页面会保留聊天 WebView 与草稿；切换实际服务器、工作目录或前端版本时才重新建立对应页面。

如果仍提示 `already has an active writer`，说明现有写入者未能通过官方协调连接，例如未参与协调的独立 CLI，或官方插件未运行。一次 turn 完成不等于写入权已释放；这种情况下需先释放会话再重试。App 不会强制接管或结束其他客户端的会话。Agent 仅连接已有官方 IPC 路由，不自行创建路由；已读标记暂只在本客户端保存。

## 要求

- Gateway 和 Agent：glibc Linux，支持 `x86_64` 与 `arm64`
- Agent 所在服务器：已单独安装并登录 Codex CLI
- Android：Android 8.0（API 26）或更高版本

Agent 安装包不包含 Codex CLI，也不自动安装、更新或托管另一套 CLI；使用系统中的 `codex` 或配置时指定的可执行文件。

## 快速开始

### 1. 安装 Gateway

在公网服务器执行：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | sudo bash -s -- gateway --systemd
```

上述命令会将 Gateway 安装到 `/opt/codex-remote`，并创建、启用和启动 systemd 服务。打开管理 TUI：

```bash
sudo /opt/codex-remote/codex-remote-gateway
```

首次打开会自动创建 tag 为 `admin` 的访问令牌。后续令牌在 TUI 中输入 tag 后创建。

查看运行状态：

```bash
sudo systemctl status codex-remote-gateway
```

### 2. 安装 Agent

在需要运行 Codex 的服务器上执行：

```bash
mkdir -p ~/codex-remote-agent
cd ~/codex-remote-agent
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | bash -s -- agent
./codex-remote-agent
```

首次启动会进入配置 TUI，依次输入 Gateway 地址、服务名称、访问令牌和 Codex 路径。配置保存在程序旁的 `agent.json`。

重新配置：

```bash
./codex-remote-agent --configure
```

### 3. 使用 Android App

从 [GitHub Releases](https://github.com/chensunlai/codex-remote/releases) 下载 APK：

1. 输入 Gateway 地址和访问令牌。
2. 在“服务”页面选择在线服务。
3. 在聊天页面选择官方前端版本并下载。
4. 点击聊天页的文件夹按钮，在远端目录列表中选择工作目录，再打开或创建 Codex 会话。

工作目录按服务器保存，默认使用该服务器的用户主目录。切换目录会重新加载官方前端，已被后端接受的任务继续运行。

Release APK 仅连接 HTTPS Gateway。

### 官方前端版本

“前端版本”提供两个选项：

- **最新**：从 VS Code Marketplace 获取 OpenAI.chatgpt 最新稳定版本。
- **指定版本**：从官方版本列表选择固定版本，列表会标识预发布及尚未适配的版本。

App 全局共用“最新”和“指定版本”两个缓存槽，每台服务器保存所选模式；两个槽选择相同版本时共用资源。初次使用按需下载，已有缓存可直接切换；菜单中的“检查并下载最新稳定版”和“更换指定版本”用于手动替换相应缓存。前端资源由 App 从官方渠道获取，不随 APK 打包。

下载官方前端时显示实际传输的百分比、已下载量、总大小和速度，并分别显示连接、下载、解压和校验阶段；下载源未提供总大小时明确显示未知。取消或失败会清理临时文件并保留已安装版本。

设置页面提供黑色、白色和 Anthropic 暖色三种主题，选择会保存并同时应用于 Android 原生界面与官方 WebView。黑白主题参考原有界面和 VS Code，暖色使用米白与陶土橙。主题由独立宿主提供标准 VS Code 主题变量，官方插件 JS/CSS 资源保持原样，可继续整包替换。

原生页面采用与编辑器一致的紧凑工具栏、平面列表、小圆角按钮和统一字号；终端背景、文字与状态条也随主题变化。手机操作仍保留至少 48 dp 的点击区域。

官方插件前端还依赖插件宿主接口。支持记录位于 [`frontend-host/adapters.json`](frontend-host/adapters.json)，包含版本、接口绑定和资源校验值。新插件版本可能需要更新宿主适配；尚未适配或校验不通过时会提示并保留已有缓存，不会声称任意前端与远端 CLI 都兼容。

### 服务器 Codex 管理

设置页面显示配置的可执行文件、磁盘安装版本、各通道实际运行版本以及最新稳定版，并提供两个按钮：

| 按钮 | 行为与高亮条件 |
| --- | --- |
| 更新 | 执行配置的 Codex 可执行文件的 `update` 命令。有已知更新时高亮；CLI 未提供该命令时禁用。更新不自动重启已有 app-server。 |
| 重启 | 重建该 Agent 启动的 app-server。磁盘版本与运行版本不同时高亮；存在活动任务、未决调用或待交互请求时先确认，确认后立即重启。 |

更新和重启互斥，页面显示执行状态及输出。版本查询失败显示未知，不据此提示有更新。更新操作不提权、不调用全局包管理器，也不修改 Codex 的登录和配置目录；需要交互或不支持 `update` 的安装方式，请在服务器上自行更新后点击“重启”。

## 配置

### Gateway

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `6767` | 监听端口 |
| `CODEX_REMOTE_DATA_DIR` | 程序默认目录 | 数据目录 |
| `CODEX_REMOTE_MAX_DOWNLOAD_BYTES` | `10485760` | 预览与下载上限 |
| `CODEX_REMOTE_MAX_UPLOAD_BYTES` | `536870912` | 上传上限 |
| `LOG_LEVEL` | `info` | 日志级别 |

### Agent

运行 `./codex-remote-agent --help` 查看连接地址、Token、服务名、Codex 路径和配置文件参数。

例如 `./codex-remote-agent --codex /path/to/codex` 使用指定 CLI。Agent 安装器只放置程序，后台运行方式由服务器管理员配置；要允许手机退出后继续执行，需要保持 Agent 进程运行。

## Gateway 管理

直接运行 `codex-remote-gateway` 打开管理 TUI，可以查看 Gateway 状态、创建或撤销带 tag 的 Token，以及查看或删除离线服务记录。新 Token 的明文只显示一次。

## 更新

重新执行安装命令即可安装最新版本。

Gateway：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | sudo bash -s -- gateway --systemd
```

Agent：

```bash
cd ~/codex-remote-agent
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | bash -s -- agent
```

安装指定版本时，在命令末尾添加 `--version vX.Y.Z`。

## 从源码构建

要求 Node.js 22+。

```bash
npm ci
npm test
npm run typecheck
npm run build
npm run package:binaries
```

构建 Android APK：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

开发宿主适配时，可以导入本机已安装的官方扩展目录或下载好的官方 VSIX：

```bash
node scripts/import-official-frontend.mjs --source /path/to/OpenAI.chatgpt.vsix
# 或使用扩展目录，并输出到一个新目录
node scripts/import-official-frontend.mjs --source /path/to/openai.chatgpt-extension \
  --output .data/official-frontends/dev
```

默认输出为 `.data/official-frontends/<插件版本>`。脚本校验发布者、版本适配记录和 bundle SHA-256，提取 WebView 资源并注入独立宿主入口；不会导入插件自带的 Codex 二进制。已有输出目录不会被覆盖。正式 Android 下载也使用同一份适配记录。
