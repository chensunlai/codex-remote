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

[[ "$(uname -s)" == "Linux" ]] || fail "当前 release 仅支持 Linux"
if command -v ldd >/dev/null 2>&1 && ldd --version 2>&1 | grep -qi musl; then
  fail "当前 release 面向 glibc Linux，检测到 musl/Alpine"
fi

case "$(uname -m)" in
  x86_64|amd64) PLATFORM="linux-x64" ;;
  aarch64|arm64) PLATFORM="linux-arm64" ;;
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
    INSTALL_DIRECTORY="$PWD"
  fi
fi
if [[ -z "$DATA_DIRECTORY" && "$COMPONENT" == "gateway" ]]; then
  DATA_DIRECTORY="$INSTALL_DIRECTORY/data"
fi

ASSET="codex-remote-${COMPONENT}-${PLATFORM}"
SCRIPT_DIRECTORY=""
if [[ -f "${BASH_SOURCE[0]}" ]]; then
  SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
if [[ -z "$LOCAL_SOURCE" && -n "$SCRIPT_DIRECTORY" && -f "$SCRIPT_DIRECTORY/release/$ASSET" ]]; then
  LOCAL_SOURCE="$SCRIPT_DIRECTORY/release"
fi

for command in install mktemp sha256sum awk; do
  command -v "$command" >/dev/null 2>&1 || fail "缺少命令: $command"
done

TEMP_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/codex-remote-install.XXXXXX")"
cleanup() {
  rm -rf -- "$TEMP_DIRECTORY"
}
trap cleanup EXIT

if [[ -n "$LOCAL_SOURCE" ]]; then
  LOCAL_SOURCE="$(cd "$LOCAL_SOURCE" && pwd)"
  [[ -f "$LOCAL_SOURCE/$ASSET" ]] || fail "本地目录缺少 $ASSET"
  [[ -f "$LOCAL_SOURCE/SHA256SUMS" ]] || fail "本地目录缺少 SHA256SUMS"
  cp "$LOCAL_SOURCE/$ASSET" "$TEMP_DIRECTORY/$ASSET"
  cp "$LOCAL_SOURCE/SHA256SUMS" "$TEMP_DIRECTORY/SHA256SUMS"
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
  download "$BASE_URL/$ASSET" "$TEMP_DIRECTORY/$ASSET"
fi

EXPECTED="$(awk -v name="$ASSET" '$2 == name || $2 == "*" name { print $1 }' "$TEMP_DIRECTORY/SHA256SUMS")"
[[ "$EXPECTED" =~ ^[0-9a-fA-F]{64}$ ]] || fail "SHA256SUMS 中没有 $ASSET 的唯一有效摘要"
ACTUAL="$(sha256sum "$TEMP_DIRECTORY/$ASSET" | awk '{print $1}')"
[[ "$ACTUAL" == "$EXPECTED" ]] || fail "$ASSET 的 SHA-256 校验失败"

install -d -m 0755 "$INSTALL_DIRECTORY"
DESTINATION="$INSTALL_DIRECTORY/codex-remote-$COMPONENT"
install -m 0755 "$TEMP_DIRECTORY/$ASSET" "$DESTINATION.new"
mv -f "$DESTINATION.new" "$DESTINATION"

if [[ "$INSTALL_SYSTEMD" == true ]]; then
  install_gateway_service
else
  print_completion
fi
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
  for command in systemctl useradd groupadd getent id ln; do
    command -v "$command" >/dev/null 2>&1 || fail "缺少 systemd 安装命令: $command"
  done
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

  if [[ ! -e "$config_file" ]]; then
    cat >"$config_file" <<EOF
HOST=0.0.0.0
PORT=6767
CODEX_REMOTE_DATA_DIR=$DATA_DIRECTORY
CODEX_REMOTE_MAX_DOWNLOAD_BYTES=10485760
CODEX_REMOTE_MAX_UPLOAD_BYTES=536870912
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
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX

[Install]
WantedBy=multi-user.target
EOF
  chmod 0644 "$service_file"
  ln -sfn "$service_file" "$systemd_link"

  systemctl daemon-reload
  systemctl enable codex-remote-gateway.service >/dev/null
  systemctl restart codex-remote-gateway.service
  systemctl is-active --quiet codex-remote-gateway.service || \
    fail "Gateway service 启动失败，请运行 systemctl status codex-remote-gateway"

  printf '%s\n' \
    "Gateway 已安装并启动: $DESTINATION" \
    "配置文件: $config_file" \
    "首次 Token: sudo cat $DATA_DIRECTORY/api-token" \
    "状态检查: sudo systemctl status codex-remote-gateway"
}

print_completion() {
  printf '%s\n' "$COMPONENT 已安装: $DESTINATION"
  if [[ "$COMPONENT" == "agent" ]]; then
    printf '%s\n' \
      "运行: $DESTINATION" \
      "Codex CLI 不包含在安装包中，Agent 会调用系统中的 codex 或 --codex 指定路径。"
  else
    install -d -m 0700 "$DATA_DIRECTORY"
    printf '%s\n' \
      "运行: CODEX_REMOTE_DATA_DIR=$DATA_DIRECTORY $DESTINATION start" \
      "默认监听: 0.0.0.0:6767"
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
  --install-dir DIRECTORY 程序目录；Agent 默认当前目录，Gateway 默认 /opt/codex-remote
  --data-dir DIRECTORY    Gateway 数据目录，默认位于程序目录的 data
  --systemd               安装并启动 Gateway systemd 服务（需要 root）

Agent 安装器只放置程序，不配置后台运行方式，也不会安装 Codex CLI。
EOF
}

main "$@"
