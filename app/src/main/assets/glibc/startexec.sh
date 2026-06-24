#!/bin/sh
# startexec.sh
#
# proot 内的 FIFO 客户端 (用户在 xfce4 桌面 terminal 跑)。
# 接收用户命令参数, 拼好后写入 FIFO, 让 fifo_exec_server 派发执行。
#
# 用法 (proot 内, xfce4 桌面 terminal):
#   startexec glibc-run winecfg                # 启动 winecfg
#   startexec glibc-run /home/xuser/.wine/drive_c/foo.exe  # 启动某个 exe
#   startexec glibc-run wine /path/to/foo.exe  # 显式 wine
#   startexec box64 /imagefs/opt/wine/bin/wine /path/to/foo.exe  # 完全透传
#
# FIFO 路径: 用 $TMPDIR (跟 fifo_exec_server 一致)
#   - proot 视角:  /tmp/.exec.fifo (因为 Proot.kt --bind=Consts.tmpDir:/tmp)
#   - Android 视角:  Consts.tmpDir/.exec.fifo
#   三者写的是同一个文件 (因为 --bind)

# FIFO 路径必须跟 fifo_exec_server.sh 里的 TMP 一致
# 用 $TMPDIR (proot 启动时 Proot.kt 注入 TMPDIR=/tmp)
# 这样既兼容 termux 视角也兼容 proot 视角
TMP="${TMPDIR:-/tmp}"
FIFO="$TMP/.exec.fifo"

# 把整个命令行拼成一行写入 FIFO
# 注意: 不引号 quote, FIFO 是字节流, 一行一条命令
echo "$*" > "$FIFO"
