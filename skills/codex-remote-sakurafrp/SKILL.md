---
name: codex-remote-sakurafrp
description: 使用 Sakura FRP 部署或维护 chensunlai/codex-remote Gateway 与 Agent，实现服务器内网直连、手机内网优先和公网 HTTPS 访问。适用于隧道、域名、可选 Nginx/ACME、systemd 托管及 App 多入口配置。
---

# Codex Remote + Sakura FRP

一个 Gateway 连接多台服务器的 Agent；可信内网直接访问 Gateway，外网经 Sakura FRP。默认 **无需自有备案域名 + 不装 Nginx + 安装到运行用户的 `~/.local/` + systemd 用户服务**，公网仍使用有效证书的 HTTPS。

## 部署前先确定三个选择

新部署先收集以下选择，一次问完并说明推荐方案。当前会话已有答案则沿用，不重复确认；只总结方案或编写文档时不询问实际部署参数。等待回答期间可检查主机、端口和版本，不先执行依赖这些选择的部署。

1. **域名**：是否使用已有备案域名？选项：无需自有备案域名（推荐默认，Sakura 子域名）、已有备案域名、已有未备案域名。有域名时收集具体子域名及 DNS 控制方式；备案通过、Sakura 接受域名、节点过白分别核实。
2. **Nginx**：是否需要 Nginx？默认不用，交由 frpc 自动 HTTPS 提供 TLS；需要独立反代配置、自主管理证书或复用现有 Nginx 时选用。明确不装 Nginx 也能走 HTTPS，Gateway 本身也支持证书配置；若选择原生 TLS，须同时说明 LAN 端口协议和证书解析的变化。
3. **部署位置**：哪台服务器、哪个用户、哪个目录？推荐其他 Agent 服务器及手机所在网络都能直接内网访问、地址稳定且长期在线的服务器；默认该用户的 `~/.local/`。这样 Agent 流量和手机在内网时的流量不经过穿透节点，减少穿透流量与延迟。不同网段之间必须实测，不能仅看是否为私网 IP。

提问示例：

> 推荐用 Sakura 提供的子域名和 HTTPS，不装 Nginx，程序放在运行用户的 `~/.local/` 并由 systemd 托管。部署服务器最好能被其他服务器直接内网访问。请确定：① 是否使用已有备案域名；② 是否需要 Nginx；③ 部署在哪台服务器、哪个用户和目录。

之后只补齐执行必需的信息：Sakura 凭据或已登录面板、已有隧道、目标机连接方式；安装 Agent 时再收集服务名和 Codex 路径。不沿用其他部署的密钥、IP、隧道 ID、域名或代理地址。用户已授权部署且方案确定后继续完成，不额外设置机械审批步骤。

## 核对当前版本

按 [版本与来源](references/versions-and-sources.md) 检索最新正式 Release，读取相同标签的安装器、使用说明和多入口实现，记录检查日期、版本、架构；安装固定到本次已核验的标签。本文基线不是永久最新版。

App、Gateway、Agent、Agent runtime 要兼容。官方插件与 Codex CLI 是另外两个组件，安装 Agent 不等于安装 Codex。只要求研究或生成 skill 时，不升级或重启现有服务。

## 选择网络方案

先读 [Sakura 隧道与域名](references/sakura.md)。备案和 Nginx 是独立选择：

| 场景 | 推荐入口及 TLS 位置 | 说明 |
| --- | --- | --- |
| 无自有备案域名、无 Nginx（默认） | TCP 隧道 + `nyat.app` 子域绑定 + frpc 自动 HTTPS → HTTP Gateway | 公网 URL 通常带端口，无需自行申请证书 |
| 自有备案域名、无 Nginx | 支持建站的节点、HTTPS 隧道 + frpc 自动 HTTPS → HTTP Gateway | 自有域名证书仍需部署和续期 |
| 自有未备案域名 | 允许该用途的非内地建站节点，或符合当前规则的 TCP HTTPS 路径 | 不当作内地 HTTP(S) 隧道可用的备案域名 |
| Gateway 原生 HTTPS、无 Nginx | 隧道 TLS 透传 → Gateway HTTPS | 需要证书及续期，LAN 也改成证书有效的 HTTPS；不再同时提供 HTTP 6767 |
| 使用 Nginx | 隧道 → Nginx TLS 端口 → HTTP Gateway | Nginx 管证书、WebSocket 和反代，不重复开 frpc TLS 终止 |

