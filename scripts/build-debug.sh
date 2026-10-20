#!/usr/bin/env bash
# Builds the installable debug APK: dist/CactusAI-debug.apk (Linux / macOS / Git Bash)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[ -f native-engine/android-arm64/libneedle.a ] || scripts/fetch-engine.sh
./gradlew :app:assembleDebug
test -f dist/CactusAI-debug.apk || { echo "build finished but dist/CactusAI-debug.apk is missing" >&2; exit 1; }
ls -la dist/CactusAI-debug.apk
echo "Install on a connected phone:   adb install -r dist/CactusAI-debug.apk"
