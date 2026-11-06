import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass
import OSLog

struct RemoteControlView: View {
    private let orientationLogger = Logger(
        subsystem: "com.tvadb.ios",
        category: "LiveScreenOrientation"
    )
    @EnvironmentObject private var model: AppModel
    @AppStorage(TVADBPreferences.remoteControlOrderKey)
    private var remoteControlOrderRawValue =
        TVADBPreferences.defaultRemoteControlOrderRawValue
    @State private var controlMode =
        TVADBPreferences.remoteControlOrder.first ?? .screen
    @State private var liveScreenControlMode =
        TVADBPreferences.liveScreenControlMode
    @State private var liveScreenOrientationRawValue =
        LiveScreenOrientationPreference.automatic.rawValue
    @State private var feedbackToken = 0
    @State private var televisionSize = CGSize(width: 1920, height: 1080)
    @State private var mousePosition = CGPoint(x: 960, y: 540)
    @State private var gesturePadIsActive = false
    @State private var mousePadReady = false
    @State private var mouseNeedsRestore = false
    @State private var liveScreenZoom: CGFloat = 1
    @State private var liveScreenOffset = CGSize.zero
    @State private var liveScreenTouchStart: CGPoint?
    @State private var liveScreenLastPanLocation: CGPoint?
    @State private var liveScreenLongDragStart: CGPoint?
    @State private var liveScreenLongDragLastLocation: CGPoint?
    @State private var isLiveScreenFullScreen = false
    @State private var fullScreenControlsExpanded = false
    @State private var isRemoteKeyboardPresented = false
    @State private var isRemoteCenterPresented = false
    @State private var showControlHelp = false
    @State private var showMouseActions = false
    @State private var pendingMouseAction: MouseMoreAction?
    @Environment(\.scenePhase) private var scenePhase
    @State private var orientationBeforeFullScreen: UIInterfaceOrientation?
    @State private var orientationTransitionTask: Task<Void, Never>?
    @State private var androidScreenRotation: Int?
    @State private var screenCoordinateSyncTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 14) {
                    deviceStatus
                    if model.isScreenRecording {
                        recordingStatus
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    Group {
                        modePicker
                        if model.mousePreviewIsRequested, model.mousePreviewImage != nil, !model.mousePreviewUsesVideoStream {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("text.preview_screenshot_mode").font(.caption).foregroundStyle(.secondary)
                                if let failure = model.mousePreviewVideoFailure { Text(failure).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                                Button("text.retry_video_preview") { connectLiveScreen() }
                                    .buttonStyle(.bordered)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Group {
                            switch controlMode {
                            case .gestures, .buttons:
                                liveScreenPanel
                            case .screen:
                                liveScreenPanel
                                    .transition(.scale(scale: 0.96).combined(with: .opacity))
                            case .mouse:
                                mousePad
                                    .transition(.scale(scale: 0.96).combined(with: .opacity))
                            }
                        }
                        .animation(.spring(response: 0.34, dampingFraction: 0.82), value: controlMode)

                    }
                    .allowsHitTesting(!model.isScreenRecording || model.isScreenRecordingReady)
                    .opacity(
                        model.isScreenRecording && !model.isScreenRecordingReady
                            ? 0.58
                            : 1
                    )
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 160)
            }
            .scrollDisabled(gesturePadIsActive)
            .navigationTitle("text.tv_remote")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                TVADBDisconnectToolbarItem()
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showControlHelp = true } label: { Image(systemName: "info.circle") }
                        .accessibilityLabel("text.control_help")
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                floatingControls.frame(maxWidth: .infinity, alignment: .trailing)
            }
            .alert("text.control_help", isPresented: $showControlHelp) {
                if controlMode == .mouse {
                    Button("text.retry_native_mouse") {
                        Task {
                            televisionSize = await model.retryMouseControl()
                            resetMousePosition()
                        }
                    }
                }
                if controlMode == .mouse, model.mouseMoveUnavailable, !model.mouseClickUnavailable {
                    Button("text.show_tap_locations") { Task { await model.setShowTouches(true) } }
                    Button("text.hide_tap_locations") { Task { await model.setShowTouches(false) } }
                }
                if model.mousePreviewImage != nil, !model.mousePreviewUsesVideoStream {
                    Button("text.retry_video_preview") { connectLiveScreen() }
                }
                Button("text.close", role: .cancel) {}
            } message: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(controlMode == .mouse ? "text.mouse_visibility_hint" :
                         model.mousePreviewImage != nil && !model.mousePreviewUsesVideoStream
                         ? "text.preview_screenshot_mode" : "text.screen_help")
                    if let failure = model.mousePreviewVideoFailure { Text(failure) }
                }
            }
            .tvadbScreenBackground()
            .sensoryFeedback(.impact(weight: .light), trigger: feedbackToken)
            .animation(
                .spring(response: 0.34, dampingFraction: 0.84),
                value: model.isScreenRecordingReady
            )
            .task(id: mouseConfigurationID) {
                let configurationID = mouseConfigurationID
                gesturePadIsActive = false
                switch controlMode {
                case .screen:
                    guard model.mousePreviewIsRequested else {
                        await model.stopMouseControl()
                        return
                    }
                    televisionSize =
                        await model.prepareTouchscreenControl()
                    guard !Task.isCancelled,
                          configurationID == mouseConfigurationID else {
                        return
                    }
                    if liveScreenControlMode == .mouse {
                        resetMousePosition()
                    }
                    androidScreenRotation =
                        await model.televisionScreenRotation()
                case .mouse:
                    mousePadReady = false
                    guard scenePhase == .active, model.connectionState.isConnected else { return }
                    screenCoordinateSyncTask?.cancel()
                    releaseInterfaceOrientationLock()
                    model.stopMousePreview()
                    let size = if mouseNeedsRestore {
                        await model.retryMouseControl()
                    } else {
                        await model.prepareMouseControl()
                    }
                    guard !Task.isCancelled, configurationID == mouseConfigurationID else { return }
                    televisionSize = size
                    resetMousePosition()
                    mouseNeedsRestore = false
                    mousePadReady = true
                case .gestures, .buttons:
                    screenCoordinateSyncTask?.cancel()
                    releaseInterfaceOrientationLock()
                    await model.stopMouseControl()
                    await model.prepareDirectionalControl()
                }
                guard !Task.isCancelled,
                      configurationID == mouseConfigurationID else {
                    return
                }
            }
            .onDisappear {
                gesturePadIsActive = false
                guard !isLiveScreenFullScreen, !showMouseActions else { return }
                screenCoordinateSyncTask?.cancel()
                releaseInterfaceOrientationLock()
                Task {
                    await model.stopMouseControl()
                }
            }
        }
        .sheet(isPresented: Binding(
            get: { isRemoteKeyboardPresented && !isLiveScreenFullScreen },
            set: { isRemoteKeyboardPresented = $0 }
        )) {
            remoteKeyboardSheet
        }
        .fullScreenCover(isPresented: Binding(
            get: { isRemoteCenterPresented && !isLiveScreenFullScreen },
            set: { isRemoteCenterPresented = $0 }
        )) { RemoteCenterPanel() }
        .sheet(isPresented: $showMouseActions, onDismiss: performPendingMouseAction) {
            MouseMoreActionsPanel { action in
                pendingMouseAction = action
                showMouseActions = false
            }
        }
        .fullScreenCover(isPresented: $isLiveScreenFullScreen) {
            fullScreenLiveScreen
                .overlay(alignment: .bottomTrailing) { floatingControls }
                .fullScreenCover(isPresented: $isRemoteCenterPresented) { RemoteCenterPanel() }
                .sheet(isPresented: $isRemoteKeyboardPresented) {
                    remoteKeyboardSheet
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active {
                model.cancelQueuedMouseMovement()
                gesturePadIsActive = false
                mousePadReady = false
                if controlMode == .mouse { mouseNeedsRestore = true }
                model.stopMousePreview(clearFrame: false)
            }
        }
        .onChange(of: isLiveScreenFullScreen) { _, isPresented in
            handleFullScreenPresentationChange(isPresented)
        }
        .onChange(of: currentLiveScreenSourceSize) { oldSize, newSize in
            handleLiveScreenSourceSizeChange(
                from: oldSize,
                to: newSize
            )
        }
    }

    private var floatingControls: some View {
        HStack(spacing: 12) {
            Button { isRemoteCenterPresented = true } label: {
                Label("text.remote_center", systemImage: "appletvremote.gen4")
                    .font(.subheadline.weight(.semibold)).padding(.horizontal, 16).padding(.vertical, 13)
                    .background(.regularMaterial, in: Capsule())
            }
            Button { isRemoteKeyboardPresented = true } label: {
                Image(systemName: "keyboard").font(.title2.weight(.semibold))
                    .foregroundStyle(.white).frame(width: 58, height: 58)
                    .background(TVADBTheme.accentGradient, in: Circle())
            }.accessibilityLabel("text.tv_keyboard")
        }
        .buttonStyle(TVADBPressableStyle())
        .shadow(color: .black.opacity(0.14), radius: 12, y: 5)
        .padding(.trailing, 18)
        .padding(.bottom, isLiveScreenFullScreen && fullScreenControlsExpanded ? 86 : 18)
        .disabled(!model.connectionState.isConnected || (model.isScreenRecording && !model.isScreenRecordingReady))
    }

    private var recordingStatus: some View {
        TVADBPanel {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(Color.red.opacity(0.12))
                        .frame(width: 38, height: 38)
                    if model.isScreenRecordingReady {
                        Circle()
                            .fill(Color.red)
                            .frame(width: 10, height: 10)
                            .shadow(color: .red.opacity(0.45), radius: 6)
                    } else {
                        ProgressView()
                            .tint(TVADBTheme.accent)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(
                        LocalizedStringKey(
                            model.isScreenRecordingReady
                                ? "text.screen_recording_in_progress"
                                : "text.preparing_screen_recording"
                        )
                    )
                    .font(.subheadline.weight(.semibold))
                    Text(
                        LocalizedStringKey(
                            model.isScreenRecordingReady
                                ? "text.remote_actions_are_now_being_recorded"
                                : "text.wait_until_recording_is_ready"
                        )
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding(11)
        }
    }

    private var deviceStatus: some View {
        TVADBPanel {
            HStack(spacing: 13) {
                Image(systemName: "tv.fill")
                    .font(.title3)
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(TVADBTheme.accentGradient, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text(connectedName)
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Circle()
                            .fill(TVADBTheme.status)
                            .frame(width: 7, height: 7)
                        Text("text.connected")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: "wifi")
                    .foregroundStyle(TVADBTheme.accent)
            }
            .padding(11)
        }
    }

    private var modePicker: some View {
        Picker("text.mode", selection: $controlMode) {
            ForEach(orderedControlModes) { mode in
                Label(mode.title, systemImage: mode.systemImage)
                    .tag(mode)
            }
        }
        .pickerStyle(.segmented)
        .padding(5)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .onChange(of: controlMode) { _, mode in
            feedbackToken += 1
        }
        .onChange(of: remoteControlOrderRawValue) {
            controlMode = orderedControlModes.first ?? .screen
        }
    }

    private var orderedControlModes: [RemoteControlMode] {
        TVADBPreferences.remoteControlOrder(from: remoteControlOrderRawValue)
    }

    private var mouseConfigurationID: String {
        let mode = "\(controlMode.rawValue):\(liveScreenControlMode.rawValue)"
        guard controlMode == .mouse else { return mode }
        return "\(mode):\(scenePhase == .active):\(model.connectionState.isConnected)"
    }

    private var mousePad: some View {
        VStack(spacing: 0) {
            GeometryReader { proxy in
                ZStack {
                    if model.mouseMoveUnavailable && !model.mouseClickUnavailable {
                        coordinateTouchIndicator(in: proxy.size).allowsHitTesting(false)
                    }
                VStack(spacing: 10) {
                    Image(
                        systemName: model.mouseClickUnavailable
                            ? "rectangle.and.hand.point.up.left.filled"
                            : model.nativeMouseInputActive
                                ? "cursorarrow.rays"
                                : "cursorarrow.motionlines"
                    )
                        .font(.system(size: 38, weight: .medium))
                        .foregroundStyle(TVADBTheme.accentGradient)
                    Text(mousePadTitle)
                        .font(.subheadline.weight(.semibold))
                    Text(mousePadSubtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if model.nativeMouseInputActive {
                        Label(
                            model.physicalHIDMouseActive
                                ? "text.physical_hid_mouse_active"
                                : "text.system_mouse_input_active",
                            systemImage: "checkmark.circle.fill"
                        )
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(TVADBTheme.status)
                    }

                }
                .allowsHitTesting(false)
                    MousePadSurface(
                        enabled: mousePadReady && scenePhase == .active && controlMode == .mouse
                            && model.connectionState.isConnected
                            && !model.mouseControlIsCalibrating && !showMouseActions
                            && !isRemoteCenterPresented && !isRemoteKeyboardPresented
                            && (!model.isScreenRecording || model.isScreenRecordingReady),
                        onMove: { point, delta in
                            moveMouseOnPad(to: point, delta: delta, in: proxy.size)
                        },
                        onRelease: { translation in
                            if model.mouseClickUnavailable,
                               let key = fallbackDirectionKeyCode(for: translation) {
                                send(key)
                            }
                        },
                        onTap: { location, count in
                            updateCoordinateTouchPosition(location, in: proxy.size)
                            if count == 2 { doubleClickMouse() }
                            else { clickMouse() }
                        },
                        onHorizontalSwipe: { feedbackToken += 1 },
                        onDirection: { key in
                            guard model.connectionState.isConnected else { return false }
                            return await model.sendRemoteKey(key)
                        }
                    )
                }
            }
            .frame(height: 300)
            Divider().overlay(TVADBTheme.accent.opacity(0.15))
                    HStack(spacing: 8) {
                        Button {
                            centerMousePointer()
                        } label: {
                            Label(model.mouseControlIsCalibrating ? "text.mouse_centering" : "text.center", systemImage: "scope")
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(.thinMaterial, in: Capsule())
                        }
                        Spacer()
                        if model.nativeMouseInputActive {
                            Button {
                                toggleMouseDrag()
                            } label: {
                                Label(
                                    model.mouseDragActive
                                        ? "text.release_drag"
                                        : "text.drag_lock",
                                    systemImage: model.mouseDragActive
                                        ? "hand.raised.fill"
                                        : "hand.draw.fill"
                                )
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .foregroundStyle(
                                    model.mouseDragActive
                                        ? Color.white
                                        : TVADBTheme.accent
                                )
                                .background(
                                    model.mouseDragActive
                                        ? AnyShapeStyle(TVADBTheme.accentGradient)
                                        : AnyShapeStyle(TVADBTheme.accent.opacity(0.14)),
                                    in: Capsule()
                                )
                            }
                            .accessibilityHint("text.drag_lock_hint")
                        } else {
                            Button {
                                doubleClickMouse()
                            } label: {
                                Label("text.double_click", systemImage: "hand.tap.fill")
                                    .font(.caption.weight(.semibold))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 8)
                                    .background(TVADBTheme.accent.opacity(0.14), in: Capsule())
                            }
                        }
                        Button {
                            // Keep the pop-up independent from the touch pad's
                            // scrolling/pressed geometry and reset drag tracking.
                            gesturePadIsActive = false
                            pendingMouseAction = nil
                            showMouseActions = true
                        } label: {
                            Label("text.more", systemImage: "ellipsis")
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(.thinMaterial, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(14)
                .buttonStyle(.plain)
                .disabled(model.mouseControlIsCalibrating)
            Text("text.mouse_two_finger_navigation")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 14)
                .padding(.bottom, 14)
        }
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 30))
        .overlay { RoundedRectangle(cornerRadius: 30).strokeBorder(TVADBTheme.accent.opacity(0.18)) }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("text.mouse_trackpad")
    }

    private var liveScreenPanel: some View {
        GeometryReader { proxy in
            liveScreenCanvas(size: proxy.size, isFullScreen: false)
        }
        .frame(height: 380)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("text.interactive_live_screen")
    }

    private var fullScreenLiveScreen: some View {
        GeometryReader { proxy in
            ZStack {
                liveScreenCanvas(size: proxy.size, isFullScreen: true)
                fullScreenLiveControls
            }
        }
        .background(Color.black)
        .ignoresSafeArea()
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onDisappear {
            fullScreenControlsExpanded = false
            gesturePadIsActive = false
        }
        .onChange(of: liveScreenOrientationRawValue) {
            applyLiveScreenOrientation()
        }
        .onChange(of: detectedLiveScreenOrientation) {
            guard liveScreenOrientationPreference == .automatic else {
                return
            }
            applyLiveScreenOrientation()
        }
    }

    private func liveScreenCanvas(
        size: CGSize,
        isFullScreen: Bool
    ) -> some View {
        let controlsHeight: CGFloat = isFullScreen ? 0 : 64
        let interactionBounds = CGRect(
            x: 0,
            y: 0,
            width: size.width,
            height: max(size.height - controlsHeight, 1)
        )
        let previewImage = model.mousePreviewIsRequested ? mousePreviewImage : nil
        let hasVideo = model.mousePreviewHasVideoFrame && model.mousePreviewIsRequested
        let sourceSize =
            (hasVideo ? model.mousePreviewVideoSize : nil)
            ?? previewImage?.size
            ?? televisionSize
        let basePreviewRect = aspectFitRect(
            sourceSize: sourceSize,
            inside: interactionBounds.insetBy(
                dx: isFullScreen ? 0 : 8,
                dy: isFullScreen ? 0 : 8
            )
        )
        let displayRect = transformedLiveScreenRect(
            baseRect: basePreviewRect
        )

        return ZStack {
            if isFullScreen {
                Color.black
            } else {
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .fill(Color.black)
                    .shadow(color: .black.opacity(0.12), radius: 24, y: 12)
            }

            // Keep the display layer alive before the first H.264 sample
            // arrives. Otherwise the initial IDR can be published before
            // SwiftUI creates this view, leaving the decoder with only P
            // frames until the next keyframe.
            H264VideoSurface(hub: model.mousePreviewVideoHub)
                .frame(
                    width: basePreviewRect.width,
                    height: basePreviewRect.height
                )
                .position(
                    x: basePreviewRect.midX,
                    y: basePreviewRect.midY
                )
                .scaleEffect(liveScreenZoom)
                .offset(liveScreenOffset)
                .opacity(hasVideo ? 1 : 0.001)
                .allowsHitTesting(false)

            if !hasVideo, let previewImage {
                Image(uiImage: previewImage)
                    .resizable()
                    .interpolation(.medium)
                    .frame(
                        width: basePreviewRect.width,
                        height: basePreviewRect.height
                    )
                    .position(
                        x: basePreviewRect.midX,
                        y: basePreviewRect.midY
                    )
                    .scaleEffect(liveScreenZoom)
                    .offset(liveScreenOffset)

            } else if !hasVideo {
                livePreviewPlaceholder
                    .frame(
                        width: interactionBounds.width,
                        height: interactionBounds.height
                    )
                    .position(
                        x: interactionBounds.midX,
                        y: interactionBounds.midY
                    )
            }

            if (hasVideo || previewImage != nil),
               liveScreenControlMode == .mouse {
                livePreviewCursor(
                    in: displayRect,
                    sourceSize: sourceSize
                )
                .allowsHitTesting(false)
            }

            LiveScreenGestureLayer(
                onTap: { location in
                    handleLiveScreenTap(
                        location,
                        displayRect: displayRect,
                        sourceSize: sourceSize,
                        isDoubleTap: false
                    )
                },
                onDoubleTap: { location in
                    handleLiveScreenTap(
                        location,
                        displayRect: displayRect,
                        sourceSize: sourceSize,
                        isDoubleTap: true
                    )
                },
                onOneFingerPan: { phase, location in
                    handleLiveScreenPan(
                        phase: phase,
                        location: location,
                        displayRect: displayRect,
                        sourceSize: sourceSize
                    )
                },
                onOneFingerLongPressDrag: { phase, location in
                    handleLiveScreenLongPressDrag(
                        phase: phase,
                        location: location,
                        displayRect: displayRect,
                        sourceSize: sourceSize
                    )
                },
                onTwoFingerLongPress: {
                    feedbackToken += 1
                    send(82)
                },
                onThreeFingerPan: { translationY in
                    handleLiveScreenScroll(
                        translationY: translationY,
                        sourceSize: sourceSize
                    )
                },
                onPinch: { scale, location in
                    updateLiveScreenZoom(
                        by: scale,
                        anchor: location,
                        baseRect: basePreviewRect,
                        interactionBounds: interactionBounds
                    )
                },
                onViewportPan: { translation in
                    updateLiveScreenOffset(
                        by: translation,
                        baseRect: basePreviewRect,
                        interactionBounds: interactionBounds
                    )
                },
                onTouchActivityChanged: { isActive in
                    gesturePadIsActive = isActive
                }
            )
            .frame(
                width: interactionBounds.width,
                height: interactionBounds.height
            )
            .position(
                x: interactionBounds.midX,
                y: interactionBounds.midY
            )
            .allowsHitTesting(hasVideo || previewImage != nil)

            if !isFullScreen {
                regularLiveScreenControls
            }
        }
        .clipShape(
            RoundedRectangle(
                cornerRadius: isFullScreen ? 0 : 30,
                style: .continuous
            )
        )
        .overlay {
            if !isFullScreen {
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .strokeBorder(TVADBTheme.accent.opacity(0.24))
            }
        }
    }

    private var regularLiveScreenControls: some View {
        VStack {
            Spacer()
            HStack(spacing: 7) {
                liveScreenModeButton(.touch)
                liveScreenModeButton(.mouse)

                Spacer(minLength: 0)

                if liveScreenZoom > 1.01 {
                    Button {
                        resetLiveScreenZoom()
                    } label: {
                        Text("\(liveScreenZoom, specifier: "%.1f")×")
                            .font(.caption.weight(.bold))
                            .frame(minWidth: 32, minHeight: 32)
                            .background(.white.opacity(0.14), in: Capsule())
                    }
                    .accessibilityLabel("text.reset_zoom")
                }

                liveScreenOrientationMenu(
                    buttonSize: 32,
                    showsBackground: true
                )

                Button {
                    isLiveScreenFullScreen = true
                } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.caption.weight(.bold))
                        .frame(width: 32, height: 32)
                        .background(.white.opacity(0.14), in: Circle())
                }
                .accessibilityLabel("text.enter_full_screen")

                Button {
                    if model.mousePreviewIsRequested { model.stopMousePreview() }
                    else { connectLiveScreen() }
                } label: {
                    Image(systemName: model.mousePreviewIsRequested ? "stop.fill" : "play.fill")
                        .font(.caption.weight(.bold))
                        .frame(width: 32, height: 32)
                        .background(.white.opacity(0.14), in: Circle())
                }
                .accessibilityLabel(model.mousePreviewIsRequested ? "text.disconnect_screen" : "text.connect_screen")
            }
            .foregroundStyle(.white)
            .padding(14)
            .background(
                LinearGradient(
                    colors: [.clear, .black.opacity(0.76)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
    }

    private var fullScreenLiveControls: some View {
        VStack {
            HStack {
                Spacer()
                Button {
                    isLiveScreenFullScreen = false
                } label: {
                    Image(systemName: "xmark")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(.black.opacity(0.52), in: Circle())
                }
                .accessibilityLabel("text.exit_full_screen")
            }

            Spacer()

            if fullScreenControlsExpanded {
                VStack(spacing: 8) {
                    HStack(spacing: 9) {
                        Button {
                            fullScreenControlsExpanded = false
                        } label: {
                            Image(systemName: "chevron.down")
                                .frame(width: 34, height: 34)
                        }
                        liveScreenModeButton(.touch)
                        liveScreenModeButton(.mouse)
                        liveScreenOrientationMenu(
                            buttonSize: 34,
                            showsTitle: true
                        )

                        if liveScreenZoom > 1.01 {
                            Button {
                                resetLiveScreenZoom()
                            } label: {
                                Image(systemName: "1.magnifyingglass")
                                    .frame(width: 34, height: 34)
                            }
                            .accessibilityLabel("text.reset_zoom")
                        }
                    }


                }
                .padding(10)
                .frame(maxWidth: 520)
                .foregroundStyle(.white)
                .background(
                    .black.opacity(0.68),
                    in: RoundedRectangle(
                        cornerRadius: 20,
                        style: .continuous
                    )
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                Button {
                    fullScreenControlsExpanded = true
                } label: {
                    Image(systemName: "chevron.up")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 54, height: 30)
                        .background(.black.opacity(0.62), in: Capsule())
                }
                .accessibilityLabel("text.show_screen_controls")
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .padding(.horizontal, 16)
        .safeAreaPadding(.top, 8)
        .safeAreaPadding(.bottom, 8)
        .animation(
            .spring(response: 0.3, dampingFraction: 0.84),
            value: fullScreenControlsExpanded
        )
    }

    private var remoteKeyboardSheet: some View { RemoteKeyboardPanel() }

    private func liveScreenOrientationMenu(
        buttonSize: CGFloat,
        showsBackground: Bool = false,
        showsTitle: Bool = false
    ) -> some View {
        Menu {
            ForEach(LiveScreenOrientationPreference.allCases) { preference in
                Button {
                    feedbackToken += 1
                    liveScreenOrientationRawValue = preference.rawValue
                    applyLiveScreenOrientation(preference)
                } label: {
                    Label(
                        preference.title,
                        systemImage:
                            liveScreenOrientationPreference == preference
                                ? "checkmark"
                                : preference.systemImage
                    )
                }
            }
        } label: {
            Group {
                if showsTitle {
                    Label(
                        "text.rotate_screen",
                        systemImage: "rectangle.landscape.rotate"
                    )
                    .font(.caption2.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 8)
                    .frame(height: buttonSize)
                } else {
                    Image(
                        systemName:
                            liveScreenOrientationPreference.systemImage
                    )
                    .font(.caption.weight(.bold))
                    .frame(width: buttonSize, height: buttonSize)
                }
            }
                .background(
                    showsBackground
                        ? Color.white.opacity(0.14)
                        : Color.clear,
                    in: Circle()
                )
        }
        .accessibilityLabel("text.screen_orientation")
    }

    private func liveScreenModeButton(
        _ mode: LiveScreenControlMode
    ) -> some View {
        Button {
            feedbackToken += 1
            liveScreenControlMode = mode
        } label: {
            Label(mode.title, systemImage: mode.systemImage)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 10)
                .frame(height: 32)
                .foregroundStyle(
                    liveScreenControlMode == mode
                        ? Color.white
                        : Color.white.opacity(0.72)
                )
                .background(
                    liveScreenControlMode == mode
                        ? AnyShapeStyle(TVADBTheme.accentGradient)
                        : AnyShapeStyle(Color.white.opacity(0.12)),
                    in: Capsule()
                )
        }
    }

    private var mousePreviewImage: UIImage? {
        model.mousePreviewImage
    }

    private enum DetectedLiveScreenOrientation: Equatable {
        case portrait
        case landscape
    }

    private var liveScreenOrientationPreference:
        LiveScreenOrientationPreference {
        LiveScreenOrientationPreference(
            rawValue: liveScreenOrientationRawValue
        ) ?? .automatic
    }

    private var currentLiveScreenSourceSize: CGSize {
        (model.mousePreviewHasVideoFrame
            ? model.mousePreviewVideoSize
            : nil)
            ?? mousePreviewImage?.size
            ?? televisionSize
    }

    private var detectedLiveScreenOrientation:
        DetectedLiveScreenOrientation {
        let size = currentLiveScreenSourceSize
        return size.width >= size.height ? .landscape : .portrait
    }

    private var activeWindowScene: UIWindowScene? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
    }

    private func applyLiveScreenOrientation(
        _ preference: LiveScreenOrientationPreference? = nil
    ) {
        let selectedPreference =
            preference ?? liveScreenOrientationPreference
        let orientationMask: UIInterfaceOrientationMask
        switch selectedPreference {
        case .automatic:
            orientationMask = detectedLiveScreenOrientation == .landscape
                ? .landscape
                : .portrait
        case .portrait:
            orientationMask = .portrait
        case .landscapeLeft:
            orientationMask = .landscapeLeft
        case .landscapeRight:
            orientationMask = .landscapeRight
        }
        orientationLogger.info(
            "Applying preference \(selectedPreference.rawValue, privacy: .public), mask \(orientationMask.rawValue)"
        )
        requestInterfaceOrientation(orientationMask)
    }

    private func handleFullScreenPresentationChange(_ isPresented: Bool) {
        orientationLogger.info(
            "Full-screen presentation changed: \(isPresented)"
        )
        orientationTransitionTask?.cancel()
        if isPresented {
            liveScreenOrientationRawValue =
                LiveScreenOrientationPreference.automatic.rawValue
            orientationBeforeFullScreen =
                activeWindowScene?.interfaceOrientation
            orientationLogger.info(
                "Saved previous orientation \(orientationBeforeFullScreen?.rawValue ?? -1)"
            )
            orientationTransitionTask = Task { @MainActor in
                // Wait for the cover transition before changing its geometry.
                try? await Task.sleep(for: .milliseconds(520))
                guard !Task.isCancelled else { return }
                orientationLogger.info(
                    "Full-screen presentation settled; applying orientation"
                )
                applyLiveScreenOrientation(.automatic)
            }
        } else {
            restoreOrientationAfterFullScreen()
        }
    }

    private func restoreOrientationAfterFullScreen() {
        guard let orientationBeforeFullScreen else {
            releaseInterfaceOrientationLock()
            return
        }
        self.orientationBeforeFullScreen = nil
        let orientationMask: UIInterfaceOrientationMask
        switch orientationBeforeFullScreen {
        case .portrait:
            orientationMask = .portrait
        case .portraitUpsideDown:
            orientationMask = .portraitUpsideDown
        case .landscapeLeft:
            orientationMask = .landscapeLeft
        case .landscapeRight:
            orientationMask = .landscapeRight
        default:
            orientationMask = .all
        }
        requestInterfaceOrientation(
            orientationMask,
            releaseOrientationLockAfterUpdate: true
        )
        orientationTransitionTask = Task { @MainActor in
            // UIKit can reject geometry changes while a full-screen cover is
            // still dismissing. Retry after the transition has settled.
            try? await Task.sleep(for: .milliseconds(420))
            guard !Task.isCancelled else { return }
            requestInterfaceOrientation(
                orientationMask,
                releaseOrientationLockAfterUpdate: true
            )
        }
    }

    private func requestInterfaceOrientation(
        _ orientationMask: UIInterfaceOrientationMask,
        releaseOrientationLockAfterUpdate: Bool = false
    ) {
        guard let scene = activeWindowScene else {
            orientationLogger.error(
                "No foreground window scene for orientation request"
            )
            return
        }
        TVADBAppDelegate.supportedInterfaceOrientations = orientationMask
        var viewController = scene.keyWindow?.rootViewController
        viewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        while let presentedViewController =
            viewController?.presentedViewController {
            presentedViewController
                .setNeedsUpdateOfSupportedInterfaceOrientations()
            viewController = presentedViewController
        }
        scene.requestGeometryUpdate(
            .iOS(interfaceOrientations: orientationMask)
        ) { error in
            self.orientationLogger.error(
                "Geometry update failed for mask \(orientationMask.rawValue): \(error.localizedDescription, privacy: .public)"
            )
        }
        guard releaseOrientationLockAfterUpdate else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(700))
            TVADBAppDelegate.supportedInterfaceOrientations = .all
            scene.keyWindow?.rootViewController?
                .setNeedsUpdateOfSupportedInterfaceOrientations()
        }
    }

    private func releaseInterfaceOrientationLock() {
        orientationTransitionTask?.cancel()
        TVADBAppDelegate.supportedInterfaceOrientations = .all
        guard let scene = activeWindowScene else { return }
        var viewController = scene.keyWindow?.rootViewController
        viewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        while let presentedViewController =
            viewController?.presentedViewController {
            presentedViewController
                .setNeedsUpdateOfSupportedInterfaceOrientations()
            viewController = presentedViewController
        }
    }

    private func handleLiveScreenSourceSizeChange(
        from oldSize: CGSize,
        to newSize: CGSize
    ) {
        guard controlMode == .screen,
              oldSize.width > 0,
              oldSize.height > 0,
              newSize.width > 0,
              newSize.height > 0,
              abs(oldSize.width - newSize.width) > 0.5
                || abs(oldSize.height - newSize.height) > 0.5 else {
            return
        }

        screenCoordinateSyncTask?.cancel()
        let previousRotation = androidScreenRotation
        screenCoordinateSyncTask = Task { @MainActor in
            let currentRotation =
                await model.televisionScreenRotation()
            guard !Task.isCancelled else { return }

            let mappedPosition = rotatedMousePosition(
                mousePosition,
                from: oldSize,
                to: newSize,
                oldRotation: previousRotation,
                newRotation: currentRotation
            )
            mousePosition = mappedPosition
            televisionSize = newSize
            liveScreenLastPanLocation = nil
            liveScreenTouchStart = nil
            androidScreenRotation =
                currentRotation ?? previousRotation
        }
    }

    private func rotatedMousePosition(
        _ point: CGPoint,
        from oldSize: CGSize,
        to newSize: CGSize,
        oldRotation: Int?,
        newRotation: Int?
    ) -> CGPoint {
        let normalizedX = min(
            max(point.x / max(oldSize.width - 1, 1), 0),
            1
        )
        let normalizedY = min(
            max(point.y / max(oldSize.height - 1, 1), 0),
            1
        )

        let mapped: CGPoint
        if let oldRotation, let newRotation {
            switch (newRotation - oldRotation + 4) % 4 {
            case 1:
                mapped = CGPoint(
                    x: 1 - normalizedY,
                    y: normalizedX
                )
            case 2:
                mapped = CGPoint(
                    x: 1 - normalizedX,
                    y: 1 - normalizedY
                )
            case 3:
                mapped = CGPoint(
                    x: normalizedY,
                    y: 1 - normalizedX
                )
            default:
                mapped = CGPoint(
                    x: normalizedX,
                    y: normalizedY
                )
            }
        } else {
            mapped = CGPoint(x: normalizedX, y: normalizedY)
        }

        return CGPoint(
            x: mapped.x * max(newSize.width - 1, 0),
            y: mapped.y * max(newSize.height - 1, 0)
        )
    }

    private func connectLiveScreen() {
        model.startMousePreview()
        Task {
            televisionSize = await model.prepareTouchscreenControl()
            guard model.mousePreviewIsRequested else { return }
            androidScreenRotation = await model.televisionScreenRotation()
        }
    }

    @ViewBuilder
    private var livePreviewPlaceholder: some View {
        VStack(spacing: 12) {
            if model.mousePreviewIsLoading {
                ProgressView().tint(.white)
                Text("text.loading_live_screen").font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Button("text.disconnect_screen") { model.stopMousePreview() }
                    .buttonStyle(.bordered).tint(.white)
            } else {
                Image(systemName: "display")
                    .font(.system(size: 34, weight: .medium))
                    .foregroundStyle(TVADBTheme.accent)
                Text(model.mousePreviewIsRequested ? "text.live_screen_unavailable" : "text.screen_not_connected")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                Button(model.mousePreviewIsRequested ? "text.retry" : "text.connect_screen") {
                    connectLiveScreen()
                }
                .buttonStyle(.borderedProminent).tint(TVADBTheme.primaryButtonTint)
                .disabled(!model.connectionState.isConnected)
            }
        }
    }

    private func livePreviewCursor(
        in previewRect: CGRect,
        sourceSize: CGSize
    ) -> some View {
        let width = max(sourceSize.width, 1)
        let height = max(sourceSize.height, 1)
        let x = previewRect.minX
            + min(max(mousePosition.x / width, 0), 1) * previewRect.width
        let y = previewRect.minY
            + min(max(mousePosition.y / height, 0), 1) * previewRect.height

        return Image(systemName: "cursorarrow")
            .font(.system(size: 25, weight: .bold))
            .foregroundStyle(.white)
            .shadow(color: .black.opacity(0.88), radius: 2, x: 1, y: 2)
            .position(x: x, y: y)
            .animation(.linear(duration: 0.045), value: mousePosition)
    }

    private func aspectFitRect(
        sourceSize: CGSize,
        inside bounds: CGRect
    ) -> CGRect {
        guard sourceSize.width > 0,
              sourceSize.height > 0,
              bounds.width > 0,
              bounds.height > 0 else {
            return bounds
        }
        let scale = min(
            bounds.width / sourceSize.width,
            bounds.height / sourceSize.height
        )
        let fittedSize = CGSize(
            width: sourceSize.width * scale,
            height: sourceSize.height * scale
        )
        return CGRect(
            x: bounds.midX - fittedSize.width / 2,
            y: bounds.midY - fittedSize.height / 2,
            width: fittedSize.width,
            height: fittedSize.height
        )
    }

    private func coordinateTouchIndicator(in padSize: CGSize) -> some View {
        let width = max(televisionSize.width, 1)
        let height = max(televisionSize.height, 1)
        let interactionHeight = max(padSize.height, 1)
        let x = min(
            max(mousePosition.x / width * padSize.width, 14),
            max(padSize.width - 14, 14)
        )
        let y = min(
            max(mousePosition.y / height * interactionHeight, 14),
            max(interactionHeight - 14, 14)
        )

        return ZStack {
            Circle()
                .fill(TVADBTheme.accent.opacity(0.16))
                .frame(width: 28, height: 28)
            Circle()
                .fill(TVADBTheme.accent)
                .frame(width: 8, height: 8)
        }
        .position(x: x, y: y)
        .animation(.linear(duration: 0.06), value: mousePosition)
    }

    private var connectedName: String {
        if case .connected(let name) = model.connectionState { return name }
        return model.currentDevice?.name ?? "Android TV"
    }

    // Preserve swipe fallback only for TVs without native mouse support.
    private func fallbackDirectionKeyCode(for translation: CGSize) -> Int? {
        guard max(abs(translation.width), abs(translation.height)) > 18 else { return nil }
        if abs(translation.width) > abs(translation.height) {
            return translation.width > 0 ? 22 : 21
        }
        return translation.height > 0 ? 20 : 19
    }

    private func send(_ code: Int) {
        feedbackToken += 1
        Task { await model.sendRemoteKey(code) }
    }

    private func centerMousePointer() {
        feedbackToken += 1
        Task {
            if await model.centerMousePointer(in: televisionSize) {
                resetMousePosition()
            }
        }
    }

    private func resetMousePosition(movePointer: Bool = false) {
        mousePosition = CGPoint(
            x: televisionSize.width * 0.5,
            y: televisionSize.height * 0.5
        )
        if movePointer {
            let point = mousePosition
            Task {
                await model.moveMouse(to: point)
            }
        }
    }

    private var mousePadTitle: String {
        if model.mouseClickUnavailable {
            return L10n.string("text.button_compatibility_mode")
        }
        if model.mouseMoveUnavailable {
            return L10n.string("text.coordinate_touch_mode")
        }
        if model.nativeMouseInputActive {
            return L10n.string("text.swipe_to_move_the_native_pointer")
        }
        return L10n.string("text.swipe_to_move_tap_or_double_tap")
    }

    private var mousePadSubtitle: String {
        if model.mouseClickUnavailable {
            return L10n.string("text.tap_to_confirm_swipe_to_navigate_double_tap_to_confirm_twice")
        }
        if model.mouseMoveUnavailable {
            return L10n.string("text.mouse_coordinate_instructions")
        }
        if model.nativeMouseInputActive {
            if model.physicalHIDMouseActive {
                return L10n.string(
                    "text.android_recognized_tvadb_as_a_physical_hid_mouse"
                )
            }
            return L10n.string("text.android_recognized_tvadb_as_a_virtual_mouse")
        }
        return L10n.string("text.the_tv_firmware_must_support_a_system_mouse_pointer")
    }

    private func clickMouse() {
        feedbackToken += 1
        Task {
            if model.mouseDragActive {
                await model.setMouseDragActive(false)
            } else {
                await model.clickMouse(at: mousePosition)
            }
        }
    }

    private func performPendingMouseAction() {
        let action = pendingMouseAction
        pendingMouseAction = nil
        guard let action, model.connectionState.isConnected,
              scenePhase == .active, controlMode == .mouse else { return }
        // UIKit has finished dismissing the sheet before we update the TV or
        // local drag state. Closing the panel without selection sends nothing.
        switch action {
        case .doubleClick: doubleClickMouse()
        case .rightClick: rightClickMouse()
        case .middleClick: middleClickMouse()
        case .scrollUp: scrollMouse(amount: 1)
        case .scrollDown: scrollMouse(amount: -1)
        }
    }

    private func doubleClickMouse() {
        feedbackToken += 1
        Task {
            if model.mouseDragActive { await model.setMouseDragActive(false) }
            await model.doubleClickMouse(at: mousePosition)
        }
    }

    private func toggleMouseDrag() {
        feedbackToken += 1
        Task {
            await model.setMouseDragActive(!model.mouseDragActive)
        }
    }

    private func rightClickMouse() {
        feedbackToken += 1
        Task {
            if model.mouseDragActive {
                await model.setMouseDragActive(false)
            }
            await model.rightClickMouse()
        }
    }

    private func middleClickMouse() {
        feedbackToken += 1
        Task {
            if model.mouseDragActive {
                await model.setMouseDragActive(false)
            }
            await model.middleClickMouse()
        }
    }

    private func scrollMouse(amount: Int) {
        feedbackToken += 1
        Task {
            await model.scrollMouse(amount: amount)
        }
    }

    private func transformedLiveScreenRect(baseRect: CGRect) -> CGRect {
        CGRect(
            x: baseRect.midX
                - baseRect.width * liveScreenZoom / 2
                + liveScreenOffset.width,
            y: baseRect.midY
                - baseRect.height * liveScreenZoom / 2
                + liveScreenOffset.height,
            width: baseRect.width * liveScreenZoom,
            height: baseRect.height * liveScreenZoom
        )
    }

    private func liveScreenPoint(
        for location: CGPoint,
        in displayRect: CGRect,
        sourceSize: CGSize,
        clampsToScreen: Bool
    ) -> CGPoint? {
        guard displayRect.width > 0, displayRect.height > 0 else {
            return nil
        }
        if !clampsToScreen && !displayRect.contains(location) {
            return nil
        }
        let normalizedX = min(
            max((location.x - displayRect.minX) / displayRect.width, 0),
            1
        )
        let normalizedY = min(
            max((location.y - displayRect.minY) / displayRect.height, 0),
            1
        )
        return CGPoint(
            x: normalizedX * max(sourceSize.width - 1, 0),
            y: normalizedY * max(sourceSize.height - 1, 0)
        )
    }

    private func handleLiveScreenTap(
        _ location: CGPoint,
        displayRect: CGRect,
        sourceSize: CGSize,
        isDoubleTap: Bool
    ) {
        switch liveScreenControlMode {
        case .mouse:
            // The live screen is a relative trackpad in mouse mode. Tapping
            // anywhere on the visible picture clicks the cursor's current
            // position; only dragging is allowed to move that cursor.
            guard displayRect.contains(location) else { return }
            let point = mousePosition
            feedbackToken += 1
            Task {
                if isDoubleTap {
                    await model.tapTouchscreen(at: point)
                    try? await Task.sleep(for: .milliseconds(120))
                    await model.tapTouchscreen(at: point)
                } else {
                    await model.tapTouchscreen(at: point)
                }
            }
        case .touch:
            // Touch mode maps the finger's absolute picture coordinate to the
            // Android display, matching a real touchscreen.
            guard let point = liveScreenPoint(
                for: location,
                in: displayRect,
                sourceSize: sourceSize,
                clampsToScreen: false
            ) else {
                return
            }
            feedbackToken += 1
            Task {
                await model.tapTouchscreen(at: point)
                if isDoubleTap {
                    try? await Task.sleep(for: .milliseconds(120))
                    await model.tapTouchscreen(at: point)
                }
            }
        }
    }

    private func handleLiveScreenPan(
        phase: LiveScreenPanPhase,
        location: CGPoint,
        displayRect: CGRect,
        sourceSize: CGSize
    ) {
        switch phase {
        case .began:
            liveScreenLastPanLocation = location
            liveScreenTouchStart = liveScreenPoint(
                for: location,
                in: displayRect,
                sourceSize: sourceSize,
                clampsToScreen: true
            )
        case .changed:
            if liveScreenControlMode == .mouse,
               let previousLocation = liveScreenLastPanLocation {
                updateLiveScreenMousePosition(
                    from: previousLocation,
                    to: location,
                    displayRect: displayRect,
                    sourceSize: sourceSize
                )
            }
            liveScreenLastPanLocation = location
        case .ended:
            if liveScreenControlMode == .touch,
               let start = liveScreenTouchStart,
                      let end = liveScreenPoint(
                        for: location,
                        in: displayRect,
                        sourceSize: sourceSize,
                        clampsToScreen: true
                      ),
                      hypot(end.x - start.x, end.y - start.y) >= 12 {
                Task {
                    await model.swipeTouchscreen(
                        from: start,
                        to: end
                    )
                }
            }
            liveScreenLastPanLocation = nil
            liveScreenTouchStart = nil
        case .cancelled:
            liveScreenLastPanLocation = nil
            liveScreenTouchStart = nil
        }
    }

    private func handleLiveScreenLongPressDrag(
        phase: LiveScreenPanPhase,
        location: CGPoint,
        displayRect: CGRect,
        sourceSize: CGSize
    ) {
        switch phase {
        case .began:
            feedbackToken += 1
            liveScreenLongDragLastLocation = location

            switch liveScreenControlMode {
            case .mouse:
                liveScreenLongDragStart = mousePosition
            case .touch:
                liveScreenLongDragStart = liveScreenPoint(
                    for: location,
                    in: displayRect,
                    sourceSize: sourceSize,
                    clampsToScreen: true
                )
            }

        case .changed:
            if liveScreenControlMode == .mouse,
               let previousLocation = liveScreenLongDragLastLocation {
                updateLiveScreenMousePosition(
                    from: previousLocation,
                    to: location,
                    displayRect: displayRect,
                    sourceSize: sourceSize
                )
            }
            liveScreenLongDragLastLocation = location

        case .ended:
            if liveScreenControlMode == .mouse,
               let previousLocation = liveScreenLongDragLastLocation {
                updateLiveScreenMousePosition(
                    from: previousLocation,
                    to: location,
                    displayRect: displayRect,
                    sourceSize: sourceSize
                )
            }

            let mode = liveScreenControlMode
            let start = liveScreenLongDragStart
            let end: CGPoint? = switch mode {
            case .mouse:
                mousePosition
            case .touch:
                liveScreenPoint(
                    for: location,
                    in: displayRect,
                    sourceSize: sourceSize,
                    clampsToScreen: true
                )
            }
            clearLiveScreenLongDragState()

            Task { @MainActor in
                // Screen control deliberately uses absolute Android display
                // coordinates for dragging in both modes. This works even
                // when the TV rejects a persistent HID/uinput mouse button.
                if let start, let end {
                    let distance = hypot(
                        end.x - start.x,
                        end.y - start.y
                    )
                    if mode == .mouse, distance < 12 {
                        // Some Android builds collapse a zero-distance
                        // `draganddrop` into a normal tap. Use an explicit
                        // held touchscreen gesture so a stationary press at
                        // the virtual cursor reliably opens context menus.
                        await model.longPressTouchscreen(at: end)
                    } else {
                        await model.dragTouchscreen(
                            from: start,
                            to: end
                        )
                    }
                }
            }

        case .cancelled:
            clearLiveScreenLongDragState()
        }
    }

    private func updateLiveScreenMousePosition(
        from previousLocation: CGPoint,
        to location: CGPoint,
        displayRect: CGRect,
        sourceSize: CGSize
    ) {
        let xScale = sourceSize.width / max(displayRect.width, 1)
        let yScale = sourceSize.height / max(displayRect.height, 1)
        mousePosition = CGPoint(
            x: min(
                max(
                    mousePosition.x
                        + (location.x - previousLocation.x) * xScale,
                    0
                ),
                max(sourceSize.width - 1, 0)
            ),
            y: min(
                max(
                    mousePosition.y
                        + (location.y - previousLocation.y) * yScale,
                    0
                ),
                max(sourceSize.height - 1, 0)
            )
        )
    }

    private func clearLiveScreenLongDragState() {
        liveScreenLongDragStart = nil
        liveScreenLongDragLastLocation = nil
    }

    private func handleLiveScreenScroll(
        translationY: CGFloat,
        sourceSize: CGSize
    ) {
        guard abs(translationY) >= 28 else { return }
        feedbackToken += 1

        switch liveScreenControlMode {
        case .mouse:
            let centerX = min(
                max(mousePosition.x, 0),
                max(sourceSize.width - 1, 0)
            )
            let startY = translationY < 0
                ? sourceSize.height * 0.72
                : sourceSize.height * 0.28
            let endY = translationY < 0
                ? sourceSize.height * 0.28
                : sourceSize.height * 0.72
            Task {
                await model.swipeTouchscreen(
                    from: CGPoint(x: centerX, y: startY),
                    to: CGPoint(x: centerX, y: endY),
                    durationMilliseconds: 320
                )
            }
        case .touch:
            let centerX = sourceSize.width / 2
            let startY = translationY < 0
                ? sourceSize.height * 0.72
                : sourceSize.height * 0.28
            let endY = translationY < 0
                ? sourceSize.height * 0.28
                : sourceSize.height * 0.72
            Task {
                await model.swipeTouchscreen(
                    from: CGPoint(x: centerX, y: startY),
                    to: CGPoint(x: centerX, y: endY),
                    durationMilliseconds: 320
                )
            }
        }
    }

    private func updateLiveScreenZoom(
        by scale: CGFloat,
        anchor: CGPoint,
        baseRect: CGRect,
        interactionBounds: CGRect
    ) {
        let oldZoom = liveScreenZoom
        let newZoom = min(max(oldZoom * scale, 1), 4)
        guard abs(newZoom - oldZoom) > 0.0001 else { return }

        let oldCenter = CGPoint(
            x: baseRect.midX + liveScreenOffset.width,
            y: baseRect.midY + liveScreenOffset.height
        )
        let ratio = newZoom / oldZoom
        let proposedOffset = CGSize(
            width: anchor.x
                - (anchor.x - oldCenter.x) * ratio
                - baseRect.midX,
            height: anchor.y
                - (anchor.y - oldCenter.y) * ratio
                - baseRect.midY
        )
        liveScreenZoom = newZoom
        liveScreenOffset = clampedLiveScreenOffset(
            proposedOffset,
            scale: newZoom,
            baseRect: baseRect,
            interactionBounds: interactionBounds
        )
    }

    private func updateLiveScreenOffset(
        by translation: CGSize,
        baseRect: CGRect,
        interactionBounds: CGRect
    ) {
        guard liveScreenZoom > 1.01 else { return }
        liveScreenOffset = clampedLiveScreenOffset(
            CGSize(
                width: liveScreenOffset.width + translation.width,
                height: liveScreenOffset.height + translation.height
            ),
            scale: liveScreenZoom,
            baseRect: baseRect,
            interactionBounds: interactionBounds
        )
    }

    private func clampedLiveScreenOffset(
        _ proposedOffset: CGSize,
        scale: CGFloat,
        baseRect: CGRect,
        interactionBounds: CGRect
    ) -> CGSize {
        let scaledWidth = baseRect.width * scale
        let scaledHeight = baseRect.height * scale
        let horizontalLimit = max(
            0,
            (scaledWidth - interactionBounds.width) / 2
                + abs(baseRect.midX - interactionBounds.midX)
        )
        let verticalLimit = max(
            0,
            (scaledHeight - interactionBounds.height) / 2
                + abs(baseRect.midY - interactionBounds.midY)
        )
        return CGSize(
            width: min(max(proposedOffset.width, -horizontalLimit), horizontalLimit),
            height: min(max(proposedOffset.height, -verticalLimit), verticalLimit)
        )
    }

    private func resetLiveScreenZoom() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) {
            liveScreenZoom = 1
            liveScreenOffset = .zero
        }
    }

    private func updateCoordinateTouchPosition(
        _ location: CGPoint,
        in padSize: CGSize
    ) {
        guard model.mouseMoveUnavailable else { return }
        let interactionHeight = max(padSize.height, 1)
        mousePosition = CGPoint(
            x: min(
                max(location.x / max(padSize.width, 1) * televisionSize.width, 0),
                televisionSize.width - 1
            ),
            y: min(
                max(location.y / interactionHeight * televisionSize.height, 0),
                televisionSize.height - 1
            )
        )
    }

    private func moveMouseOnPad(to location: CGPoint, delta: CGPoint, in padSize: CGSize) {
        if model.mouseMoveUnavailable {
            updateCoordinateTouchPosition(location, in: padSize)
            return
        }
        if model.nativeMouseInputActive {
            let scale = televisionSize.width / max(padSize.width, 1) * 1.35
            model.enqueueRelativeMouseMove(CGPoint(x: delta.x * scale, y: delta.y * scale))
            return
        }
        let xScale = televisionSize.width / max(padSize.width, 1) * 1.35
        let yScale = televisionSize.height / max(padSize.height, 1) * 1.35
        mousePosition = CGPoint(
            x: min(max(mousePosition.x + delta.x * xScale, 0), televisionSize.width - 1),
            y: min(max(mousePosition.y + delta.y * yScale, 0), televisionSize.height - 1)
        )
        let point = mousePosition
        Task { await model.moveMouse(to: point) }
    }
}

