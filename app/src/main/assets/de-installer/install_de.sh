#!/usr/bin/env bash
# Linbox-WinEmu: 安装图形桌面 (XFCE4 / KDE) 到 PRoot 容器内
# 用法: install_de.sh <xfce4|kde|skip>  (容器内执行)
# 约定: 以 root 运行 (proot --link2symlink 下本身就是 fake root, 用 sudo -E 也能跑)

set -e

DE="${1:-xfce4}"

if [ "$(id -u)" -ne 0 ]; then
    echo "[install_de] 请以 root 运行(proot 默认就是 fake root)" >&2
    exit 1
fi

# 识别发行版
. /etc/os-release
DISTRO_ID="${ID:-unknown}"
DISTRO_LIKE="${ID_LIKE:-}"
echo "[install_de] 检测到发行版: ID=$DISTRO_ID ID_LIKE=$DISTRO_LIKE"
echo "[install_de] 目标桌面: $DE"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

run_installer() {
    case "$1" in
        debian|ubuntu|kali|debian*|ubuntu*)
            bash "$SCRIPT_DIR/debian_apt.sh" "$DE" ;;
        arch|archlinux|manjaro|endeavouros|garuda|arch*)
            bash "$SCRIPT_DIR/arch_pacman.sh" "$DE" ;;
        fedora|nobara|ultramarine|rhel|centos|rocky|alma|fedora*)
            bash "$SCRIPT_DIR/fedora_dnf.sh" "$DE" ;;
        alpine)
            bash "$SCRIPT_DIR/alpine_apk.sh" "$DE" ;;
        opensuse*|suse|tumbleweed|leap)
            bash "$SCRIPT_DIR/opensuse_zypper.sh" "$DE" ;;
        void)
            bash "$SCRIPT_DIR/void_xbps.sh" "$DE" ;;
        *)
            case "$DISTRO_LIKE" in
                *debian*|*ubuntu*) bash "$SCRIPT_DIR/debian_apt.sh" "$DE" ;;
                *arch*)            bash "$SCRIPT_DIR/arch_pacman.sh" "$DE" ;;
                *fedora*|*rhel*)   bash "$SCRIPT_DIR/fedora_dnf.sh" "$DE" ;;
                *suse*)            bash "$SCRIPT_DIR/opensuse_zypper.sh" "$DE" ;;
                *) echo "[install_de] 不支持的发行版: $DISTRO_ID" >&2; exit 2 ;;
            esac
            ;;
    esac
}

run_installer "$DISTRO_ID"

# 通用收尾
echo "[install_de] 通用收尾: 配置 locale、dbus、默认会话..."
bash "$SCRIPT_DIR/common_post.sh" "$DE"

echo "[install_de] 完成 ✅  下次启动 X11 后,会话里选 $DE 即可。"
