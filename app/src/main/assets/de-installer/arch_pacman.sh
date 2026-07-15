#!/usr/bin/env bash
# Arch / Manjaro 系 (pacman) 桌面环境安装
# 必装: libxkbcommon-x11, dbus  (Linbox-WinEmu Termux:X11 必须)

set -e
DE="${1:-xfce4}"

if [ ! -d /etc/pacman.d/gnupg ] || [ -z "$(ls -A /etc/pacman.d/gnupg 2>/dev/null)" ]; then
    echo "[arch_pacman] 初始化 pacman keyring..."
    pacman-key --init || true
    pacman-key --populate archlinux || pacman-key --populate manjaro || true
fi

pacman -Sy --noconfirm --needed archlinux-keyring manjaro-keyring 2>/dev/null || pacman -Sy --noconfirm

echo "[arch_pacman] 安装通用基础包..."
pacman -S --noconfirm --needed \
    base base-devel \
    sudo dbus dbus-x11 \
    glibc \
    pulseaudio \
    libxkbcommon libxkbcommon-x11 \
    mesa mesa-vulkan-drivers vulkan-icd-loader libdrm libgbm \
    noto-fonts-cjk wqy-zenhei wqy-microhei \
    fcitx5 fcitx5-chinese-addons fcitx5-gtk fcitx5-qt fcitx5-configtool \
    unzip p7zip nano git \
    xdg-utils

case "$DE" in
    xfce4)
        echo "[arch_pacman] 安装 XFCE4..."
        pacman -S --noconfirm --needed \
            xfce4 xfce4-goodies \
            xfce4-pulseaudio-plugin \
            mousepad
        ;;
    kde)
        echo "[arch_pacman] 安装 KDE Plasma..."
        pacman -S --noconfirm --needed \
            plasma-meta sddm \
            dolphin \
            plasma-nm plasma-pa
        ;;
    skip)
        echo "[arch_pacman] 跳过桌面,只装 X11 基础..."
        pacman -S --noconfirm --needed xorg-server dbus libxkbcommon-x11
        ;;
    *)
        echo "[arch_pacman] 未知 DE: $DE" >&2; exit 3 ;;
esac

pacman -Scc --noconfirm || true

echo "[arch_pacman] 桌面 $DE 安装完成 ✅"
