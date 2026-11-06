#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 or newer}"
: "${ANDROID_JAR:?Set ANDROID_JAR to an Android platform android.jar}"
: "${R8_JAR:?Set R8_JAR to the Android D8 compiler JAR}"
BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex"
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  "$PROJECT_DIR/android-helper/src/com/tvadb/helper/AppPresentationHelper.java" \
  "$PROJECT_DIR/android-helper/src/com/tvadb/helper/VideoStream.java"
"$JAVA_HOME/bin/java" -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 29 \
  --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" \
  "$BUILD_DIR/classes/com/tvadb/helper/"*.class
cp "$BUILD_DIR/dex/classes.dex" "$PROJECT_DIR/iosadb/Resources/TVADBVideoStream.dex"
cp "$BUILD_DIR/dex/classes.dex" "$PROJECT_DIR/android-client/app/src/main/assets/TVADBVideoStream.dex"