private struct MousePadSurface: UIViewRepresentable {
    var enabled: Bool
    var onMove: (CGPoint, CGPoint) -> Void
    var onRelease: (CGSize) -> Void
    var onTap: (CGPoint, Int) -> Void
    var onHorizontalSwipe: () -> Void
    var onDirection: @MainActor (Int) async -> Bool

    func makeUIView(context: Context) -> Surface {
        Surface()
    }

    func updateUIView(_ view: Surface, context: Context) {
        view.parent = self
        if view.isUserInteractionEnabled && !enabled { view.cancelTouches() }
        view.isUserInteractionEnabled = enabled
    }

    final class Surface: UIView, UIGestureRecognizerDelegate {
        var parent: MousePadSurface?
        private let repeater = DirectionalKeyRepeater()
        private var touches = Set<UITouch>()
        private var usedMultipleFingers = false
        private var navigation = TwoFingerNavigation()
        private var origin = CGPoint.zero
        private var previous = CGPoint.zero
        private var isMoving = false
        private lazy var tracking = TrackingGesture(target: nil, action: nil)
        private lazy var singleTap = makeTap(count: 1)
        private lazy var doubleTap = makeTap(count: 2)

        init() {
            super.init(frame: .zero)
            isMultipleTouchEnabled = true
            tracking.delegate = self
            tracking.cancelsTouchesInView = false
            addGestureRecognizer(tracking)
            singleTap.require(toFail: doubleTap)
            addGestureRecognizer(singleTap)
            addGestureRecognizer(doubleTap)
        }

