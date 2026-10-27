#!/usr/bin/env bash
# Builds MCEF's libjcef: CinemaMod/java-cef at the pinned commit + the patches in jcef-patches/, packaged exactly like the upstream
# archive (<platform>/ with the libraries, helper and resources) with its SHA-256, ready to attach to a GitHub release.
#
#   tools/build-natives.sh PLATFORM [OUT_DIR]       PLATFORM: linux_amd64 | windows_amd64 | macos_amd64 | macos_arm64
#
# Environment: JCEF_COMMIT   java-cef commit (default: the one the mod pins, read from JCEF_COMMIT_FILE or below)
#              WORK          scratch directory (default: ./build-natives-work)
#              JAVA_HOME     a JDK (JNI headers; any version >= 8)
# Needs git, cmake >= 3.19, ninja, python3, a C++ toolchain (gcc/clang; MSVC from a "Developer Command Prompt" on Windows),
# and on Linux the GTK 3 and X11 development packages (libgtk-3-dev). Downloads the CEF binary distribution (~250 MB) itself.
set -euo pipefail
PLATFORM="${1:?usage: build-natives.sh PLATFORM [OUT_DIR]}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${2:-$HERE/build-natives-out}"
WORK="${WORK:-$HERE/build-natives-work}"
JCEF_COMMIT="${JCEF_COMMIT:-eaeb3d4370aa3526ee237ad1981ad59af3de4dd1}"
REVISION="mcef$(tr -d '[:space:]' < "$HERE/jcef-patches/VERSION")"
ID="$REVISION+${JCEF_COMMIT:0:7}"          # what the library reports at run time: mcef1+eaeb3d4
TAG="natives-$REVISION-${JCEF_COMMIT:0:7}"  # the release tag the mod's downloader looks for

case "$PLATFORM" in
  linux_amd64|windows_amd64) ARCH_ARGS=() ;;
  macos_amd64) ARCH_ARGS=(-DPROJECT_ARCH=x86_64) ;;
  macos_arm64) ARCH_ARGS=(-DPROJECT_ARCH=arm64) ;;
  *) echo "unsupported platform '$PLATFORM' (linux_arm64 and windows_arm64 are not built: upstream ships x86-64 binaries for them, see MCEFPlatform#hasMatchingNatives)"; exit 2 ;;
esac

echo "== java-cef $JCEF_COMMIT + patches ($REVISION) for $PLATFORM, build id $ID"
rm -rf "$WORK/src"; mkdir -p "$WORK" "$OUT"
git clone --quiet https://github.com/CinemaMod/java-cef "$WORK/src"
git -C "$WORK/src" checkout --quiet "$JCEF_COMMIT"
for p in "$HERE"/jcef-patches/*.patch; do
  git -C "$WORK/src" apply --check "$p"
  git -C "$WORK/src" apply "$p"
  echo "applied $(basename "$p")"
done

mkdir -p "$WORK/src/jcef_build"
( cd "$WORK/src/jcef_build"
  cmake -G Ninja -DCMAKE_BUILD_TYPE=Release -DMCEF_NATIVE_BUILD_ID="$ID" "${ARCH_ARGS[@]}" ..
  ninja )

REL="$WORK/src/jcef_build/native/Release"
[ -d "$REL" ] || { echo "build output $REL is missing"; exit 1; }
STAGE="$WORK/stage"; rm -rf "$STAGE"; mkdir -p "$STAGE/$PLATFORM"
cp -R "$REL"/. "$STAGE/$PLATFORM/"
rm -f "$STAGE/$PLATFORM"/*.a "$STAGE/$PLATFORM"/*.lib "$STAGE/$PLATFORM"/*.exp "$STAGE/$PLATFORM"/*.pdb 2>/dev/null || true
# CEF's Linux libcef.so ships with symbols (1.7 GB); the upstream archive has it stripped (214 MB). Same here, or the download is 3x larger.
if [ "$PLATFORM" = linux_amd64 ]; then strip --strip-unneeded "$STAGE/$PLATFORM/libcef.so"; fi
tar -C "$STAGE" -czf "$OUT/$PLATFORM.tar.gz" "$PLATFORM"
if command -v sha256sum >/dev/null; then ( cd "$OUT" && sha256sum "$PLATFORM.tar.gz" | cut -d' ' -f1 > "$PLATFORM.tar.gz.sha256" )
else ( cd "$OUT" && shasum -a 256 "$PLATFORM.tar.gz" | cut -d' ' -f1 > "$PLATFORM.tar.gz.sha256" ); fi
cat > "$OUT/$PLATFORM.build.json" <<JSON
{ "platform": "$PLATFORM", "buildId": "$ID", "releaseTag": "$TAG", "javaCefCommit": "$JCEF_COMMIT",
  "cef": "116.0.27+gd8c85ac+chromium-116.0.5845.190", "patches": "$(cd "$HERE/jcef-patches" && ls *.patch | tr '\n' ' ')",
  "sha256": "$(cat "$OUT/$PLATFORM.tar.gz.sha256")", "builtAt": "$(date -u +%FT%TZ)" }
JSON
echo "== done: $OUT/$PLATFORM.tar.gz  sha256 $(cat "$OUT/$PLATFORM.tar.gz.sha256")"
