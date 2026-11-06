struct MouseMotionAccumulator {
    private var x = 0.0
    private var y = 0.0

    var hasWholeMovement: Bool { abs(x) >= 1 || abs(y) >= 1 }

    mutating func add(x: Double, y: Double) {
        self.x += x
        self.y += y
    }

    mutating func take() -> (x: Int, y: Int)? {
        let dx = Int(x.rounded(.towardZero))
        let dy = Int(y.rounded(.towardZero))
        guard dx != 0 || dy != 0 else { return nil }
        x -= Double(dx)
        y -= Double(dy)
        return (dx, dy)
    }
}