        required init?(coder: NSCoder) { nil }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool {
            other === singleTap || other === doubleTap
        }

        private func makeTap(count: Int) -> UITapGestureRecognizer {
            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
            tap.numberOfTapsRequired = count
            tap.cancelsTouchesInView = false
            tap.delaysTouchesEnded = false
            return tap
        }

        @objc private func handleTap(_ tap: UITapGestureRecognizer) {
            guard !usedMultipleFingers, parent?.enabled == true else { return }
            parent?.onTap(tap.location(in: self), tap.numberOfTapsRequired)
        }

        func beginTracking(_ newTouches: Set<UITouch>) {
            if touches.isEmpty {
                usedMultipleFingers = false
                navigation = TwoFingerNavigation()
                singleTap.isEnabled = true
                doubleTap.isEnabled = true
                isMoving = false
            }
            touches.formUnion(newTouches)
            if touches.count == 1, let touch = touches.first {
                origin = touch.location(in: self)
                previous = origin
            } else {
                usedMultipleFingers = true
                singleTap.isEnabled = false
                doubleTap.isEnabled = false
                if touches.count == 2 { origin = touchCenter }
                else {
                    navigation.cancel()
                    repeater.stop()
                }
            }
        }

        func moveTracking() {
            if usedMultipleFingers {
                guard touches.count == 2, let parent else { return }
                let point = touchCenter
                guard let key = navigation.update(dx: point.x - origin.x, dy: point.y - origin.y) else {
                    return
                }
                if key == 21 || key == 22 {
                    parent.onHorizontalSwipe()
                    repeater.start(key: key, send: parent.onDirection)
                } else {
                    Task { await parent.onDirection(key) }
                }
            } else if let touch = touches.first {
                let point = touch.location(in: self)
                let delta = CGPoint(x: point.x - previous.x, y: point.y - previous.y)
                guard isMoving || hypot(delta.x, delta.y) >= 3 else { return }
                isMoving = true
                parent?.onMove(point, delta)
                previous = point
            }
        }

