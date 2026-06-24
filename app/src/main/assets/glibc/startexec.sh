#!/bin/sh
# startexec.sh
#
# proot 内的 FIFO 客户端 (用户在 xfce4 桌面 terminal 跑)。
# 写一行命令到 FIFO, 让 Android 端的 fifo_exec_server 派发执行。
#
# 关键 (用户原话):
#   "proot 利用 startexec 进行定义 FIFO 管道路径"
#   "是 proot 终端里利用 startexec 进行定义 FIFO 管道路径
#    (安卓发的 fifo 的 tmp 路径而不是 proot 内部 tmp)"
#
# 也就是说: FIFO 路径 = **Android 视角的 tmp 绝对路径** (不是 proot 内部的 /tmp)
#   Consts.tmpDir.absolutePath = /data/data/a.io.github.ewt45.winemulator/cache/tmp
#
# proot 内的 /tmp = host Consts.tmpDir (proot 启动时 --bind), 所以 proot 内的 /tmp
# 也是同一个文件, 但 startexec 用 Android 视角的绝对路径更明确。
#
# 用法 (proot 内, xfce4 桌面 terminal):
#   startexec glibc-run winecfg
#   startexec glibc-run /home/xuser/.wine/drive_c/foo.exe
#   startexec glibc-run wine /path/foo.exe
#   startexec box64 /imagefs/opt/wine/bin/wine /path/foo.exe

# FIFO 路径: Android 视角的 tmp 绝对路径
# (跟 fifo_exec_server.sh 里的 TMP 完全一致)
TMP="${TMPDIR:-/data/data/a.io.github.ewt45.winemulator/cache/tmp}"
FIFO="$TMP/.exec.fifo"

# 把整个命令行拼成一行写入 FIFO
# 注意: 不引号 quote, FIFO 是字节流, 一行一条命令
echo "$*" > "$FIFO"
