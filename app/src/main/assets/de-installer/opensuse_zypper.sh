#!/usr/bin/env bash
# openSUSE (zypper) 桌面环境安装

set -e
DE="${1:-xfce4}"

zypper --non-interactive refresh

echo "[opensuse_zypper] 安装通用基础包..."
zypper --non-interactive install \
    sudo dbus-1 dbus-1-x11 \
    pulseaudio alsa-utils alsa-plugins \
    libxkbcommon-x11-0 libxkbcommon0-0 \
    Mesa Mesa-vulkan-drivers libvulkan1 libdrm2 libgbm1 \
    xset xrandr \
    noto-sans-cjk-fonts wqy-zenhei-fonts wqy-microhei-fonts \
    fcitx5 fcitx5-chinese-addons fcitx5-gtk fcitx5-qt \
    unzip p7zip vim nano git htop \
    xdg-utils \
    NetworkManager

case "$DE" in
    xfce4)
        echo "[opensuse_zypper] 安装 XFCE4..."
        zypper --non-interactive install -t pattern xfce
        zypper --non-interactive install \
            xfce4-terminal xfce4-panel xfce4-power-manager \
            lightdm lightdm-gtk-greeter \
            thunar thunar-plugin-archive
        ;;
    kde)
        echo "[opensuse_zypper] 安装 KDE Plasma..."
        zypper --non-interactive install -t pattern kde
        zypper --non-interactive install sddm
        ;;
    skip)
        echo "[opensuse_zypper] 跳过桌面,只装 X11 基础..."
        zypper --non-interactive install xorg-x11-server dbus-1-x11 libxkbcommon-x11-0
        ;;
    *)
        echo "[opensuse_zypper] 未知 DE: $DE" >&2; exit 3 ;;
esac

echo "[opensuse_zypper] 桌面 $DE 安装完成 ✅"
