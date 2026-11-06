@main struct LANScanEligibilityTests {
    static func main() {
        assert(LANScanEligibility.evaluate(wifi: nil, ethernet: nil, defaultPath: nil) == .checking)
        assert(LANScanEligibility.evaluate(wifi: false, ethernet: nil, defaultPath: true) == .checking)
        // Cellular/VPN with no usable physical LAN must not permit scans.
        assert(LANScanEligibility.evaluate(wifi: false, ethernet: false, defaultPath: true) == .noLAN)
        assert(LANScanEligibility.evaluate(wifi: false, ethernet: false, defaultPath: false) == .offline)
        // Wi-Fi or wired LAN is sufficient even if the default/VPN path is not
        // usable. Internet connectivity is not required to reach a local TV.
        assert(LANScanEligibility.evaluate(wifi: true, ethernet: false, defaultPath: false) == .available)
        assert(LANScanEligibility.evaluate(wifi: false, ethernet: true, defaultPath: nil) == .available)
        assert(LANScanEligibility.evaluate(wifi: true, ethernet: nil, defaultPath: nil) == .available)
        let sequence: [(Bool?, Bool?, Bool?)] = [(nil,nil,nil),(true,false,true),(false,false,true),(false,false,false),(false,true,true)]
        let states = sequence.map { LANScanEligibility.evaluate(wifi: $0.0, ethernet: $0.1, defaultPath: $0.2) }
        assert(states.map(\.canScan) == [false,true,false,false,true])
        for status in [LANScanEligibility.checking,.available,.offline,.noLAN] {
            assert(!status.messageKey.isEmpty)
        }
        print("LAN eligibility: startup, cellular/VPN, offline, Wi-Fi, Ethernet and recovery passed")
    }
}
