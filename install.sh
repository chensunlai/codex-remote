#!/usr/bin/env bash
set -euo pipefail

PROGRAM="codex-remote installer"
main() {
COMPONENT="${1:-}"
if [[ "$COMPONENT" == "agent" || "$COMPONENT" == "gateway" ]]; then
  shift
elif [[ "$COMPONENT" == "--help" || "$COMPONENT" == "-h" ]]; then
  usage
  exit 0
else
  usage >&2
  exit 2
fi

REPOSITORY="${CODEX_REMOTE_REPO:-chensunlai/codex-remote}"
VERSION="${CODEX_REMOTE_VERSION:-latest}"
INSTALL_DIRECTORY="${CODEX_REMOTE_INSTALL_DIR:-}"
DATA_DIRECTORY="${CODEX_REMOTE_DATA_DIR:-}"
LOCAL_SOURCE=""
INSTALL_SYSTEMD=false

while (($# > 0)); do
  case "$1" in
    --repo)
      require_value "$@"
      REPOSITORY="$2"
      shift 2
      ;;
    --version)
      require_value "$@"
      VERSION="$2"
      shift 2
      ;;
    --install-dir)
      require_value "$@"
      INSTALL_DIRECTORY="$2"
      shift 2
      ;;
    --data-dir)
      require_value "$@"
      DATA_DIRECTORY="$2"
      shift 2
      ;;
    --from)
      require_value "$@"
      LOCAL_SOURCE="$2"
      shift 2
      ;;
    --systemd)
      INSTALL_SYSTEMD=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "未知参数: $1"
      ;;
  esac
done

case "$(uname -s)" in
  Linux) SYSTEM="linux" ;;
  Darwin)
    SYSTEM="darwin"
    [[ "$COMPONENT" == "agent" ]] || fail "macOS 仅提供 Agent；Gateway 请安装在 Linux 上"
    ;;
  *) fail "当前安装器支持 Linux 和 macOS Agent" ;;
esac
if [[ "$SYSTEM" == "linux" ]] && command -v ldd >/dev/null 2>&1 && ldd --version 2>&1 | grep -qi musl; then
  fail "当前 release 面向 glibc Linux，检测到 musl/Alpine"
fi

case "$(uname -m)" in
  x86_64|amd64) PLATFORM="$SYSTEM-x64" ;;
  aarch64|arm64) PLATFORM="$SYSTEM-arm64" ;;
  *) fail "不支持的 CPU 架构: $(uname -m)" ;;
esac

if [[ "$INSTALL_SYSTEMD" == true && "$COMPONENT" != "gateway" ]]; then
  fail "--systemd 只适用于 Gateway；Agent 的后台方式由使用者管理"
fi
if [[ "$INSTALL_SYSTEMD" == true && "$EUID" -ne 0 ]]; then
  fail "安装 systemd 服务需要 root，请使用 sudo"
fi

if [[ -z "$INSTALL_DIRECTORY" ]]; then
  if [[ "$COMPONENT" == "gateway" ]]; then
    INSTALL_DIRECTORY="/opt/codex-remote"
  else
    INSTALL_DIRECTORY="$HOME/.local/codex-remote"
  fi
fi
if [[ -z "$DATA_DIRECTORY" && "$COMPONENT" == "gateway" ]]; then
  DATA_DIRECTORY="$INSTALL_DIRECTORY/data"
fi
if [[ "$INSTALL_SYSTEMD" == true ]]; then
  for command in systemctl useradd groupadd getent id ln chown curl flock stat; do
    command -v "$command" >/dev/null 2>&1 || fail "缺少 systemd 安装命令: $command"
  done
fi

ASSET="codex-remote-${COMPONENT}-${PLATFORM}"
RUNTIME_ASSET="codex-remote-agent-runtime-${PLATFORM}.tar.gz"
WITH_RUNTIME=false
SCRIPT_DIRECTORY=""
if [[ -f "${BASH_SOURCE[0]}" ]]; then
  SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
