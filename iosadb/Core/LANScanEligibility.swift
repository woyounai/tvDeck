/// Passive path checks do not scan the LAN or prove local-network permission.
enum LANScanEligibility: Equatable {
    case checking, available, offline, noLAN
    var canScan: Bool { self == .available }
    var messageKey: String {
        switch self {
        case .checking: "text.lan_checking"
        case .available: "text.lan_available"
        case .offline: "text.lan_offline"
        case .noLAN: "text.lan_unavailable"
        }
    }
    static func evaluate(wifi: Bool?, ethernet: Bool?, defaultPath: Bool?) -> Self {
        if wifi == true || ethernet == true { return .available }
        guard wifi != nil, ethernet != nil, let defaultPath else { return .checking }
        return defaultPath ? .noLAN : .offline
    }
}
