#!/bin/sh
# glibc-run.sh
#
# 在 linbox 的 proot 容器内启动 box64+wine,把 Windows 程序显示到
# Termux:X11 上。
#
# 为什么这个脚本能解决问题:
#   proot 启动时已经把 host 的 $Consts.tmpDir (cacheDir/tmp) bind 到
#   rootfs 的 /tmp。所以 proot 内部访问 /tmp/.X11-unix/X13 就是
#   Termux:X11 真实的 socket,box64+wine 在 proot 里启动后, X11 客户端
#   库按 DISPLAY=:13 找 $TMPDIR/.X11-unix/X13,直接连上。
#
#   box64 的 linterp 在 ImageFsInstaller.rewriteLinterps() 里被改成了
#   /imagefs/usr/lib/ld-linux-aarch64.so.1 (proot 路径),proot bind 把
#   imagefs 挂到 /imagefs,所以 linterp 加载也工作。
#
#   即使 box64 在 proot 里运行的 syscall 会被 ptrace 翻译,因为 linterp
#   已经是 glibc loader (不是 musl 静态),早期那批会 SIGSEGV 的 syscall
#   (set_robust_list 等) 由 glibc ld.so 处理,而不是 box64 主二进制,崩
#   溃概率大幅下降。
#
# 用法 (在 proot shell 里跑):
#   glibc-run                                          # 启动 wine 助手(winefile/winecfg)
#   glibc-run winecfg                                  # 同上,显式传命令
#   glibc-run /home/xuser/.wine/drive_c/Program.exe    # 直接跑某个 exe
#   glibc-run /path/to/file.exe -arg1 -arg2            # 带参数
#
# 推荐: 在 linbox 设置 → PRoot 参数 → "启动后执行命令" 里填:
#   glibc-run /home/xuser/.wine/drive_c/<你的游戏>.exe
# 这样容器一启动就自动跑游戏。

set -e

IMAGEFS="/imagefs"

# 1) 定位 box64/wine
BOX64=""
for cand in \
    "$IMAGEFS/usr/local/bin/box64" \
    "$IMAGEFS/usr/bin/box64" \
    "$IMAGEFS/bin/box64"; do
    if [ -x "$cand" ]; then
        BOX64="$cand"
        break
    fi
done
if [ -z "$BOX64" ]; then
    echo "glibc-run: box64 在 imagefs 里找不到。" >&2
    echo "请确认 imagefs 已解压 (proot 启动时会自动解压 assets/imagefs/imagefs.tzst)" >&2
    echo "已检查的位置:" >&2
    echo "  $IMAGEFS/usr/local/bin/box64" >&2
    echo "  $IMAGEFS/usr/bin/box64" >&2
    echo "  $IMAGEFS/bin/box64" >&2
    exit 2
fi

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
    echo "glibc-run: wine 在 imagefs 里找不到。" >&2
    echo "已检查的位置:" >&2
    echo "  $IMAGEFS/opt/wine/bin/wine" >&2
    echo "  $IMAGEFS/opt/wine/bin/wine64" >&2
    echo "  $IMAGEFS/usr/bin/wine" >&2
    echo "  $IMAGEFS/usr/bin/wine64" >&2
    exit 2
fi

# 2) 共享 tmp: 关键是这一行。
#    proot 已经把 host 的 tmpDir bind 到 /tmp。这里只是显式设 TMPDIR
#    让 wine 客户端库去找 /tmp/.X11-unix/X13。
export TMPDIR="/tmp"
export XDG_RUNTIME_DIR="/tmp"

# 3) X11 server socket。linbox 启动 Termux:X11 时用的是 :13
#    (参见 emu/manager/DisplayManager.kt: cmdLine = ".. :13")
export DISPLAY="${DISPLAY:-:13}"

# 4) box64 preset (由 Proot.kt 在 attach 时通过 LINBOX_GLIBC_PRESET 注入)
#    用户在设置里改 box64_preset 这里就会生效。
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

