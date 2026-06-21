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

# Diagnostic helper: dump the failing entry with full stat so the user
# can tell whether the bind mount is missing, the file is missing, or
# the executable bit was stripped on extract.
diag() {
    echo "glibc-run: $1" >&2
    echo "  ---" >&2
    echo "  proot sees /imagefs as:" >&2
    if [ -e /imagefs ]; then
        ls -la /imagefs 2>&1 | head -10 >&2
        echo "  contents:" >&2
        find /imagefs -maxdepth 3 2>&1 | head -20 >&2
    else
        echo "  /imagefs does not exist — proot's --bind=...:/imagefs" >&2
        echo "  did not take effect. Most likely cause: linbox 还没" >&2
        echo "  解压 assets/imagefs/imagefs.tzst 到 imagefs 目录。" >&2
        echo "  检查 logcat ImageFsInstaller 看 extract 有没有跑。" >&2
    fi
    echo "  ---" >&2
    exit 127
}

# Sanity check: bind mount present and box64 file is reachable.
# We deliberately do NOT require -x here: even if the tar extract
# stripped the executable bit (a known Android+commons-compress gotcha
# for files on /data/data/.../files/), proot itself runs as our
# unprivileged app uid and can chmod the file back. Try chmod +x
# once before giving up.
if [ ! -d /imagefs ]; then
    diag "/imagefs 目录不存在"
fi
if [ ! -e "$BOX64" ]; then
    diag "$BOX64 not found (imagefs 装上但 box64 文件缺失)"
fi
# Best-effort re-chmod. Ignore errors.
chmod +x "$BOX64" 2>/dev/null || true
chmod +x "$WINE64" 2>/dev/null || true

if [ ! -x "$BOX64" ]; then
    diag "$BOX64 not executable even after chmod +x"
fi
if [ ! -x "$WINE64" ]; then
    diag "$WINE64 not executable even after chmod +x"
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
