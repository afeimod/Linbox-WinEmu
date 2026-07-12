#!/usr/bin/env bash
# Alpine Linux (apk) 桌面环境安装
# Alpine 资源少,推荐 XFCE4; KDE 较重,可装但慢

set -e
DE="${1:-xfce4}"

apk update

echo "[alpine_apk] 安装通用基础包..."
apk add \
    sudo dbus dbus-x11 \
    pulseaudio alsa-lib alsa-utils \
    libxkbcommon libxkbcommon-x11 \
    mesa mesa-vulkan-drivers vulkan-loader libdrm libgbm \
    xrandr xset \
    font-noto-cjk wqy-zenhei \
    fcitx5 fcitx5-chinese-addons fcitx5-gtk fcitx5-qt \
    unzip p7zip vim nano git htop \
    xdg-utils \
    networkmanager

case "$DE" in
    xfce4)
        echo "[alpine_apk] 安装 XFCE4..."
        apk add \
            xfce4 xfce4-terminal xfce4-screensaver \
            lightdm lightdm-gtk-greeter \
            thunar thunar-archive-plugin thunar-volman \
            mousepad ristretto parole
        ;;
    kde)
        echo "[alpine_apk] 安装 KDE Plasma..."
        apk add plasma-desktop konsole dolphin sddm
        ;;
    skip)
        echo "[alpine_apk] 跳过桌面,只装 X11 基础..."
        apk add xorg-server dbus-x11 libxkbcommon-x11
        ;;
    *)
        echo "[alpine_apk] 未知 DE: $DE" >&2; exit 3 ;;
esac

echo "[alpine_apk] 桌面 $DE 安装完成 ✅"
