#!/bin/sh
# glibc-run.sh
#
# glibc-run 在 proot 内的 imagefs 镜像下准备 box64+wine 环境, 然后 exec。
#
# 调用方式 (proot 内, xfce4 桌面 terminal):
#   glibc-run                                  # 默认 winefile
#   glibc-run winecfg                          # wine 子命令
#   glibc-run /path/to/foo.exe                 # 启动某个 exe
#   glibc-run wine /path/to/foo.exe            # 显式 wine
#
# 关键: glibc-run 在 proot 内的 imagefs 镜像下跑
#   - /imagefs 是 imagefs bind mount
#   - /imagefs/usr/bin/box64: musl 链接的 box64 (兼容外层 proot)
#   - /imagefs/opt/wine/bin/wine: glibc 链接的 wine (启动时进入 glibc loader)
#   - /imagefs/usr/lib/ld-linux-aarch64.so.1: glibc loader
#   - box64 启动 wine 时, wine 的 linterp 加载上面的 glibc loader,
#     wine 进入 glibc env, 加载 glibc .so 库

# 用 set -e 但允许 exec 失败
# 实际故意不用 set -e, 错误信息要打印

# ============================================================
# 1) 定位 box64
# ============================================================
IMAGEFS="${IMAGEFS:-/imagefs}"
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
# 3) TMPDIR / DISPLAY
# ============================================================
export TMPDIR="${TMPDIR:-/tmp}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"
export DISPLAY="${DISPLAY:-:13}"

# ============================================================
# 4) 找 ld-linux-aarch64.so.1
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
# 5) box64 preset
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
# 10) sysvshm
# ============================================================
SYSVSHM_LIB="$IMAGEFS/usr/lib/aarch64-linux-gnu/libandroid-sysvshm.so"
if [ -f "$SYSVSHM_LIB" ]; then
    export LD_PRELOAD="libandroid-sysvshm.so"
    export ANDROID_SYSVSHM_SERVER="$IMAGEFS/tmp/.sysvshm/SM0"
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
# 13) PATH (让 wine 启动后能找到 wineserver 等)
# ============================================================
export PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin:/usr/local/bin:/usr/bin:/bin:${PATH:-}"
export BOX64_PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin"

echo "glibc-run: DISPLAY=$DISPLAY TMPDIR=$TMPDIR PRESET=$PRESET" >&2

# ============================================================
# 14) 解析参数, exec box64
# ============================================================
#   glibc-run                  -> box64 wine winefile
#   glibc-run winecfg          -> box64 wine winecfg (wine 子命令)
#   glibc-run /path/foo.exe    -> box64 wine /path/foo.exe
#   glibc-run wine /path/foo.exe -> box64 wine /path/foo.exe (显式)
#   glibc-run box64 <args>     -> box64 <args> (完全透传)
#   glibc-run ls /imagefs      -> box64 ls /imagefs (其他 binary)
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
