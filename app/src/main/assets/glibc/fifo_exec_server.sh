#!/bin/sh
# fifo_exec_server.sh
#
# 在 linbox 启的外层 proot (xfce4 桌面的 proot) 内跑的 FIFO server。
# 监听 $TMPDIR/.exec.fifo (外层 proot 视角下的 /tmp/ = host $TMPDIR)。
#
# 派发时启**新 proot 进程**进入 glibc 镜像 (imagefs bind 到 /imagefs),
# 在新 proot 内的 glibc env 跑 box64+wine。
#
# 关键 mobox 风格架构:
#   [Android linbox] 启 proot (linbox rootfs)
#     └── [外层 proot] 跑 xfce4 桌面 (linbox alias 启 startxfce4)
#         ├── xfce4 桌面的 terminal 是外层 proot 内的 sh
#         │     └── 用户跑 "startexec X" 写一行到 .exec.fifo
#         └── [fifo server 后台跑] 循环从 .exec.fifo 读
#               └── 派发: 在外层 proot 启新 proot 进程进 imagefs
#                     └── 新 proot 内: box64+wine 跑 (glibc env)

# 用 set -e 但允许空循环
# 实际故意不用 set -e, server 是常驻, 错误不能让它退出

# ============================================================
# 路径: 外层 proot 视角下 /tmp = host 的 $TMPDIR
# (Proot.kt 启 proot 时 --bind=${tmpdir.absolutePath}:/tmp)
# ============================================================
TMP="${TMPDIR:-/tmp}"
FIFO="$TMP/.exec.fifo"
LOCK="$TMP/.exec-lock"
LOG="$TMP/.exec.log"

# 启动时清理残留
rm -f "$FIFO" "$LOCK" 2>/dev/null

# 创建 FIFO 和 lock
mkfifo "$FIFO" 2>/dev/null || true
chmod 0666 "$FIFO" 2>/dev/null || true
touch "$LOCK" || { echo "fifo_exec_server: 无法创建 $LOCK" >&2; exit 1; }

# FIFO 保持打开 (read 不会因没人写而 EOF)
exec 3<>"$FIFO"

echo "fifo_exec_server: 启动 pid=$$ FIFO=$FIFO" >&2
echo "[$(date +%H:%M:%S 2>/dev/null || echo unknown)] server started pid=$$" > "$LOG"

# ============================================================
# 主循环: 锁文件存在就一直跑
# ============================================================
while [ -e "$LOCK" ]; do
    # 读一行命令; read 返回 0 成功, 非 0 EOF
    cmd=""
    read -r cmd <&3 || {
        # EOF: FIFO 没人写或被关。重建 FIFO, 继续。
        exec 3<&- 2>/dev/null || true
        rm -f "$FIFO" 2>/dev/null || true
        mkfifo "$FIFO" 2>/dev/null || {
            sleep 0.1
            continue
        }
        chmod 0666 "$FIFO" 2>/dev/null || true
        exec 3<>"$FIFO"
        continue
    }

    # 跳过空行
    [ -z "$cmd" ] && continue

    # 派发: 在外层 proot 启新 proot 进程, 进 glibc 镜像
    # 新 proot 用同一份 linbox rootfs (--rootfs) + bind imagefs 到 /imagefs
    # 但 cmd 直接在外层 proot 跑 (因为 cmd 已经是 "proot ... -- /imagefs/glibc-run.sh X"
    #   或者 "/imagefs/usr/bin/box64 ...")
    #
    # 关键: cmd 是"在外层 proot 视角下能 exec 的命令", 例如:
    #   /imagefs/usr/bin/box64 /imagefs/opt/wine/bin/wine winecfg
    #   /imagefs/glibc-run.sh winecfg
    # 这些命令在外层 proot 内 exec, 但 /imagefs 下的 box64 是 musl 链接
    # (兼容外层 proot 的 libc), box64 启动 wine 时进入 glibc loader
    # (因为 wine 的 linterp 指向 /imagefs/usr/lib/ld-linux-aarch64.so.1
    # = glibc loader, 加载 glibc .so 库)
    ts="$(date +%H:%M:%S 2>/dev/null || echo unknown)"
    echo "[$ts] exec: $cmd" >> "$LOG"
    # 不重定向 stdout/stderr — wine 自己的输出用户需要看
    /bin/sh -c "$cmd" </dev/null &
done

# 锁文件被删了, server 退出
exec 3<&- 2>/dev/null || true
rm -f "$FIFO" "$LOCK" 2>/dev/null || true
echo "fifo_exec_server: 退出" >&2
exit 0
