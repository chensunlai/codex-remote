# TLS 选择：frpc、Gateway 原生与 Nginx

选择证书终止位置时读本文件。Nginx 是可选反代，不是 HTTPS 的必要条件。

| TLS 终止处 | Gateway 监听 | 证书来源/续期 | 适用条件 |
| --- | --- | --- | --- |
| frpc（默认） | HTTP 6767 | Sakura 子域绑定提供证书；自有域名则自管 | 无自有备案域名、无 Nginx，仍保留 LAN HTTP |
| Gateway 原生 | 配置端口变为 HTTPS | 自管有效证书及自动续期 | 内外网都能使用证书匹配的 HTTPS 地址 |
| Nginx | HTTP 6767；另有 Nginx HTTPS 8443 | ACME 自动签发/部署到 Nginx | 保留 LAN HTTP，并需要自主反代/证书控制 |

一次只在选定位置终止 TLS。隧道传输层的加密与应用 HTTPS 是不同层，不能相互替代。

## Gateway 自带 HTTPS

v2.3.1 源码确认支持这两个环境变量，必须成对设置：

```ini
HOST=0.0.0.0
PORT=6767
CODEX_REMOTE_TLS_CERT=/实际用户主目录/.local/codex-remote/certs/fullchain.pem
CODEX_REMOTE_TLS_KEY=/实际用户主目录/.local/codex-remote/certs/privkey.pem
```

`server/src/config.ts` 读取证书和密钥，`server/src/app.ts` 传入 Fastify HTTPS，`server/src/cli.ts` 只启动一个 `HOST:PORT` listener。因此当前实现：

- 启用后 `6767` 是 HTTPS，原来的 `http://LAN-IP:6767` 和 `http://127.0.0.1:6767` 不再可用；不是自动增加一个 443 listener，也不是 HTTP/HTTPS 同端口兼容。
- 所有 Agent、App 的 LAN 入口和探测都要改为 HTTPS。私网 IP/localhost 通常不在证书 SAN 中；使用证书匹配的域名，通过内网 DNS/目标主机解析映射到 Gateway LAN 地址，确保内网流量确实不绕公网。可为 LAN 配一个证书覆盖的独立域名，公网域名继续解析 Sakura。
- 不能只为“两个协议”开两份 Gateway 并共享 data/Token；它们是独立进程和 Agent 连接注册表，既不等价，也可能并发写数据。
- 建立 TCP 或 HTTPS 类型隧道，目标为 Gateway 的 TLS 端口，**关闭 frpc 自动 HTTPS**，公网证书必须匹配公网访问域名。
- 原生 TLS 不包含自动签证。所核验实现启动时读证书文件，未提供热重载/ACME；续期部署后需要按受支持方式使新证书生效，当前方案安排 Gateway 重启并验证 Agent 重连，不将 SIGHUP 当作已支持功能。

原生 HTTPS 是无 Nginx 的可选方案。若用户仍要求内网 HTTP 6767，默认选择 frpc TLS 或 Nginx TLS，不擅自改变该要求。若只使用 Sakura 托管子域，不假设能直接拿到可供 Gateway 自动续期的证书文件；默认由 frpc 消费托管证书更简单。

