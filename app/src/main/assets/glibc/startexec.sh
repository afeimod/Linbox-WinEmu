#!/bin/bash
# startexec.sh
#
# 把命令写到 FIFO, 让 fifo_exec_server 派发执行。
#
# 调用方式 (跟 mobox 一致, 但 linbox 没 termux):
#   **在 proot 内跑这个脚本** (例如 xfce4 桌面的 terminal, 未来用)
#   或者 Android 端 fork sh 跑 (现在的实现, 模拟 mobox 调用方)
#
# FIFO 路径:
#   - 默认用 $TMPDIR/.exec.fifo
#   - termux 视角默认是 /data/data/com.termux/files/usr/tmp
#   - proot 视角默认是 /tmp (--shared-tmp)
#   - Android 端 Consts.tmpDir.absolutePath
#   三者写的是同一个文件 (因为 --bind / --shared-tmp)
#
# 用法:
#   startexec "glibc-run winecfg"
#   startexec "/imagefs/usr/bin/box64 /imagefs/opt/wine/bin/wine winecfg"
#   startexec "proot-distro login debian --shared-tmp -- /bin/bash -c 'box64 wine winecfg'"

# 跟 fifo_exec_server 一致, 用 $TMPDIR
# 默认是 termux 视角的绝对路径 (mobox 原版就是这样的)
TMP="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
FIFO="$TMP/.exec.fifo"

# 写入 FIFO (一行)
# 注意: 不引号 quote, FIFO 是字节流, 一行一条命令
echo "$*" > "$FIFO"
