# Codex Remote

Codex Remote 是一个自托管的 Android Codex 客户端，用于从手机访问远程服务器上的 Codex 会话、终端和文件。

## 架构

```text
Android：原生界面 + 官方 WebView
                  ↕ WSS
Gateway：认证与公开宿主消息转发
                  ↕ WSS（Agent 主动连接）
Agent：Node.js 宿主 → 官方 extension.js → Codex App Server
```

官方 WebView 与 `extension.js` 来自同一份 OpenAI.chatgpt 插件。Agent 提供公开的 VS Code 宿主 API，文件、子进程和客户端协调由官方扩展直接在服务器执行。手机提供文件选择、剪贴板、通知、预览和浏览器操作；Gateway 不解释官方消息内容。

Agent 与 App 使用同一个访问令牌后，App 才能看到对应服务。访问令牌由 Android 原生网络层持有。Gateway 默认端口为 `6767`。

## 功能

官方扩展负责会话、聊天、流式展示、模型选项与审批。App 保留服务器切换、工作目录选择、终端和文件管理。文件预览和下载上限默认是 `10 MiB`。

切换 App 页面保留同一个聊天 WebView。手机后台、断网或彻底关闭时，Agent 不结束官方扩展，已接受的任务继续运行；需要用户回答时等待恢复连接。重连不自动重发结果不明的操作。手机页面被回收后重新加载官方页面，由官方扩展恢复状态。

后台运行依赖 Agent、扩展宿主与 App Server 持续运行。关闭 Agent、服务器重启、手动重启 Codex 或应用插件更新可能中断任务。官方设置等独立 WebView 面板显示在聊天页上层，底层聊天页面保持运行；原生返回箭头和系统返回可关闭上层面板，随后恢复切换服务器行为。系统键盘、原生弹窗与 JavaScript 确认窗口优先处理返回。

客户端协调使用服务器上官方扩展自身的实现。未参与官方协调的 CLI 仍可能报告 `already has an active writer`；App 不强制抢占其他写入者。

新链路不导入官方页面的私有导出、不强制覆盖功能开关，也不自行解析 Codex 会话文件。代码编辑、编辑器上下文、代码跳转、登录回调和 Windows/WSL 不在移动宿主的支持范围内。用户需提前在服务器登录。

## 要求

- Gateway 和 Agent：glibc Linux，支持 `x86_64` 与 `arm64`
- Agent 所在服务器：已单独安装并登录 Codex CLI
- Android：Android 8.0（API 26）或更高版本

Agent 安装器包含官方扩展宿主和独立的 Node.js 24.18.0 运行时，保存在程序旁的 `runtime/`；无需安装系统 Node.js。Codex CLI 仍需单独安装并登录，Agent 使用系统中的 `codex` 或配置时指定的可执行文件。

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
3. 在聊天页右上角“更多选项”中点击“检查并更新前端”，首次安装由此触发。
4. 点击聊天页的文件夹按钮，在远端目录列表中选择工作目录，再打开或创建 Codex 会话。

工作目录按服务器保存，默认使用该服务器的用户主目录。切换目录会重新加载官方前端，已被后端接受的任务继续运行。

Release APK 仅连接 HTTPS Gateway。

### 上传与预览

- 聊天附件：点击官方输入框的 **＋ → Files and folders（文件和文件夹）**，在 Android 文件选择器中选择手机图片或文件，可多选。上传后出现在输入框中，点击发送才会交给 Codex；取消选择不会提交消息。
- 聊天附件保存在所选服务器的 `~/.codex-remote-uploads/<随机目录>/原文件名`，避免覆盖同名文件。它们会留在服务器供后续会话引用，可在文件页手动清理。
- 聊天中的本地图片链接和文件页使用同一个原生预览器：PNG、JPEG、WebP 等图片支持双指缩放；文本最多预览 256 KiB，其他二进制文件提示下载查看。预览受 Gateway 下载限制约束，App 额外限制图片数据不超过 20 MiB，并缩小过大位图以控制内存。

### 鼠标模式

聊天页右上角的指针按钮开启或关闭鼠标模式：单指滑动移动原生指针，松手保持悬停，轻点在指针处点击，双指滑动滚动页面。关闭后恢复普通触屏操作；开关模式不重新加载前端。实体鼠标直接使用原有输入。

部分官方按钮只在浏览器检测到鼠标设备时响应悬停，模拟事件不会改变这项设备能力。遇到图片附件的删除按钮不显示时，可用模式工具条的上一项、下一项箭头切换键盘焦点，显示官方删除按钮，再移动指针点击。焦点操作只发送标准 Tab / Shift+Tab，不读取或修改官方页面元素。

### 官方前端更新与主题

不提供版本选择。点击“检查并更新前端”后，Agent 从官方 Marketplace 检查并下载最新正式版的完整插件，手机加载对应版本的官方资源。插件可能包含较大的桌面二进制资源，下载进度、总大小和速度以 Agent 的实际传输为准。

更新全部手动触发，日常启动使用已安装版本。新包先校验并验证公开扩展生命周期；失败保留当前版本。有宿主正在运行时，应用更新前确认重启影响；选择“稍后”会保留待应用包，同一个按钮可继续应用。资源版本与宿主版本保持配套。

设置页面提供黑色、白色和 Anthropic 暖色三种主题，同时应用于 Android 原生界面与官方 WebView。主题与字体缩放使用宿主的标准 VS Code CSS 变量及主题元数据，官方 JS/CSS 保持原样。

模型列表、推理档位及功能开关由官方插件和后端决定。原先通过私有导出覆盖 Ultra、模型列表及设置路由的适配不进入新 App。连接与工作目录偏好继续保留，旧适配器内部状态不自行转换；升级前请保存未发送草稿。

所有原生文件预览提供下载，网页链接提供“在浏览器打开”。

### 服务器 Codex 管理

设置页面显示配置的可执行文件、磁盘安装版本、各通道实际运行版本以及最新稳定版，并提供两个按钮：

| 按钮 | 行为与高亮条件 |
| --- | --- |
| 更新 | 执行配置的 Codex 可执行文件的 `update` 命令。有已知更新时高亮；CLI 未提供该命令时禁用。更新不自动重启已有 app-server。 |
| 重启 | 重建该 Agent 的扩展宿主及其 app-server。磁盘版本与运行版本不同时高亮；存在运行进程时先确认，确认后立即重启。 |

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

要求 Node.js 24.18.0+。打包 Agent 时会下载并校验对应架构的官方 Node.js 运行时，输出 Agent 二进制及 `codex-remote-agent-runtime-linux-<架构>.tar.gz`；手动部署时两者都需安装。开发时可通过 `CODEX_REMOTE_NODE_PATH` 指定 Node.js。

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

验证完整官方扩展与真实页面（需安装 Playwright，可用 `CODEX_PLAYWRIGHT_MODULE` 指定其入口）：

```bash
npm run frontend:smoke -- --extension /path/to/openai.chatgpt-extension
```

测试宿主只实现公开 VS Code API，官方资源通过摘要验证保持不变。旧 `frontend-host/` 和导入脚本用于旧链路回归，不打包到新 Android App。
