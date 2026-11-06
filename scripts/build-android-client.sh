#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK with platform 35}"
cd "$PROJECT_DIR/android-client"
./gradlew --no-daemon testDebugUnitTest assembleDebug
mkdir -p "$PROJECT_DIR/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$PROJECT_DIR/dist/TVDeck-Android-debug.apk"
echo "Generated dist/TVDeck-Android-debug.apk"
