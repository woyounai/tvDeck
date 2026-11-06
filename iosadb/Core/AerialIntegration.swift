import Foundation
import CryptoKit

enum AerialFlavor: String, CaseIterable, Sendable {
    case standard, guardVersion = "guard", lite
    var packageName: String { self == .lite ? "app.orchard.tv.lite" : "app.orchard.tv" }
    var filename: String { "aerial-\(rawValue).apk" }
}

struct AerialInstallation: Sendable {
    let flavor: AerialFlavor
    let version: String
    let code: UInt64

    static func parse(package: String, dump: String) throws -> Self {
        let lines = dump.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }
        guard ["app.orchard.tv", "app.orchard.tv.lite"].contains(package),
              let version = lines.first(where: { $0.hasPrefix("versionName=") }).map({ String($0.dropFirst(12)) }), !version.isEmpty,
              let codeLine = lines.first(where: { $0.hasPrefix("versionCode=") }),
              let code = codeLine.dropFirst(12).split(whereSeparator: \.isWhitespace).first.flatMap({ UInt64($0) }) else {
            throw AerialIntegrationError.invalidStatus
        }
        return Self(flavor: package.hasSuffix(".lite") ? .lite : version.contains("-guard") ? .guardVersion : .standard, version: version, code: code)
    }
}

enum AerialIntegrationError: LocalizedError {
    case invalidStatus, releaseUnavailable, releaseHTTP(Int), downloadHTTP(Int), invalidDownload, deviceChanged, unsupportedSDK
    var errorDescription: String? {
        let key = switch self {
        case .invalidStatus: "error.aerial_detection"
        case .releaseUnavailable: "error.aerial_release"
        case .releaseHTTP: "error.aerial_release_http"
        case .downloadHTTP: "error.aerial_download_http"
        case .invalidDownload: "error.aerial_download"
        case .deviceChanged: "error.aerial_device_changed"
        case .unsupportedSDK: "error.aerial_unsupported_sdk"
        }
        switch self {
        case .releaseHTTP(let status), .downloadHTTP(let status):
            return String(format: L10n.string(key), status)
        default:
            return L10n.string(key)
        }
    }
}

struct AerialRelease: Sendable {
    let version: String
    let url: URL
    let size: Int
    let sha256: String
    static let api = URL(string: "https://api.github.com/repos/pengchujin/aerial-tv/releases/latest")!

    static func parse(_ data: Data, flavor: AerialFlavor) throws -> Self {
        struct Release: Decodable {
            struct Asset: Decodable { let name: String; let browser_download_url: URL; let size: Int; let digest: String? }
            let tag_name: String; let draft: Bool; let prerelease: Bool; let assets: [Asset]
        }
        let release: Release
        do { release = try JSONDecoder().decode(Release.self, from: data) }
        catch { throw AerialIntegrationError.releaseUnavailable }
        guard !release.draft, !release.prerelease,
              let asset = release.assets.first(where: { $0.name == flavor.filename }),
              asset.size > 0, asset.size <= 64 * 1024 * 1024,
              let digest = asset.digest, digest.hasPrefix("sha256:"),
              digest.dropFirst(7).count == 64,
              digest.dropFirst(7).allSatisfy({ $0.isASCII && $0.isHexDigit }),
              let components = URLComponents(url: asset.browser_download_url, resolvingAgainstBaseURL: false),
              components.scheme == "https", components.host == "github.com", components.port == nil,
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil,
              release.tag_name.range(of: #"^v[0-9]+(?:\.[0-9]+)*$"#, options: .regularExpression) != nil,
              components.percentEncodedPath == "/pengchujin/aerial-tv/releases/download/\(release.tag_name)/\(flavor.filename)" else {
            throw AerialIntegrationError.releaseUnavailable
        }
        return Self(version: release.tag_name, url: asset.browser_download_url, size: asset.size, sha256: String(digest.dropFirst(7)).lowercased())
    }

    static func latest(flavor: AerialFlavor) async throws -> Self {
        var request = URLRequest(url: api, timeoutInterval: 25)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("TVDeck", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw AerialIntegrationError.releaseHTTP(status) }
        guard data.count <= 1024 * 1024 else { throw AerialIntegrationError.releaseUnavailable }
        return try parse(data, flavor: flavor)
    }

    func verify(_ data: Data) throws {
        guard data.count == size,
              SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == sha256 else { throw AerialIntegrationError.invalidDownload }
    }

    func download(progress: @Sendable (Double) async -> Void = { _ in }) async throws -> Data {
        let request = URLRequest(url: url, timeoutInterval: 120)
        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw AerialIntegrationError.downloadHTTP(status) }
        var data = Data()
        data.reserveCapacity(size)
        for try await byte in bytes {
            guard data.count < size else { throw AerialIntegrationError.invalidDownload }
            data.append(byte)
            if data.count % 65536 == 0 { await progress(Double(data.count) / Double(size)) }
        }
        try verify(data)
        await progress(1)
        return data
    }
}

