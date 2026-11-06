import AVFoundation
import SwiftUI
import UIKit

/// Delivers independent, decoded image samples. A busy display skips obsolete
/// images safely; it never drops compressed reference frames or replays a GOP.
final class H264VideoFrameHub: @unchecked Sendable {
    private final class WeakLayer {
        weak var value: AVSampleBufferDisplayLayer?
        init(_ value: AVSampleBufferDisplayLayer) { self.value = value }
    }
    private let lock = NSLock()
    private var layers: [WeakLayer] = []
    private let frames = LatestFrameBuffer<H264VideoSample>()
    func beginSession(_ id: UUID) { frames.beginSession(id) }
    func attach(_ layer: AVSampleBufferDisplayLayer) {
        lock.lock()
        layers.removeAll { $0.value == nil }
        guard !layers.contains(where: { $0.value === layer }) else { lock.unlock(); return }
        layers.append(WeakLayer(layer))
        lock.unlock()
        DispatchQueue.main.async { [weak self, weak layer] in
            guard let layer, let latest = self?.frames.latest else { return }
            if layer.status == .failed { layer.flushAndRemoveImage() }
            if layer.isReadyForMoreMediaData { layer.enqueue(latest.sampleBuffer) }
        }
    }
    func enqueue(_ sample: H264VideoSample, sessionID: UUID) {
        guard frames.offer(sample, session: sessionID) else { return }
        DispatchQueue.main.async { [weak self] in self?.displayLatest() }
    }
    private func displayLatest() {
        guard let sample = frames.takePending() else { return }
        lock.lock()
        layers.removeAll { $0.value == nil }
        let targets = layers.compactMap(\.value)
        lock.unlock()
        for layer in targets {
            if layer.status == .failed { layer.flushAndRemoveImage() }
            guard layer.isReadyForMoreMediaData else { continue }
            layer.enqueue(sample.sampleBuffer)
        }
    }
    func clear() {
        frames.clear()
        lock.lock()
        let targets = layers.compactMap(\.value)
        layers.removeAll { $0.value == nil }
        lock.unlock()
        DispatchQueue.main.async { targets.forEach { $0.flushAndRemoveImage() } }
    }
}

final class H264SampleBufferView: UIView {
    override class var layerClass: AnyClass {
        AVSampleBufferDisplayLayer.self
    }

    var displayLayer: AVSampleBufferDisplayLayer {
        layer as! AVSampleBufferDisplayLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        displayLayer.backgroundColor = UIColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

struct H264VideoSurface: UIViewRepresentable {
    let hub: H264VideoFrameHub

    func makeUIView(context: Context) -> H264SampleBufferView {
        let view = H264SampleBufferView()
        hub.attach(view.displayLayer)
        return view
    }

    func updateUIView(
        _ uiView: H264SampleBufferView,
        context: Context
    ) {
        hub.attach(uiView.displayLayer)
    }

    static func dismantleUIView(
        _ uiView: H264SampleBufferView,
        coordinator: Void
    ) {
        uiView.displayLayer.flushAndRemoveImage()
    }
}
