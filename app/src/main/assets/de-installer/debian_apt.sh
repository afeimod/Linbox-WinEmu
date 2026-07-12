#!/usr/bin/env bash
# Debian / Ubuntu 系 (apt) 桌面环境安装
# 必装: libxkbcommon-x11-dev, dbus-x11  (Linbox-WinEmu Termux:X11 桥接必须)
# 同时带中文字体/输入法/locales/PulseAudio/Vulkan 栈,开箱即用

set -e
DE="${1:-xfce4}"

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

apt-get update -y

# ============ 通用基础 (无论 XFCE 还是 KDE 都要) ============
echo "[debian_apt] 安装通用基础包..."
apt-get install -y --no-install-recommends \
    ca-certificates curl wget gnupg \
    sudo dbus dbus-x11 \
    locales locales-all \
    pulseaudio pulseaudio-utils alsa-utils \
    libasound2 libasound2-data \
    libpulse0 libpulse-mainloop-glib0 \
    libxkbcommon-x11-dev \
    libgl1 libegl1 libgles2 libvulkan1 mesa-vulkan-drivers \
    libdrm2 libgbm1 \
    x11-utils x11-xserver-utils \
    fonts-noto-cjk fonts-wqy-zenhei fonts-wqy-microhei \
    fcitx5 fcitx5-chinese-addons fcitx5-frontend-gtk3 fcitx5-frontend-qt5 \
    unzip p7zip-full vim nano git htop \
    xdg-utils menu menu-l10n \
    network-manager net-tools

# ============ DE 专属 ============
case "$DE" in
    xfce4)
        echo "[debian_apt] 安装 XFCE4..."
        apt-get install -y --no-install-recommends \
            xfce4 xfce4-terminal xfce4-goodies \
            lightdm lightdm-gtk-greeter \
            dbus-x11 \
            xfce4-pulseaudio-plugin \
            thunar thunar-archive-plugin thunar-volman \
            mousepad ristretto parole
        ;;
    kde)
        echo "[debian_apt] 安装 KDE Plasma..."
        apt-get install -y --no-install-recommends \
            kde-plasma-desktop sddm \
            plasma-nm plasma-pa plasma-workspace \
            dolphin konsole ark gwenview spectacle okular \
            kate kcalc plasma-systemmonitor
        ;;
    skip)
        echo "[debian_apt] 跳过桌面安装,只装 X11 基础..."
        apt-get install -y --no-install-recommends \
            xorg dbus-x11 libxkbcommon-x11-dev
        ;;
    *)
        echo "[debian_apt] 未知 DE: $DE" >&2; exit 3 ;;
esac

apt-get clean
rm -rf /var/lib/apt/lists/*

echo "[debian_apt] 桌面 $DE 安装完成 ✅"