# 5) 让 wine 客户端库能加载 glibc 动态库。imagefs 里 box64 用
#    /imagefs/usr/lib/ld-linux-aarch64.so.1 作 linterp,加载的是
#    glibc loader。wine 自己也是 glibc 二进制,需要找同样这套 loader。
export LD_LIBRARY_PATH="$IMAGEFS/usr/lib/x86_64-linux-gnu:$IMAGEFS/lib/x86_64-linux-gnu:$IMAGEFS/usr/lib/aarch64-linux-gnu:$IMAGEFS/usr/lib:${LD_LIBRARY_PATH:-}"
export BOX64_LD_LIBRARY_PATH="$IMAGEFS/usr/lib/x86_64-linux-gnu:$IMAGEFS/lib/x86_64-linux-gnu"

# 6) wine 自身需要的路径
export WINEPREFIX="${WINEPREFIX:-$IMAGEFS/home/xuser/.wine}"
export WINEDEBUG="${WINEDEBUG:-}-all"
export WINEESYNC=1
export WINEFSYNC=0

# 7) sysvshm — wine 用 System V IPC 共享显存,proot 通过
#    --sysvipc 启用了但还需要 client 端连 server。winlator 的
#    imagefs 里有 libandroid-sysvshm.so, 如果存在就 LD_PRELOAD 它。
SYSVSHM_LIB="$IMAGEFS/usr/lib/aarch64-linux-gnu/libandroid-sysvshm.so"
if [ -f "$SYSVSHM_LIB" ]; then
    export LD_PRELOAD="libandroid-sysvshm.so"
    export ANDROID_SYSVSHM_SERVER="$IMAGEFS/tmp/.sysvshm/SM0"
fi

# 8) 字体
export FONTCONFIG_PATH="${FONTCONFIG_PATH:-$IMAGEFS/usr/etc/fonts}"

# 9) pulseaudio: linbox 把 pulse server 暴露在 tcp:127.0.0.1:4713
export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"

# 10) 组装命令
#     用法: glibc-run <args>
#
#     分四种情况:
#       a) 无参数                       -> box64 wine winefile  (开 winefile)
#       b) 第一参数是 wine 子命令字面量    -> box64 <cmd>          (wine 子命令本身就是独立二进制)
#       c) 第一参数是 exe 或绝对路径      -> box64 wine <args>    (作为 wine 启动)
#       d) 其他 (如 glibc-run ls /imagefs)-> box64 <args>          (当独立二进制跑)
#
#     这样用户在快捷启动设置里可以写:
#         glibc-run winecfg
#         glibc-run /home/xuser/.wine/drive_c/foo.exe
#         glibc-run regedit
echo "glibc-run: DISPLAY=$DISPLAY TMPDIR=$TMPDIR PRESET=$PRESET" >&2

if [ "$#" -eq 0 ]; then
    # (a) 默认 winefile
    if [ -x "$IMAGEFS/opt/wine/bin/winefile" ]; then
        echo "glibc-run: exec $BOX64 $WINE $IMAGEFS/opt/wine/bin/winefile" >&2
        exec "$BOX64" "$WINE" "$IMAGEFS/opt/wine/bin/winefile"
    else
        echo "glibc-run: exec $BOX64 winefile" >&2
        exec "$BOX64" winefile
    fi
fi

first="$1"
case "$first" in
    wine|winecfg|winefile|wineboot|wineserver|regsvr32|regedit|msiexec|cmd|start)
        # (b) wine 子命令本身。wine 子命令都是 wine 同二进制的
        # hardlink/symlink, 直接 box64 <cmd> ... 让 box64 拦截 exec 并
        # 自动重定向到 wine (winlator 同样做法)。原始 argv 是
        # [winecfg, ...], 不需要拆开。
        echo "glibc-run: exec $BOX64 $*" >&2
        exec "$BOX64" "$@"
        ;;
    *.exe|*.EXE|/*)
        # (c) 是 exe 路径或绝对路径, 走 box64 wine <args>
        echo "glibc-run: exec $BOX64 $WINE $*" >&2
        exec "$BOX64" "$WINE" "$@"
        ;;
    *)
        # (d) 其他,当独立二进制跑
        echo "glibc-run: exec $BOX64 $*" >&2
        exec "$BOX64" "$@"
        ;;
esac
