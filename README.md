# Codex Remote

用 Android App 远程操作服务器上的 Codex。项目由三个端组成：

```text
服务器 Agent -- HTTPS/WSS 出站连接 --> 公网 Gateway:6767 <-- HTTPS/WSS -- Android App
     |                                      |
 Codex daemon、Shell、文件                  Token 隔离、转发、事件缓存
```

- **服务器 Agent**：运行在需要操作的服务器上，只主动连接 Gateway，不要求服务器有入站端口或公网 IP。
- **Gateway**：公网汇合与转发节点，进程默认监听 `0.0.0.0:6767`，只有命令行管理，没有网页后台。
- **Android App**：使用 Gateway 地址和同一用户 Token 登录，显示服务、Codex 会话、聊天、终端和目录。

Android App 关闭不会发出 turn interrupt，也不会关闭服务器 Agent 或 Codex daemon。Agent 如何常驻由使用者自行安排。

## 要求

- Gateway 和 Agent release：glibc Linux x64 或 arm64，不需要安装 Node.js、Bun 或 npm
- Agent 所在服务器：单独安装并登录 Codex CLI；Codex 不包含在本项目二进制中
- 完整交互终端：Linux 建议安装 util-linux 提供的 `script` 命令；缺失时 Agent 会退化为普通 Shell 管道
- Android 8.0（API 26）+
- 从源码构建：Node.js 22+