        func endTracking(_ ended: Set<UITouch>) -> Bool {
            if !usedMultipleFingers, let touch = ended.first {
                let point = touch.location(in: self)
                parent?.onRelease(CGSize(width: point.x - origin.x, height: point.y - origin.y))
            }
            navigation.cancel()
            repeater.stop()
            touches.subtract(ended)
            if touches.isEmpty {
                singleTap.isEnabled = true
                doubleTap.isEnabled = true
            }
            return touches.isEmpty
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil { cancelTouches() }
        }

        func cancelTouches() {
            tracking.isEnabled = false
            resetTracking()
            tracking.isEnabled = true
        }

        func resetTracking() {
            repeater.stop()
            touches.removeAll()
            usedMultipleFingers = true
            singleTap.isEnabled = false
            doubleTap.isEnabled = false
            singleTap.isEnabled = true
            doubleTap.isEnabled = true
        }

        private var touchCenter: CGPoint {
            let points = touches.map { $0.location(in: self) }
            return CGPoint(x: (points[0].x + points[1].x) / 2,
                           y: (points[0].y + points[1].y) / 2)
        }
    }

    // Claim touches before the enclosing ScrollView can begin scrolling.
    private final class TrackingGesture: UIGestureRecognizer {
        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            guard let surface = view as? Surface else { return }
            surface.beginTracking(touches)
            if state == .possible { state = .began }
        }

        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
            (view as? Surface)?.moveTracking()
            state = .changed
        }

        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
            if (view as? Surface)?.endTracking(touches) == true { state = .ended }
        }

        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
            (view as? Surface)?.resetTracking()
            state = .cancelled
        }
    }
}

