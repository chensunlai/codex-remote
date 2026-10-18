# Codex Remote

把官方 Codex VS Code 插件带到 Android。**直接复用官方聊天界面和 `extension.js`，不重写会话、模型选择、审批或多端协调逻辑。**

我们负责手机宿主、连接和传输，聊天行为由官方插件与 Codex App Server 处理。前端和扩展来自同一份官方插件包，文件保持原样，可独立更新；无需为每次界面变化重新实现一套聊天客户端。

[下载 Android APK](https://github.com/chensunlai/codex-remote/releases/latest/download/codex-remote-android.apk) · [版本发布](https://github.com/chensunlai/codex-remote/releases) · [使用与维护](docs/usage.md) · [开发与构建](docs/development.md)

## 架构

```text
Android App
  官方 WebView + 原生手机能力
          ↕ HTTPS / WSS
Gateway
  认证、服务发现、资源与消息转发
          ↕ WSS（Agent 主动连接）
Agent
  Node.js 宿主 → 官方 extension.js → Codex App Server
```

| 组件 | 部署位置与职责 |
| --- | --- |
| App | 手机端；显示官方界面，补充附件选择、文件预览、鼠标模式等能力 |
| Gateway | 手机和 Agent 都能访问的服务器；默认端口 `6767`，不解释官方聊天消息 |
| Agent | 已安装并登录 Codex CLI 的服务器；运行官方扩展宿主，连接本机 App Server |

Gateway 和 Agent 可以同机部署。Agent 主动向 Gateway 建立连接，无需单独开放入站端口；一个 Gateway 可以接入多个 Agent。**App 和 Agent 使用同一个 Gateway 访问令牌，才能看到对应服务器。**

宿主实现插件所需的公开 VS Code API，不读取前端私有导出，不自行解析 Codex 会话文件。主题与触屏适配由宿主处理；当前不提供代码编辑、编辑器上下文、代码跳转或登录回调，也不支持 Windows/WSL。官方插件更新仍可能带来新的宿主能力要求。

## 快速使用

准备好以下环境：

- **Gateway / Agent**：glibc Linux，支持 x86_64 和 arm64。
- **Agent**：运行用户已安装并登录 Codex CLI，能够执行 `codex`。安装包自带 Node.js 和扩展宿主，无需另装 Node.js。
- **App**：Android 8.0 及以上，系统 WebView 可用；Release APK 使用 Gateway 的 **HTTPS 地址**。

### 1. Gateway

在 Gateway 服务器执行：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | sudo bash -s -- gateway --systemd
```

默认安装到 **`/opt/codex-remote`**，创建并启动 systemd 服务。安装前需有 `curl`、`flock` 等系统工具。

查看运行状态和首次生成的管理员令牌：

```bash
sudo systemctl status codex-remote-gateway
sudo cat /opt/codex-remote/data/api-token
```

这个令牌就是 App 中的 **Gateway Key**，不是 OpenAI API Key。使用它连接 App 后，可以在手机上创建和管理令牌；新建令牌的明文只显示一次。

也可以在服务器打开交互式管理界面：

```bash
sudo /opt/codex-remote/codex-remote-gateway
```

安装脚本按参数安装，交互界面由程序提供。不使用 systemd 时，省略安装命令中的 `--systemd`，再执行 `sudo /opt/codex-remote/codex-remote-gateway start` 启动服务。

### 2. Agent

在运行 Codex 的服务器上，**以已登录 Codex 的用户**执行：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | bash -s -- agent

~/.local/codex-remote/codex-remote-agent
```

默认安装到 **`~/.local/codex-remote`**。首次启动按提示填写：

| 配置项 | 示例或说明 |
| --- | --- |
| Gateway 地址 | `https://codex.example.com` |
| 服务名称 | App 中显示的名称，如 `开发服务器` |
| 访问令牌 | 与 App 使用的 Gateway Key 一致 |
| Codex 路径 | 默认 `codex`，也可指定可执行文件的绝对路径 |

配置保存在安装目录的 `agent.json`，后续启动直接使用；需要重新配置时加 `--configure`。

安装器不为 Agent 创建后台服务。完成配置并退出前台进程后，可用 tmux 托管：

```bash
tmux new -s codex-remote-agent "$HOME/.local/codex-remote/codex-remote-agent"
```

按 `Ctrl+B`，再按 `D` 离开；`tmux attach -t codex-remote-agent` 返回。不要同时运行同一配置的多个 Agent；tmux 不会随服务器重启自动恢复。

### 3. App

安装 [Android APK](https://github.com/chensunlai/codex-remote/releases/latest/download/codex-remote-android.apk)，然后：

1. 填写 Gateway HTTPS 地址和同一访问令牌。
2. 在“服务器”页面选择在线 Agent。
3. 点击 **“检查并下载官方前端”**，等待插件下载与初始化完成。
4. 用右上角文件夹按钮选择工作目录，再打开或新建会话。

工作目录按服务器保存，默认是 Agent 用户的主目录。官方插件由 Agent 从 Marketplace 下载，手机加载同一版本的 WebView 资源。

> **安装到其他目录或升级旧安装：** 安装命令添加 `--install-dir /原安装目录`，配置和数据会保留；Agent 更新后需自行重启。手动部署 Agent 时，二进制和 `runtime/` 必须配套更新。详见[更新说明](docs/usage.md#更新)。

## 特色功能

### 官方插件可替换，业务逻辑不分叉

直接使用官方界面和扩展实现，会话、模型选项、推理档位、审批及多端协调跟随官方插件与后端。插件只提供最新正式版，通过聊天页菜单手动检查更新；新包校验或生命周期验证失败时保留当前版本，不修改官方 JS、CSS 文件。

### 手机离线，任务继续

切换 App 页面保留同一个聊天 WebView；手机断网或彻底关闭，不主动结束服务器上的扩展和 App Server。已被后端接受的任务继续执行，需要审批时等待用户回来。短暂断线可恢复事件，结果不明的用户操作不会自动重发，避免重复执行。

### 为触屏补齐桌面交互

右上角可开启虚拟鼠标：滑动移动指针、轻点点击、双指滚动，也可切换键盘焦点。开启时临时适配悬停样式，让图片上的删除按钮等桌面控件可操作，关闭后恢复。

聊天附件直接从手机选择；文件链接由原生预览器打开，图片可缩放，所有原生文件预览均提供下载按钮，网页链接可交给浏览器。黑、白、Anthropic 暖色三种主题及字体缩放统一应用于原生界面、聊天和终端。

### 管理操作集中到手机

管理员可在 App 中查看 Gateway 和全部服务状态、创建或撤销 Token、调整传输限制，并更新或重启受支持的 Gateway 安装。普通令牌只访问自己绑定的服务。

App 还能检查自身和 Agent 的新版本，查看 Codex CLI 的安装版本与运行版本。有新版时高亮“更新”；磁盘版本与运行版本不一致时高亮“重启”，两项操作分开执行。

### 缓存、压缩与大消息兜底

官方资源在手机本地缓存；会话链路自动协商标准 WebSocket 压缩，减少重复传输文本的流量。真实会话同步样本中，约 **22.56 MiB → 6.55 MiB，减少 71%**；实际效果随内容变化，图片等已压缩内容收益较小。

大消息才触发透明分块：Agent / Gateway 发送阈值为 `32 MiB`，手机上行为 `8 MiB`，重组后原样交给官方插件。管理员可选择完整消息上限 `64 / 128 / 256 / 512 MiB`，默认 `128 MiB`，重连后生效。文件上传与下载上限独立，均默认 `10 MiB`。

具体操作、配置位置和排错方法见[使用与维护](docs/usage.md)，传输协议见[传输层说明](transport/README.md)。
