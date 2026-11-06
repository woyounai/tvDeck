import Foundation

private final class MemoryDefaults: UserDefaults {
    private var values: [String: Any] = [:]
    override func data(forKey key: String) -> Data? { values[key] as? Data }
    override func set(_ value: Any?, forKey key: String) { values[key] = value }
    override func removeObject(forKey key: String) { values.removeValue(forKey: key) }
}

@main struct WidgetRemoteTests {
    static func main() {
        let defaults = MemoryDefaults(suiteName: "TVADBWidgetTests")!
        assert(WidgetRemoteStore.load(from: defaults) == nil)
        let first = WidgetRemoteState(connectionID: UUID(), deviceID: UUID(), name: "TV", connectedAt: .now)
        WidgetRemoteStore.save(first, to: defaults)
        assert(WidgetRemoteStore.load(from: defaults) == first)
        for key in WidgetRemoteCommand.allCases {
            assert(TVRemoteKey(rawValue: key.rawValue) != nil)
            assert(WidgetRemoteStore.accepts(connection: first.connectionID.uuidString, command: key.rawValue, state: first))
        }
        assert(!WidgetRemoteStore.accepts(connection: "invalid", command: 23, state: first))
        assert(!WidgetRemoteStore.accepts(connection: first.connectionID.uuidString, command: 9999, state: first))
        // Reconnecting even the same TV rotates the connection ID. A delayed
        // button from an old timeline cannot send to this new connection.
        var reconnected = first
        reconnected.connectionID = UUID()
        assert(!WidgetRemoteStore.accepts(connection: first.connectionID.uuidString, command: 23, state: reconnected))
        let second = WidgetRemoteState(connectionID: UUID(), deviceID: UUID(), name: "Other TV", connectedAt: .now)
        assert(!WidgetRemoteStore.accepts(connection: first.connectionID.uuidString, command: 23, state: second))
        WidgetRemoteStore.save(nil, to: defaults)
        assert(WidgetRemoteStore.load(from: defaults) == nil)
        assert(!WidgetRemoteStore.accepts(connection: first.connectionID.uuidString, command: 23, state: WidgetRemoteStore.load(from: defaults)))
        defaults.set(Data("invalid JSON".utf8), forKey: WidgetRemoteStore.key)
        assert(WidgetRemoteStore.load(from: defaults) == nil)
        print("Widget state, key mappings, reconnect/device-switch and disconnect guards passed")
    }
}
