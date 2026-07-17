#!/system/bin/sh
# glibc-run.sh
#
# Android 端跑的 box64+wine 启动器, 被 fifo_exec_server.sh 派发时调用。
#
# 关键 (用户原话):
#   "我的 glibc 是绝对路径 /data/data/a.io.github.ewt45.winemulator/files/imagefs 编译的"
#   "而且 proot 无法访问才让 linboxapp 使用 fifo 管道服务让它能正常访问"
#   "这样就可以 startexec glibc-run 运行 glibc 的 box64 wine"
#
# 所以这里:
#   - IMAGEFS 默认 = Android 绝对路径
#   - 这个脚本由 fifo server 在 Android 进程 fork, Android 进程能直接
#     exec imagefs 里的 box64+wine (编译路径就是 Android 绝对路径)
#   - 不需要 proot, 直接 exec box64
#
# imagefs 里关键二进制 (Android 视角):
#   - /data/data/.../files/imagefs/usr/bin/box64      (musl 链接)
#   - /data/data/.../files/imagefs/opt/wine/bin/wine  (glibc 链接)
#   - /data/data/.../files/imagefs/usr/lib/ld-linux-aarch64.so.1 (glibc loader)
#
# 调用方式 (由 fifo server 派发, 命令行 = startexec 拼出来的整条):
#   glibc-run winecfg
#   glibc-run /home/xuser/.wine/drive_c/foo.exe
#   glibc-run wine /path/foo.exe
#   glibc-run box64 <args>     # 完全透传

# ============================================================
# 1) 定位 box64
# ============================================================
IMAGEFS="${IMAGEFS:-/data/data/a.io.github.ewt45.winemulator/files/imagefs}"
BOX64=""
for cand in \
    "$IMAGEFS/usr/bin/box64" \
    "$IMAGEFS/usr/local/bin/box64" \
    "$IMAGEFS/bin/box64"; do
    if [ -x "$cand" ]; then
        BOX64="$cand"
        break
    fi
done
if [ -z "$BOX64" ]; then
    echo "glibc-run: box64 在 $IMAGEFS 里找不到" >&2
    exit 2
fi

# ============================================================
# 2) 定位 wine
# ============================================================
WINE=""
for cand in \
    "$IMAGEFS/opt/wine/bin/wine" \
    "$IMAGEFS/opt/wine/bin/wine64" \
    "$IMAGEFS/usr/bin/wine" \
    "$IMAGEFS/usr/bin/wine64"; do
    if [ -x "$cand" ]; then
        WINE="$cand"
        break
    fi
done
if [ -z "$WINE" ]; then
    echo "glibc-run: wine 在 $IMAGEFS 里找不到" >&2
    exit 2
fi

# ============================================================
# 3) TMPDIR / DISPLAY / XAUTHORITY
#   Android 进程跑, 直接用 Android tmpDir (跟 Termux:X11 同目录)
#   X11 socket = $TMPDIR/.X11-unix/X<DISPLAY>
#   XAUTHORITY = $TMPDIR/.Xauthority (Termux:X11 会创建)
# ============================================================
LINBOX_TMP="/data/data/a.io.github.ewt45.winemulator/cache/tmp"
export TMPDIR="${TMPDIR:-$LINBOX_TMP}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-$LINBOX_TMP}"
export DISPLAY="${DISPLAY:-:13}"
export XAUTHORITY="${XAUTHORITY:-$LINBOX_TMP/.Xauthority}"
# 不需要 xauth: Termux:X11 默认用 uid 检查,不需要 MIT-MAGIC-COOKIE
unset XAUTHORITY

# ============================================================
# 4) 找 ld-linux-aarch64.so.1 (glibc loader, wine 需要)
# ============================================================
LDSO=""
for cand in \
    "$IMAGEFS/usr/lib/ld-linux-aarch64.so.1" \
    "$IMAGEFS/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "$IMAGEFS/lib/ld-linux-aarch64.so.1" \
    "$IMAGEFS/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "$IMAGEFS/usr/lib64/ld-linux-aarch64.so.1"; do
    if [ -x "$cand" ]; then
        LDSO="$cand"
        break
    fi
done
if [ -z "$LDSO" ]; then
    LDSO="$(find "$IMAGEFS" -maxdepth 8 -type f -name 'ld-linux-aarch64.so.1' -executable 2>/dev/null | head -1)"
fi
echo "glibc-run: box64=$BOX64 wine=$WINE ldso=${LDSO:-(NOT FOUND)}" >&2

