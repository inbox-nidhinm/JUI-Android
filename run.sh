#!/bin/sh
# Build, install on the connected emulator or phone, and launch.
#   ./run.sh            debug build
#   ./run.sh shot NAME  also save a screenshot to build/shots/NAME.png
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
./gradlew --console=plain -q assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
# Whichever mood icon is enabled, the launcher intent finds it.
"$ADB" shell monkey -p com.nidhin.jui.android -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
if [ "$1" = shot ]; then
  sleep 3; mkdir -p build/shots
  "$ADB" exec-out screencap -p > "build/shots/$2.png"
fi
