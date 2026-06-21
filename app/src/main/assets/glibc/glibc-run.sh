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

# Find box64. winlator imagefs layouts we know about:
#   7.x:        /imagefs/usr/local/bin/box64
#   older 6.x:  /imagefs/usr/bin/box64
find_box64() {
    for cand in \
        "$IMAGEFS/usr/local/bin/box64" \
        "$IMAGEFS/usr/bin/box64" \
        "$IMAGEFS/bin/box64" ; do
        if [ -x "$cand" ]; then echo "$cand"; return 0; fi
    done
    return 1
}

# Find wine binary. winlator uses plain `wine` (not wine64) — wine64 is
# only used inside the prefix by wine's loader to switch to 64-bit.
# Probe multiple candidate layouts and return the first executable one.
find_wine() {
    for cand in \
        "$IMAGEFS/opt/wine/bin/wine" \
        "$IMAGEFS/usr/bin/wine" \
        "$IMAGEFS/bin/wine" \
        "$IMAGEFS/opt/wine/bin/wine64" \
        "$IMAGEFS/usr/bin/wine64" \
        "$IMAGEFS/bin/wine64" ; do
        if [ -x "$cand" ]; then echo "$cand"; return 0; fi
    done
    return 1
}

# Diagnostic helper: dump the failing entry with full stat so the user
# can tell whether the bind mount is missing, the file is missing, or
# the executable bit was stripped on extract.
diag() {
    echo "glibc-run: $1" >&2
    echo "  ---" >&2
    echo "  proot sees /imagefs as:" >&2
    if [ -e /imagefs ]; then
        ls -la /imagefs 2>&1 | head -15 >&2
        echo "  contents (depth 3):" >&2
        find /imagefs -maxdepth 3 2>&1 | head -30 >&2
        echo "  box64 candidates:" >&2
        for cand in "$IMAGEFS/usr/local/bin/box64" "$IMAGEFS/usr/bin/box64" "$IMAGEFS/bin/box64"; do
            if [ -e "$cand" ]; then
                ls -la "$cand" >&2
            else
                echo "  not found: $cand" >&2
            fi
        done
        echo "  wine candidates:" >&2
        for cand in "$IMAGEFS/opt/wine/bin/wine" "$IMAGEFS/usr/bin/wine" "$IMAGEFS/bin/wine"; do
            if [ -e "$cand" ]; then
                ls -la "$cand" >&2
            else
                echo "  not found: $cand" >&2
            fi
        done
    else
        echo "  /imagefs does not exist — proot's --bind=...:/imagefs" >&2
        echo "  did not take effect. 检查 logcat ImageFsInstaller 看 extract 有没有跑。" >&2
    fi
    echo "  ---" >&2
    exit 127
}

if [ ! -d /imagefs ]; then
    diag "/imagefs 目录不存在"
fi

# Try to find box64. We intentionally try BEFORE chmod +x because some
# imagefs layouts place box64 in /usr/bin/box64 (older winlator), and
# chmod-ing the wrong path won't help.
BOX64=$(find_box64 || true)
if [ -z "$BOX64" ]; then
    # Last-ditch: try chmod +x on the most likely paths, then re-find.
    chmod +x "$IMAGEFS/usr/local/bin/box64" 2>/dev/null || true
    chmod +x "$IMAGEFS/usr/bin/box64" 2>/dev/null || true
    chmod +x "$IMAGEFS/opt/wine/bin/wine" 2>/dev/null || true
    chmod +x "$IMAGEFS/opt/wine/bin/wine64" 2>/dev/null || true
    chmod +x "$IMAGEFS/usr/bin/wine" 2>/dev/null || true
    chmod +x "$IMAGEFS/usr/bin/wine64" 2>/dev/null || true
    BOX64=$(find_box64 || true)
    if [ -z "$BOX64" ]; then
        diag "box64 not found in any known layout (tried usr/local/bin, usr/bin, bin)"
    fi
fi

WINE=$(find_wine || true)
if [ -z "$WINE" ]; then
    WINE="$BOX64"   # fall back; glibc-run will fail later if wine really missing
    echo "glibc-run: wine not found in any known layout, will try to use box64 path" >&2
fi

# Point glibc loader at imagefs libs.
export BOX64_LD_LIBRARY_PATH="$IMAGEFS/usr/lib/x86_64-linux-gnu:$IMAGEFS/lib/x86_64-linux-gnu"
export LD_LIBRARY_PATH="$BOX64_LD_LIBRARY_PATH"
export WINEPREFIX="${WINEPREFIX:-$IMAGEFS/home/xuser/.wine}"

# Standard runtime paths so wine can find its own sub-bins.
export PATH="$IMAGEFS/opt/wine/bin:$IMAGEFS/usr/local/bin:$IMAGEFS/usr/bin:$IMAGEFS/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

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
exec "$BOX64" "$WINE" "$@"
