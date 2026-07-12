#!/usr/bin/env bash
# 通用收尾: 任何发行版装完 DE 后都跑一次

set -e
DE="${1:-xfce4}"

# 1) Locale
if command -v locale-gen >/dev/null 2>&1; then
    locale-gen zh_CN.UTF-8 en_US.UTF-8 2>/dev/null || true
fi
if [ -f /etc/locale.conf ] 2>/dev/null; then
    echo "LANG=zh_CN.UTF-8" > /etc/locale.conf
fi
export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8

# 2) 时区
if [ -f /etc/localtime ] && command -v timedatectl >/dev/null 2>&1; then
    timedatectl set-timezone Asia/Shanghai 2>/dev/null || true
fi

# 3) dbus 服务
mkdir -p /var/run/dbus
dbus-daemon --system --fork 2>/dev/null || true

# 4) 决定 DE 命令
case "$DE" in
    xfce4)
        DE_NAME="XFCE"; DE_COMMAND="startxfce4" ;;
    kde)
        DE_NAME="KDE"; DE_COMMAND="startplasma-x11" ;;
    skip)
        cat > /etc/skel/.xinitrc <<'EOF'
export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8
export GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx
EOF
        echo "[common_post] skip 模式: 写入空 .xinitrc"
        exit 0 ;;
    *) echo "[common_post] 未知 DE: $DE" >&2; exit 3 ;;
esac

# 5) 写 .xinitrc (用 dbus-launch, 启动 fcitx5 + DE)
cat > /etc/skel/.xinitrc <<EOF
# Linbox-WinEmu 默认会话: $DE_NAME
export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8
export GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx
export XDG_SESSION_TYPE=x11
export XDG_CURRENT_DESKTOP=$DE_NAME
export XDG_SESSION_DESKTOP=$DE_NAME

# dbus 会话里跑 fcitx5 + 桌面
dbus-launch --exit-with-session $DE_COMMAND
EOF

# 6) 已存在的用户也复制一份
if [ -d /root ]; then
    cp /etc/skel/.xinitrc /root/.xinitrc 2>/dev/null || true
fi

echo "[common_post] locale = zh_CN.UTF-8, 默认会话 = $DE"
echo "[common_post] ✅ 通用配置完成"