if [[ -z "$LOCAL_SOURCE" && -n "$SCRIPT_DIRECTORY" && -f "$SCRIPT_DIRECTORY/release/$ASSET" ]]; then
  LOCAL_SOURCE="$SCRIPT_DIRECTORY/release"
fi

for command in install mktemp awk; do
  command -v "$command" >/dev/null 2>&1 || fail "缺少命令: $command"
done
if command -v sha256sum >/dev/null 2>&1; then
  SHA256_COMMAND=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SHA256_COMMAND=(shasum -a 256)
else
  fail "缺少 SHA-256 校验命令: sha256sum 或 shasum"
fi

TEMP_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/codex-remote-install.XXXXXX")"
cleanup() {
  rm -rf -- "$TEMP_DIRECTORY"
}
trap cleanup EXIT

if [[ -n "$LOCAL_SOURCE" ]]; then
  LOCAL_SOURCE="$(cd "$LOCAL_SOURCE" && pwd)"
  [[ -f "$LOCAL_SOURCE/$ASSET" ]] || fail "本地目录缺少 $ASSET"
  [[ -f "$LOCAL_SOURCE/SHA256SUMS" ]] || fail "本地目录缺少 SHA256SUMS"
  cp "$LOCAL_SOURCE/SHA256SUMS" "$TEMP_DIRECTORY/SHA256SUMS"
  select_runtime
  cp "$LOCAL_SOURCE/$ASSET" "$TEMP_DIRECTORY/$ASSET"
  if [[ "$WITH_RUNTIME" == true ]]; then
    [[ -f "$LOCAL_SOURCE/$RUNTIME_ASSET" ]] || fail "本地目录缺少 $RUNTIME_ASSET"
    cp "$LOCAL_SOURCE/$RUNTIME_ASSET" "$TEMP_DIRECTORY/$RUNTIME_ASSET"
  fi