private enum LiveScreenPanPhase {
    case began
    case changed
    case ended
    case cancelled
}

private struct LiveScreenGestureLayer: UIViewRepresentable {
    let onTap: (CGPoint) -> Void
    let onDoubleTap: (CGPoint) -> Void
    let onOneFingerPan: (LiveScreenPanPhase, CGPoint) -> Void
    let onOneFingerLongPressDrag: (LiveScreenPanPhase, CGPoint) -> Void
    let onTwoFingerLongPress: () -> Void
    let onThreeFingerPan: (CGFloat) -> Void
    let onPinch: (CGFloat, CGPoint) -> Void
    let onViewportPan: (CGSize) -> Void
    let onTouchActivityChanged: (Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isMultipleTouchEnabled = true
        view.isUserInteractionEnabled = true
        view.accessibilityLabel = L10n.string("text.interactive_live_screen")

        let singleTap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleSingleTap(_:))
        )
        singleTap.numberOfTapsRequired = 1
        singleTap.numberOfTouchesRequired = 1

        let doubleTap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleDoubleTap(_:))
        )
        doubleTap.numberOfTapsRequired = 2
        doubleTap.numberOfTouchesRequired = 1
        singleTap.require(toFail: doubleTap)

        let oneFingerPan = UIPanGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleOneFingerPan(_:))
        )
        oneFingerPan.minimumNumberOfTouches = 1
        oneFingerPan.maximumNumberOfTouches = 1

        let oneFingerLongPressDrag = UILongPressGestureRecognizer(
            target: context.coordinator,
            action: #selector(
                Coordinator.handleOneFingerLongPressDrag(_:)
            )
        )
        oneFingerLongPressDrag.minimumPressDuration = 0.38
        oneFingerLongPressDrag.allowableMovement = 30
        oneFingerLongPressDrag.numberOfTouchesRequired = 1

        // A screen swipe must never be interpreted as the click that follows
        // a mouse-up event. Let the pan recognizer make the first decision;
        // a stationary touch still lets the tap recognizers proceed.
        singleTap.require(toFail: oneFingerPan)
        doubleTap.require(toFail: oneFingerPan)
        singleTap.require(toFail: oneFingerLongPressDrag)
        doubleTap.require(toFail: oneFingerLongPressDrag)

        let twoFingerLongPress = UILongPressGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTwoFingerLongPress(_:))
        )
        twoFingerLongPress.minimumPressDuration = 0.55
        twoFingerLongPress.allowableMovement = 16
        twoFingerLongPress.numberOfTouchesRequired = 2

        let twoFingerPan = UIPanGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTwoFingerPan(_:))
        )
        twoFingerPan.minimumNumberOfTouches = 2
        twoFingerPan.maximumNumberOfTouches = 2

        let threeFingerPan = UIPanGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleThreeFingerPan(_:))
        )
        threeFingerPan.minimumNumberOfTouches = 3
        threeFingerPan.maximumNumberOfTouches = 3

        let pinch = UIPinchGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handlePinch(_:))
        )

        [
            singleTap,
            doubleTap,
            oneFingerPan,
            oneFingerLongPressDrag,
            twoFingerLongPress,
            twoFingerPan,
            threeFingerPan,
            pinch
        ].forEach {
            $0.delegate = context.coordinator
            $0.cancelsTouchesInView = true
            view.addGestureRecognizer($0)
        }

        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.parent = self
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var parent: LiveScreenGestureLayer
        private var activeRecognizers: Set<ObjectIdentifier> = []
        private var panMoved = false
        private var oneFingerLongDragActive = false
        private var oneFingerPanClaimedByLongDrag = false
        private var suppressTapUntil = Date.distantPast

        init(parent: LiveScreenGestureLayer) {
            self.parent = parent
        }

        @objc func handleSingleTap(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended,
                  Date() >= suppressTapUntil,
                  let view = recognizer.view else {
                return
            }
            parent.onTap(recognizer.location(in: view))
        }

        @objc func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended,
                  Date() >= suppressTapUntil,
                  let view = recognizer.view else {
                return
            }
            parent.onDoubleTap(recognizer.location(in: view))
        }

        @objc func handleOneFingerPan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let location = recognizer.location(in: view)
            switch recognizer.state {
            case .began:
                if oneFingerLongDragActive {
                    oneFingerPanClaimedByLongDrag = true
                    setActive(recognizer, true)
                    return
                }
                oneFingerPanClaimedByLongDrag = false
                panMoved = false
                setActive(recognizer, true)
                parent.onOneFingerPan(.began, location)
            case .changed:
                guard !oneFingerPanClaimedByLongDrag,
                      !oneFingerLongDragActive else {
                    return
                }
                let translation = recognizer.translation(in: view)
                if hypot(translation.x, translation.y) >= 8 {
                    panMoved = true
                }
                parent.onOneFingerPan(.changed, location)
            case .ended:
                if oneFingerPanClaimedByLongDrag {
                    oneFingerPanClaimedByLongDrag = false
                    panMoved = false
                    setActive(recognizer, false)
                    return
                }
                parent.onOneFingerPan(.ended, location)
                if panMoved {
                    suppressTapUntil = Date().addingTimeInterval(0.2)
                }
                panMoved = false
                setActive(recognizer, false)
            case .cancelled, .failed:
                if oneFingerPanClaimedByLongDrag {
                    oneFingerPanClaimedByLongDrag = false
                    panMoved = false
                    setActive(recognizer, false)
                    return
                }
                parent.onOneFingerPan(.cancelled, location)
                panMoved = false
                setActive(recognizer, false)
            default:
                break
            }
        }

        @objc func handleOneFingerLongPressDrag(
            _ recognizer: UILongPressGestureRecognizer
        ) {
            guard let view = recognizer.view else { return }
            let location = recognizer.location(in: view)
            switch recognizer.state {
            case .began:
                oneFingerLongDragActive = true
                oneFingerPanClaimedByLongDrag = true
                panMoved = false
                suppressTapUntil = Date().addingTimeInterval(0.3)
                // A pan may already have entered `.began` while the finger
                // was settling. Clear its partial state before drag takes
                // ownership of the gesture.
                parent.onOneFingerPan(.cancelled, location)
                setActive(recognizer, true)
                parent.onOneFingerLongPressDrag(.began, location)
            case .changed:
                parent.onOneFingerLongPressDrag(.changed, location)
            case .ended:
                parent.onOneFingerLongPressDrag(.ended, location)
                oneFingerLongDragActive = false
                suppressTapUntil = Date().addingTimeInterval(0.3)
                setActive(recognizer, false)
            case .cancelled, .failed:
                parent.onOneFingerLongPressDrag(.cancelled, location)
                oneFingerLongDragActive = false
                suppressTapUntil = Date().addingTimeInterval(0.3)
                setActive(recognizer, false)
            default:
                break
            }
        }

        @objc func handleTwoFingerLongPress(
            _ recognizer: UILongPressGestureRecognizer
        ) {
            switch recognizer.state {
            case .began:
                setActive(recognizer, true)
                parent.onTwoFingerLongPress()
            case .ended, .cancelled, .failed:
                setActive(recognizer, false)
            default:
                break
            }
        }

        @objc func handleTwoFingerPan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view else { return }
            switch recognizer.state {
            case .began:
                setActive(recognizer, true)
                recognizer.setTranslation(.zero, in: view)
            case .changed:
                let translation = recognizer.translation(in: view)
                parent.onViewportPan(
                    CGSize(width: translation.x, height: translation.y)
                )
                recognizer.setTranslation(.zero, in: view)
            case .ended, .cancelled, .failed:
                setActive(recognizer, false)
            default:
                break
            }
        }

        @objc func handleThreeFingerPan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view else { return }
            switch recognizer.state {
            case .began:
                setActive(recognizer, true)
            case .ended:
                parent.onThreeFingerPan(recognizer.translation(in: view).y)
                setActive(recognizer, false)
            case .cancelled, .failed:
                setActive(recognizer, false)
            default:
                break
            }
        }

        @objc func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
            guard let view = recognizer.view else { return }
            switch recognizer.state {
            case .began:
                setActive(recognizer, true)
                recognizer.scale = 1
            case .changed:
                parent.onPinch(
                    recognizer.scale,
                    recognizer.location(in: view)
                )
                recognizer.scale = 1
            case .ended, .cancelled, .failed:
                setActive(recognizer, false)
            default:
                break
            }
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer:
                UIGestureRecognizer
        ) -> Bool {
            true
        }

        private func setActive(
            _ recognizer: UIGestureRecognizer,
            _ isActive: Bool
        ) {
            let identifier = ObjectIdentifier(recognizer)
            if isActive {
                activeRecognizers.insert(identifier)
            } else {
                activeRecognizers.remove(identifier)
            }
            parent.onTouchActivityChanged(!activeRecognizers.isEmpty)
        }
    }
}