# ============================================================
# 5) box64 preset (LINBOX_GLIBC_PRESET 由用户设置, 这里给个默认)
# ============================================================
PRESET="${LINBOX_GLIBC_PRESET:-compatibility}"
case "$PRESET" in
    performance)
        export BOX64_DYNAREC=1
        export BOX64_DYNAREC_BIGBLOCK=3
        export BOX64_DYNAREC_FASTROUND=1
        export BOX64_DYNAREC_FASTNAN=1
        export BOX64_DYNAREC_SAFEFLAGS=0
        export BOX64_DYNAREC_CALLRET=1
        ;;
    intermediate)
        export BOX64_DYNAREC=1
        export BOX64_DYNAREC_BIGBLOCK=2
        export BOX64_DYNAREC_FASTROUND=1
        export BOX64_DYNAREC_FASTNAN=0
        export BOX64_DYNAREC_SAFEFLAGS=0
        export BOX64_DYNAREC_CALLRET=1
        ;;
    safe)
        export BOX64_DYNAREC=0
        ;;
    compatibility|*)
        export BOX64_DYNAREC=1
        export BOX64_DYNAREC_BIGBLOCK=2
        export BOX64_DYNAREC_FASTROUND=0
        export BOX64_DYNAREC_FASTNAN=0
        export BOX64_DYNAREC_SAFEFLAGS=0
        export BOX64_DYNAREC_CALLRET=0
        ;;
esac

# ============================================================
# 6) wine lib 路径 (LD_LIBRARY_PATH)
# ============================================================
WINE_LIB_CANDIDATES=""
for c in \
    "$IMAGEFS/opt/wine/lib" \
    "$IMAGEFS/opt/wine/lib64" \
    "$IMAGEFS/opt/wine/lib/wine/x86_64-unix" \
    "$IMAGEFS/opt/wine/lib64/wine/x86_64-unix" \
    "$IMAGEFS/usr/lib/x86_64-linux-gnu" \
    "$IMAGEFS/lib/x86_64-linux-gnu" \
    "$IMAGEFS/usr/lib/aarch64-linux-gnu" \
    "$IMAGEFS/usr/lib"; do
    if [ -d "$c" ]; then
        if [ -z "$WINE_LIB_CANDIDATES" ]; then
            WINE_LIB_CANDIDATES="$c"
        else
            WINE_LIB_CANDIDATES="$WINE_LIB_CANDIDATES:$c"
        fi
    fi
done
export LD_LIBRARY_PATH="$WINE_LIB_CANDIDATES:${LD_LIBRARY_PATH:-}"
export BOX64_LD_LIBRARY_PATH="$WINE_LIB_CANDIDATES"

# ============================================================
# 7) WINEDLLPATH
# ============================================================
WINE_DLL_PATH=""
for c in \
    "$IMAGEFS/opt/wine/lib/wine" \
    "$IMAGEFS/opt/wine/lib64/wine" \
    "$IMAGEFS/opt/wine/lib/wine/x86_64-unix"; do
    if [ -d "$c" ]; then
        WINE_DLL_PATH="$c"
        break
    fi
done
if [ -z "$WINE_DLL_PATH" ]; then
    for c in \
        "$IMAGEFS/opt/wine/lib" \
        "$IMAGEFS/opt/wine/lib64"; do
        if [ -d "$c" ]; then
            WINE_DLL_PATH="$c"
            break
        fi
    done
fi
export WINEDLLPATH="$WINE_DLL_PATH"

# ============================================================
# 8) WINELOADER
# ============================================================
WINELOADER=""
for c in \
    "$IMAGEFS/opt/wine/bin/wine-preloader" \
    "$IMAGEFS/opt/wine/bin/wine64-preloader" \
    "$IMAGEFS/opt/wine/lib/wine/x86_64-unix/wine-preloader" \
    "$IMAGEFS/opt/wine/lib64/wine/x86_64-unix/wine-preloader"; do
    if [ -x "$c" ]; then
        WINELOADER="$c"
        break
    fi
done
if [ -n "$WINELOADER" ]; then
    export WINELOADER
    echo "glibc-run: WINELOADER=$WINELOADER" >&2
fi

# ============================================================
# 9) wine 自身路径
# ============================================================
export WINEPREFIX="${WINEPREFIX:-$IMAGEFS/home/xuser/.wine}"
export WINEDEBUG="${WINEDEBUG:-}-all"
export WINEESYNC=1
export WINEFSYNC=0

# ============================================================
# 10) sysvshm (Android 进程跑, 库路径必须绝对路径)
# ============================================================
SYSVSHM_LIB="$IMAGEFS/usr/lib/aarch64-linux-gnu/libandroid-sysvshm.so"
if [ -f "$SYSVSHM_LIB" ]; then
    # 用绝对路径, 因为 Android LD 不会自己找 /imagefs/usr/lib/...
    export LD_PRELOAD="$SYSVSHM_LIB"
    # sm server 路径用 linboxapp 的 tmp (Android 进程能直接创建)
    export ANDROID_SYSVSHM_SERVER="$LINBOX_TMP/.sysvshm/SM0"
    mkdir -p "$LINBOX_TMP/.sysvshm" 2>/dev/null || true
fi

# ============================================================
# 11) 字体
# ============================================================
export FONTCONFIG_PATH="${FONTCONFIG_PATH:-$IMAGEFS/usr/etc/fonts}"

