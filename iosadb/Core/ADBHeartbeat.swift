import Foundation

final class ADBActivity: @unchecked Sendable {
    private let lock = NSLock()
    private var lastActivity: ContinuousClock.Instant

    init(now: ContinuousClock.Instant = .now) {
        lastActivity = now
    }

    func record(now: ContinuousClock.Instant = .now) {
        lock.withLock { lastActivity = max(lastActivity, now) }
    }

    func heartbeatDue(now: ContinuousClock.Instant = .now) -> Bool {
        lock.withLock { lastActivity.duration(to: now) >= .seconds(60) }
    }
}
