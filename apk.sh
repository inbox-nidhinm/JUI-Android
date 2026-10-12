#!/bin/sh
# Build the release APK and keep a copy in apk/JUI.apk, the file to sideload
# onto a phone. Signed with this Mac's debug key (~/.android/debug.keystore):
# a later APK installs over an earlier one only if it carries the same key.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
./gradlew --console=plain -q assembleRelease
mkdir -p apk
cp app/build/outputs/apk/release/app-release.apk apk/JUI.apk
ls -lh apk/JUI.apk
