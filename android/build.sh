#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

ARGS=()
if command -v aapt2 >/dev/null 2>&1; then
  ARGS+=("-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)")
fi

bash ./gradlew :app:assembleDebug :app:assembleRelease --no-daemon "${ARGS[@]}"

DIST="$(cd .. && pwd)/dist"
VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION="${VERSION:-1.0.2}"
mkdir -p "$DIST"
cp app/build/outputs/apk/debug/app-debug.apk "$DIST/Arclyric-$VERSION-debug.apk"
cp app/build/outputs/apk/release/app-release.apk "$DIST/Arclyric-$VERSION-release.apk"
(
  cd "$DIST"
  sha256sum ./*.apk > SHA256SUMS.txt
)

echo "APK 已输出到 $DIST"
