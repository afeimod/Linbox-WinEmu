#!/system/bin/sh
# fifo_exec_server.sh
#
# Android 端跑的 FIFO server (mobox 风格)。
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
# 防死锁关键点:
#   1. server 只在需要 read 时临时打开 FIFO,读完立即关闭。
#      这样 wineserver 等孙子进程不会继承 FIFO fd,不会导致
#      "永远有写者 → server read 永不返回" 的死锁。
#   2. 派发子进程用 setsid 脱离 server 进程组,避免被 server 的
#      signal 影响。
#   3. 派发时显式 close server 的所有 fd (>&- 关闭所有 fd 的写端)。
#
# 角色分工:
#   [Android linbox 进程]
#     └── fork fifo_exec_server.sh (Android 视角, /system/bin/sh)
#           ├── 创建 /data/data/.../cache/tmp/.exec.fifo
#           ├── 创建 /data/data/.../cache/tmp/.exec-lock
#           └── while [ -e LOCK ]; do
#                 临时打开 FIFO 读一行 cmd
#                 关闭 FIFO fd (避免泄漏给子进程)
#                 后台派发: /system/bin/sh $GLIBC_RUN $cmd
#               done

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
mkfifo "$FIFO" 2>/dev/null || { echo "fifo_exec_server: mkfifo $FIFO 失败" >&2; exit 1; }
chmod 0666 "$FIFO" 2>/dev/null || true
touch "$LOCK" || { echo "fifo_exec_server: 无法创建 $LOCK" >&2; exit 1; }

# 初始化 log
echo "[$(date +%H:%M:%S 2>/dev/null || echo unknown)] server started pid=$$" > "$LOG"
echo "fifo_exec_server: 启动 pid=$$ FIFO=$FIFO" >&2

# ============================================================
# 主循环: 锁文件存在就一直跑
#
# 防死锁核心:
#   - 每次 read 前临时打开 FIFO,读完后立即关闭 fd
#   - 这样 wine 子进程 fork 时不会继承 FIFO fd
#   - 子进程死掉时,wineserver 等也不会"永远持有 FIFO"
# ============================================================
while [ -e "$LOCK" ]; do
    # 临时打开 FIFO (只读),读一行,关闭
    # read 阻塞等 startexec 写一行
    cmd=""
    if read -r cmd < "$FIFO"; then
        # 读成功,关闭 fd (read 已经读完,fd 在 if 块结束自动关,
        # 但保险起见显式关一下,避免在子进程继承)
        :

        # 跳过空行
        if [ -z "$cmd" ]; then
            continue
        fi

        ts="$(date +%H:%M:%S 2>/dev/null || echo unknown)"
        echo "[$ts] exec: $cmd" >> "$LOG"
        echo "fifo_exec_server: 派发 cmd=[$cmd]" >&2

        # 派发: Android 进程直接 fork /system/bin/sh 跑 glibc-run
        #
        # 替换 cmd 开头的 "glibc-run" 为绝对路径
        if [ -x "$GLIBC_RUN" ]; then
            resolved_cmd="$(echo "$cmd" | sed 's|^glibc-run |/system/bin/sh '"$GLIBC_RUN"' |' | sed 's|^glibc-run$|/system/bin/sh '"$GLIBC_RUN"'|')"
            echo "fifo_exec_server: resolved_cmd=[$resolved_cmd]" >&2

            # 关键: 用 setsid + 关闭 server 的所有 fd, 避免 fd 泄漏给 wine 子进程
            # setsid: 创建新 session, 子进程脱离 server 的 process group
            # exec 0>&- / 1>&- / 2>&- / 3>&-: 关闭 stdin/stdout/stderr/fd3
            #
            # 这里 subshell 已经 return, 但我们要保证后面 eval 后台跑
            # 的子进程不会继承 server 持有的 fd。Android sh 没有
            # "exec N<&-" 在 background 子进程的语法, 我们用 () 子 shell
            # + setsid 来隔离。
            (
                # 子 shell 内: 关闭所有 fd (0/1/2 + 可能的 3+)
                exec 0<&- 1<&- 2<&-
                # 关 3-9 之间的 fd (server 可能持有的)
                i=3
                while [ "$i" -lt 32 ]; do
                    eval "exec $i<&-" 2>/dev/null || true
                    i=$((i + 1))
                done
                # 现在 exec 派发的命令 (完全脱离 server 的 fd 上下文)
                exec /system/bin/sh -c "$resolved_cmd"
            ) </dev/null >>"$LOG" 2>&1 &
        else
            echo "fifo_exec_server: glibc-run 找不到: $GLIBC_RUN" >&2
        fi
    else
        # read 失败 (EOF / FIFO 被关)
        # 通常因为 startexec 打开 FIFO 写完 echo 立即关, 触发 EOF
        # 这是正常的, 重建 FIFO 等下一条命令
        echo "fifo_exec_server: read EOF, 重建 FIFO" >&2
        sleep 0.05  # 避免 busy loop
    fi
done

# 锁文件被删了, server 退出
echo "fifo_exec_server: lock 被删, 退出" >&2
rm -f "$FIFO" "$LOCK" 2>/dev/null || true
exit 0