else
  if command -v curl >/dev/null 2>&1; then
    DOWNLOAD_TOOL="curl"
  elif command -v wget >/dev/null 2>&1; then
    DOWNLOAD_TOOL="wget"
  else
    fail "缺少下载命令: curl 或 wget"
  fi
  [[ "$REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || \
    fail "请通过 --repo OWNER/REPO 或 CODEX_REMOTE_REPO 指定 GitHub 仓库"
  [[ "$VERSION" == "latest" || "$VERSION" =~ ^v[0-9A-Za-z._-]+$ ]] || \
    fail "版本格式无效: $VERSION"
  if [[ "$VERSION" == "latest" ]]; then
    BASE_URL="https://github.com/$REPOSITORY/releases/latest/download"
  else
    BASE_URL="https://github.com/$REPOSITORY/releases/download/$VERSION"
  fi
  download "$BASE_URL/SHA256SUMS" "$TEMP_DIRECTORY/SHA256SUMS"
  select_runtime
  download "$BASE_URL/$ASSET" "$TEMP_DIRECTORY/$ASSET"
  if [[ "$WITH_RUNTIME" == true ]]; then
    download "$BASE_URL/$RUNTIME_ASSET" "$TEMP_DIRECTORY/$RUNTIME_ASSET"
  fi
fi

verify_asset "$ASSET"
if [[ "$WITH_RUNTIME" == true ]]; then
  verify_asset "$RUNTIME_ASSET"
  command -v tar >/dev/null 2>&1 || fail "缺少命令: tar"
  mkdir "$TEMP_DIRECTORY/extracted"
  tar -xzf "$TEMP_DIRECTORY/$RUNTIME_ASSET" -C "$TEMP_DIRECTORY/extracted" \
    --no-same-owner --no-same-permissions
  [[ -x "$TEMP_DIRECTORY/extracted/runtime/node" ]] || fail "Agent 运行时缺少 Node.js"
  [[ -f "$TEMP_DIRECTORY/extracted/runtime/extension-host/runtime.cjs" ]] || fail "Agent 运行时缺少扩展宿主"
  [[ "$("$TEMP_DIRECTORY/extracted/runtime/node" --version)" == "v24.18.0" ]] || fail "Agent Node.js 运行时版本无效"
fi

install -d -m 0755 "$INSTALL_DIRECTORY"
DESTINATION="$INSTALL_DIRECTORY/codex-remote-$COMPONENT"
install -m 0755 "$TEMP_DIRECTORY/$ASSET" "$DESTINATION.new"
if [[ "$WITH_RUNTIME" == true ]]; then
  # Keep the previous runtime until the following installation; running hosts are not stopped.
  rm -rf -- "$INSTALL_DIRECTORY/runtime.previous"
  if [[ -e "$INSTALL_DIRECTORY/runtime" ]]; then
    mv "$INSTALL_DIRECTORY/runtime" "$INSTALL_DIRECTORY/runtime.previous"
  fi
  if ! cp -a "$TEMP_DIRECTORY/extracted/runtime" "$INSTALL_DIRECTORY/runtime"; then
    rm -rf -- "$INSTALL_DIRECTORY/runtime"
    if [[ -e "$INSTALL_DIRECTORY/runtime.previous" ]]; then
      mv "$INSTALL_DIRECTORY/runtime.previous" "$INSTALL_DIRECTORY/runtime"
    fi
    fail "Agent 运行时安装失败，已恢复原运行时"
  fi
fi
mv -f "$DESTINATION.new" "$DESTINATION"

if [[ "$INSTALL_SYSTEMD" == true ]]; then
  install_gateway_service
else
  print_completion
fi
}

# Older release manifests do not include a separate runtime archive.
select_runtime() {
  if [[ "$COMPONENT" == "agent" ]] && \
    awk -v name="$RUNTIME_ASSET" '$2 == name || $2 == "*" name { found = 1 } END { exit !found }' "$TEMP_DIRECTORY/SHA256SUMS"; then
    WITH_RUNTIME=true
  fi
}

verify_asset() {
  local asset="$1"
  local expected actual
  expected="$(awk -v name="$asset" '$2 == name || $2 == "*" name { print $1 }' "$TEMP_DIRECTORY/SHA256SUMS")"
  [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || fail "SHA256SUMS 中没有 $asset 的唯一有效摘要"
  actual="$("${SHA256_COMMAND[@]}" "$TEMP_DIRECTORY/$asset" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || fail "$asset 的 SHA-256 校验失败"
}

download() {
  local url="$1"
  local output="$2"
  if [[ "$DOWNLOAD_TOOL" == "curl" ]]; then
    curl --fail --silent --show-error --location --retry 3 \
      --proto '=https' --tlsv1.2 \
      --output "$output" "$url"
  else
    wget --quiet --tries=3 --output-document="$output" "$url"
  fi
}

install_gateway_service() {
  [[ "$DESTINATION" =~ ^/[A-Za-z0-9._/-]+$ ]] || \
    fail "systemd 二进制路径只能包含字母、数字、点、下划线、横线和斜线"
  [[ "$DATA_DIRECTORY" =~ ^/[A-Za-z0-9._/-]+$ ]] || \
    fail "systemd 数据路径只能包含字母、数字、点、下划线、横线和斜线"

  local config_file="$INSTALL_DIRECTORY/gateway.env"
  local service_file="$INSTALL_DIRECTORY/codex-remote-gateway.service"
  local systemd_link="/etc/systemd/system/codex-remote-gateway.service"

  local service_user="codex-remote"
  local service_group="codex-remote"
  local nologin="/usr/sbin/nologin"
  [[ -x "$nologin" ]] || nologin="/sbin/nologin"
  [[ -x "$nologin" ]] || nologin="/bin/false"

  if ! getent group "$service_group" >/dev/null; then
    groupadd --system "$service_group"
  fi
  if ! id -u "$service_user" >/dev/null 2>&1; then
    useradd --system --gid "$service_group" --home-dir "$DATA_DIRECTORY" \
      --no-create-home --shell "$nologin" "$service_user"
  fi
  install -d -m 0700 -o "$service_user" -g "$service_group" "$DATA_DIRECTORY"
  chown -R "$service_user:$service_group" "$DATA_DIRECTORY"

  if [[ ! -e "$config_file" ]]; then
    cat >"$config_file" <<EOF
HOST=0.0.0.0
PORT=6767
CODEX_REMOTE_DATA_DIR=$DATA_DIRECTORY
CODEX_REMOTE_MAX_DOWNLOAD_BYTES=10485760
CODEX_REMOTE_MAX_UPLOAD_BYTES=10485760
LOG_LEVEL=info
EOF
    chmod 0600 "$config_file"
  fi

  cat >"$service_file" <<EOF
[Unit]
Description=Codex Remote Gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$service_user
Group=$service_group
WorkingDirectory=$DATA_DIRECTORY
EnvironmentFile=$config_file
Environment=CODEX_REMOTE_MANAGED_GATEWAY=1
ExecStart=$DESTINATION start
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateDevices=true
PrivateTmp=true
ProtectControlGroups=true
ProtectHome=true
ProtectKernelModules=true
ProtectKernelTunables=true
ProtectSystem=strict
ReadWritePaths=$DATA_DIRECTORY
ReadWritePaths=/var/lib/codex-remote-control/requests
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK

[Install]
WantedBy=multi-user.target
EOF
  chmod 0644 "$service_file"
  ln -sfn "$service_file" "$systemd_link"

  install_gateway_maintenance

  systemctl daemon-reload
  systemctl enable --now codex-remote-gateway-maintenance.path >/dev/null
  systemctl enable codex-remote-gateway.service >/dev/null
  systemctl restart codex-remote-gateway.service
  if ! gateway_service_is_stable; then
    systemctl --no-pager --full status codex-remote-gateway.service >&2 || true
    if command -v journalctl >/dev/null 2>&1; then
      journalctl --no-pager -u codex-remote-gateway.service -n 20 >&2 || true
    fi
    fail "Gateway service 启动失败，错误信息见上方日志"
  fi

  printf '%s\n' \
    "Gateway 已安装并启动: $DESTINATION" \
    "配置文件: $config_file" \
    "首次 Token: sudo cat $DATA_DIRECTORY/api-token" \
    "管理: sudo $DESTINATION" \
    "手机远程更新/重启: 已启用（管理员密钥）" \
    "状态检查: sudo systemctl status codex-remote-gateway"
}

# Root performs only these two fixed actions. The unprivileged Gateway cannot
# supply shell commands, download URLs, service names or destination paths.
install_gateway_maintenance() {
  local control_directory="/var/lib/codex-remote-control"
  local helper="$INSTALL_DIRECTORY/gateway-maintenance.sh"
  local path_file="$INSTALL_DIRECTORY/codex-remote-gateway-maintenance.path"
  local maintenance_service="$INSTALL_DIRECTORY/codex-remote-gateway-maintenance.service"
  local current_path="$INSTALL_DIRECTORY" permissions
  while [[ "$current_path" != "/" ]]; do
    [[ -d "$current_path" && ! -L "$current_path" ]] || fail "远程维护安装目录不能包含符号链接"
    permissions="$(stat -c %a "$current_path")"
    [[ "$(stat -c %u "$current_path")" == 0 ]] && (( (8#$permissions & 8#022) == 0 )) || \
      fail "远程维护的程序目录及父目录必须由 root 拥有且其他用户不可写: $current_path"
    current_path="$(dirname "$current_path")"
  done
  [[ ! -L "$control_directory" ]] || fail "维护控制目录不能是符号链接"
  install -d -m 0755 -o root -g root "$control_directory"
  [[ ! -L "$control_directory/requests" ]] || fail "维护请求目录不能是符号链接"
  install -d -m 0730 -o root -g codex-remote "$control_directory/requests"
  local update_supported=false
  [[ "$REPOSITORY" != chensunlai/codex-remote ]] || update_supported=true
  printf '{"dataDirectory":"%s","executable":"%s","updateSupported":%s}\n' "$DATA_DIRECTORY" "$DESTINATION" "$update_supported" \
    >"$control_directory/installation.json.new"
  chmod 0644 "$control_directory/installation.json.new"
  mv -f "$control_directory/installation.json.new" "$control_directory/installation.json"
  write_gateway_maintenance_helper >"$helper.new"
  chmod 0755 "$helper.new"
  chown root:root "$helper.new"
  mv -f "$helper.new" "$helper"
  cat >"$path_file" <<EOF
[Unit]
Description=Codex Remote Gateway maintenance requests

[Path]
DirectoryNotEmpty=$control_directory/requests
Unit=codex-remote-gateway-maintenance.service

[Install]
WantedBy=multi-user.target
EOF
  cat >"$maintenance_service" <<EOF
[Unit]
Description=Codex Remote Gateway fixed maintenance helper
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
User=root
EnvironmentFile=$INSTALL_DIRECTORY/gateway.env
ExecStart=$helper
TimeoutStartSec=15min
UMask=0077
NoNewPrivileges=true
PrivateDevices=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=$INSTALL_DIRECTORY $control_directory
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
EOF
  chmod 0644 "$path_file" "$maintenance_service"
  ln -sfn "$path_file" /etc/systemd/system/codex-remote-gateway-maintenance.path
  ln -sfn "$maintenance_service" /etc/systemd/system/codex-remote-gateway-maintenance.service
}

write_gateway_maintenance_helper() {
  local update_supported=false
  [[ "$REPOSITORY" != chensunlai/codex-remote ]] || update_supported=true
  printf '#!/usr/bin/env bash\nINSTALL_DIRECTORY=%q\nCONTROL_DIRECTORY=%q\nUPDATE_SUPPORTED=%s\n' \
    "$INSTALL_DIRECTORY" "/var/lib/codex-remote-control" "$update_supported"
  cat <<'MAINTENANCE'
set -euo pipefail
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
DESTINATION="$INSTALL_DIRECTORY/codex-remote-gateway"
SERVICE=codex-remote-gateway.service
exec 9>"$CONTROL_DIRECTORY/lock"
flock -x 9
operation_active=false
replacement_pending=false
old_version=""
stage=""

write_status() {
  local state="$1" output="$2" error="${3:-}"
  printf '{"id":"%s","type":"%s","state":"%s","output":"%s","error":"%s"}\n' \
    "$operation_id" "$action" "$state" "$output" "$error" >"$CONTROL_DIRECTORY/.operation.$$"
  chmod 0644 "$CONTROL_DIRECTORY/.operation.$$"
  mv -f "$CONTROL_DIRECTORY/.operation.$$" "$CONTROL_DIRECTORY/operation.json"
}

cleanup() {
  if [[ "$replacement_pending" == true && -n "$stage" && -f "$stage/previous" ]]; then
    if mv -f "$stage/previous" "$DESTINATION" && systemctl restart "$SERVICE" && healthy "$old_version"; then
      write_status failed '已恢复原版本' '维护操作中断，已自动回滚'
    else
      write_status failed '维护操作中断，请检查服务状态' '无法确认原版本启动状态，已保留恢复文件'
      stage=""
    fi
    operation_active=false
  fi
  if [[ "$operation_active" == true ]]; then
    write_status failed '维护操作失败' '维护服务异常退出，请查看服务器维护日志'
  fi
  [[ -z "$stage" ]] || rm -rf -- "$stage"
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

download() {
  curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 300 \
    --proto '=https' --proto-redir '=https' --tlsv1.2 --max-filesize "$3" --output "$2" "$1"
}

health_version() {
  local host="${HOST:-0.0.0.0}" scheme=http
  local -a tls_options=()
  [[ "$host" != 0.0.0.0 ]] || host=127.0.0.1
  [[ "$host" != :: ]] || host=::1
  [[ "$host" != *:* ]] || host="[$host]"
  if [[ -n "${CODEX_REMOTE_TLS_CERT:-}" ]]; then
    scheme=https
    # This is the local startup probe; release downloads always verify TLS.
    tls_options=(--insecure)
  fi
  curl --fail --silent --show-error --noproxy '*' --max-time 2 "${tls_options[@]}" \
    "$scheme://$host:${PORT:-6767}/healthz" 2>/dev/null \
    | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

healthy() {
  local expected="${1:-}" check version consecutive=0 previous_pid="" pid
  for check in {1..20}; do
    sleep 1
    pid="$(systemctl show --value --property MainPID "$SERVICE")"
    version="$(health_version || true)"
    if [[ "$pid" != 0 && -n "$version" && ( -z "$expected" || "$version" == "$expected" ) ]] \
      && systemctl is-active --quiet "$SERVICE"; then
      if [[ "$pid" == "$previous_pid" ]]; then consecutive=$((consecutive + 1)); else consecutive=1; fi
      previous_pid="$pid"
      (( consecutive < 3 )) || return 0
    else
      consecutive=0
    fi
  done
  return 1
}

perform_update() {
  local latest_url version platform asset expected actual old_core
  [[ "$UPDATE_SUPPORTED" == true ]] || return 1
  write_status running '正在检查最新正式版本'
  latest_url="$(curl --fail --silent --show-error --location --connect-timeout 15 --max-time 60 \
    --proto '=https' --proto-redir '=https' --tlsv1.2 --output /dev/null --write-out '%{url_effective}' \
    https://github.com/chensunlai/codex-remote/releases/latest)" || return 1
  [[ "$latest_url" =~ ^https://github.com/chensunlai/codex-remote/releases/tag/v([0-9]{1,9}\.[0-9]{1,9}\.[0-9]{1,9})$ ]] || return 1
  version="${BASH_REMATCH[1]}"
  old_version="$(health_version || true)"
  [[ "$old_version" =~ ^[0-9]{1,9}\.[0-9]{1,9}\.[0-9]{1,9}([-+][0-9A-Za-z.+-]+)?$ ]] || return 1
  old_core="${old_version%%[-+]*}"
  if [[ "$old_version" == "$version" ]]; then write_status succeeded 'Gateway 已是最新版本'; return 0; fi
  if [[ "$old_core" != "$version" ]] && \
    [[ "$(printf '%s\n' "$old_core" "$version" | sort -V | tail -n 1)" == "$old_core" ]]; then
    write_status succeeded '当前 Gateway 版本高于最新正式版，未降级'
    return 0
  fi
  case "$(uname -m)" in
    x86_64|amd64) platform=linux-x64 ;;
    aarch64|arm64) platform=linux-arm64 ;;
    *) return 1 ;;
  esac
  asset="codex-remote-gateway-$platform"
  stage="$(mktemp -d "$INSTALL_DIRECTORY/.gateway-maintenance.XXXXXX")" || return 1
  write_status running '正在下载并校验 Gateway'
  download "https://github.com/chensunlai/codex-remote/releases/download/v$version/SHA256SUMS" "$stage/SHA256SUMS" 1048576 || return 1
  expected="$(awk -v name="$asset" '$2 == name || $2 == "*" name { print $1 }' "$stage/SHA256SUMS")"
  [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || return 1
  download "https://github.com/chensunlai/codex-remote/releases/download/v$version/$asset" "$stage/download" 268435456 || return 1
  actual="$(sha256sum "$stage/download" | awk '{print $1}')"
  [[ "$actual" == "${expected,,}" && -f "$DESTINATION" && ! -L "$DESTINATION" ]] || return 1
  cp -p "$DESTINATION" "$stage/previous" || return 1
  install -m 0755 "$stage/download" "$stage/next" || return 1
  write_status running '正在重启并验证新版本'
  replacement_pending=true
  mv -f "$stage/next" "$DESTINATION" || return 1
  if systemctl restart "$SERVICE" && healthy "$version"; then
    replacement_pending=false
    write_status succeeded 'Gateway 更新完成'
  else
    write_status running '新版本启动失败，正在恢复原版本'
    mv -f "$stage/previous" "$DESTINATION" || return 1
    replacement_pending=false
    if systemctl restart "$SERVICE" && healthy "$old_version"; then
      write_status failed '已恢复原版本' '新版本启动检查失败，已自动回滚'
    else
      write_status failed '已恢复原程序，请检查服务状态' '新版本及原版本启动检查均失败，请查看服务器日志'
    fi
  fi
}

shopt -s nullglob dotglob
for request in "$CONTROL_DIRECTORY/requests/"*; do
  name="${request##*/}"
  if [[ -f "$request" && ! -L "$request" && "$name" =~ ^(update|restart)-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$ ]]; then
    action="${BASH_REMATCH[1]}"
    operation_id="${BASH_REMATCH[2]}"
    write_status running '维护操作已开始'
    operation_active=true
    rm -f -- "$request"
    sleep 1
    if [[ "$action" == restart ]]; then
      if systemctl restart "$SERVICE" && healthy; then
        write_status succeeded 'Gateway 重启完成'
      else
        write_status failed 'Gateway 重启失败' '服务启动检查失败，请查看服务器日志'
      fi
    elif ! perform_update; then
      if [[ "$replacement_pending" == true ]]; then
        cleanup
        replacement_pending=false
      else
        write_status failed 'Gateway 更新失败' '下载、校验或维护操作失败，请查看服务器维护日志'
      fi
    fi
    operation_active=false
    [[ -z "$stage" ]] || rm -rf -- "$stage"
    stage=""
  else
    # Never follow request symlinks or interpret request file contents.
    if [[ -d "$request" && ! -L "$request" ]]; then
      rejected="$CONTROL_DIRECTORY/rejected.$$.$RANDOM"
      if mv -T -- "$request" "$rejected"; then rm -rf -- "$rejected"; fi
    else
      rm -f -- "$request" || true
    fi
  fi
done
MAINTENANCE
}

gateway_service_is_stable() {
  local consecutive_active_checks=0
  local check
  for check in 1 2 3 4 5; do
    sleep 1
    if systemctl is-active --quiet codex-remote-gateway.service; then
      consecutive_active_checks=$((consecutive_active_checks + 1))
      if ((consecutive_active_checks >= 2)); then
        return 0
      fi
    else
      consecutive_active_checks=0
    fi
  done
  return 1
}

print_completion() {
  printf '%s\n' "$COMPONENT 已安装: $DESTINATION"
  if [[ "$COMPONENT" == "agent" ]]; then
    printf '%s\n' \
      "运行: $DESTINATION" \
      "Codex CLI 不包含在安装包中，Agent 会调用系统中的 codex 或 --codex 指定路径。"
    if [[ "$WITH_RUNTIME" == true ]]; then
      printf '%s\n' "官方扩展宿主及 Node.js 运行时: $INSTALL_DIRECTORY/runtime"
    fi
  else
    install -d -m 0700 "$DATA_DIRECTORY"
    if [[ "$DATA_DIRECTORY" == "$INSTALL_DIRECTORY/data" ]]; then
      printf '%s\n' \
        "管理: $DESTINATION" \
        "运行: $DESTINATION start" \
        "默认监听: 0.0.0.0:6767"
    else
      printf '%s\n' \
        "管理: CODEX_REMOTE_DATA_DIR=$DATA_DIRECTORY $DESTINATION" \
        "运行: CODEX_REMOTE_DATA_DIR=$DATA_DIRECTORY $DESTINATION start" \
        "默认监听: 0.0.0.0:6767"
    fi
  fi
}

require_value() {
  (($# >= 2)) || fail "$1 需要一个值"
}

fail() {
  printf '%s: %s\n' "$PROGRAM" "$1" >&2
  exit 1
}

usage() {
  cat <<'EOF'
install.sh <agent|gateway> [options]

  --repo OWNER/REPO       覆盖 GitHub 仓库
  --version VERSION       release 标签，默认 latest
  --from DIRECTORY        从本地 release 目录安装
  --install-dir DIRECTORY 程序目录；Agent 默认 ~/.local/codex-remote，Gateway 默认 /opt/codex-remote
  --data-dir DIRECTORY    Gateway 数据目录，默认位于程序目录的 data
  --systemd               安装并启动 Gateway systemd 服务（需要 root）

Agent 支持 Linux 和 macOS（Intel / Apple Silicon），包含官方扩展宿主及 Node.js 运行时。
不配置后台运行方式，也不会安装 Codex CLI。Gateway 仅支持 Linux。
EOF
}

main "$@"
