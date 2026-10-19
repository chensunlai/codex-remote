# Sakura 隧道、域名与默认 HTTPS

创建/修改隧道时读本文件；具体 API 字段与账户资格查本次官方文档/面板，不凭旧 API 拼写写入。

## 节点和客户端

先列出现有隧道、子域、可用节点、账户限制，避免重复申请。按功能允许、长连接可用性、主机和手机的实际路径、延迟/丢包、带宽及剩余流量选节点。地理距离与 ping 只作辅助，不能硬编码某个节点“最优”。

通过面板或当前 API 建立隧道；已有新建授权和可用账户凭据则执行。没有凭据时交付准确待填字段，不声称已创建。Sakura 访问密钥、Gateway Token、DNS API 密钥互不替代。

沿用现有官方启动器（Linux daemon 为 `natfrp-service`）或独立 frpc。新建无桌面的 systemd 部署可用 Sakura 官方 frpc；两者不要同时启动同一隧道。核验下载来源、架构和可用校验信息。

## 默认：无需自有备案域名、不用 Nginx

```text
外网手机 --HTTPS/WSS--> 节点:远程端口 --隧道--> 本机 frpc TLS 终止 --> 127.0.0.1:6767 HTTP
内网手机/其他 Agent -------------------------------------------> Gateway-LAN-IP:6767 HTTP
```

1. 先启动 Gateway。frpc 与 Gateway 同机时，建立 **TCP** 隧道，本地地址 `127.0.0.1`，本地端口 `6767`；远程端口使用允许的空闲端口或系统分配值。
2. “自动 HTTPS”设置为“自动”（`auto_https=auto`），本地服务仍是 HTTP，不改本地端口为 443。
3. 在“子域绑定”申请/复用 `nyat.app` 子域，为该隧道建立绑定。使用面板实际返回的完整域名，不拼造域名。
4. 重启隧道使绑定生效，检查已加载该域名证书的日志；初次签发可能需几分钟。持续失败则查绑定/证书状态，不无限重复创建或申请。
5. 用实际 `https://完整子域.nyat.app:远程端口` 验证可信证书、名称及 WSS。只开启自动 HTTPS 而无正确域名绑定，可能还是自签证书，不能算完成。

该方案无需自行拥有/备案域名或维护 acme.sh。证书和绑定方式见 [子域绑定](https://doc.natfrp.com/bestpractice/domain-bind.html)。不能用证书不匹配的节点域名/IP 替换访问域名。

## 备案与协议

以 [Web 应用指南](https://doc.natfrp.com/app/http.html) 及当前节点约束为准：

| 条件 | 路径 |
| --- | --- |
| 无自有域名 | 默认 TCP + Sakura 子域 + HTTPS |
| 自有未备案域名 | 允许的非内地 HTTP(S) 建站节点，或允许的 TCP HTTPS 路径 |
| 自有备案域名 | 支持建站且接受该域名的内地 HTTP(S) 节点；其他允许方案也可 |

内地 HTTP(S) 类型隧道要求备案；TCP 承载明文 HTTP 有另外的地域限制，不能直接将 `6767` 的 HTTP 暴露到不允许该用途的内地 TCP 节点。默认方案将公网流量转为 HTTPS，但仍须满足节点具体要求。备案、节点过白、实名/账户资格、TLS 证书分别核实；装不装 Nginx 不改变这些条件。

## 自有域名与 DNS

收集完整域名，如 `codex.example.com`。阿里云等 DNS 面板通常填写主机记录 `codex`、类型 `CNAME`、记录值为实际 Sakura 节点域名，不带协议/端口/路径。先查冲突的同名记录，再按确定方案变更。公网 DNS 不指向内网 IP；隧道绑定与 DNS 名称必须完全一致。

同一域名的 HTTP/HTTPS 隧道放在同一节点。验证公共 DNS 解析后才签发证书。用户可手动设置 DNS；HTTP-01 不需要 DNS API 密钥。

**有自有域名但无 Nginx**：可使用 HTTPS 隧道+frpc 自动 HTTPS，目标仍是 HTTP `6767`。按 [frpc 证书规则](https://doc.natfrp.com/frpc/ssl.html) 部署工作目录下的 `<域名>.crt`（完整链）与 `<域名>.key`，配置自动续期和生效机制，例如已授权 DNS API 的 ACME DNS-01。备案本身不会提供证书，没有验证通道时也不能声称自动签发完成。默认 Sakura 子域方案可免去自管证书。

**Gateway 原生 HTTPS**：产品支持直接加载证书，隧道透传到 Gateway TLS 端口即可，无需 Nginx，但该端口不再同时提供 HTTP，内网地址和证书解析也要同步调整；详见 [原生 TLS](tls-and-nginx.md)。

**选 Nginx**：隧道目标指向 Nginx TLS 端口，关闭 frpc 自动 HTTPS，由 Nginx 管理 TLS；见 [TLS、Nginx 与 ACME](tls-and-nginx.md)。

## Sakura 访问认证

Gateway 自带 Token 认证。另加 Sakura 浏览器认证后，手机公网 IP 变化、隧道重启可能需要再次授权；App 的 API 请求不会自动填写认证页面。优先选用符合账户/节点规定且不要求额外浏览器授权的路径，不能绕过平台强制要求。

若确实要求，说明蜂窝 IP 变化的影响，按 [访问认证文档](https://doc.natfrp.com/bestpractice/frpc-auth.html) 授权并验证切网，必要时换允许的节点。认证页的 200 不是 Gateway 成功；不能为此关闭 Gateway Token。

## 独立 frpc 用户服务

[官方手册](https://doc.natfrp.com/frpc/manual.html) 支持通过环境拉取配置。`~/.local/sakura-frp/frpc.env`（600）：

```ini
NATFRP_TOKEN=本次账户访问密钥
NATFRP_TARGET=本次隧道ID或逗号分隔列表
```

```ini
[Unit]
Description=Sakura FRP for Codex Remote
After=network.target codex-remote-gateway.service

[Service]
Type=simple
WorkingDirectory=%h/.local/sakura-frp
EnvironmentFile=%h/.local/sakura-frp/frpc.env
ExecStart=%h/.local/sakura-frp/frpc --disable_log_color
Restart=always
RestartSec=5
UMask=0077

[Install]
WantedBy=default.target
```

执行前检查所下载 frpc 的 `--help`。不在 ExecStart 或日志中展开密钥。沿用启动器时按其文档设置持久工作目录和控制 socket，不能将 frpc 环境文件冒充启动器配置。

App 多入口直接使用 Gateway LAN 端口，默认无需 Sakura `local_access` 或修改内网 DNS。要求内网也用 HTTPS 时才另选有效域名+内网 DNS+本地 TLS 入口，并说明增加的配置。
