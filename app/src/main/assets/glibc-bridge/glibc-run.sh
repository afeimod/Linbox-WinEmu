#!/bin/sh
# /usr/local/bin/glibc-run — invoked inside the proot container.
# Forwards a wine command to the Android-side GlibcWineBridge, which runs
# box64+wine natively (no proot, no ptrace, much faster).
#
# Usage inside the proot shell:
#
#   glibc-run /path/to/game.exe
#   glibc-run wine regedit
#   glibc-run --some-wine-arg /path/to/anything.exe
#
# Endpoint discovery:
#   $LINBOX_GLIBC_ENDPOINT  — set by linbox's proot startup script.
#     Format A (abstract socket): "abstract:linbox-glibc-bridge"
#     Format B (two FIFOs):       "/path/to/in|/path/to/out"
#
# Auto-detects whether socat, ncat, or plain shell is available.

set -e

ENDPOINT="${LINBOX_GLIBC_ENDPOINT:-abstract:linbox-glibc-bridge}"
KEY="glibc-$$-$(date +%s%N)"

if [ "$#" -eq 0 ]; then
    echo "usage: $0 <wine-args...>" >&2
    exit 2
fi

# Build the EXEC payload (tab-separated: verb, key, args)
PAYLOAD="EXEC	${KEY}	$*"

USE_SOCKET=0
case "$ENDPOINT" in
    abstract:*|/*)
        if command -v socat >/dev/null 2>&1; then USE_SOCKET=1
        elif command -v ncat >/dev/null 2>&1; then USE_SOCKET=1
        fi
        ;;
esac

# ============================================================
# Transport A: single socket
# ============================================================
if [ "$USE_SOCKET" -eq 1 ]; then
    SOCK="$ENDPOINT"
    case "$SOCK" in abstract:*) SOCK="abstract:${SOCK#abstract:}";; esac
    if command -v socat >/dev/null 2>&1; then
        printf '%s\n' "$PAYLOAD" | socat - "UNIX-CONNECT:$SOCK"
        exit $?
    fi
    if command -v ncat >/dev/null 2>&1; then
        printf '%s\n' "$PAYLOAD" | ncat -U -- "$SOCK"
        exit $?
    fi
    echo "no socat/ncat available" >&2
    exit 3
fi

# ============================================================
# Transport B: two FIFOs
# ============================================================
IN_FIFO="${ENDPOINT%|*}"
OUT_FIFO="${ENDPOINT#*|}"

# Open the response fifo for writing FIRST so the bridge doesn't block.
exec 3>"$OUT_FIFO"
exec 4<"$IN_FIFO"

printf '%s\n' "$PAYLOAD" >&4

EXIT_CODE=0
while IFS= read -r line <&4; do
    verb=$(printf '%s' "$line" | cut -f1)
    rest=$(printf '%s' "$line" | cut -f3-)
    case "$verb" in
        OK)  : ;;
        OUT) printf '%s\n' "$rest" ;;
        END) EXIT_CODE="$rest"; break ;;
        ERR) printf 'ERROR: %s\n' "$rest" >&2; EXIT_CODE=1; break ;;
        *)   printf '%s\n' "$line" ;;
    esac
done

exec 4<&-
exec 3>&-
exit "$EXIT_CODE"
