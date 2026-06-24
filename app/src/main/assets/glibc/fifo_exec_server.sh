#!/bin/sh
# fifo_exec_server.sh
#
# Android 进程启的 FIFO server (在 proot 内跑, 因为 Android 启 proot
# 时已经把 imagefs bind 到 /imagefs)。
#
# 关键架构 (用户原话):
#   Android 负责:
#     1) 启 proot (自带的静态 proot 二进制)
#     2) bind imagefs 到 /imagefs (让 proot 内有 glibc 镜像)
#     3) 启 fifo server (这个脚本) 监听 .exec.fifo
#     4) 收到命令, 用 proot 内的 sh -c 派发执行
#   proot 内 (xfce4 桌面的 terminal) 负责:
#     1) 跑 "startexec glibc-run *.exe" — 写一行到 FIFO
#     2) fifo server 派发, glibc-run 准备 box64+wine 环境, exec box64
#   proot 内的 glibc-run 负责:
#     1) 找 box64 / wine / LDSO
#     2) 设环境变量 (DISPLAY, PULSE_SERVER, LD_LIBRARY_PATH, WINEDLLPATH, WINELOADER)
#     3) exec box64 wine <args>

# ============================================================
# 路径: proot 视角下 /tmp = host 的 $TMPDIR
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

    # 派发: proot 内的 sh -c 跑命令
    # 命令形如:
    #   /imagefs/glibc-run.sh /home/xuser/.wine/drive_c/foo.exe
    #   /imagefs/usr/bin/box64 /imagefs/opt/wine/bin/wine winecfg
    # 这些命令在 proot 内 exec, box64 是 proot 内 (linbox rootfs) musl
    # 链接的 box64, 它启动 wine 时进入 glibc loader
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