/// UIKit supplies explicit touch-up, drag-exit and cancellation events, so a
/// cancelled SwiftUI gesture cannot leave the direction repeating indefinitely.
enum RemoteGlassAppearance {
    static func configure(_ button: UIButton, icon: String) {
        button.configuration = nil
        button.setImage(UIImage(systemName: icon,
            withConfiguration: UIImage.SymbolConfiguration(pointSize: 22, weight: .semibold)), for: .normal)
        button.tintColor = .white
        button.layer.cornerRadius = 20
        button.layer.borderWidth = 1
        button.layer.shadowColor = UIColor.black.cgColor
        button.layer.shadowOpacity = 0.25
        button.layer.shadowRadius = 8
        button.layer.shadowOffset = CGSize(width: 0, height: 5)
        update(button, pressed: false)
    }
    static func update(_ button: UIButton, pressed: Bool) {
        button.backgroundColor = pressed
            ? UIColor(red: 0.25, green: 0.43, blue: 0.75, alpha: 1)
            : UIColor(red: 0.18, green: 0.22, blue: 0.32, alpha: 1)
        button.layer.borderColor = pressed
            ? UIColor(red: 0.36, green: 0.59, blue: 1, alpha: 1).cgColor
            : UIColor.white.withAlphaComponent(0.20).cgColor
        button.layer.borderWidth = pressed ? 2 : 1
    }
}

