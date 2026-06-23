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
#
# 注意: 这三个路径的顺序很关键。winlator 的 imagefs 解压后 box64
# 默认在 usr/bin/box64 (不是 usr/local/bin/box64 — 老 README 错的)。
# 见 ImageFs.kt:isValid 注释 "usr/bin/box64, NOT usr/local/bin/box64"。
# 但有些打包者把 box64 装在 usr/local/bin/ (跟 Alpine 等发行版风格),
# 所以两个路径都要查。find 命令在 imagefs 里搜也能定位非标位置。
#
# 先尝试 cand 列表 (常规位置),如果都不行再 fallback 到 find 全文搜。
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
# Fallback: 用 find 搜 imagefs (部分非标布局使用)
if [ -z "$BOX64" ]; then
    BOX64="$(find "$IMAGEFS" -maxdepth 6 -type f -name box64 -executable 2>/dev/null | head -1)"
fi
if [ -z "$BOX64" ]; then
    echo "glibc-run: box64 在 imagefs 里找不到。" >&2
    echo "请确认 imagefs 已解压 (proot 启动时会自动解压 assets/imagefs/imagefs.tzst)" >&2
    echo "已检查的位置:" >&2
    echo "  $IMAGEFS/usr/bin/box64" >&2
    echo "  $IMAGEFS/usr/local/bin/box64" >&2
    echo "  $IMAGEFS/bin/box64" >&2
    echo "" >&2
    echo "诊断信息 (列出 imagefs 顶层):" >&2
    ls -la "$IMAGEFS" 2>/dev/null >&2 || echo "  (无法列出 $IMAGEFS)" >&2
    echo "" >&2
    echo "诊断信息 (imagefs 里所有可执行的 box64-like 二进制):" >&2
    find "$IMAGEFS" -maxdepth 8 -type f -name 'box*' -executable 2>/dev/null | head -5 >&2
    exit 2
fi

# 验证 box64 能真正 exec。"-x 文件存在可执行位" 不够,还要 kernel 能
# 加载它的 PT_INTERP (ld-linux-aarch64.so.1)。如果在容器外能用
# ldd 看 linterp 路径, 但 kernel exec 报 not found, 说明 linterp
# 被改成了一个在 imagefs 里不存在的路径 (ImageFsInstaller.rewriteLinterps
# 会把 linterp 改成 /imagefs/usr/lib/ld-linux-aarch64.so.1, 但有的
# imagefs 实际 layout 是 usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1)。
# 这里只打印诊断, 不能直接 fix linterp (那需要宿主 App 重跑一次
# installIfNeeded 才能重新 patchelf)。
echo "glibc-run: box64 resolved to $BOX64" >&2
# 查 imagefs 里 ld-linux-aarch64.so.1 的所有可能位置。
# 不同镜像把 loader 放在不同目录, 默认是 /usr/lib/, winlator 的 imagefs
# 可能在 aarch64-linux-gnu/ 子目录。
LDSOS=""
for cand in \
    "$IMAGEFS/usr/lib/ld-linux-aarch64.so.1" \
    "$IMAGEFS/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "$IMAGEFS/lib/ld-linux-aarch64.so.1" \
    "$IMAGEFS/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "$IMAGEFS/usr/lib64/ld-linux-aarch64.so.1"; do
    if [ -x "$cand" ]; then
        LDSOS="$LDSOS $cand"
    fi
done
LDSO="$(echo $LDSOS | awk '{print $1}')"
if [ -z "$LDSO" ]; then
    LDSO="$(find "$IMAGEFS" -maxdepth 8 -type f -name 'ld-linux-aarch64.so.1' -executable 2>/dev/null | head -1)"
fi
echo "glibc-run: ld-linux-aarch64.so.1 resolved to ${LDSO:-(NOT FOUND)}" >&2

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

# PATH: wine 启动后需要在 PATH 里能找到 wine-preloader (wine 自己的
# loader) 和 wine 自己的脚本 (wineserver 等)。winlator 默认是
# imagefs/bin:$imagefs/usr/bin 等。
export PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin:/usr/local/bin:/usr/bin:/bin:${PATH:-}"
export BOX64_PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin"

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
#
# 顺序很重要 (靠前的优先)。wine 启动时需要找:
#   - /imagefs/opt/wine/lib/      — wine 自己的 glibc/libc/wine 内核
#   - /imagefs/opt/wine/lib/wine/x86_64-unix/  — wine unix lib dir
#   - /imagefs/usr/lib/x86_64-linux-gnu/      — imagefs 的 x86_64 lib (glibc, libstdc++)
#   - /imagefs/lib/x86_64-linux-gnu/           — 备选
# 缺失任何一个都可能导致 "could not exec the wine loader" (wine 主
# binary exec 后找不到 preloader/loader)。
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

