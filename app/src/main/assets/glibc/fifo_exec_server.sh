#!/bin/sh
# fifo_exec_server.sh
#
# proot 内跑的 FIFO server (mobox 风格, 跟用户在 termux 的脚本一致)。
#
# 关键架构 (用户原话):
#   - app 创建 FIFO 管道和锁文件并启动 fifo 服务
#   - 启动 proot 时正确启 fifo_exec_server
#   - proot 终端才可以用 startexec 写命令到 FIFO
#   - glibc 编译时绑绝对路径 /data/data/.../files/imagefs,
#     proot 内能看到 (--bind=imagefs:/imagefs), 用 glibc-run.sh
#     启 box64+wine
#
# 角色分工:
#   [proot shell 启动]
#     └── start.sh 调本脚本 (后台 &)
#           ├── 创建 /tmp/.exec.fifo      (proot 内 /tmp = host tmpDir)
#           ├── 创建 /tmp/.exec-lock
#           └── while [ -e LOCK ]; do
#                 read cmd < FIFO
#                 /imagefs/usr/local/bin/glibc-run.sh $cmd &
#               done
#
#   [proot 内 xfce4 terminal]
#     └── 用户跑: startexec "cmd"
#           └── echo "$*" > /tmp/.exec.fifo
#
# 用法 (proot 内):
#   startexec glibc-run winecfg
#   startexec glibc-run /home/xuser/.wine/drive_c/foo.exe
#   startexec glibc-run wine /path/foo.exe

# ============================================================
# 路径
# ============================================================
TMP="${TMPDIR:-/tmp}"
FIFO="$TMP/.exec.fifo"
LOCK="$TMP/.exec-lock"
LOG="$TMP/.exec.log"

# imagefs 内 glibc-run (proot bind 后 /imagefs 可见)
GLIBC_RUN="/imagefs/usr/local/bin/glibc-run.sh"

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

    ts="$(date +%H:%M:%S 2>/dev/null || echo unknown)"
    echo "[$ts] exec: $cmd" >> "$LOG"
    echo "fifo_exec_server: 派发 cmd=[$cmd]" >&2

    # 派发: 直接 exec glibc-run (server 跑在 proot 内, 能访问 /imagefs)
    #
    # glibc-run 自己处理参数分流 (winecfg / .exe / box64 / 透传),
    # 它内部会找 box64+wine+lib, 设 LD_LIBRARY_PATH, exec box64 wine。
    #
    # 用 eval 重新做 shell 词法分析, 这样 startexec 拼出来的
    # 含空格/引号的参数能正确传透。
    #
    # 后台跑 (&): wine 本身是常驻进程, 派发后立刻回来读下一条命令
    # stdin 重定向避免 wine 等交互阻塞 FIFO
    if [ -x "$GLIBC_RUN" ]; then
        eval "$GLIBC_RUN $cmd" </dev/null >/dev/null 2>&1 &
    else
        echo "fifo_exec_server: glibc-run 找不到: $GLIBC_RUN" >&2
    fi
done

# 锁文件被删了, server 退出
exec 3<&- 2>/dev/null || true
rm -f "$FIFO" "$LOCK" 2>/dev/null || true
echo "fifo_exec_server: 退出" >&2
exit 0