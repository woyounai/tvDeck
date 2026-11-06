/// Standard Android TV key codes; unsupported actions remain device-dependent.
enum TVRemoteKey: Int, CaseIterable {
    case home = 3, back = 4
    case zero = 7, one, two, three, four, five, six, seven, eight, nine
    case up = 19, down, left, right, ok
    case volumeUp = 24, volumeDown, power
    case enter = 66, delete = 67, menu = 82, search = 84, playPause = 85
    case rewind = 89, fastForward = 90
    case mute = 164, channelUp = 166, channelDown = 167, settings = 176, input = 178
}

enum RemoteControlOrder {
    static func normalized(_ rawValue: String?) -> [String] {
        let available = ["screen", "mouse"]
        var result: [String] = []
        for item in (rawValue ?? "").split(separator: ",").map(String.init) {
            if available.contains(item), !result.contains(item) { result.append(item) }
        }
        for item in available where !result.contains(item) { result.append(item) }
        return result
    }
}
