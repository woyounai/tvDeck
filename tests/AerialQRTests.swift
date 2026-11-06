import Foundation

@main
struct AerialQRTests {
    static func main() {
        for host in ["192.168.1.20", "10.0.0.1", "172.16.0.1", "172.31.255.254"] {
            let value = "http://\(host):8790/abc234xy/"
            precondition(AerialQREndpoint(value)?.url.absoluteString == value)
        }
        precondition(AerialQREndpoint("http://192.168.1.20:8799/abc234xy/") != nil)
        for value in [
            "http://192.168.1.20:8800/abc234xy/",
            "https://192.168.1.20:8790/abc234xy/", "http://8.8.8.8:8790/abc234xy/",
            "http://172.32.0.1:8790/abc234xy/", "http://127.0.0.1:8790/abc234xy/",
            "http://192.168.01.20:8790/abc234xy/", "http://tv.local:8790/abc234xy/",
            "http://192.168.1.20:5555/abc234xy/", "http://192.168.1.20:8790/abc234xy",
            "http://user@192.168.1.20:8790/abc234xy/", "http://192.168.1.20:8790/abc234xy/?x=1",
            "http://192.168.1.20:8790/abc234xy/#x", "http://192.168.1.20:8790/abc234xy/api/state",
            "http://192.168.1.20:8790/%61bc234xy/", "http://192.168.1.20:8790/abc234x0/",
            "http://192.168.1.20:8790/abc234x/", "tvadb://connect?host=192.168.1.20&port=5555"
        ] { precondition(AerialQREndpoint(value) == nil, "Invalid QR accepted") }
        precondition(TVADBQREndpoint("tvadb://connect?host=192.168.1.20&port=5555")?.port == 5555)
        switch ConnectionQRCode("http://192.168.1.20:8790/abc234xy/") {
        case .aerial(let url): precondition(url.port == 8790)
        default: preconditionFailure("Aerial QR was not routed")
        }
        switch ConnectionQRCode("tvadb://connect?host=192.168.1.20&port=5555") {
        case .device(let device): precondition(device.port == 5555)
        default: preconditionFailure("Device QR was not routed")
        }
        for invalid in ["https://example.com/", "http://192.168.1.20:8790/", "http://8.8.8.8:8790/abc234xy/", "tvadb://connect?host=192.168.1.20&port=0"] {
            precondition(ConnectionQRCode(invalid) == nil, "Unsupported QR accepted")
        }
        print("Aerial QR trust boundary tests passed")
    }
}
