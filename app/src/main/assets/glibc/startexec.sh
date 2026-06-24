#!/bin/sh
# startexec.sh
#
# proot 内的 FIFO 客户端 (xfce4 桌面 terminal 跑)。
# 把命令行写到 FIFO, 让 fifo_exec_server 派发执行 glibc-run。
#
# 关键 (用户原话):
#   "在 proot 里使用 startexec 定义 FIFO 管道路径和将命令参数拼接后写入 FIFO"
#   "echo "$*" > "$FIFO""
#
# FIFO 路径 = proot 内 /tmp = host tmpDir
#   (proot --bind=tmpDir:/tmp, 跟 fifo_exec_server 创建的 FIFO 同目录)
#
# 用法 (proot 内, xfce4 桌面 terminal):
#   startexec glibc-run winecfg
#   startexec glibc-run /home/xuser/.wine/drive_c/foo.exe
#   startexec glibc-run wine /path/foo.exe

# FIFO 路径: proot 内 /tmp = host tmpDir (proot 启时 --bind=tmpDir:/tmp)
TMP="${TMPDIR:-/tmp}"
FIFO="$TMP/.exec.fifo"

if [ ! -p "$FIFO" ]; then
    # FIFO 不存在 (server 没启). 报个错, 免得用户以为调用成功了。
    echo "startexec: FIFO 不存在: $FIFO (fifo_exec_server 启了没?)" >&2
    exit 1
fi

# 把整个命令行拼成一行写入 FIFO
# FIFO 是字节流, 一行一条命令, server 读一行派发一次
echo "$*" > "$FIFO"