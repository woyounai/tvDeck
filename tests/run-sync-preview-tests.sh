#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
swiftc -module-cache-path "$test_dir/cache" iosadb/Core/RemoteEditorProtocol.swift \
  iosadb/Models/RemoteKeyboardSync.swift iosadb/Core/LatestFrameBuffer.swift iosadb/Core/TVRemoteKey.swift iosadb/Core/ShellCommandCatalog.swift \
  iosadb/Core/ADBHeartbeat.swift tests/SyncAndPreviewTests.swift -o "$test_dir/tests"
"$test_dir/tests"
