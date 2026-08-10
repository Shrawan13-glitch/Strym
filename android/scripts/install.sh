#!/usr/bin/env bash
# Download the latest debug APK built by CI and install it on a connected
# Android device via adb.
#
# Usage:
#   ./install.sh                 # latest successful CI build on main
#   ./install.sh <run-id>        # a specific CI run
#   ./install.sh local           # install the last local ./gradlew assembleDebug
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-latest}"
APK=""

case "$MODE" in
  local)
    APK="$(ls -t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)"
    [[ -n "$APK" ]] || { echo "no local APK — run: cd android && ./gradlew assembleDebug" >&2; exit 1; }
    ;;
  latest)
    RUN_ID="$(gh run list --workflow=ci.yml --branch=main --status=success --limit=1 --json databaseId -q '.[0].databaseId')"
    [[ -n "$RUN_ID" ]] || { echo "no successful CI run found" >&2; exit 1; }
    DIR="$(mktemp -d)"
    gh run download "$RUN_ID" -n strym-debug-apk -D "$DIR"
    APK="$(ls -t "$DIR"/*.apk | head -1)"
    ;;
  *)
    DIR="$(mktemp -d)"
    gh run download "$MODE" -n strym-debug-apk -D "$DIR"
    APK="$(ls -t "$DIR"/*.apk | head -1)"
    ;;
esac

echo ">> installing: $APK"
adb install -r "$APK"
echo ">> launching Strym"
adb shell am start -n com.strym.app/.MainActivity
