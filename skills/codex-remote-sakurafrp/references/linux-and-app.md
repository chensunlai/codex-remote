# Linux 部署、systemd 与 App 多入口

共同步骤；默认 Gateway HTTP + frpc TLS，原生 TLS 的差异见 [TLS 选择](tls-and-nginx.md)。

## 布局与安装

使用目标运行用户的真实 HOME，不假定 `/home/user`，不覆盖 HOME/CODEX_HOME 改变配置来源。

```text
~/.local/codex-remote/         Gateway、gateway.env、data/、systemd/
~/.local/codex-remote-agent/   可选 Agent、agent.json、agent.env、runtime/、agent-data/、systemd/
~/.local/sakura-frp/          frpc 或启动器、私密配置、证书/状态、systemd/
~/.local/nginx/               仅选 Nginx 时创建
~/.local/acme.sh/             仅自行管理 ACME 时创建
~/.config/systemd/user/       systemd 用户 unit 注册位置，可链接上述 systemd/ 文件
```

沿用现有安装目录，避免默认目录变化造成第二份空配置。检查当前 Release 架构/libc 支持，核验基线为 glibc Linux x64/arm64。

读取最新正式 Release，下载相同标签的安装器，检查内容和 `--help` 后执行。示例标签必须换成本次已核验版本：

```bash
release_tag=v2.3.1  # 示例，执行时重新核验
install_stage=$(mktemp -d)
curl -fL "https://raw.githubusercontent.com/chensunlai/codex-remote/$release_tag/install.sh" \
  -o "$install_stage/install.sh"
bash "$install_stage/install.sh" gateway --version "$release_tag" \
  --install-dir "$HOME/.local/codex-remote" \
  --data-dir "$HOME/.local/codex-remote/data"
# 仅当该主机也需要执行 Codex：
bash "$install_stage/install.sh" agent --version "$release_tag" \
  --install-dir "$HOME/.local/codex-remote-agent"
```

安装器校验 Release `SHA256SUMS`。手工下载也需校验，Agent 二进制和 runtime 包必须同一标签/架构；不能只更新二进制。Codex CLI 单独发现/安装，记录真实路径和版本。

**用户服务的取舍**：默认不加安装器 `--systemd`，自行创建用户 unit。当前官方 `--systemd` 是 root 系统服务，另外配置 `/var/lib/codex-remote-control` 维护组件，要求安装目录及父目录满足 root 所有权/权限约束，不能与用户可写的 `~/.local` 混用。用户服务支持开机启动、崩溃恢复和手动升级，但不能据此宣称支持 App 远程更新/重启 Gateway。用户要求该功能时，核实新版是否支持用户服务；仍不支持则解释模式/目录取舍，不擅自扩大成 root 部署。

## Gateway

`gateway.env`（600）示例；数据目录用实际绝对路径，EnvironmentFile 内容不展开 `$HOME`：

```ini
HOST=0.0.0.0
PORT=6767
CODEX_REMOTE_DATA_DIR=/实际用户主目录/.local/codex-remote/data
LOG_LEVEL=info
```

首次启动可在 `data/api-token` 自动生成管理员 Token；当前程序也可能在首次日志打印它，读取/交付日志时要脱敏。也可预先以 600 权限安全生成 Token 文件，避免首次日志包含明文。重复部署保留原 Token。普通令牌使用当前管理功能创建，手机和 Agent 使用相同授权范围。不默认照搬某个历史实例的文件上传限制。

`systemd/codex-remote-gateway.service`：

```ini
[Unit]
Description=Codex Remote Gateway
After=network.target

[Service]
Type=simple
WorkingDirectory=%h/.local/codex-remote
EnvironmentFile=%h/.local/codex-remote/gateway.env
ExecStart=%h/.local/codex-remote/codex-remote-gateway start
Restart=always
RestartSec=5
UMask=0077
NoNewPrivileges=true

[Install]
WantedBy=default.target
```

保持可信 LAN 可达 `6767`。如有防火墙，放行指定 LAN 范围；公网通过隧道，不另外在路由器暴露明文 Gateway。用户要求绑定特定 LAN IP 时，同步调整本机 Agent/frpc 目标，不能继续假设回环可达。

## Agent

Gateway 中继，Agent 才调用各服务器上的 Codex。不把多台配置复制为同一个 serviceId。

| Agent 所在位置 | 默认方案 Gateway URL |
| --- | --- |
| Gateway 同机 | `http://127.0.0.1:6767` |
| 其他内网可达主机 | `http://Gateway的LAN地址:6767` |
| 确实无法内网直连 | 已验证公网 `https://域名[:端口]` |

交互使用 `codex-remote-agent --config 路径 --configure`；自动配置按当前 schema 写 600 权限的 JSON，新建 serviceId 用 UUID，已有部署保留：

