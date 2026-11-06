import Foundation

enum UsageConsent {
    static let version = "2026-11-06.1"
    static let storageKey = "usageConsent.record"

    enum Region: String, Codable {
        case mainland, international

        var titleKey: String { self == .mainland ? "legal_mainland" : "legal_international" }

        static func fromSystemCode(_ code: String?) -> Region {
            guard let code, !code.isEmpty else { return .mainland }
            return code.uppercased() == "CN" ? .mainland : .international
        }

        static var system: Region { fromSystemCode(Locale.current.region?.identifier) }
    }

    struct Record: Codable {
        let version: String
        let region: Region
        let language: String
        let acceptedAt: Date
    }

    static func record(from data: Data) -> Record? {
        try? JSONDecoder().decode(Record.self, from: data)
    }

    static var savedRecord: Record? {
        record(from: UserDefaults.standard.data(forKey: storageKey) ?? Data())
    }

    static func isAccepted(_ record: Record?, region: Region = .system) -> Bool {
        guard let record else { return false }
        return record.version == version && record.region == region
            && ["en", "zh-Hans"].contains(record.language)
            && record.acceptedAt.timeIntervalSince1970 > 0
    }

    static var isAccepted: Bool { isAccepted(savedRecord) }

    static func accept(region: Region, language: String, now: Date = .now) throws {
        let record = Record(version: version, region: region, language: language, acceptedAt: now)
        guard isAccepted(record) else { throw CocoaError(.coderInvalidValue) }
        UserDefaults.standard.set(try JSONEncoder().encode(record), forKey: storageKey)
    }
}
