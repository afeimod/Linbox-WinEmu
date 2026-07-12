#!/usr/bin/env bash
# Fedora / RHEL 系 (dnf) 桌面环境安装

set -e
DE="${1:-xfce4}"

dnf update -y || dnf -y update || true

echo "[fedora_dnf] 安装通用基础包..."
dnf install -y \
    sudo dbus dbus-x11 \
    glibc \
    pulseaudio pulseaudio-utils alsa-lib alsa-utils \
    libxkbcommon libxkbcommon-x11 \
    mesa-libGL mesa-libEGL mesa-vulkan-drivers vulkan-loader libdrm libgbm \
    xorg-x11-utils xorg-x11-server-utils xrandr \
    google-noto-sans-cjk-fonts google-noto-serif-cjk-fonts wqy-zenhei-fonts wqy-microhei-fonts \
    fcitx5 fcitx5-chinese-addons fcitx5-gtk fcitx5-qt fcitx5-configtool \
    unzip p7zip p7zip-plugins vim nano git htop \
    xdg-utils \
    NetworkManager-tui net-tools bind-utils

case "$DE" in
    xfce4)
        echo "[fedora_dnf] 安装 XFCE4..."
        dnf install -y \
            @xfce-desktop-environment \
            lightdm lightdm-gtk \
            xfce4-pulseaudio-plugin \
            thunar thunar-archive-plugin thunar-volman
        ;;
    kde)
        echo "[fedora_dnf] 安装 KDE Plasma..."
        dnf install -y \
            @kde-desktop-environment \
            sddm
        ;;
    skip)
        echo "[fedora_dnf] 跳过桌面,只装 X11 基础..."
        dnf install -y xorg-x11-server-Xorg dbus-x11 libxkbcommon-x11
        ;;
    *)
        echo "[fedora_dnf] 未知 DE: $DE" >&2; exit 3 ;;
esac

dnf clean all

echo "[fedora_dnf] 桌面 $DE 安装完成 ✅"