```json
{
  "version": 1,
  "gatewayUrl": "http://127.0.0.1:6767",
  "token": "从本次Gateway安全读取",
  "name": "用户选定的服务器名称",
  "serviceId": "新生成或保留的UUID",
  "codexExecutable": "/实际路径/codex"
}
```

不把 Token 输出到命令行参数。配置完整后再启用服务，避免非交互服务陷入向导。

```ini
[Unit]
Description=Codex Remote Agent
After=network.target

[Service]
Type=simple
WorkingDirectory=%h/.local/codex-remote-agent
EnvironmentFile=%h/.local/codex-remote-agent/agent.env
ExecStart=%h/.local/codex-remote-agent/codex-remote-agent --config %h/.local/codex-remote-agent/agent.json
Restart=always
RestartSec=5
TimeoutStopSec=30
UMask=0077

[Install]
WantedBy=default.target
```

创建 `agent.env`（600），固定实际 PATH，包含 Codex 及必要 Node 路径。systemd 不读取交互 shell 的 conda/nvm 初始化；Codex 绝对路径未必解决其 `#!/usr/bin/env node` 依赖。

仅在实测需要时添加已验证 `HTTP_PROXY`、`HTTPS_PROXY`、必要时 `ALL_PROXY`，用 `NO_PROXY` 给回环与实际 LAN Gateway 直连例外；按库需要补小写形式。检查代理在用户退出/机器重启后是否仍可用。不硬编码其他部署的代理地址，不无差别给 Gateway/frpc 也加代理，不全局覆盖用户环境。

## 注册与开机恢复

将选中的 unit 链接到 `~/.config/systemd/user/`，先检查已有文件，更新前备份。

```bash
systemctl --user daemon-reload
systemctl --user enable --now codex-remote-gateway.service sakura-frp.service
# 仅当已安装 Agent：
systemctl --user enable --now codex-remote-agent.service
loginctl show-user "$(id -un)" -p Linger
```

无人登录也要启动则为运行用户 `loginctl enable-linger 用户名`；是否需要管理员权限由系统决定。权限不足时报告具体未完成步骤，不声称已实现开机自启。不要用 nohup/tmux 代替用户要求的 systemd。

unit 修改后 daemon-reload，环境修改后重启相应服务。验收实际进程环境时只看相关键及脱敏值，不转储整份 `/proc/PID/environ`。

## App：一个 Gateway 的多个入口

基线 v2.3.1，执行时重新核对对应版本 UI：

1. 安装与服务端兼容的正式 APK。
2. 主地址为稳定公网 HTTPS，如 `https://完整子域.nyat.app:远程端口`，使用该 Gateway 的 Token。
3. 在 **App 设置 → 网关连接 → 实验性功能** 打开 **多个网关入口**。
4. 优先入口填 `http://Gateway的LAN地址:6767`，每行一个，按优先级排列；同时启用 **允许 HTTP 网关**。HTTP 明文传输 Token/内容，仅用于可信 LAN。
5. 主地址自动最后兜底，不必在优先列表重复填；手机上的 `127.0.0.1` 不是服务器。所有入口必须实际回到同一 Gateway 部署/数据/授权范围，不是不同 Gateway 的负载均衡。

按顺序选择首个可用入口，不是测速选最快。初次连接、断线重连、Android 网络变化、手动重连时重选；稳定连接只检查当前入口，LAN 恢复不会触发周期抢切。可点聊天页延迟指示重新选择。业务请求不跨入口盲目重发，传输和终端不保证无缝迁移。原生 TLS 方案将 LAN 入口改成证书有效的 HTTPS URL，无须启用 HTTP 开关。

## 验证与升级

先本机、另一台 LAN 主机、公网，再手机 Wi-Fi/蜂窝切换：

```bash
curl --noproxy '*' --fail --max-time 5 http://127.0.0.1:6767/healthz
```

健康路径是 `/healthz`，不是 `/health`。检查带 Token 的 `/api/v1/meta`（App 探测接口）和 `/api/v1/services`；程序从私密文件读 Token，避免进入 shell 参数/日志。同一 Token 被接受不构成严格实例身份证明，还需确认回到同一部署、授权范围和服务列表一致。

验证 WSS 和历史对话。协议路径从当前代码查，不长期只测试旧 `/api/v3/.../extension`。大历史失败先查组件配套/传输限制，再查手机资源和网络；不删除对话让测试变绿。

升级保留配置、data、serviceId、Token，二进制/runtime 配套更新后按授权时机重启。首次插件下载可能数百 MB，尽量在 LAN 上完成。交付 `systemctl --user status/restart 服务名` 与 `journalctl --user -u 服务名 -f`。
