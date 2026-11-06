#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
printf '%s\n' 'enum L10n { static func string(_ value: String) -> String { value } }' > "$test_dir/L10n.swift"
swiftc -module-cache-path "$test_dir/cache" iosadb/Core/RemoteTextInput.swift "$test_dir/L10n.swift" tests/RemoteTextInputTests.swift -o "$test_dir/tests"
"$test_dir/tests"
