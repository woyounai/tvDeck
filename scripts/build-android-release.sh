#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
case "${1:-}" in
    client) module=android-client; name=TVDeck-Android; tests=testDebugUnitTest ;;
    tv) module=android-onboarding; name=TVDeck-Connect; tests=testReleaseUnitTest ;;
    *) echo "Usage: $0 client|tv" >&2; exit 2 ;;
esac
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK with platform 35}"
export PATH="$JAVA_HOME/bin:$PATH"
# All signing fields are required together; passwords stay out of command arguments.
if [ -n "${TVADB_KEYSTORE:-}${TVADB_KEY_ALIAS:-}${TVADB_STORE_PASSWORD:-}${TVADB_KEY_PASSWORD:-}" ]; then
    : "${TVADB_KEYSTORE:?Set the release keystore path}"
    : "${TVADB_KEY_ALIAS:?Set the release key alias}"
    : "${TVADB_STORE_PASSWORD:?Set the keystore password}"
    : "${TVADB_KEY_PASSWORD:?Set the key password}"
    : "${TVADB_BUILD_TOOLS:=35.0.0}"
    tools="$ANDROID_HOME/build-tools/$TVADB_BUILD_TOOLS"
    [ -f "$TVADB_KEYSTORE" ] && [ -x "$tools/apksigner" ] && [ -x "$tools/zipalign" ] || {
        echo "Release keystore or Android build tools not found" >&2; exit 1;
    }
    TVADB_KEYSTORE=$(CDPATH= cd -- "$(dirname -- "$TVADB_KEYSTORE")" && pwd)/$(basename -- "$TVADB_KEYSTORE")
    export TVADB_STORE_PASSWORD TVADB_KEY_PASSWORD
fi
cd "$PROJECT_DIR/$module"
./gradlew --no-daemon "$tests" assembleRelease
mkdir -p "$PROJECT_DIR/dist"
source_apk="$PROJECT_DIR/$module/app/build/outputs/apk/release/app-release-unsigned.apk"
if [ -n "${TVADB_KEYSTORE:-}" ]; then
    temporary=$(mktemp -d)
    trap 'rm -rf "$temporary"' EXIT
    "$tools/zipalign" -f -P 16 4 "$source_apk" "$temporary/aligned.apk"
    "$tools/apksigner" sign --ks "$TVADB_KEYSTORE" --ks-key-alias "$TVADB_KEY_ALIAS" \
        --ks-pass env:TVADB_STORE_PASSWORD --key-pass env:TVADB_KEY_PASSWORD \
        --out "$temporary/release.apk" "$temporary/aligned.apk"
    "$tools/apksigner" verify "$temporary/release.apk"
    output="$PROJECT_DIR/dist/$name-release.apk"
    cp "$temporary/release.apk" "$output"
else
    output="$PROJECT_DIR/dist/$name-release-unsigned.apk"
    cp "$source_apk" "$output"
    echo "Unsigned APK: sign with your release key before installation/distribution."
fi
echo "Generated $output"
