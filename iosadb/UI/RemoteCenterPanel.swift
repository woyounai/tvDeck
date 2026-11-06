import SwiftUI
import UIKit

private struct RemoteGlassStyle: ButtonStyle {
    var cornerRadius: CGFloat = 20
    var expand = true
    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        configuration.label
            .foregroundStyle(.white)
            .frame(maxWidth: expand ? .infinity : nil)
            .background {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(LinearGradient(colors: pressed
                        ? [Color(red: 0.28, green: 0.47, blue: 0.82), Color(red: 0.16, green: 0.29, blue: 0.54)]
                        : [Color(red: 0.23, green: 0.27, blue: 0.39), Color(red: 0.13, green: 0.16, blue: 0.25)],
                        startPoint: .topLeading, endPoint: .bottomTrailing))
            }
            .overlay { RoundedRectangle(cornerRadius: cornerRadius)
                .strokeBorder(pressed ? Color(red: 0.36, green: 0.59, blue: 1) : .white.opacity(0.20), lineWidth: pressed ? 2 : 1) }
            .shadow(color: pressed ? .blue.opacity(0.28) : .black.opacity(0.25), radius: pressed ? 12 : 8, y: 5)
            .scaleEffect(pressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: pressed)
    }
}

struct RemoteCenterPanel: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var repeater = DirectionalKeyRepeater()
    @AppStorage(TVADBPreferences.remoteHapticsKey) private var remoteHaptics = true
    @State private var volumeOpen = false
    @State private var volumeInfo: ADBCommandService.VolumeState?
    @State private var volumeTarget = 0
    @State private var volumeDelta: CGFloat = 0
    @State private var volumeReadDelta: CGFloat = 0
    @State private var volumeSent = 0
    @State private var volumeSession = 0
    @State private var volumeCommandGeneration = 0
    @State private var volumeHapticStep = 0
    @State private var volumeBoundary: Int?
    @State private var lastVolumeTick = 0.0
    @State private var volumeSendTask: Task<Void, Never>?
    @State private var showChannelKeypad = false
    @State private var channelDigits = ""
    @State private var channelSubmitting = false
    @State private var channelError = ""
    @State private var activationFeedback = 0
    @State private var tickFeedback = 0
    @State private var showHelp = false
    @State private var feedback = 0

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                let wide = geometry.size.width >= 700
                Group {
                    if wide {
                        wideControls(minHeight: max(0, geometry.size.height - 56))
                    } else {
                        compactControls
                    }
                }
                .frame(maxWidth: wide ? 1_000 : 420, maxHeight: .infinity)
                .background(Color(red: 0.10, green: 0.13, blue: 0.22).opacity(0.88), in: RoundedRectangle(cornerRadius: 30))
                .overlay { RoundedRectangle(cornerRadius: 30).strokeBorder(.white.opacity(0.10)) }
                .shadow(color: .black.opacity(0.28), radius: 20, y: 10)
                .padding(12)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .disabled(!model.connectionState.isConnected)
                .onChange(of: wide) { _, _ in
                    repeater.stop()
                    cancelVolume()
                }
            }
            .background(LinearGradient(colors: [Color(red: 0.055, green: 0.07, blue: 0.13), Color(red: 0.10, green: 0.12, blue: 0.20)], startPoint: .topLeading, endPoint: .bottomTrailing).ignoresSafeArea())
            .navigationTitle("text.remote_center")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("text.close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showHelp = true } label: { Image(systemName: "info.circle") }
                        .accessibilityLabel("text.control_help")
                }
            }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: feedback)
        .sensoryFeedback(.impact(weight: .medium), trigger: activationFeedback)
        .sensoryFeedback(.selection, trigger: tickFeedback)
        .sheet(isPresented: $showChannelKeypad) { channelKeypad }
        .alert("text.control_help", isPresented: $showHelp) {
            Button("text.keyboard_confirm", role: .cancel) {}
        } message: {
            Text(model.fastDirectionalControlAvailable == false
                 ? "text.remote_center_compatibility_help" : "text.remote_center_help")
        }
        .task { await model.prepareDirectionalControl() }
        .onDisappear { repeater.stop(); cancelVolume() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { repeater.stop(); cancelVolume() } }
        .onChange(of: model.connectionState) { _, state in if !state.isConnected { repeater.stop(); cancelVolume(); showChannelKeypad = false } }
        .onChange(of: showHelp) { _, visible in if visible { repeater.stop() } }
    }

    private var compactControls: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 12) {
                    powerKeys
                    directionPad
                        .frame(maxWidth: .infinity)
                    navigationKeys
                    playbackKeys
                }
                .padding(16)
            }
            .scrollDisabled(volumeOpen)
            HStack(alignment: .top, spacing: 18) {
                volumeRocker(wide: false)
                VStack(spacing: 12) {
                    searchKey
                    settingsKey
                }
                channelRocker
            }
            .padding(16)
        }
    }

    private func wideControls(minHeight: CGFloat) -> some View {
        ScrollView {
            HStack(spacing: 16) {
                VStack(spacing: 12) {
                    powerKeys
                    navigationKeys
                    HStack(spacing: 12) {
                        searchKey
                        settingsKey
                    }
                }
                .frame(maxWidth: .infinity)
                directionPad
                VStack(spacing: 12) {
                    HStack(alignment: .top, spacing: 12) {
                        volumeRocker(wide: true)
                            .zIndex(volumeOpen ? 1 : 0)
                        channelRocker
                    }
                    playbackKeys
                }
                .frame(maxWidth: .infinity)
                .zIndex(volumeOpen ? 1 : 0)
            }
            .frame(minHeight: minHeight)
            .padding(16)
        }
        .scrollDisabled(volumeOpen)
    }

    private var directionPad: some View {
        VStack(spacing: 8) {
            direction(.up, icon: "chevron.up", title: "text.up")
            HStack(spacing: 8) {
                direction(.left, icon: "chevron.left", title: "text.left")
                Button { send(.ok) } label: {
                    Text(verbatim: "OK").font(.title2.bold())
                        .frame(width: 80, height: 80)
                }
                .buttonStyle(RemoteGlassStyle(cornerRadius: 26, expand: false))
                .accessibilityLabel("text.confirm")
                direction(.right, icon: "chevron.right", title: "text.right")
            }
            direction(.down, icon: "chevron.down", title: "text.down")
        }
    }

    private var powerKeys: some View {
        HStack(spacing: 12) {
            key(.power, icon: "power", title: "text.power")
            key(.input, icon: "rectangle.on.rectangle", title: "text.remote_input")
            key(.mute, icon: "speaker.slash.fill", title: "text.mute")
        }
    }

    private var navigationKeys: some View {
        HStack(spacing: 12) {
            key(.back, icon: "arrow.uturn.backward", title: "text.back")
            key(.home, icon: "house.fill", title: "text.home")
            key(.menu, icon: "line.3.horizontal", title: "text.menu")
        }
    }

    private var playbackKeys: some View {
        HStack(spacing: 12) {
            key(.rewind, icon: "backward.fill", title: "text.remote_rewind")
            key(.playPause, icon: "playpause.fill", title: "text.remote_play_pause")
            key(.fastForward, icon: "forward.fill", title: "text.remote_fast_forward")
        }
    }

    private var searchKey: some View {
        key(.search, icon: "magnifyingglass", title: "text.remote_search")
    }

    private var settingsKey: some View {
        key(.settings, icon: "gearshape", title: "text.remote_settings")
    }

    private func send(_ key: TVRemoteKey) {
        guard model.connectionState.isConnected else { return }
        if remoteHaptics { feedback += 1 }
        Task { await model.sendRemoteKey(key.rawValue) }
    }
    private func direction(_ key: TVRemoteKey, icon: String, title: String) -> some View {
        RemoteDirectionButton(icon: icon, title: L10n.string(title),
            isEnabled: model.connectionState.isConnected,
            onPress: {
                guard scenePhase == .active else { return }
                if remoteHaptics { feedback += 1 }
                repeater.start(key: key.rawValue) { code in
                    guard model.connectionState.isConnected else { return false }
                    return await model.sendRemoteKey(code)
                }
            },
            onRelease: { repeater.stop(key: key.rawValue) },
            onActivate: { send(key) }
        ).frame(width: 80, height: 72)
    }
    private func key(_ key: TVRemoteKey, icon: String, title: LocalizedStringKey) -> some View {
        Button { send(key) } label: {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 19, weight: .semibold))
                Text(title).font(.caption2).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 64)
            .contentShape(Rectangle())
        }.buttonStyle(RemoteGlassStyle())
    }
    private func volumeRocker(wide: Bool) -> some View {
        VStack(spacing: 8) {
            adjustKey(.volumeUp, icon: "plus", title: "text.remote_volume", suffix: "+", volume: true)
            Text("text.remote_volume").font(.caption2).foregroundStyle(.white.opacity(0.72))
            adjustKey(.volumeDown, icon: "minus", title: "text.remote_volume", suffix: "−", volume: true)
        }
        .frame(maxWidth: .infinity)
        .overlay(alignment: wide ? .topLeading : .topTrailing) {
            if volumeOpen, volumeInfo != nil {
                volumeRail.offset(x: wide ? -108 : 82, y: wide ? 0 : -255)
                    .allowsHitTesting(false)
            }
        }
    }
    private var volumeRail: some View {
        let fraction = volumeInfo.map {
            CGFloat(volumeTarget - $0.min) / CGFloat(max(1, $0.max - $0.min))
        } ?? 0
        let percent = Int((fraction * 100).rounded())
        return VStack(spacing: 14) {
            HStack(spacing: 6) {
                Image(systemName: "speaker.wave.2.fill")
                Text(volumeInfo == nil ? "…" : "\(percent)%").monospacedDigit()
            }
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background(Color(red: 0.20, green: 0.24, blue: 0.35), in: RoundedRectangle(cornerRadius: 16))
            .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(0.20)) }
            GeometryReader { geometry in
                ZStack(alignment: .bottom) {
                    Capsule().fill(Color(red: 0.20, green: 0.24, blue: 0.35))
                    Capsule().fill(LinearGradient(colors: [Color(red: 0.32, green: 0.56, blue: 1), Color(red: 0.58, green: 0.57, blue: 0.97)], startPoint: .top, endPoint: .bottom))
                        .padding(5)
                        .frame(height: max(0, geometry.size.height * fraction))
                    Circle().fill(.white).frame(width: 24, height: 24)
                        .shadow(color: .white.opacity(0.3), radius: 6)
                        .offset(y: -max(0, geometry.size.height * fraction - 24))
                }
                .overlay { Capsule().strokeBorder(.white.opacity(0.22), lineWidth: 1) }
            }
            .frame(width: 52, height: 210)
            .shadow(color: .black.opacity(0.30), radius: 12, y: 6)
        }
        .frame(width: 100)
        .accessibilityLabel("text.remote_volume")
        .accessibilityValue(volumeInfo.map { _ in "\(percent)%" } ?? "")
    }
    private var channelRocker: some View {
        VStack(spacing: 8) {
            adjustKey(.channelUp, icon: "plus", title: "text.remote_channel", suffix: "+", volume: false)
            Text("text.remote_channel").font(.caption2).foregroundStyle(.white.opacity(0.72))
            adjustKey(.channelDown, icon: "minus", title: "text.remote_channel", suffix: "−", volume: false)
        }
        .frame(maxWidth: .infinity)
    }
    private func adjustKey(_ key: TVRemoteKey, icon: String, title: String, suffix: String, volume: Bool) -> some View {
        RemoteHoldButton(icon: icon, title: L10n.string(title) + " " + suffix,
            isEnabled: model.connectionState.isConnected,
            onTap: { send(key) },
            onHold: { if volume { startVolume() } else { openChannelKeypad() } },
            onMove: { delta in if volume { moveVolume(delta) } },
            onEnd: { if volume { finishVolume() } }
        ).frame(height: 64)
    }
    private func startVolume() {
        guard scenePhase == .active else { return }
        volumeCommandGeneration += 1
        volumeSendTask?.cancel()
        volumeSendTask = nil
        volumeSession += 1
        let session = volumeSession
        volumeInfo = nil
        volumeOpen = true
        volumeDelta = 0
        volumeReadDelta = 0
        if remoteHaptics { activationFeedback += 1 }
        Task {
            guard let state = await model.readRemoteVolume(), volumeSession == session, volumeOpen else { return }
            volumeInfo = state
            volumeTarget = state.current
            volumeSent = state.current
            volumeHapticStep = state.current
            volumeBoundary = nil
            volumeReadDelta = volumeDelta
        }
    }
    private func moveVolume(_ delta: CGFloat) {
        volumeDelta = delta
        guard volumeOpen, let state = volumeInfo else { return }
        let next = RemoteVolumeMapping.target(start: state.current, delta: Double(delta - volumeReadDelta), min: state.min, max: state.max)
        guard next != volumeTarget else { return }
        volumeTarget = next
        if next == state.min || next == state.max {
            if remoteHaptics, volumeBoundary != next { activationFeedback += 1 }
            volumeBoundary = next
        } else {
            volumeBoundary = nil
            let now = ProcessInfo.processInfo.systemUptime
            if remoteHaptics, abs(next - volumeHapticStep) >= 4, now - lastVolumeTick >= 0.1 {
                tickFeedback += 1
                volumeHapticStep = next
                lastVolumeTick = now
            }
        }
        sendVolumeTarget()
    }
    private func sendVolumeTarget() {
        guard volumeSendTask == nil else { return }
        let generation = volumeCommandGeneration
        volumeSendTask = Task {
            while generation == volumeCommandGeneration, model.connectionState.isConnected,
                  volumeInfo != nil, volumeSent != volumeTarget {
                let next = volumeTarget
                guard await model.setRemoteVolume(next) != nil else { break }
                guard generation == volumeCommandGeneration else { break }
                volumeSent = next
            }
            if generation == volumeCommandGeneration { volumeSendTask = nil }
        }
    }
    private func finishVolume() {
        volumeSession += 1
        volumeOpen = false
        if volumeInfo != nil, volumeSent != volumeTarget { sendVolumeTarget() }
    }
    private func cancelVolume() {
        volumeCommandGeneration += 1
        volumeSession += 1
        volumeOpen = false
        volumeInfo = nil
        volumeSendTask?.cancel()
        volumeSendTask = nil
    }
    private func openChannelKeypad() {
        channelDigits = ""
        channelError = ""
        if remoteHaptics { activationFeedback += 1 }
        showChannelKeypad = true
    }
    private var channelKeypad: some View {
        Group {
            if #available(iOS 18.0, *) {
                channelKeypadSheet.presentationSizing(.form)
            } else {
                channelKeypadSheet
            }
        }
        .presentationDetents([.height(440)])
        .presentationCompactAdaptation(.sheet)
    }

    private var channelKeypadSheet: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 18) {
                        Text(channelDigits.isEmpty ? L10n.string("text.channel_empty") : channelDigits)
                            .font(.largeTitle.monospacedDigit()).frame(maxWidth: .infinity)
                        if !channelError.isEmpty { Text(channelError).font(.caption).foregroundStyle(.red) }
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 12) {
                            ForEach(1...9, id: \.self) { digit in channelDigit(digit) }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }
                    .padding(20)
                }
                Divider()
                HStack(spacing: 12) {
                    Button {
                        if !channelDigits.isEmpty {
                            channelDigits.removeLast()
                            if remoteHaptics { tickFeedback += 1 }
                        }
                    } label: {
                        Image(systemName: "delete.left")
                    }
                    .disabled(channelDigits.isEmpty)
                    .frame(maxWidth: .infinity)
                    channelDigit(0)
                        .frame(maxWidth: .infinity)
                    Button("text.keyboard_confirm") { submitChannel() }
                        .disabled(channelDigits.isEmpty || channelSubmitting)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .frame(maxWidth: 420)
            .frame(maxWidth: .infinity)
            .navigationTitle("text.remote_channel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) {
                Button("text.close") { showChannelKeypad = false }
            }}
        }
    }

    private func channelDigit(_ digit: Int) -> some View {
        Button("\(digit)") {
            guard channelDigits.count < 4 else { return }
            channelDigits.append(String(digit))
            channelError = ""
            if remoteHaptics { tickFeedback += 1 }
        }.disabled(channelSubmitting)
    }
    private func submitChannel() {
        guard !channelDigits.isEmpty, !channelSubmitting else { return }
        channelSubmitting = true
        let digits = channelDigits
        Task {
            let accepted = await model.tuneRemoteChannel(digits)
            channelSubmitting = false
            if accepted { showChannelKeypad = false }
            else { channelError = L10n.string("text.channel_failed") }
        }
    }
}

