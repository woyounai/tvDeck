#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PLATFORM_NAME="${1:-}"
ARCH_NAME="${2:-arm64}"

case "$PLATFORM_NAME/$ARCH_NAME" in
  iphoneos/arm64|iphonesimulator/arm64|iphonesimulator/x86_64) ;;
  *)
    echo "错误：不支持的 ADB 平台／架构：$PLATFORM_NAME/$ARCH_NAME" >&2
    exit 1
    ;;
esac

ARCHIVE="$PROJECT_ROOT/Vendor/adb-prebuilt/libadb-full-${PLATFORM_NAME}-${ARCH_NAME}.zip"
OUTPUT_DIR="$PROJECT_ROOT/Vendor/adb-mobile/output/${PLATFORM_NAME}/${ARCH_NAME}"
OUTPUT_LIBRARY="$OUTPUT_DIR/libadb-full.a"

if [[ ! -f "$ARCHIVE" ]]; then
  echo "错误：缺少 ADB 预编译库：$ARCHIVE" >&2
  exit 1
fi

if [[ -f "$OUTPUT_LIBRARY" && "$OUTPUT_LIBRARY" -nt "$ARCHIVE" ]]; then
  xcrun lipo "$OUTPUT_LIBRARY" -verify_arch "$ARCH_NAME"
  exit 0
fi

mkdir -p "$OUTPUT_DIR"
# Extract a single expected member, verify before replacing the cached library.
TEMP_LIBRARY=$(mktemp "$OUTPUT_DIR/.libadb-full.XXXXXX")
trap 'rm -f "$TEMP_LIBRARY"' EXIT
/usr/bin/unzip -p "$ARCHIVE" libadb-full.a > "$TEMP_LIBRARY"
xcrun lipo "$TEMP_LIBRARY" -verify_arch "$ARCH_NAME"
mv -f "$TEMP_LIBRARY" "$OUTPUT_LIBRARY"
touch "$OUTPUT_LIBRARY"
echo "已准备 ADB 静态库：$OUTPUT_LIBRARY"
