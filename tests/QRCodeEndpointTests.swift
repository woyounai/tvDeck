import Foundation

@main struct QRCodeEndpointTests {
    static func main() {
        let valid = TVADBQREndpoint("tvadb://connect?host=192.168.1.23&port=5555")
        assert(valid?.host == "192.168.1.23" && valid?.port == 5555)
        assert(TVADBQREndpoint("tvadb://connect?host=10.0.0.2&port=32768") != nil)
        for payload in [
            "https://connect?host=192.168.1.23&port=5555",
            "tvadb://connect?host=8.8.8.8&port=5555",
            "tvadb://connect?host=192.168.1.23&port=0",
            "tvadb://connect?host=192.168.1.23&port=65536",
            "tvadb://connect?host=192.168.001.23&port=5555",
            "tvadb://connect?host=192.168.1.23&host=10.0.0.1",
            "tvadb://connect?host=192.168.1.23&port=5555&key=secret",
            "tvadb://connect?host=example.com&port=5555"
        ] {
            assert(TVADBQREndpoint(payload) == nil, payload)
        }
        print("QR endpoint validation passed")
    }
}
