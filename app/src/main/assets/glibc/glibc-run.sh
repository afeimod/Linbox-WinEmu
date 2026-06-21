#!/bin/sh
# glibc-run.sh
#
# IMPORTANT: This script lives in the proot container at
#   /usr/local/bin/glibc-run
#
# But it CANNOT launch box64+wine from inside proot, because
# proot's ptrace layer cannot translate the early startup syscalls
# (set_robust_list, prctl, set_tid_address, ...) that musl-built
# static box64 binaries need. The result is SIGSEGV before box64
# even reaches main().
#
# This script exists as a stub that:
#   1. Tells the user what's wrong.
#   2. Invokes `am start` to launch the Android-side launcher
#      (GlibcLauncherActivity) which forks box64 directly on the
#      Android side where it has no proot interference.
#
# The Android launcher requires the box64 binary to be patched
# with `patchelf --set-interpreter
# /data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/lib/ld-linux-aarch64.so.1`
# (the Android-side absolute path, NOT the proot-side /imagefs/...).
#
# Same for wine and any other ELF binary in the imagefs.

echo "glibc-run: this command has been superseded by the Android-side launcher." >&2
echo "" >&2
echo "Use one of:" >&2
echo "  1. Tap the 'glibc' / 'wine' icon on the home screen" >&2
echo "  2. Run from adb shell on your PC:" >&2
echo "     adb shell am start -n a.io.github.ewt45.winemulator/org.github.ewt45.winemulator.glibc.GlibcLauncherActivity" >&2
echo "" >&2
echo "If you get 'binary not executable' or 'linterp not found', patchelf" >&2
echo "your box64 / wine to use the Android-side absolute path:" >&2
echo "  adb shell patchelf --set-interpreter \\" >&2
echo "    /data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/lib/ld-linux-aarch64.so.1 \\" >&2
echo "    /data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/local/bin/box64" >&2
echo "" >&2
exit 2
