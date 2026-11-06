import Foundation

@main struct DirectionalKeyRepeaterTests {
    @MainActor
    static func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while !condition() {
            precondition(ContinuousClock.now < deadline, "Timed out")
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    @MainActor
    static func main() async throws {
        var motion = MouseMotionAccumulator()
        motion.add(x: 0.75, y: -0.5)
        assert(!motion.hasWholeMovement && motion.take() == nil)
        motion.add(x: 0.75, y: -1.75)
        motion.add(x: 3.75, y: 0.75)
        let batch = motion.take()
        assert(batch?.x == 5 && batch?.y == -1)
        motion.add(x: -1.5, y: 1.75)
        let remainder = motion.take()
        assert(remainder?.x == -1 && remainder?.y == 1)
        assert(motion.take() == nil)

        var swipe = TwoFingerNavigation()
        assert(swipe.update(dx: 13, dy: 0) == nil)
        assert(swipe.update(dx: 20, dy: 18) == nil)
        assert(swipe.update(dx: 24, dy: 18) == 22)
        assert(swipe.update(dx: -40, dy: 0) == nil)
        swipe = TwoFingerNavigation()
        assert(swipe.update(dx: -14, dy: 0) == 21)
        swipe = TwoFingerNavigation()
        assert(swipe.update(dx: 0, dy: -14) == 19)
        swipe = TwoFingerNavigation()
        assert(swipe.update(dx: 0, dy: 14) == 20)
        swipe = TwoFingerNavigation()
        swipe.cancel()
        assert(swipe.update(dx: 40, dy: 0) == nil)

        assert(RemoteVolumeMapping.target(start: 7, delta: 0, min: 0, max: 15) == 7)
        assert(RemoteVolumeMapping.target(start: 7, delta: 100, min: 0, max: 15) == 15)
        assert(RemoteVolumeMapping.target(start: 7, delta: -100, min: 0, max: 15) == 0)
        assert(RemoteVolumeMapping.target(start: 15, delta: -20, min: 0, max: 15) < 15)
        var remoteTaps = 0, remoteHolds = 0, remoteEnds = 0
        var remoteDeltas: [Double] = []
        let remote = RemoteHoldInteraction(
            onTap: { remoteTaps += 1 }, onHold: { remoteHolds += 1 },
            onMove: { remoteDeltas.append($0) }, onEnd: { remoteEnds += 1 }
        )
        remote.begin(y: 100); remote.finish()
        assert(remoteTaps == 1 && remoteHolds == 0)
        remote.begin(y: 100); remote.move(y: 120); remote.finish()
        assert(remoteTaps == 1 && remoteHolds == 0) // moving before hold cancels
        remote.begin(y: 100)
        try await waitUntil { remoteHolds == 1 }
        remote.move(y: 80); remote.move(y: 120); remote.finish()
        assert(remoteTaps == 1 && remoteEnds == 1 && remoteDeltas == [20, -20])
        assert(DirectionalKeyRepeater.delay(beforeRepeat: 0) == .milliseconds(100))
        assert(DirectionalKeyRepeater.delay(beforeRepeat: 1) == .milliseconds(90))
        assert(DirectionalKeyRepeater.delay(beforeRepeat: 5) == .milliseconds(55))
        assert(DirectionalKeyRepeater.delay(beforeRepeat: 13) == .milliseconds(35))
        var taps = 0
        var holds = 0
        var releases = 0
        let press = DirectionalPressInteraction(
            onTap: { taps += 1 }, onHold: { holds += 1 },
            onRelease: { releases += 1 }
        )
        press.begin()
        press.finish() // down/up in the SAME actor turn was previously lost
        assert(taps == 1 && holds == 0 && releases == 1)
        try await Task.sleep(for: .milliseconds(400))
        assert(taps == 1 && holds == 0)
        press.begin()
        try await waitUntil { holds == 1 }
        press.finish()
        assert(taps == 1 && releases == 2) // no extra click after a hold
        press.begin()
        press.cancel() // leaving the button must not count as a click
        press.finish()
        try await Task.sleep(for: .milliseconds(400))
        assert(taps == 1 && holds == 1)

        let repeater = DirectionalKeyRepeater()
        var keys: [Int] = []
        repeater.start(key: 21) { keys.append($0); return true }
        try await waitUntil { keys.count == 1 }
        repeater.start(key: 21) { keys.append($0); return true }
        try await Task.sleep(for: .milliseconds(50))
        assert(keys == [21]) // one immediate event, no duplicate touch-down
        repeater.stop(key: 21)
        try await Task.sleep(for: .milliseconds(450))
        assert(keys == [21]) // short tap does not leave a repeat behind

        repeater.start(key: 22) { keys.append($0); return true }
        try await waitUntil { keys.count >= 3 }
        assert(keys.dropFirst().allSatisfy { $0 == 22 })
        repeater.stop()
        let stoppedCount = keys.count
        try await Task.sleep(for: .milliseconds(450))
        assert(keys.count == stoppedCount)

        repeater.start(key: 19) { keys.append($0); return true }
        try await waitUntil { keys.last == 19 }
        repeater.start(key: 20) { keys.append($0); return true }
        repeater.stop(key: 19) // an old finger's release cannot stop the new key
        assert(repeater.activeKey == 20)
        try await waitUntil { keys.last == 20 }
        repeater.stop()

        var pending: CheckedContinuation<Bool, Never>?
        var slowCount = 0
        repeater.start(key: 21) { _ in
            slowCount += 1
            return await withCheckedContinuation { pending = $0 }
        }
        try await waitUntil { pending != nil }
        try await Task.sleep(for: .milliseconds(650))
        assert(slowCount == 1) // slow transport never queues repeat requests
        repeater.stop()
        pending?.resume(returning: true)
        try await Task.sleep(for: .milliseconds(450))
        assert(slowCount == 1)

        var failures = 0
        repeater.start(key: 22) { _ in failures += 1; return false }
        try await waitUntil { failures == 1 && repeater.activeKey == nil }
        repeater.start(key: 23) { _ in fatalError("OK must not repeat") }
        assert(repeater.activeKey == nil)
        for code in [24, 25, 166, 167] {
            repeater.start(key: code) { keys.append($0); return true }
            try await waitUntil { keys.last == code }
            repeater.stop(key: code)
        }
        print("DirectionalKeyRepeater tests passed")
    }
}
