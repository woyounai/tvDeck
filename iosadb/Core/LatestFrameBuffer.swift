import Foundation

/// Bounded decoded-frame mailbox: one latest frame and at most one scheduled
/// display delivery. Never use this to discard inter-dependent H.264 packets.
final class LatestFrameBuffer<Element: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var session: UUID?
    private var current: Element?
    private var pending: Element?
    private var scheduled = false
    func beginSession(_ id: UUID) {
        lock.lock(); defer { lock.unlock() }
        session = id; current = nil; pending = nil; scheduled = false
    }
    func offer(_ value: Element, session id: UUID) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard session == id else { return false }
        current = value; pending = value
        guard !scheduled else { return false }
        scheduled = true
        return true
    }
    func takePending() -> Element? {
        lock.lock(); defer { lock.unlock() }
        let result = pending; pending = nil; scheduled = false
        return result
    }
    var latest: Element? {
        lock.lock(); defer { lock.unlock() }; return current
    }
    func clear() {
        lock.lock(); defer { lock.unlock() }
        session = nil; current = nil; pending = nil; scheduled = false
    }
}

enum LivePreviewProfile {
    static func size(width: Int, height: Int) -> (width: Int, height: Int) {
        let scale = min(1.0, 1280.0 / Double(max(width, height, 1)))
        return (max(16, Int(Double(width) * scale) / 16 * 16),
                max(16, Int(Double(height) * scale) / 16 * 16))
    }
}

/// Incremental, bounded framing for the shell-owned hardware capture helper.
struct VideoPacketParser {
    enum Failure: Error { case invalid, remote(Int, Int) }
    struct Packet {
        let kind: UInt8
        let data: Data
        func integer(_ offset: Int) -> Int {
            Int(Int32(bitPattern: data.dropFirst(offset).prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }))
        }
    }
    private var pending = Data()
    private var magic = false
    private var info = false
    static let limit = 4 * 1024 * 1024

    mutating func append(_ chunk: Data) throws -> [Packet] {
        guard chunk.count <= Self.limit, pending.count + chunk.count <= Self.limit * 2 + 5 else { throw Failure.invalid }
        pending.append(chunk)
        if !magic {
            guard pending.count >= 8 else { return [] }
            guard pending.prefix(8) == Data("TVADBV01".utf8) else { throw Failure.invalid }
            pending = Data(pending.dropFirst(8)); magic = true
        }
        var packets: [Packet] = []
        while pending.count >= 5 {
            let kind = pending[0]
            let count = pending[1..<5].reduce(0) { ($0 << 8) | Int($1) }
            guard (1...4).contains(kind), (1...Self.limit).contains(count),
                  kind != 1 || count == 20, kind != 4 || count == 8 else { throw Failure.invalid }
            guard pending.count >= count + 5 else { break }
            let packet = Packet(kind: kind, data: pending.subdata(in: 5..<(5 + count)))
            pending = Data(pending.dropFirst(count + 5))
            if kind == 4 { throw Failure.remote(packet.integer(0), packet.integer(4)) }
            if kind == 1 {
                guard !info, stride(from: 0, to: 16, by: 4).allSatisfy({ (1...16384).contains(packet.integer($0)) }),
                      (1...30).contains(packet.integer(16)) else { throw Failure.invalid }
                info = true
            } else {
                guard info, (packet.data.count > 3 && packet.data.starts(with: [0, 0, 1])) || (packet.data.count > 4 && packet.data.starts(with: [0, 0, 0, 1])) else { throw Failure.invalid }
            }
            packets.append(packet)
        }
        return packets
    }

    func finish() throws {
        guard magic, info, pending.isEmpty else { throw Failure.invalid }
    }
}
