#!/usr/bin/env bash
# Void Linux (xbps) 桌面环境安装

set -e
DE="${1:-xfce4}"

xbps-install -Suy xbps
xbps-install -uy

echo "[void_xbps] 安装通用基础包..."
xbps-install -Sy \
    sudo dbus dbus-x11 \
    pulseaudio alsa-lib alsa-utils \
    libxkbcommon libxkbcommon-x11 \
    Mesa Vulkan-Loader mesa-vulkan-drivers libdrm libgbm \
    noto-fonts-cjk wqy-zenhei-fonts wqy-microhei-fonts \
    fcitx5 fcitx5-chinese-addons fcitx5-gtk fcitx5-qt \
    unzip p7zip vim nano git htop \
    xdg-utils \
    NetworkManager

case "$DE" in
    xfce4)
        echo "[void_xbps] 安装 XFCE4..."
        xbps-install -Sy xfce4 xfce4-terminal lightdm lightdm-gtk-greeter thunar
        ;;
    kde)
        echo "[void_xbps] 安装 KDE Plasma..."
        xbps-install -Sy kde5 sddm
        ;;
    skip)
        echo "[void_xbps] 跳过桌面,只装 X11 基础..."
        xbps-install -Sy xorg-server dbus-x11 libxkbcommon-x11
        ;;
    *)
        echo "[void_xbps] 未知 DE: $DE" >&2; exit 3 ;;
esac

xbps-remove -Oo

echo "[void_xbps] 桌面 $DE 安装完成 ✅"
