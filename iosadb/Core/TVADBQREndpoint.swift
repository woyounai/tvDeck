import Foundation

struct TVADBQREndpoint {
    let host: String
    let port: UInt16

    init?(_ payload: String) {
        guard let url = URLComponents(string: payload),
              url.scheme == "tvadb", url.host == "connect", url.path.isEmpty,
              let items = url.queryItems, items.count == 2,
              let host = items.first(where: { $0.name == "host" })?.value,
              let portText = items.first(where: { $0.name == "port" })?.value,
              items.filter({ $0.name == "host" }).count == 1,
              items.filter({ $0.name == "port" }).count == 1,
              let port = UInt16(portText), port > 0 else { return nil }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4,
              parts.allSatisfy({ part in
                  part.allSatisfy({ $0.isASCII && $0.isNumber })
                    && UInt8(part).map { String($0) == part } == true
              }),
              let first = UInt8(parts[0]), let second = UInt8(parts[1]),
              first == 10 || (first == 172 && (16...31).contains(second))
                || (first == 192 && second == 168) else { return nil }
        self.host = host
        self.port = port
    }
}

struct AerialQREndpoint {
    let url: URL

    init?(_ payload: String) {
        guard let components = URLComponents(string: payload),
              components.scheme == "http", let host = components.host,
              let port = components.port, (8790...8799).contains(port), components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              TVADBQREndpoint("tvadb://connect?host=\(host)&port=8790") != nil else { return nil }
        let path = components.path.split(separator: "/", omittingEmptySubsequences: false)
        let alphabet = Set("abcdefghjkmnpqrstuvwxyz23456789")
        guard path.count == 3, path[0].isEmpty, path[2].isEmpty,
              path[1].count == 8, path[1].allSatisfy({ alphabet.contains($0) }),
              payload == "http://\(host):\(port)/\(path[1])/",
              let url = components.url else { return nil }
        self.url = url
    }
}

enum ConnectionQRCode {
    case device(TVADBQREndpoint)
    case aerial(URL)

    init?(_ payload: String) {
        if let aerial = AerialQREndpoint(payload) { self = .aerial(aerial.url) }
        else if let device = TVADBQREndpoint(payload) { self = .device(device) }
        else { return nil }
    }
}