# ============================================================
# 12) pulseaudio
# ============================================================
export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"

# ============================================================
# 13) PATH
# ============================================================
export PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin:/system/bin:/system/xbin:${PATH:-}"
export BOX64_PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin"

# ============================================================
# 13.5) X11 连通性探测 + 关键 env 重新固化 (修复 musl box64 exec wine 时丢 env)
#
# 背景:
#   - proot 桌面可以显示 (xfce4 在 proot 内跑, 直接连 TMPDIR/.X11-unix/X13)
#   - glibc-run (Android 进程直接 exec box64+wine) 无法显示
#
#   glibc-run 在 Android 进程空间被 fifo server 派发,理论上 TMPDIR 和
#   proot 内一致 (同 inode), 走同一个 X11 socket。
#
#   但 box64 是 musl 静态链接, musl 在 execve 派生子进程时,出于安全
#   会丢弃一些 "进程启动时不应继承" 的变量, 包括:
#       LD_PRELOAD, LD_LIBRARY_PATH (取决于版本)
#   派发给 wine (glibc 链接) 后, 关键 env 可能被 musl 清掉,导致
#   wine 找不到 X11 socket / 找不到 libandroid-sysvshm.so / 找不到 wine libs。
#
# 修复: 在 exec box64 之前, 把所有关键 env **显式重新 export 一次**,
#       让 box64 启动时自带完整环境, exec wine 时透传。
#
# 同时: 加 X11 socket 探测, 如果 socket 文件不存在, 直接报错, 免得
#       wine 启动后默默连 :0 失败。
# ============================================================

# 重新固化关键 env (即使外面已经 export, 这里再 export 一次确保)
export TMPDIR="$LINBOX_TMP"
export XDG_RUNTIME_DIR="$LINBOX_TMP"
export DISPLAY="${DISPLAY:-:13}"
unset XAUTHORITY
unset XDG_SESSION_TYPE  # 避免被误判成 wayland

# X11 socket 探测
X11_SOCK_DIR="$TMPDIR/.X11-unix"
X11_SOCK_FILE="$X11_SOCK_DIR/X${DISPLAY#:}"
echo "glibc-run: X11 socket probe: $X11_SOCK_FILE" >&2
if [ -S "$X11_SOCK_FILE" ]; then
    echo "glibc-run: X11 socket OK ($(stat -c '%a %U:%G' "$X11_SOCK_FILE" 2>/dev/null || echo '?') )" >&2
else
    echo "glibc-run: WARNING X11 socket 找不到: $X11_SOCK_FILE" >&2
    echo "glibc-run: 列出 $X11_SOCK_DIR 看看:" >&2
    ls -la "$X11_SOCK_DIR" 2>&1 >&2 || true
fi

# 探测 wine lib path 真的存在
echo "glibc-run: WINEDLLPATH=$WINEDLLPATH" >&2
[ -d "$WINEDLLPATH" ] || echo "glibc-run: WARNING WINEDLLPATH 目录不存在" >&2
echo "glibc-run: LD_LIBRARY_PATH=$LD_LIBRARY_PATH" >&2
echo "glibc-run: BOX64_LD_LIBRARY_PATH=$BOX64_LD_LIBRARY_PATH" >&2

# WINEDEBUG 保留用户原设置 (默认 -all, 不强制开 X11 debug)
# 如果想看 wine 启动细节, 设环境变量 LINBOX_GLIBC_DEBUG=1 即可
if [ "${LINBOX_GLIBC_DEBUG:-0}" = "1" ]; then
    export WINEDEBUG="${WINEDEBUG:-+x11,+loaddll,+module}"
    echo "glibc-run: LINBOX_GLIBC_DEBUG=1, WINEDEBUG=$WINEDEBUG" >&2
fi

echo "glibc-run: DISPLAY=$DISPLAY TMPDIR=$TMPDIR PRESET=$PRESET" >&2

# chdir 到 imagefs 根, 让 box64/wine 找 dll/资源时用相对路径能对上
cd "$IMAGEFS" || cd /

# ============================================================
# 14) 解析参数, exec box64
# ============================================================
if [ "$#" -eq 0 ]; then
    if [ -x "$IMAGEFS/opt/wine/bin/winefile" ]; then
        exec "$BOX64" "$WINE" "$IMAGEFS/opt/wine/bin/winefile"
    else
        exec "$BOX64" winefile
    fi
fi

first="$1"
case "$first" in
    wine|winecfg|winefile|wineboot|wineserver|regsvr32|regedit|msiexec|cmd|start)
        exec "$BOX64" "$@"
        ;;
    box64|box86)
        exec "$BOX64" "$@"
        ;;
    *.exe|*.EXE|/*)
        exec "$BOX64" "$WINE" "$@"
        ;;
    *)
        exec "$BOX64" "$@"
        ;;
esac