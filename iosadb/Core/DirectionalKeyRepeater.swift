import Foundation

enum RemoteVolumeMapping {
    static func target(start: Int, delta: Double, min: Int, max: Int, travel: Double = 200) -> Int {
        guard max > min, travel > 0 else { return start }
        let value = start + Int((delta / travel * Double(max - min)).rounded())
        return Swift.min(max, Swift.max(min, value))
    }
}

@MainActor
final class RemoteHoldInteraction {
    private var timer: Task<Void, Never>?
    private var active = false
    private var held = false
    private var downY = 0.0
    private var currentY = 0.0
    private var activationY = 0.0
    private let onTap: () -> Void
    private let onHold: () -> Void
    private let onMove: (Double) -> Void
    private let onEnd: () -> Void

    init(onTap: @escaping () -> Void, onHold: @escaping () -> Void,
         onMove: @escaping (Double) -> Void, onEnd: @escaping () -> Void) {
        self.onTap = onTap; self.onHold = onHold; self.onMove = onMove; self.onEnd = onEnd
    }
    func begin(y: Double) {
        cancel()
        active = true
        downY = y
        currentY = y
        activationY = y
        timer = Task { @MainActor [weak self] in
            do { try await Task.sleep(for: .milliseconds(280)) } catch { return }
            guard let self, self.active else { return }
            self.held = true
            self.activationY = self.currentY
            self.onHold()
        }
    }
    func move(y: Double) {
        guard active else { return }
        currentY = y
        if held { onMove(activationY - y) }
        else if abs(y - downY) > 10 { cancel() }
    }
    func finish() {
        let tap = active && !held
        cancel()
        if tap { onTap() }
    }
    func cancel() {
        timer?.cancel(); timer = nil
        if held { onEnd() }
        active = false
        held = false
    }
    deinit { timer?.cancel() }
}

struct TwoFingerNavigation {
    private var finished = false

    mutating func update(dx: Double, dy: Double) -> Int? {
        guard !finished else { return nil }
        let x = abs(dx)
        let y = abs(dy)
        guard max(x, y) >= 14, max(x, y) >= min(x, y) * 1.25 else { return nil }
        finished = true
        if x > y { return dx > 0 ? 22 : 21 }
        return dy > 0 ? 20 : 19
    }

    mutating func cancel() { finished = true }
}

/// One awaited key event at a time: a slow TV never builds a repeat backlog.
@MainActor
final class DirectionalKeyRepeater {
    private var task: Task<Void, Never>?
    private(set) var activeKey: Int?
    private var generation = 0

    static func delay(beforeRepeat index: Int) -> Duration {
        if index == 0 { return .milliseconds(100) }
        if index < 5 { return .milliseconds(90) }
        if index < 13 { return .milliseconds(55) }
        return .milliseconds(35)
    }

    func start(key: Int, send: @escaping @MainActor (Int) async -> Bool) {
        guard (19...22).contains(key) || [24, 25, 166, 167].contains(key), activeKey != key else { return }
        stop()
        activeKey = key
        let token = generation
        task = Task { [weak self] in
            var repeatIndex = 0
            while !Task.isCancelled {
                guard await send(key) else { break }
                guard !Task.isCancelled else { break }
                do {
                    try await Task.sleep(for: Self.delay(beforeRepeat: repeatIndex))
                } catch { break }
                repeatIndex += 1
            }
            if self?.generation == token { self?.stop() }
        }
    }

    func stop(key: Int? = nil) {
        if let key, key != activeKey { return }
        generation += 1
        task?.cancel()
        task = nil
        activeKey = nil
    }

    deinit { task?.cancel() }
}

/// The tap action is committed on touch-up and never belongs to a cancelled
/// repeat task. Holding consumes the tap, so release cannot send an extra key.
@MainActor
final class DirectionalPressInteraction {
    private var holdTask: Task<Void, Never>?
    private var tracking = false
    private var didHold = false
    private let onTap: () -> Void
    private let onHold: () -> Void
    private let onRelease: () -> Void

    init(onTap: @escaping () -> Void, onHold: @escaping () -> Void,
         onRelease: @escaping () -> Void) {
        self.onTap = onTap
        self.onHold = onHold
        self.onRelease = onRelease
    }

    func begin() {
        guard !tracking else { return }
        tracking = true
        didHold = false
        holdTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(for: .milliseconds(350)) }
            catch { return }
            guard let self, self.tracking else { return }
            self.didHold = true
            self.onHold()
        }
    }

    func finish() {
        let shouldTap = tracking && !didHold
        cancel()
        if shouldTap { onTap() }
    }

    func cancel() {
        holdTask?.cancel()
        holdTask = nil
        let wasTracking = tracking
        tracking = false
        if wasTracking { onRelease() }
    }

    deinit { holdTask?.cancel() }
}
