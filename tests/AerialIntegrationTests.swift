import Foundation
import CryptoKit

enum L10n { static func string(_ value: String) -> String { value } }

@main
struct AerialIntegrationTests {
    static func main() throws {
        let standard = try AerialInstallation.parse(package: "app.orchard.tv", dump: "  versionCode=99 minSdk=21\n  versionName=1.77\n")
        precondition(standard.code == 99 && standard.flavor == .standard)
        let guardVersion = try AerialInstallation.parse(package: "app.orchard.tv", dump: "versionName=1.77-guard\nversionCode=99")
        precondition(guardVersion.flavor == .guardVersion)
        let lite = try AerialInstallation.parse(package: "app.orchard.tv.lite", dump: "versionName=0.1\nversionCode=1")
        precondition(lite.flavor == .lite)
        for dump in ["", "versionCode=99", "versionCode=99bad\nversionName=1.77", "versionCode=-1\nversionName=1.77"] {
            do { _ = try AerialInstallation.parse(package: "app.orchard.tv", dump: dump); preconditionFailure("Invalid status accepted") } catch {}
        }
        let bytes = Data("test".utf8)
        let digest = "sha256:" + SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        func metadata(url: String = "https://github.com/pengchujin/aerial-tv/releases/download/v1.77/aerial-standard.apk", size: Int = 4, hash: String? = nil, prerelease: Bool = false) throws -> Data {
            try JSONSerialization.data(withJSONObject: ["tag_name": "v1.77", "draft": false, "prerelease": prerelease, "assets": [["name": "aerial-standard.apk", "browser_download_url": url, "size": size, "digest": hash ?? digest]]])
        }
        let release = try AerialRelease.parse(metadata(), flavor: .standard)
        try release.verify(bytes)
        for bad in [Data("evil".utf8), Data("tes".utf8)] {
            do { try release.verify(bad); preconditionFailure("Bad download accepted") } catch {}
        }
        let invalid = [try metadata(url: "http://github.com/pengchujin/aerial-tv/releases/download/v1.77/aerial-standard.apk"), try metadata(url: "https://example.com/aerial-standard.apk"), try metadata(url: "https://github.com/other/repo/releases/download/v1.77/aerial-standard.apk"), try metadata(size: 0), try metadata(size: 64 * 1024 * 1024 + 1), try metadata(hash: "sha256:1234"), try metadata(prerelease: true)]
        for data in invalid {
            do { _ = try AerialRelease.parse(data, flavor: .standard); preconditionFailure("Bad release accepted") } catch {}
        }
        do { _ = try AerialRelease.parse(metadata(), flavor: .guardVersion); preconditionFailure("Wrong edition accepted") } catch {}
        do {
            _ = try AerialRelease.parse(Data("invalid JSON".utf8), flavor: .standard)
            preconditionFailure("Invalid release metadata accepted")
        } catch AerialIntegrationError.releaseUnavailable {}
        precondition(try! AerialSetup.mergedListeners("null") == AerialSetup.listener)
        let existing = "com.vendor/.Notify$Inner"
        let merged = try AerialSetup.mergedListeners(existing)
        precondition(merged == existing + ":" + AerialSetup.listener)
        precondition(try! AerialSetup.mergedListeners(merged) == merged)
        do { _ = try AerialSetup.mergedListeners("Error: denied"); preconditionFailure("Bad setting accepted") } catch {}
        precondition(AerialSetup.grantCommands(.lite).count == 1)
        let home = AerialSetup.homeStatus(flavor: .standard, resolved: "android.app.role.HOME\napp.orchard.tv/.MainActivity\n", accessibility: "com.vendor/.Service:\(AerialSetup.homeGuard)\n")
        precondition(home.defaultHome && home.guardEnabled)
        let other = AerialSetup.homeStatus(flavor: .standard, resolved: "com.vendor/.Home", accessibility: "null")
        precondition(!other.defaultHome && !other.guardEnabled)
        precondition(AerialSetup.launchCommand(.lite).contains(".HomeActivity"))
        precondition(AerialSetup.launchSucceeded("Starting: Intent {}\nStatus: ok\n"))
        precondition(!AerialSetup.launchSucceeded("Error: Activity class does not exist"))
        precondition(!AerialSetup.commandSucceeded(code: 0, output: "Unknown operation string: AUTO_START"))
        precondition(!AerialSetup.commandSucceeded(code: 1, output: ""))
        print("Aerial installation, setup and release verification tests passed")
    }
}