Codex 的连接握手、thread、turn 和事件模型遵循 [Codex App Server 文档](https://developers.openai.com/codex/app-server)。

## 一键安装

安装器根据 `x86_64` / `aarch64` 自动选择 GitHub Release 二进制，并使用同一 release 的 `SHA256SUMS` 校验后再原子替换程序。

服务器 Agent（不需要 root，也不配置后台运行；安装到当前目录）：

```bash
mkdir -p ~/codex-remote-agent
cd ~/codex-remote-agent
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | bash -s -- agent

./codex-remote-agent
```

公网 Gateway（安装为 systemd service）：

```bash
wget -qO- https://raw.githubusercontent.com/chensunlai/codex-remote/main/install.sh \
  | sudo bash -s -- gateway --systemd
```

固定版本时追加 `--version vX.Y.Z`。安装器内部会使用可用的 `curl` 或 `wget` 下载 release。它只下载 Codex Remote，不会下载或打包 Codex CLI。Gateway 安装器只管理自身 systemd service，不配置 Nginx；Agent 安装器只放置二进制，后台方式由使用者决定。

## 1. 使用 Gateway

systemd 安装完成后：

```bash
sudo systemctl status codex-remote-gateway
sudo cat /opt/codex-remote/data/api-token
```

首次 Token 只用于初始化。继续创建和管理用户 Token：

```bash
sudo env CODEX_REMOTE_DATA_DIR=/opt/codex-remote/data \
  /opt/codex-remote/codex-remote-gateway token create phone-user
sudo env CODEX_REMOTE_DATA_DIR=/opt/codex-remote/data \
  /opt/codex-remote/codex-remote-gateway token list
sudo env CODEX_REMOTE_DATA_DIR=/opt/codex-remote/data \
  /opt/codex-remote/codex-remote-gateway token revoke TOKEN_ID
sudo env CODEX_REMOTE_DATA_DIR=/opt/codex-remote/data \
  /opt/codex-remote/codex-remote-gateway service list
```

Token 明文只在创建时显示，Gateway 持久化 SHA-256 摘要。撤销记录会持久化，环境变量或 bootstrap 文件不会在下次启动时把已撤销 Token 重新启用。

Gateway 自有文件全部位于 `/opt/codex-remote`：程序和 `gateway.env` 在根目录，持久化数据位于 `data/`，systemd unit 源文件也在根目录。`/etc/systemd/system` 中只有 systemd 所需的符号链接。默认监听 `0.0.0.0:6767`，下载/预览上限为 `10MiB`。项目同时保留 [systemd 示例](deploy/codex-remote-gateway.service.example)；Gateway 没有网页管理端。

## 2. 使用服务器 Agent

```bash
cd ~/codex-remote-agent
./codex-remote-agent
```

程序依次询问 Gateway 地址、服务名和 Token。也可以显式传参：

```bash
./codex-remote-agent \
  --gateway https://gateway.example.com \
  --name build-server \
  --token TOKEN
```

默认调用 `PATH` 中的 `codex`，也可以传入 `--codex /path/to/codex`。配置以 `agent.json` 保存在 Agent 程序所在目录，文件权限为 `0600`；因此移动整个目录即可同时移动程序和配置。再次运行会复用服务 ID；`--configure` 更新地址、名称或 Token 时也不会产生重复服务记录。

Agent 优先执行 Codex daemon bootstrap 并通过 app-server proxy 连接；若当前 Codex 不是 standalone managed install，则自动回退到 stdio app-server。两种模式都允许 App 关闭后继续运行任务，daemon 模式还支持 Agent 重启后恢复。Agent 与 Gateway 断线后会指数退避重连；它不开放监听端口。

## 3. 构建和使用 Android App

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次打开 App：

1. 输入 Gateway 地址。
2. 输入与该服务器 Agent 相同的用户 Token。
3. 在“服务”中选择在线服务。
4. 在“会话”中创建或继续 Codex thread；在“终端”和“文件”中进行服务器操作。

会话列表先读取轻量摘要。聊天正文按 `(Gateway, Token, Service, Thread)` 缓存在 Android SQLite 中，只有远端 `updatedAt` 变化、会话仍在运行或用户继续对话时才读取完整历史；缓存采用 LRU，最多 200 个 thread / 50MiB。事件游标按服务持久化，重连只重放增量事件。

## 地址与加密

- Gateway 进程默认提供 HTTP/WS，端口 `6767`。
- 地址没有显式端口时遵循 URL 标准：`http://` 使用 80，`https://` 使用 443，不自动追加 `6767`。
- 直接访问默认 Gateway 使用 `http://HOST:6767`。
- HTTPS 反向代理场景在 App 和 Agent 中填写 `https://gateway.example.com`；两端会自动使用 WSS，系统证书链和主机名校验保持开启。
- release APK 禁止明文 HTTP；debug APK 允许 HTTP，便于本地调试。
- Gateway 也支持可选的 `CODEX_REMOTE_TLS_KEY` / `CODEX_REMOTE_TLS_CERT` 原生 TLS，但不要求使用。

Bearer Token 只放在 `Authorization` 请求头。Gateway 日志会脱敏该请求头，API 响应带 `Cache-Control: no-store`。Android Token 使用系统 Keystore 加密保存；终端 WebView 只加载 APK 内的 xterm.js，CSP 禁止页面发起网络请求，Token 不进入 JavaScript。

## 文件限制

下载和预览在 Gateway 侧共用同一上限，默认 `10MiB`：

```bash
export CODEX_REMOTE_MAX_DOWNLOAD_BYTES=10485760
```

Gateway 在请求流之前读取 Agent 文件元数据并返回 `413 DOWNLOAD_TOO_LARGE`，传输过程中还会再次计数，防止检查后文件变大。Android 同时使用 `/api/v1/meta` 下发的限制提前禁用入口。文本预览最多渲染前 256KiB，避免大文本阻塞手机 UI；文件总大小超过 Gateway 阈值时整个预览请求仍会被拒绝。

上传上限由 `CODEX_REMOTE_MAX_UPLOAD_BYTES` 配置，默认 `512MiB`。

## 从源码构建和发布

```bash
npm ci
npm test
npm run typecheck
npm run build

# 构建当前 Linux 架构的 Agent + Gateway standalone 二进制
npm run package:binaries

# GitHub Release 使用的两个目标
npm run package:binaries -- --targets linux-x64,linux-arm64
```

产物位于 `release/`，同时生成 `SHA256SUMS`。构建器固定使用 Bun 1.3.14，将 Bun runtime 和 npm 依赖嵌入各自的 ELF 文件，不包含 Codex CLI；每个文件超过 `100MB` 时构建失败。不要对生成文件再次执行 `strip`，这会破坏 Bun standalone 的嵌入入口。

本地构建后可以直接测试安装器：

```bash
./install.sh agent --from release --install-dir "$PWD/release/test-agent"
./install.sh gateway --from release --install-dir "$PWD/release/test-gateway"
```

推送版本标签会触发 `.github/workflows/release.yml`，运行测试后自动创建包含 x64/arm64 二进制和摘要的 GitHub Release：

```bash
VERSION="v$(node -p "require('./package.json').version")"
git tag "$VERSION"
git push origin main "$VERSION"
```

Android 开发构建：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

xterm.js 及 FitAddon 作为约 0.5MiB 的本地静态资源随 APK 发布。升级依赖后运行 `npm run sync:terminal` 同步资源和许可证。
