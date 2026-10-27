#!/usr/bin/env bash
# Linux smoke test of a natives archive built by build-natives.sh: unpacks it, loads libjcef in a JVM under Xvfb with the unmodified
# java-cef Java classes, and checks (1) the build id and feature list are reported, (2) a 24 fps request really yields about 24
# paints per second while the host pumps at 250 Hz, (3) with the threaded loop requested the paints continue during host stalls.
# Usage: tools/smoke-natives.sh path/to/linux_amd64.tar.gz
set -euo pipefail
ARCHIVE="$(cd "$(dirname "${1:?archive}")" && pwd)/$(basename "$1")"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
tar -xzf "$ARCHIVE" -C "$W"
REL="$W/linux_amd64"
git clone --quiet https://github.com/CinemaMod/java-cef "$W/jcef" && git -C "$W/jcef" checkout --quiet "${JCEF_COMMIT:-eaeb3d4370aa3526ee237ad1981ad59af3de4dd1}"
mkdir -p "$W/classes" "$W/bench"
( cd "$W/jcef" && find java/org -name '*.java' | grep -v /tests/ > "$W/src.txt" && javac -nowarn -d "$W/classes" @"$W/src.txt" 2>&1 | grep -E 'error' || true )
javac -d "$W/classes" -cp "$W/classes" "$HERE/NativeFeatures.java" "$HERE/NativeExt.java"
javac -d "$W/bench" -cp "$W/classes" "$HERE/jcef-patches/bench/OsrBench.java"
run() { xvfb-run -a -s "-screen 0 1280x800x24" java -Djcef.path="$REL" -cp "$W/classes:$W/bench" "$@" OsrBench 2>&1 | grep -E 'NATIVE|RESULT' ; }
OUT1="$(run -Dbench.seconds=6 -Dbench.fps=24)"; echo "$OUT1"
echo "$OUT1" | grep -q 'frame-rate threaded-loop' || { echo "FAIL: build id / features not reported"; exit 1; }
RATE="$(echo "$OUT1" | sed -n 's/.*paints=[0-9]* (\([0-9.]*\)\/s).*/\1/p')"
awk -v r="$RATE" 'BEGIN{ exit !(r>=22 && r<=26) }' || { echo "FAIL: 24 fps request gave $RATE paints/s"; exit 1; }
OUT2="$(run -Dbench.seconds=6 -Dbench.mode=mt -Dbench.fps=60 -Dbench.stallMs=1000 -Dbench.stallEveryMs=1500)"; echo "$OUT2"
RATE2="$(echo "$OUT2" | sed -n 's/.*paints=[0-9]* (\([0-9.]*\)\/s).*/\1/p')"
awk -v r="$RATE2" 'BEGIN{ exit !(r>=55) }' || { echo "FAIL: threaded loop gave $RATE2 paints/s under stalls"; exit 1; }
echo "smoke test passed"
