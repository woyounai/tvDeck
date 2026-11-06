import Foundation

/// Best-effort IME evidence for display; OEM dumps are not a sending prerequisite.
enum RemoteTextInput {
    static func canSend(text: String, isConnected: Bool, isSending: Bool) -> Bool {
        isConnected && !isSending && !text.isEmpty
    }

    static func hasEditor(_ dump: String) -> Bool {
        guard !dump.contains("mCurFocusedWindow=null"),
              !dump.contains("mCurClient=null"),
              let marker = dump.range(of: "mCurAttribute") else { return false }
        let remainder = String(dump[marker.upperBound...].prefix(1000))
        guard !remainder.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("=null"),
              !remainder.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix(": null") else { return false }
        // Stop before the next dumpsys member, rather than matching a stale client.
        let block = remainder.components(separatedBy: "\n").prefix { line in
            !line.trimmingCharacters(in: .whitespaces).hasPrefix("m")
        }.joined(separator: "\n")
        guard let range = block.range(of: #"inputType=0x[0-9a-fA-F]+"#, options: .regularExpression),
              let value = UInt(block[range].dropFirst(12), radix: 16) else { return false }
        return value & 0xf != 0
    }

    static func commands(for text: String) throws -> [String] {
        guard !text.isEmpty, text.utf8.count <= 4096,
              text.unicodeScalars.allSatisfy({ (32...126).contains($0.value) }) else {
            throw InputError.unsupportedText
        }
        // Android expands %s to space. Isolate literal percentages first.
        var commands: [String] = []
        let parts = text.components(separatedBy: "%")
        for (index, part) in parts.enumerated() {
            if index > 0 { commands.append("input text '%' 2>&1") }
            if !part.isEmpty {
                let encoded = part.replacingOccurrences(of: " ", with: "%s")
                    .replacingOccurrences(of: "'", with: "'\"'\"'")
                commands.append("input text '\(encoded)' 2>&1")
            }
        }
        return commands
    }

    enum InputError: LocalizedError {
        case unsupportedText
        var errorDescription: String? {
            switch self {
            case .unsupportedText: return L10n.string("text.keyboard_ascii_only")
            }
        }
    }
}
