# Codex Remote

Codex Remote 是一个自托管的 Android Codex 客户端，用于从手机访问远程服务器上的 Codex 会话、终端和文件。

## 架构

```text
运行 Codex 的服务器                  公网 Gateway                 Android App
codex-remote-agent  ─────────────▶  codex-remote-gateway  ◀─────────────
        │
     Codex CLI
```

- **Agent**：运行在需要使用 Codex 的服务器上，主动连接 Gateway。
- **Gateway**：负责认证、服务发现、请求转发和事件缓存，默认端口为 `6767`。
- **Android App**：提供会话、聊天、终端和文件浏览界面。

Agent 与 App 使用同一个访问令牌后，App 才能看到对应的服务。

## 功能

支持 Codex 会话与聊天、思考和执行过程展示、审批、模型设置、交互式终端、文件管理以及聊天记录增量缓存。文件预览和下载上限默认是 `10 MiB`。

## 要求

- Gateway 和 Agent：glibc Linux，支持 `x86_64` 与 `arm64`
- Agent 所在服务器：已单独安装并登录 Codex CLI
- Android：Android 8.0（API 26）或更高版本

Agent 安装包不包含 Codex CLI。

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
3. 在“会话”页面打开或创建 Codex 会话。

Release APK 仅连接 HTTPS Gateway。

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