三种 TLS 终止方式和原生 HTTPS 限制见 [TLS 与 Nginx](references/tls-and-nginx.md)。

无备案不等于必须明文 HTTP；有备案不等于必须 Nginx。TCP 上承载 HTTPS 与 Sakura 的 HTTPS 类型隧道也不同。节点资格、访问认证、端口约束以本次面板及官方文档为准。

## 实施顺序

1. **盘点现场**：检查现有安装、用户、unit、端口、LAN 路由、DNS、隧道和数据。更新前备份配置、二进制及配套 runtime，保留 Token、服务 ID 和会话。
2. **先通内网**：按 [Linux 安装、托管与 App](references/linux-and-app.md) 安装 Gateway。保留可信 LAN 对 `6767` 的访问，默认 HTTP、`HOST=0.0.0.0`；选择原生 TLS 时按参考同步调整为 HTTPS。不要因反代而改为只监听回环。测试本机及其他服务器到 Gateway。
3. **Agent**：只在需要执行 Codex 的服务器安装，Gateway 主机不一定需要 Agent。同机 Agent 用 `http://127.0.0.1:6767`，其他可达服务器用 LAN 地址，不能直连时才用公网。原生 TLS 模式使用证书匹配且内网可达的 HTTPS 域名。每台保持独立服务 ID。
4. **公网入口**：默认 Sakura 子域+可信 HTTPS；Nginx 路径额外读 [TLS、Nginx 与 ACME](references/tls-and-nginx.md)。不要给默认路径无故安装 Nginx、ACME 或临时 Web 服务。
5. **持久托管**：Gateway、可选 Agent、frpc 或启动器由 systemd 托管，退出自动恢复，用户服务检查 linger。代理仅在实测需要时写入环境文件，并给 LAN/回环配置直连例外。
6. **一个 Gateway 多个入口**：公网 HTTPS 为稳定主地址，LAN 为优先入口；可信 LAN HTTP 需要 App 实验开关。不要部署两套独立 Gateway 冒充多个入口。
7. **验收和交付**：按下述标准检查；无法操作手机或重启主机的项目明确标注未验证。

## 验收与交付

- 本机 `/healthz` 正常，另一台 LAN 主机可访问 `6767`。
- LAN/公网带 Token 的 `/api/v1/meta` 返回正确 JSON、同一授权范围且服务列表一致。TCP 连通、健康页或 HTTP 200 单独不足以证明 App 可用。
- 公网证书可信且名称匹配，HTTPS/WSS 实际通过，没有 Sakura 认证页挡住 API；原生 TLS 路径还需验证 LAN 的域名/证书与实际直连路由。
- Agent 在线，可读取目录、加载官方插件、打开至少一条已有对话；大历史会话检查组件配套，不能只测空白首页。
- 手机可信 Wi-Fi 走 LAN，蜂窝走公网，回 Wi-Fi 后重选 LAN；未触发网络变化时可点延迟指示手动重选。检查草稿恢复，文件传输失败需手动重试。
- 检查 unit 启用、进程状态、实际环境及 linger；受控重启服务后重新连接。只有实际重启主机才称“已验证重启恢复”。
- 自管证书方案另查 challenge 通道、续期 timer 和证书部署钩子。

交付：版本、主机/用户、LAN URL、公网 URL、App 两个实验开关及入口顺序、Agent 连接地址、配置/Token 文件位置、systemd 命令、证书责任方、已验证与未完成项。密钥不写入 skill 或公开报告。说明用户服务与官方 root 维护组件的能力差异，见安装参考。

## 排错

按“入口/TLS → 认证元数据 → Agent → 官方资源 → App Server → 历史对话”定位。终端正常而 systemd 卡住时比较实际 PATH、代理和可达性，有证据才加代理。登录失效、大消息限制、网络超时分别诊断，不能把旧案例当作所有转圈问题的原因。

不要为排错清空会话、更换用户模型供应商、注销登录或关闭 TLS 校验。重启 Agent 会影响扩展宿主与运行任务，遵守用户已授权的范围与时机。
