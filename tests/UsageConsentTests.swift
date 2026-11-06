import Foundation

@main struct UsageConsentTests {
    static func main() throws {
        assert(UsageConsent.Region.fromSystemCode(nil) == .mainland)
        assert(UsageConsent.Region.fromSystemCode("") == .mainland)
        assert(UsageConsent.Region.fromSystemCode("cn") == .mainland)
        for code in ["HK", "MO", "TW", "US"] {
            assert(UsageConsent.Region.fromSystemCode(code) == .international)
        }
        assert(!UsageConsent.isAccepted(nil))
        assert(UsageConsent.record(from: Data("damaged".utf8)) == nil)
        let accepted = UsageConsent.Record(
            version: UsageConsent.version, region: .mainland,
            language: "en", acceptedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let encoded = try JSONEncoder().encode(accepted)
        assert(UsageConsent.isAccepted(UsageConsent.record(from: encoded), region: .mainland))
        assert(!UsageConsent.isAccepted(accepted, region: .international))
        assert(!UsageConsent.isAccepted(UsageConsent.Record(
            version: "older", region: .mainland, language: "en", acceptedAt: .now
        )))
        assert(!UsageConsent.isAccepted(UsageConsent.Record(
            version: UsageConsent.version, region: .international, language: "", acceptedAt: .now
        )))
        assert(!UsageConsent.isAccepted(UsageConsent.Record(
            version: UsageConsent.version, region: .mainland,
            language: "zh-Hans", acceptedAt: Date(timeIntervalSince1970: 0)
        )))
        print("Usage consent tests passed")
    }
}