private struct RemoteHoldButton: UIViewRepresentable {
    let icon: String
    let title: String
    let isEnabled: Bool
    let onTap: () -> Void
    let onHold: () -> Void
    let onMove: (CGFloat) -> Void
    let onEnd: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> TouchButton {
        let button = TouchButton(type: .system)
        RemoteGlassAppearance.configure(button, icon: icon)
        button.coordinator = context.coordinator
        return button
    }
    func updateUIView(_ button: TouchButton, context: Context) {
        context.coordinator.parent = self
        button.accessibilityLabel = title
        button.accessibilityHint = L10n.string("text.remote_hold_action")
        button.accessibilityCustomActions = [UIAccessibilityCustomAction(
            name: L10n.string("text.remote_hold_action"), target: context.coordinator,
            selector: #selector(Coordinator.accessibilityHold))]
        button.tintColor = .white
        button.isEnabled = isEnabled
        if !isEnabled { context.coordinator.cancel() }
    }
    static func dismantleUIView(_ button: TouchButton, coordinator: Coordinator) {
        coordinator.cancel()
        button.restoreScroll()
    }

    final class TouchButton: UIButton {
        weak var coordinator: Coordinator?
        private weak var scrollView: UIScrollView?
        override var isHighlighted: Bool {
            didSet { RemoteGlassAppearance.update(self, pressed: isHighlighted) }
        }
        func restoreScroll() { scrollView?.panGestureRecognizer.isEnabled = true; scrollView = nil }
        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard let touch = touches.first, let window else { return }
            isHighlighted = true
            var ancestor = superview
            while let view = ancestor, !(view is UIScrollView) { ancestor = view.superview }
            scrollView = ancestor as? UIScrollView
            scrollView?.panGestureRecognizer.isEnabled = false
            coordinator?.begin(touch, y: touch.location(in: window).y)
        }
        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard let touch = touches.first, let window else { return }
            coordinator?.move(touch, y: touch.location(in: window).y)
        }
        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard let touch = touches.first else { return }
            coordinator?.finish(touch)
            isHighlighted = false
            restoreScroll()
        }
        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
            coordinator?.cancel()
            isHighlighted = false
            restoreScroll()
        }
        override func accessibilityActivate() -> Bool { coordinator?.parent.onTap(); return true }
    }
    @MainActor final class Coordinator: NSObject {
        var parent: RemoteHoldButton
        private weak var touch: UITouch?
        private var interaction: RemoteHoldInteraction!
        init(_ parent: RemoteHoldButton) {
            self.parent = parent
            super.init()
            interaction = RemoteHoldInteraction(
                onTap: { [weak self] in self?.parent.onTap() },
                onHold: { [weak self] in self?.parent.onHold() },
                onMove: { [weak self] delta in self?.parent.onMove(CGFloat(delta)) },
                onEnd: { [weak self] in self?.parent.onEnd() }
            )
        }
        func begin(_ touch: UITouch, y: CGFloat) {
            cancel()
            self.touch = touch
            interaction.begin(y: Double(y))
        }
        func move(_ touch: UITouch, y: CGFloat) {
            guard self.touch === touch else { return }
            interaction.move(y: Double(y))
        }
        func finish(_ touch: UITouch) {
            guard self.touch === touch else { return }
            interaction.finish()
            self.touch = nil
        }
        func cancel() {
            interaction.cancel()
            touch = nil
        }
        @objc func accessibilityHold() -> Bool { parent.onHold(); return true }
    }
}
