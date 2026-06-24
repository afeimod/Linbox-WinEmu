#!/bin/sh
# fifo_exec_server.sh
#
# 真正的 mobox 风格 FIFO server (linbox v5 适配版)。
#
# linbox 没有 termux, 所以这个脚本**直接跑在 proot 内** (imagefs bind
# 到 /imagefs 后, proot 启动时 exec 它)。
#
# 调用链 (跟 mobox 一致, 但 server 在 proot 内):
#   [Android linbox 进程]
#      └── 启 proot (自带的静态 proot 二进制, --bind=Consts.tmpDir:/tmp)
#   [proot 内 (linbox rootfs, busybox/ash 系)]
#      ├── fifo server (这个脚本, 常驻)
#      └── (未来) xfce4 桌面的 terminal 跑 startexec 写 FIFO
#   [proot fifo server 读到]
#      └── sh -c "box64 wine winecfg" & 派发
#        (proot 内的 sh 派发, box64 在 proot 内跑, box64 启动 wine
#         时进入 glibc loader 加载 glibc 二进制)
#
# 关键: 这个脚本的 sh shebang 是 proot 内的 busybox ash
#       mobox 原版用 bash (termux 内), linbox 用 sh (busybox, 更轻量)
#
# FIFO 路径: 用 $TMPDIR (proot 启动时设 = /tmp, --bind=Consts.tmpDir:/tmp)
#   - proot 视角下:  /tmp/.exec.fifo (= host Consts.tmpDir/.exec.fifo)
#   - Android 视角:  Consts.tmpDir/.exec.fifo
#   - xfce4 terminal (proot 内) 视角:  /tmp/.exec.fifo
#   三者写的是同一个文件

# 故意不用 set -e, 这个 server 是常驻, 错误要打印但不能退出

# ============================================================
# 路径: proot 视角下 /tmp = host 的 Consts.tmpDir
# (Proot.kt 启动时 --bind=${tmpdir.absolutePath}:/tmp)
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

    # 派发: 用 proot 内的 sh -c 跑, 放后台, 不阻塞 server
    # 这是 proot 内的 sh (busybox ash), "cmd" 在 proot 内执行
    # cmd 通常是:
    #   /imagefs/usr/bin/box64 /imagefs/opt/wine/bin/wine winecfg
    #   /imagefs/glibc-run.sh winecfg
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
