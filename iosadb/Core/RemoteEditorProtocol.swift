import Foundation

struct RemoteEditorSnapshot: Equatable, Sendable {
    var token: String
    var text: String
}

enum RemoteEditorReply: Equatable, Sendable {
    case state(RemoteEditorSnapshot), conflict(RemoteEditorSnapshot)
    case noEditor, unsupported, done
}

enum RemoteEditorProtocol {
    enum Failure: Error { case invalidReply, unsupportedText, rejected }
    static func encode(_ text: String) throws -> String {
        guard text.utf8.count <= 8192, !text.contains("\0") else { throw Failure.unsupportedText }
        return text.isEmpty ? "-" : Data(text.utf8).base64EncodedString()
    }
    static func parse(_ line: String, sequence: Int) throws -> RemoteEditorReply {
        let parts = line.split(separator: " ").map(String.init)
        guard parts.count >= 2, parts[1] == String(sequence) else { throw Failure.invalidReply }
        switch parts[0] {
        case "NONE" where parts.count == 2: return .noEditor
        case "UNSUPPORTED" where parts.count == 2: return .unsupported
        case "DONE" where parts.count == 2: return .done
        case "STATE", "CONFLICT":
            guard parts.count == 4, Data(base64Encoded: parts[2]) != nil else { throw Failure.invalidReply }
            let text: String
            if parts[3] == "-" { text = "" }
            else {
                guard let data = Data(base64Encoded: parts[3]), data.count <= 8192,
                      let decoded = String(data: data, encoding: .utf8) else { throw Failure.invalidReply }
                text = decoded
            }
            let snapshot = RemoteEditorSnapshot(token: parts[2], text: text)
            return parts[0] == "STATE" ? .state(snapshot) : .conflict(snapshot)
        default: throw Failure.rejected
        }
    }
}