# WINEDLLPATH: 告诉 wine 在哪里找 ntdll/kernel32 等 DLL。这是 winlator
# 的标准做法, 让 BOX64_LD_LIBRARY_PATH 同时覆盖 wine lib 路径。如果
# 这个变量空, wine 启动后会报 "could not exec the wine loader" 或
# "wine: cannot find L\"C:\\windows\\system32\\..." 错误。
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
    # 退而求其次
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

# WINELOADER: 告诉 wine 主 binary 用哪个 ELF 作为 preloader。新版 wine
# (>=8.x) 的 preloader 通常是独立的 wine-preloader 二进制。如果 imagefs
# 里没有这个文件, wine 会报 "could not exec the wine loader"。我们
# 检测到存在就设上, wine 主 binary exec 时会直接用它。
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

# ============================================================
# 执行包装: 优先 exec BOX64,失败则 fallback 到 ld.so 手动加载。
#
# 为什么需要这个 fallback:
#   一些 imagefs 解压后, box64 的 PT_INTERP (ld-linux-aarch64.so.1)
#   路径不对 (例如指向了原镜像里的 /lib/ 而不是 /imagefs/usr/lib/)。
#   直接 exec box64 时 kernel 会报 "not found" (POSIX 把 ENOEXEC
#   表为 not found)。
#
#   ImageFsInstaller.rewriteLinterps 应该会把这些 linterp 改成
#   /imagefs/usr/lib/ld-linux-aarch64.so.1, 但万一不完整, 脚本可以
#   fallback: 用 imagefs 里实际找到的 ld-linux-aarch64.so.1 手动
#   启动 box64,这样即使 PT_INTERP 错了也能跑。
#
# 用法: do_exec <binary> <args...>
#   尝试 1: 直接 exec <binary>
#   尝试 2: 如果 LDSO 存在,exec <LDSO> --library-path ... <binary> <args>
# ============================================================
do_exec() {
    local bin="$1"; shift
    # 先尝试直接 exec。set +e 临时关 set -e, 让 exec 失败不会 abort。
    set +e
    echo "glibc-run: exec $bin $*" >&2
    "$bin" "$@"
    rc=$?
    if [ $rc -eq 0 ]; then
        exit 0
    fi
    echo "glibc-run: 直接启动 $bin 失败 (rc=$rc), 尝试 ld.so fallback..." >&2

    # Fallback: 用 ld.so 手动加载。
    #
    # 为什么不传 --library-path:
    #   有些 glibc ld.so (特别是老版本/ARM 定制版) 对 --library-path
    #   选项处理不一致: 有的会把 --library-path + 路径 + 接下来 1 个
    #   token 当成"路径 + 程序", 把剩余的 argv 偏移。box64 启动后
    #   看到的 argv 就少了 argv[0] 和 wine 这两项。
    #
    #   最安全的做法: 让 ld.so 通过 LD_LIBRARY_PATH env 找 box64 的
    #   依赖, 不传 --library-path 选项。这样 ld.so 收到的 argv 是
    #   [ld.so, bin, args...], 加载 bin 时把 args 给 bin, bin 收到
    #   argv = [bin, args...], 这正是我们期望的 (box64 argv[1] = wine)。
    if [ -n "$LDSO" ] && [ -x "$LDSO" ]; then
        # 临时设个 LD_LIBRARY_PATH 供 ld.so 找 aarch64 deps, 不影响
        # box64 的 BOX64_LD_LIBRARY_PATH (box64 读自己的 env)。
        LIBPATH_FOR_LDSO="$IMAGEFS/usr/lib/aarch64-linux-gnu:$IMAGEFS/usr/lib:$IMAGEFS/lib/aarch64-linux-gnu:$IMAGEFS/lib:${LD_LIBRARY_PATH:-}"
        echo "glibc-run: fallback exec $LDSO $bin $*" >&2
        LD_LIBRARY_PATH="$LIBPATH_FOR_LDSO" exec "$LDSO" "$bin" "$@"
    fi

    echo "glibc-run: 没有可用的 ld.so fallback,退出 rc=$rc" >&2
    set -e
    return $rc
}

if [ "$#" -eq 0 ]; then
    # (a) 默认 winefile
    if [ -x "$IMAGEFS/opt/wine/bin/winefile" ]; then
        do_exec "$BOX64" "$WINE" "$IMAGEFS/opt/wine/bin/winefile"
        exit $?
    else
        do_exec "$BOX64" winefile
        exit $?
    fi
fi

first="$1"
case "$first" in
    wine|winecfg|winefile|wineboot|wineserver|regsvr32|regedit|msiexec|cmd|start)
        do_exec "$BOX64" "$@"
        exit $?
        ;;
    *.exe|*.EXE|/*)
        do_exec "$BOX64" "$WINE" "$@"
        exit $?
        ;;
    *)
        do_exec "$BOX64" "$@"
        exit $?
        ;;
esac
