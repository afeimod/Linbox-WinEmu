#!/bin/sh
# glibc-run.sh
#
# Runs a Windows .exe inside the linbox proot container using box64 + wine64.
# This script runs *inside* proot, NOT on the Android side.
#
# The proot container has the imagefs directory bind-mounted at
# `/imagefs` (see Proot.kt --bind flag). All box64/wine/glibc binaries
# are looked up there.
#
# Usage:
#   glibc-run [box64-options] <windows-program.exe> [args...]
#
# Examples:
#   glibc-run /imagefs/home/xuser/drive_c/Games/Game.exe
#   glibc-run --preset performance /imagefs/home/xuser/drive_c/Program.exe
#
# Exit codes match box64/wine64.

set -e

IMAGEFS=/imagefs
BOX64="$IMAGEFS/usr/local/bin/box64"
WINE64="$IMAGEFS/opt/wine/bin/wine64"

# Sanity check. If imagefs wasn't bind-mounted (e.g. the user upgraded
# from v1 and hasn't reinstalled) bail out with a clear message instead
# of the cryptic "wine: not found".
if [ ! -x "$BOX64" ]; then
    echo "glibc-run: $BOX64 不可执行或不存在" >&2
    echo "  请检查 linbox 是否把 imagefs bind 到 /imagefs。" >&2
    echo "  重新安装 glibc 资产包即可。" >&2
    exit 127
fi
if [ ! -x "$WINE64" ]; then
    echo "glibc-run: $WINE64 不可执行或不存在" >&2
    echo "  imagefs 内的 wine 没装好,试试重新下载 imagefs 资产。" >&2
    exit 127
fi

# Point glibc loader at imagefs libs. This is the entire point: wine64
# is glibc-linked but proot itself can't run glibc binaries — box64
# intercepts the exec and re-dispatches with the correct loader.
export BOX64_LD_LIBRARY_PATH="$IMAGEFS/usr/lib/x86_64-linux-gnu:$IMAGEFS/lib/x86_64-linux-gnu"
export LD_LIBRARY_PATH="$BOX64_LD_LIBRARY_PATH"
export WINEPREFIX="${WINEPREFIX:-$IMAGEFS/home/xuser/.wine}"

# Standard runtime paths so wine can find its own sub-bins.
export PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# X11 / audio. linbox's proot already sets DISPLAY=:13 (Xlorie).
# We *don't* override DISPLAY here so the user's existing xfce session
# keeps working.
export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"

# Optional: pick up preset flags from env (MainEmuActivity exports
# LINBOX_GLIBC_PRESET=performance|compatibility|...).
case "${LINBOX_GLIBC_PRESET:-compatibility}" in
    performance)
        export BOX64_DYNAREC=1
        export BOX64_DYNAREC_BIGBLOCK=3
        export BOX64_DYNAREC_FASTROUND=1
        export BOX64_DYNAREC_FASTNAN=1
        export BOX64_DYNAREC_SAFEFLAGS=0
        export BOX64_DYNAREC_CALLRET=1
        ;;
    intermediate)
        export BOX64_DYNAREC=1
        export BOX64_DYNAREC_BIGBLOCK=2
        export BOX64_DYNAREC_FASTROUND=1
        export BOX64_DYNAREC_FASTNAN=0
        export BOX64_DYNAREC_SAFEFLAGS=0
        export BOX64_DYNAREC_CALLRET=1
        ;;
    safe)
        export BOX64_DYNAREC=0
        ;;
    *)
        # compatibility (default)
        export BOX64_DYNAREC=1
        export BOX64_DYNAREC_BIGBLOCK=2
        export BOX64_DYNAREC_FASTROUND=0
        export BOX64_DYNAREC_FASTNAN=0
        export BOX64_DYNAREC_SAFEFLAGS=0
        export BOX64_DYNAREC_CALLRET=0
        ;;
esac

# Suppress box64 banner unless explicitly enabled.
export BOX64_NOBANNER="${BOX64_NOBANNER:-1}"

# Run.
exec "$BOX64" "$WINE64" "$@"
