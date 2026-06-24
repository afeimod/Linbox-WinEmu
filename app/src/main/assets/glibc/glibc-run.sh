#!/bin/sh
# glibc-run.sh
#
# 在 proot 内的 glibc chroot 环境下启动 box64+wine。
#
# v4 调用方式 (mobox 风格):
#   - 这个脚本不是直接被用户调用, 而是由 fifo_exec_server.sh 派发:
#       startexec.sh "box64 $PROOT_PATH/foo.exe"
#       startexec.sh "box64 wine $PROOT_PATH/foo.exe"
#       startexec.sh "glibc-run $PROOT_PATH/foo.exe"   # (兼容旧 API)
#       startexec.sh "glibc-run winecfg"
#   - fifo_exec_server 用 sh -c 执行, 不带额外 env, 但 proot 启动
#     时已经注入了 DISPLAY, PULSE_SERVER, LD_LIBRARY_PATH 等
#   - 脚本里只做"找 box64/wine + 找 LDSO + 准备 env" 的事, 真正
#     启动 box64 用 exec (不 fork sh, 避免多一层 process)
#
# 为什么这样设计:
#   - glibc-run.sh 是**解析器**, 不是执行器: 它把 "glibc-run <args>"
#     翻译成 "box64 <args>" (加上 wine 前缀如果需要的话)
#   - 真正启动 box64 的 exec 在脚本最后, 由 sh -c 派发, fifo_exec_server
#     是 & 后台启动, 所以 wine 在 proot 内常驻
#   - 跟 mobox 的 wine 启动方式一致: 一次 fork, exec 完即终
#
# 用法 (proot 内):
#   glibc-run                                  # 启动 winefile
#   glibc-run winecfg                          # 启动 winecfg
#   glibc-run /home/xuser/.wine/drive_c/foo.exe    # 启动某 exe
#   glibc-run wine /home/xuser/.wine/drive_c/foo.exe  # 显式 wine
#   glibc-run box64 /imagefs/opt/wine/bin/wine file.exe  # 完全透传

set -e

IMAGEFS="/imagefs"

# 1) 定位 box64。winlator 风格: usr/bin/box64 是首选, 但有些包
#    装到 usr/local/bin/, 两个路径都要查。
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
    echo "glibc-run: box64 在 imagefs 里找不到" >&2
    exit 2
fi

# 2) 定位 wine。winlator 默认装在 opt/wine/bin/wine
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
    echo "glibc-run: wine 在 imagefs 里找不到" >&2
    exit 2
fi

# 3) TMPDIR / DISPLAY
export TMPDIR="${TMPDIR:-/tmp}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"
export DISPLAY="${DISPLAY:-:13}"

# 4) 找 ld-linux-aarch64.so.1。不同 imagefs 把 loader 放在不同
#    目录, 默认是 /usr/lib/, winlator 的 imagefs 可能在
#    aarch64-linux-gnu/ 子目录。
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

# 5) box64 preset (由 Proot.kt 注入)
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

# 6) wine lib 路径
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

# 7) WINEDLLPATH
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

# 8) WINELOADER
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

# 9) wine 自身需要的路径
export WINEPREFIX="${WINEPREFIX:-$IMAGEFS/home/xuser/.wine}"
export WINEDEBUG="${WINEDEBUG:-}-all"
export WINEESYNC=1
export WINEFSYNC=0

# 10) sysvshm
SYSVSHM_LIB="$IMAGEFS/usr/lib/aarch64-linux-gnu/libandroid-sysvshm.so"
if [ -f "$SYSVSHM_LIB" ]; then
    export LD_PRELOAD="libandroid-sysvshm.so"
    export ANDROID_SYSVSHM_SERVER="$IMAGEFS/tmp/.sysvshm/SM0"
fi

# 11) 字体
export FONTCONFIG_PATH="${FONTCONFIG_PATH:-$IMAGEFS/usr/etc/fonts}"

# 12) pulseaudio
export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"

# 13) PATH
export PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin:/usr/local/bin:/usr/bin:/bin:${PATH:-}"
export BOX64_PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin"

echo "glibc-run: DISPLAY=$DISPLAY TMPDIR=$TMPDIR PRESET=$PRESET" >&2

# ============================================================
# 14) 解析参数, exec box64
#
# 用法:
#   glibc-run                      -> box64 wine winefile
#   glibc-run winecfg              -> box64 wine winecfg (wine 子命令)
#   glibc-run /path/to/foo.exe     -> box64 wine /path/to/foo.exe
#   glibc-run wine /path/foo.exe   -> box64 wine /path/foo.exe (显式)
#   glibc-run box64 wine foo       -> box64 wine foo (完全透传)
#   glibc-run ls /imagefs          -> box64 ls /imagefs (其他 binary)
# ============================================================
if [ "$#" -eq 0 ]; then
    # (a) 默认 winefile
    if [ -x "$IMAGEFS/opt/wine/bin/winefile" ]; then
        exec "$BOX64" "$WINE" "$IMAGEFS/opt/wine/bin/winefile"
    else
        exec "$BOX64" winefile
    fi
fi

first="$1"
case "$first" in
    wine|winecfg|winefile|wineboot|wineserver|regsvr32|regedit|msiexec|cmd|start)
        # (b) wine 子命令字面量
        exec "$BOX64" "$@"
        ;;
    box64|box86)
        # (c) 完全透传 (用户显式 box64 / box86)
        exec "$BOX64" "$@"
        ;;
    *.exe|*.EXE|/*)
        # (d) 绝对路径或 .exe
        exec "$BOX64" "$WINE" "$@"
        ;;
    *)
        # (e) 其他 binary
        exec "$BOX64" "$@"
        ;;
esac