源码：[config.ts](https://github.com/chensunlai/codex-remote/blob/v2.3.1/server/src/config.ts)、[app.ts](https://github.com/chensunlai/codex-remote/blob/v2.3.1/server/src/app.ts)、[cli.ts](https://github.com/chensunlai/codex-remote/blob/v2.3.1/server/src/cli.ts)。部署时复查对应版本。

## Nginx + 自有域名 + ACME HTTP-01

用于用户明确选择 Nginx，且域名/节点支持 HTTP(S) 隧道的路径：

```text
公网 443 --Sakura HTTPS隧道--> 127.0.0.1:8443 Nginx TLS --> 127.0.0.1:6767 Gateway HTTP
公网 80  --Sakura HTTP隧道---> 127.0.0.1:6768 Nginx webroot
LAN -----------------------------------------------> Gateway-LAN-IP:6767 HTTP
```

Nginx 二进制可复用系统安装，专用配置、pid、缓存、证书和日志放 `~/.local/nginx/`；不修改其他站点。用高位回环端口运行用户服务。下面 `ROOT` 和 `DOMAIN` 需替换成实际绝对目录和完整域名，Nginx 不会自动展开它们；保留 `$http_upgrade` 等 Nginx 变量原样。

`http {}` 内包含：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}
```

站点配置：

```nginx
server {
    listen 127.0.0.1:6768;
    server_name DOMAIN;
    location ^~ /.well-known/acme-challenge/ {
        root ROOT/www/acme;
        default_type text/plain;
        try_files $uri =404;
    }
    location / { return 301 https://DOMAIN$request_uri; }
}
server {
    listen 127.0.0.1:8443 ssl;
    server_name DOMAIN;
    ssl_certificate ROOT/certs/DOMAIN/fullchain.pem;
    ssl_certificate_key ROOT/certs/DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    # 按本次 Gateway 文件上传上限设置，示例与默认 10 MiB 对齐。
    client_max_body_size 10m;
    location / {
        proxy_pass http://127.0.0.1:6767;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

整份主配置还需配置 `events`、MIME include、专用 pid、error log 和用户可写临时目录。`client_max_body_size` 是 HTTP 请求体限制，不能当成 WebSocket 会话消息上限；multipart 边界也计入请求体，要求接近 Gateway 上限的上传时给反代留余量。

用 systemd 用户服务以 `Type=simple` + Nginx `daemon off;` 启动；ExecStart 使用已发现的二进制路径和专用 `-p`/`-c`。配置 `ExecReload`，在成功 `nginx -t` 后才 reload；所有验证/启动/reload 必须指向同一份专用配置。端口冲突时重新选择并同步调整隧道，不抢占其他服务。

## 证书引导与续期

1. 先部署仅 HTTP 的 Nginx 配置，永久 webroot 为 `~/.local/nginx/www/acme`，HTTP 隧道指向 `6768`。不要先加载不存在证书的 TLS server。
2. 自有域名 CNAME 指向实际节点，HTTP/HTTPS 隧道在同一节点。创建随机测试文件，从公网 `http://域名/.well-known/acme-challenge/文件` 读回内容，验证后删除测试文件。
3. acme.sh 安装到 `~/.local/acme.sh`，通过所用版本的 `--home`、`--config-home`（如设置）保持账户/证书状态一致。使用 systemd timer 时选择 `--nocron`，不再留一套 cron 续期。当前选用 CA 要明确指定，不假设默认 CA 是 Let's Encrypt。
4. `acme.sh --issue --server letsencrypt -d 域名 --webroot 实际webroot`。HTTP-01 的 **公网验证端口必须是 80**，本机 `6768` 是由隧道映射后的端口，不是让 CA 改端口。该模式不需要阿里云 AccessKey。
5. 使用 `--install-cert` 将私钥和完整链部署到 Nginx 专用证书目录，设置成功后 reload 的钩子；不让 Nginx 直接读取 acme.sh 内部工作文件。然后启用 TLS server，配置 HTTPS 隧道到 `8443`，关闭 frpc 自动 HTTPS，验证可信 TLS 与 WSS。
6. 设置持久 systemd oneshot `acme-renew.service` + `acme-renew.timer`，例如每天两次运行 `acme.sh --cron --home 实际目录`，配置 `Persistent=true` 和随机延迟。部署钩子只校验/reload 证书使用方，不重启 Gateway/Agent。
7. 验证 timer 状态、下次执行时间、保存的证书部署路径和 reload 钩子；不要靠反复强制生产签发来测试。保留 HTTP 隧道/challenge location 用于将来续期。

该方案不能以 Python 临时 HTTP 进程完成一次签发后就撤掉验证通道，也不能仅配置 Sakura 的 HTTP→HTTPS 跳转而没有确认 challenge 仍可访问。若公网 80 不可用，改用用户授权的 DNS-01 自动验证或其他有维护方案的证书来源，不能声称 HTTP-01 可改为任意公网端口。

Gateway 原生 TLS 自管证书可用 DNS-01：需可自动更新 TXT 的授权方式；手工 TXT 每次续期都要人工参与，不属于无人值守部署。无需 Nginx 的模式不要为 ACME 偷偷加一个未说明的 Nginx。

依据：[acme.sh](https://github.com/acmesh-official/acme.sh)、[安装参数](https://github.com/acmesh-official/acme.sh/wiki/How-to-install)、[Let's Encrypt 验证类型](https://letsencrypt.org/docs/challenge-types/)。