struct AerialSetupResult: Sendable {
    let report: String
    let warnings: Int
    let launched: Bool
}

enum AerialSetup {
    static let listener = "app.orchard.tv/app.orchard.tv.guard.RevivalListener"
    static let homeGuard = "app.orchard.tv/app.orchard.tv.guard.HomeGuardService"
    static let resolveHomeCommand = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT"
    static func homeStatus(flavor: AerialFlavor, resolved: String, accessibility: String) -> (defaultHome: Bool, guardEnabled: Bool) {
        let component = resolved.split(whereSeparator: \.isNewline).last?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return (component.hasPrefix(flavor.packageName + "/"), accessibility.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":").contains(Substring(homeGuard)))
    }
    static func grantCommands(_ flavor: AerialFlavor) -> [String] {
        if flavor == .lite { return ["pm grant app.orchard.tv.lite android.permission.WRITE_SECURE_SETTINGS"] }
        return [
            "pm grant app.orchard.tv android.permission.ACCESS_FINE_LOCATION",
            "pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS",
            "appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow",
            "appops set app.orchard.tv WRITE_SETTINGS allow",
            "appops set app.orchard.tv REQUEST_INSTALL_PACKAGES allow",
            "appops set app.orchard.tv GET_USAGE_STATS allow",
            "appops set app.orchard.tv android:get_usage_stats allow",
            "appops set app.orchard.tv ACCESS_RESTRICTED_SETTINGS allow",
            "appops set app.orchard.tv APP_AUTO_START allow",
            "appops set app.orchard.tv APP_ASSOC_START allow",
            "appops set app.orchard.tv AUTO_START allow",
            "appops set app.orchard.tv ASSOC_START allow"
        ]
    }
    static func mergedListeners(_ raw: String) throws -> String {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.contains("\n"), !value.contains("\r"), !value.contains("\0") else { throw AerialIntegrationError.invalidStatus }
        var components = value.isEmpty || value == "null" ? [] : value.split(separator: ":").map(String.init)
        guard components.allSatisfy({ $0.contains("/") && !$0.contains(" ") }) else { throw AerialIntegrationError.invalidStatus }
        if !components.contains(listener) { components.append(listener) }
        return components.joined(separator: ":")
    }
    static func launchCommand(_ flavor: AerialFlavor) -> String {
        "am start -W -n " + (flavor == .lite ? "app.orchard.tv.lite/.HomeActivity" : "app.orchard.tv/.MainActivity")
    }
    static func commandSucceeded(code: Int, output: String) -> Bool {
        let lower = output.lowercased()
        return code == 0 && !["error:", "exception", "unknown command", "unknown operation", "not found", "permission denial"].contains(where: lower.contains)
    }
    static func launchSucceeded(_ output: String) -> Bool {
        output.range(of: #"(?m)^\s*Status:\s*ok\s*$"#, options: .regularExpression) != nil
    }
}