struct RemoteDirectionButton: UIViewRepresentable {
    var icon: String
    var title: String
    var isEnabled: Bool
    var onPress: () -> Void
    var onRelease: () -> Void
    var onActivate: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIView(context: Context) -> DirectionTouchButton {
        let button = DirectionTouchButton(type: .system)
        RemoteGlassAppearance.configure(button, icon: icon)
        button.addTarget(context.coordinator, action: #selector(Coordinator.press), for: .touchDown)
        button.addTarget(context.coordinator, action: #selector(Coordinator.endPress),
                         for: [.touchUpOutside, .touchCancel, .touchDragExit])
        button.addTarget(context.coordinator, action: #selector(Coordinator.tapUp), for: .touchUpInside)
        button.activate = { context.coordinator.parent.onActivate() }
        return button
    }

    func updateUIView(_ button: DirectionTouchButton, context: Context) {
        context.coordinator.parent = self
        button.accessibilityLabel = title
        button.tintColor = .white
        button.accessibilityHint = L10n.string("text.hold_direction_to_accelerate")
        button.isEnabled = isEnabled
        if !isEnabled { context.coordinator.endPress() }
    }

    static func dismantleUIView(_ button: DirectionTouchButton, coordinator: Coordinator) {
        coordinator.endPress()
        button.activate = nil
    }

    @MainActor
    final class Coordinator: NSObject {
        var parent: RemoteDirectionButton
        private var interaction: DirectionalPressInteraction!
        init(parent: RemoteDirectionButton) {
            self.parent = parent
            super.init()
            interaction = DirectionalPressInteraction(
                onTap: { [weak self] in self?.parent.onActivate() },
                onHold: { [weak self] in self?.parent.onPress() },
                onRelease: { [weak self] in self?.parent.onRelease() }
            )
        }
        @objc func press() { interaction.begin() }
        @objc func tapUp() { interaction.finish() }
        @objc func endPress() { interaction.cancel() }
    }

    final class DirectionTouchButton: UIButton {
        var activate: (() -> Void)?
        override var isHighlighted: Bool {
            didSet { RemoteGlassAppearance.update(self, pressed: isHighlighted) }
        }
        override func accessibilityActivate() -> Bool {
            guard isEnabled else { return false }
            activate?()
            return true
        }
    }
}

private enum MouseMoreAction: String, CaseIterable, Identifiable {
    case doubleClick, rightClick, middleClick, scrollUp, scrollDown
    var id: String { rawValue }
    var titleKey: String {
        switch self {
        case .doubleClick: "text.double_click"
        case .rightClick: "text.right_click"
        case .middleClick: "text.middle_click"
        case .scrollUp: "text.scroll_up"
        case .scrollDown: "text.scroll_down"
        }
    }
    var icon: String {
        switch self {
        case .doubleClick: "hand.tap.fill"
        case .rightClick: "arrowshape.turn.up.left.fill"
        case .middleClick: "button.programmable"
        case .scrollUp: "arrow.up"
        case .scrollDown: "arrow.down"
        }
    }
}

private struct MouseMoreActionsPanel: View {
    @Environment(\.dismiss) private var dismiss
    var select: (MouseMoreAction) -> Void
    var body: some View {
        NavigationStack {
            List(MouseMoreAction.allCases) { action in
                Button { select(action) } label: {
                    Label(LocalizedStringKey(action.titleKey), systemImage: action.icon)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.primary)
            }
            .tvadbScreenBackground()
            .navigationTitle("text.mouse_more_actions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("text.close") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
