import Foundation

/// Shared presentation data only. ADB keys and saved endpoints stay in the app.
struct WidgetRemoteState: Codable, Equatable {
    var connectionID: UUID
    var deviceID: UUID
    var name: String
    var connectedAt: Date
    var lastActionSucceeded: Bool?
}

enum WidgetRemoteCommand: Int, CaseIterable {
    case home = 3, back = 4
    case up = 19, down = 20, left = 21, right = 22, ok = 23
    case volumeUp = 24, volumeDown = 25, power = 26
    case menu = 82, playPause = 85, mute = 164, input = 178
}

enum WidgetRemoteStore {
    static let kind = "TVADBRemoteWidget"
    static let key = "tvadb.widget.remote.v1"
    static var groupID: String {
        Bundle.main.object(forInfoDictionaryKey: "TVADBAppGroup") as? String ?? "group.com.tvadb.ios"
    }
    static func load(from defaults: UserDefaults? = UserDefaults(suiteName: groupID)) -> WidgetRemoteState? {
        guard let data = defaults?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(WidgetRemoteState.self, from: data)
    }
    static func save(_ state: WidgetRemoteState?, to defaults: UserDefaults? = UserDefaults(suiteName: groupID)) {
        if let state, let data = try? JSONEncoder().encode(state) { defaults?.set(data, forKey: key) }
        else { defaults?.removeObject(forKey: key) }
    }
    static func accepts(connection: String, command: Int, state: WidgetRemoteState?) -> Bool {
        guard let state else { return false }
        return UUID(uuidString: connection) == state.connectionID && WidgetRemoteCommand(rawValue: command) != nil
    }
}
