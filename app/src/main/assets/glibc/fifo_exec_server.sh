#!/system/bin/sh
# fifo_exec_server.sh
#
# Android 端跑的 FIFO server (mobox 风格, 跟用户在 termux 的原型一致)。
# 由 linboxapp (Android 进程) fork /system/bin/sh 跑这个脚本。
#
# 关键架构 (用户原话):
#   "安卓 app 创建 FIFO 管道和锁文件并启动 fifo 服务"
#   "使 proot 能够正确管理 /data/data/.../files/imagefs 的 glibc 环境"
#   "然后在 proot 里使用 startexec 定义 FIFO 管道路径
#    和将命令参数拼接后写入 FIFO"
#   "echo "$*" > "$FIFO""
#
# 为什么必须在 Android 端跑?
#   glibc 二进制编译时绑了绝对路径
#       /data/data/a.io.github.ewt45.winemulator/files/imagefs
#   这个路径只有 linboxapp 进程 (uid=u_a_X) 能直接 exec,
#   proot 内 (uid 一般是 nobody) 看不到 /data/data/.../files
#   (只 bind 了 tmpDir:/tmp 和 imagefs:/imagefs)。
#   所以 fifo server 必须跑在 Android 进程空间,
#   派发时直接 fork /system/bin/sh 跑 imagefs 里的 glibc-run.sh。
#
# 角色分工:
#   [Android linbox 进程]
#     └── fork fifo_exec_server.sh (Android 视角, /system/bin/sh)
#           ├── 创建 /data/data/.../cache/tmp/.exec.fifo
#           ├── 创建 /data/data/.../cache/tmp/.exec-lock
#           └── while [ -e LOCK ]; do
#                 read cmd < FIFO
#                 /system/bin/sh $IMAGEFS/usr/local/bin/glibc-run.sh $cmd &
#               done
#
#   [proot 内 xfce4 桌面 terminal]
#     └── 用户跑: startexec "cmd"
#           └── echo "$*" > /tmp/.exec.fifo
#             (proot --bind=tmpDir:/tmp, /tmp = host tmpDir = server 创建的 FIFO 同目录)

# ============================================================
# 路径 (Android 视角)
# ============================================================
TMP="${TMPDIR:-/data/data/a.io.github.ewt45.winemulator/cache/tmp}"
FIFO="$TMP/.exec.fifo"
LOCK="$TMP/.exec-lock"
LOG="$TMP/.exec.log"

# glibc-run 在 imagefs 内 (Android 进程能直接 exec)
GLIBC_RUN="/data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/local/bin/glibc-run.sh"

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

    # 派发: Android 进程直接 fork /system/bin/sh 跑 glibc-run
    #
    # 约定: startexec 拼出来的 cmd 是 "glibc-run <args>" 的形式,
    #       server 负责把 "glibc-run" 替换成绝对路径, 让 Android 进程
    #       能直接 exec imagefs 里的 glibc-run.sh。
    #
    # 用 eval 重新做 shell 词法分析, 这样 startexec 拼出来的
    # 含空格/引号的参数能正确传透。
    #
    # 后台跑 (&): wine 本身是常驻进程, 派发后立刻回来读下一条命令
    # stdin 重定向避免 wine 等交互阻塞 FIFO
    if [ -x "$GLIBC_RUN" ]; then
        # 替换 cmd 开头的 "glibc-run" 为绝对路径
        resolved_cmd="$(echo "$cmd" | sed 's|^glibc-run |/system/bin/sh '"$GLIBC_RUN"' |' | sed 's|^glibc-run$|/system/bin/sh '"$GLIBC_RUN"'|')"
        eval "$resolved_cmd" </dev/null >/dev/null 2>&1 &
    else
        echo "fifo_exec_server: glibc-run 找不到: $GLIBC_RUN" >&2
    fi
done

# 锁文件被删了, server 退出
exec 3<&- 2>/dev/null || true
rm -f "$FIFO" "$LOCK" 2>/dev/null || true
echo "fifo_exec_server: 退出" >&2
exit 0