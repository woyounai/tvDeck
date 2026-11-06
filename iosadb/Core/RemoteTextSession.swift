import Foundation

/// A single keyboard sheet owns this session. A request waits for an actual
/// editor snapshot; successful stream writes alone never count as text sync.
actor RemoteTextSession {
    private let stream: ADBPersistentShellSession
    private var sequence = 0
    private var busy = false
    init(stream: ADBPersistentShellSession) { self.stream = stream }
    func close() async { await stream.close() }
    func readEditor() async throws -> RemoteEditorReply { try await exchange("GET") }
    func setText(_ snapshot: RemoteEditorSnapshot, text: String) async throws -> RemoteEditorReply {
        try await exchange("SET", arguments: "\(snapshot.token) \(try RemoteEditorProtocol.encode(snapshot.text)) \(try RemoteEditorProtocol.encode(text))")
    }
    func confirm(_ snapshot: RemoteEditorSnapshot) async throws -> RemoteEditorReply {
        try await exchange("CONFIRM", arguments: "\(snapshot.token) \(try RemoteEditorProtocol.encode(snapshot.text))")
    }
    private func exchange(_ verb: String, arguments: String = "") async throws -> RemoteEditorReply {
        guard !busy else { throw RemoteEditorProtocol.Failure.invalidReply }
        busy = true
        defer { busy = false }
        sequence += 1
        let command = "\(verb) \(sequence)" + (arguments.isEmpty ? "" : " " + arguments) + "\n"
        try await stream.write(Data(command.utf8))
        return try RemoteEditorProtocol.parse(await Self.line(from: stream), sequence: sequence)
    }
    static func line(from stream: ADBPersistentShellSession, timeoutSeconds: Int = 5) async throws -> String {
        try await withTaskCancellationHandler {
            try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                var data = Data()
                while let chunk = try await stream.read() {
                    data.append(chunk)
                    guard data.count <= 50000 else { throw RemoteEditorProtocol.Failure.invalidReply }
                    if let end = data.firstIndex(of: 10) {
                        return String(decoding: data[..<end], as: UTF8.self)
                            .trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                }
                throw ADBClientError.connectionClosed
            }
            group.addTask {
                try await Task.sleep(for: .seconds(timeoutSeconds))
                try Task.checkCancellation()
                await stream.close()
                throw ADBClientError.connectionTimedOut
            }
            defer { group.cancelAll() }
            guard let result = try await group.next() else { throw ADBClientError.connectionClosed }
            return result
            }
        } onCancel: {
            Task { await stream.close() }
        }
    }
}
