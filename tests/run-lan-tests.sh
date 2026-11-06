#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/cache" iosadb/Core/LANScanEligibility.swift tests/LANScanEligibilityTests.swift -o "$test_dir/tests"
"$test_dir/tests"
