#!/bin/bash
set -euo pipefail
PROJECT_ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEVICE_ID="${1:?Pass a booted iOS simulator UDID}"
SIM_ARCH="${ADB_SIMULATOR_ARCH:-$(uname -m)}"
"$PROJECT_ROOT/scripts/prepare-adb-prebuilt.sh" iphonesimulator "$SIM_ARCH"
SMOKE_DIR=$(mktemp -d)
trap 'rm -rf "$SMOKE_DIR"' EXIT
xcrun --sdk iphonesimulator clang++ -target "$SIM_ARCH-apple-ios17.0-simulator" -std=c++20 \
  "$PROJECT_ROOT/tests/ADBSimulatorSmoke.cpp" \
  "$PROJECT_ROOT/Vendor/adb-mobile/output/iphonesimulator/$SIM_ARCH/libadb-full.a" \
  -lz -framework Foundation -o "$SMOKE_DIR/adb-smoke"
# Version only: no network connection, server startup or TV commands.
xcrun simctl spawn "$DEVICE_ID" "$SMOKE_DIR/adb-smoke"
