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
#
# Naming matches the Android bridge:
#   IN_FIFO  = req fifo — sh WRITES, bridge READS
#   OUT_FIFO = resp fifo — bridge WRITES, sh READS
#
# fd 3: read+write on OUT_FIFO (so we never see EOF if the bridge flushes
#      between writes; also avoids the "write end closed → SIGPIPE" trap)
# fd 4: write-only on IN_FIFO (this is where we send the EXEC payload)
# fd 5: read-only on IN_FIFO  (kept open so our writer on fd 4 doesn't
#      get SIGPIPE if the bridge side closes its reader — Linux sends
#      SIGPIPE to the writer as soon as the last reader is gone)
# ============================================================
IN_FIFO="${ENDPOINT%|*}"
OUT_FIFO="${ENDPOINT#*|}"

# Open OUT_FIFO first for reading, so the bridge's blocking open(OUT, O_WRONLY)
# can return. Then open IN_FIFO write end (bridge has it open for read).
exec 3<>"$OUT_FIFO"
exec 4>"$IN_FIFO"
exec 5<"$IN_FIFO"

printf '%s\n' "$PAYLOAD" >&4

EXIT_CODE=0
while IFS= read -r line <&3; do
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

exec 3<&-
exec 4>&-
exec 5<&-
exit "$EXIT_CODE"
