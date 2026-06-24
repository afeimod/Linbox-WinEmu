#!/system/bin/sh
# fifo_exec_server.sh
#
# Android 端跑的 FIFO server (mobox 风格, 跟用户在 termux 的脚本一致)。
#
# 关键架构 (用户原话):
#   - fifo_exec_server 是 **Android 端** 创建 FIFO 管道和锁文件
#   - fifo 的 tmp 路径是 **Android 视角的 tmp** = Consts.tmpDir.absolutePath
#     (= /data/data/a.io.github.ewt45.winemulator/cache/tmp)
#   - fifo server 监听 FIFO, 收到命令, **启 proot 子进程** 派发执行
#   - 派发时启 proot, 在 proot 内 imagefs bind /imagefs, 跑 cmd
#
# 角色分工:
#   [Android linbox 进程]
#     └── fork fifo_exec_server.sh (Android 视角, 用 /system/bin/sh)
#           ├── 创建 /data/data/.../cache/tmp/.exec.fifo
#           ├── 创建 /data/data/.../cache/tmp/.exec-lock
#           └── 监听 FIFO, 收到命令 sh -c 派发
#                 └── 派发时启 proot 子进程, 在 proot 内跑 cmd
#                   (cmd 形如 "glibc-run winecfg", 在 proot 内 sh -c 跑)
#   [proot 内 (xfce4 桌面)]
#     └── 用户跑: startexec "cmd"
#           └── startexec 用 $TMPDIR (Android 视角绝对路径) 写 FIFO
#             ("glibc-run winecfg")

# ============================================================
# 路径: Android 视角下的 tmp
# Consts.tmpDir.absolutePath = /data/data/a.io.github.ewt45.winemulator/cache/tmp
# ============================================================
TMP="${TMPDIR:-/data/data/a.io.github.ewt45.winemulator/cache/tmp}"
FIFO="$TMP/.exec.fifo"
LOCK="$TMP/.exec-lock"
LOG="$TMP/.exec.log"

# proot 二进制 (Android 端路径)
PROOT_BIN="/data/data/a.io.github.ewt45.winemulator/files/proot"
ROOTFS="/data/data/a.io.github.ewt45.winemulator/files/rootfs/current"
IMAGEFS="/data/data/a.io.github.ewt45.winemulator/files/imagefs"

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

    # 派发: Android 端启 proot 子进程, 在 proot 内跑 cmd
    #
    # proot 子进程的参数:
    #   --rootfs=$ROOTFS
    #   --bind=$TMP:/tmp
    #   --bind=$IMAGEFS:/imagefs
    #   -L --link2symlink --sysvipc --kill-on-exit
    #   /usr/bin/env -i DISPLAY=:13 PULSE_SERVER=... PATH=... /bin/sh -c "$cmd"
    #
    # 这样 cmd 在 proot 内 sh -c 跑, cmd 里的 "glibc-run" 这种命令能解析
    # (因为 /imagefs/usr/local/bin 在 PATH 里)
    ts="$(date +%H:%M:%S 2>/dev/null || echo unknown)"
    echo "[$ts] exec: $cmd" >> "$LOG"

    # 启 proot 子进程跑 cmd
    "$PROOT_BIN" \
        -L --link2symlink --sysvipc --kill-on-exit \
        --rootfs="$ROOTFS" \
        --bind="$TMP:/tmp" \
        --bind="$IMAGEFS:/imagefs" \
        /usr/bin/env -i \
            DISPLAY=:13 \
            PULSE_SERVER=tcp:127.0.0.1:4713 \
            LINBOX_GLIBC_PRESET=compatibility \
            PATH="/imagefs/usr/local/bin:/imagefs/usr/bin:/imagefs/opt/wine/bin:/usr/local/bin:/usr/bin:/bin" \
            TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp HOME=/ \
            /bin/sh -c "$cmd" </dev/null &
done

# 锁文件被删了, server 退出
exec 3<&- 2>/dev/null || true
rm -f "$FIFO" "$LOCK" 2>/dev/null || true
echo "fifo_exec_server: 退出" >&2
exit 0
