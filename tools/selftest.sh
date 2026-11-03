#!/usr/bin/env bash
# =============================================================================
#  Run the core self-test only. No Gradle, no Minecraft needed.
#
#      ./tools/selftest.sh
#
#  core/ does not depend on any MC or loader class, so javac can compile it
#  directly. Use this to sanity-check the logic before fighting with Gradle.
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

OUT="$(mktemp -d "${TMPDIR:-/tmp}/spd-selftest-XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

if ! command -v javac >/dev/null 2>&1; then
    echo "[ERROR] javac not found. Install a JDK 17+ from https://adoptium.net/"
    exit 1
fi

# ---------------------------------------------------------------------------
# Static check first: unbalanced braces, calls to undefined methods, types used
# without an import. Each of these has broken a build in this project before,
# and all of them are compile-blocking. Runs only when python is available.
# ---------------------------------------------------------------------------
PY=""
if command -v python3 >/dev/null 2>&1; then
    PY=python3
elif command -v python >/dev/null 2>&1; then
    PY=python
fi

if [ -n "$PY" ]; then
    echo "[selftest] static check ..."
    if ! $PY tools/jcheck.py .; then
        echo ""
        echo "[selftest] static check reported ERRORS - fix them before compiling"
        exit 1
    fi
fi

echo "[selftest] compiling core ..."
find core/src/main/java -name '*.java' > "$OUT/sources.txt"
if ! javac -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"; then
    echo "[selftest] FAILED to compile"
    exit 1
fi

echo "[selftest] running ..."
echo ""
java -Dfile.encoding=UTF-8 -cp "$OUT/classes" org.sharedplayerdata.core.SelfTest
RC=$?
echo ""
if [ "$RC" -eq 0 ]; then
    echo "[selftest] PASSED"
else
    echo "[selftest] FAILED (exit code $RC)"
fi
exit $RC
