import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ToolsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var history: InstallHistoryStore
    @State private var screenshot: UIImage?
    @State private var showScreenshot = false
    @State private var showInfo = false
    @State private var showNetworkDiagnostics = false
    @State private var showHistory = false
    @State private var showLogcat = false
    @State private var showSettings = false
    @State private var rebootTarget: String?
    @State private var isStartingRecording = false
    @State private var aerialFlavor: AerialFlavor = .standard

    private struct AerialConsentRequest {
        let generation: UUID
        let device: String
        let flavor: AerialFlavor
        let installing: Bool
    }
    @State private var aerialConsentRequest: AerialConsentRequest?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 20) {
                    connectionHeader

                    TVADBPanel {
                        NavigationLink {
                            aerialDetails
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "play.fill")
                                    .foregroundStyle(TVADBTheme.cyan)
                                    .frame(width: 42, height: 42)
                                    .background(TVADBTheme.cyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("text.aerial").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                                    aerialStatus.font(.caption).foregroundStyle(model.aerialError == nil ? Color.secondary : Color.red)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(.tertiary)
                            }
                            .padding(14)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }

                    VStack(alignment: .leading, spacing: 11) {
                        sectionTitle("text.screen_debugging", subtitle: "text.screenshots_logs_and_device_status")
                        screenCaptureCard
                        recordingCard
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                            compactTool("text.live_logs", icon: "text.alignleft", tint: TVADBTheme.deepBlue) {
                                showLogcat = true
                            }
                            compactTool("text.device_information", icon: "info.circle.fill", tint: TVADBTheme.cyan) {
                                Task {
                                    await model.loadDeviceInformation()
                                    if model.errorMessage == nil { showInfo = true }
                                }
                            }
                            compactTool("text.network_diagnostics", icon: "network", tint: TVADBTheme.cyan) {
                                Task {
                                    await model.loadNetworkDiagnostics()
                                    if model.errorMessage == nil { showNetworkDiagnostics = true }
                                }
                            }
                            compactTool("text.installation_history", icon: "clock.arrow.circlepath", tint: TVADBTheme.accent) {
                                showHistory = true
                            }
                        }
                    }

                    toolGroup("text.device_management", subtitle: "text.open_common_system_pages_on_the_tv") {
                        actionRow("text.open_android_settings", icon: "gearshape.fill", tint: TVADBTheme.accent) {
                            shell("am start -a android.settings.SETTINGS")
                        }
                        Divider().padding(.leading, 51)
                        actionRow("text.open_developer_options", icon: "hammer.fill", tint: TVADBTheme.cyan) {
                            shell("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                        }
                    }

                    toolGroup("text.risky_actions", subtitle: "text.the_tv_will_disconnect_immediately") {
                        actionRow("text.restart_tv", icon: "arrow.clockwise.circle.fill", tint: .red, isDestructive: true) {
                            rebootTarget = ""
                        }
                        Divider().padding(.leading, 51)
                        actionRow("text.restart_to_recovery", icon: "cross.case.fill", tint: .red, isDestructive: true) {
                            rebootTarget = "recovery"
                        }
                        Divider().padding(.leading, 51)
                        actionRow("text.restart_to_bootloader", icon: "memorychip.fill", tint: .red, isDestructive: true) {
                            rebootTarget = "bootloader"
                        }
                    }

                    toolGroup("TVDeck", subtitle: "text.connection_and_transfer_preferences") {
                        actionRow("text.settings", icon: "slider.horizontal.3", tint: TVADBTheme.accent) {
                            showSettings = true
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .padding(.bottom, 88)
            }
            .navigationTitle("text.tools")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                TVADBDisconnectToolbarItem()
            }
            .tvadbScreenBackground()
            .overlay(alignment: .bottom) {
                if model.isBusy && !model.isScreenRecording {
                    TVADBPanel {
                        VStack(spacing: 0) {
                            HStack(spacing: 12) {
                                ProgressView()
                                Text(model.operationText)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .padding(14)
                            if let progress = model.progress {
                                ProgressView(value: progress).padding(.horizontal, 14).padding(.bottom, 10)
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 6)
                }
            }
            .sheet(isPresented: $showScreenshot) { ScreenshotView(image: screenshot) }
            .sheet(isPresented: $showInfo) { DeviceInfoView() }
            .sheet(isPresented: $showNetworkDiagnostics) {
                NavigationStack {
                    List(Array(model.networkDiagnostics.values.enumerated()), id: \.offset) { _, item in
                        Section(item.0) {
                            Text(item.1.isEmpty ? L10n.string("text.no_network_data") : item.1)
                                .font(.system(.caption, design: .monospaced))
                                .textSelection(.enabled)
                        }
                    }
                    .navigationTitle("text.network_diagnostics")
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("text.done") { showNetworkDiagnostics = false }
                        }
                    }
                }
            }
            .sheet(isPresented: $showHistory) { InstallHistoryView() }
            .sheet(isPresented: $showLogcat) { LogcatView() }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(isPresented: Binding(get: { model.aerialSetupReport != nil }, set: { if !$0 { model.aerialSetupReport = nil } })) {
                NavigationStack {
                    ScrollView { Text(model.aerialSetupReport ?? "").font(.system(.caption, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding() }
                        .navigationTitle("text.aerial_setup_report")
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("text.close") { model.aerialSetupReport = nil } } }
                }
            }
            .alert(
                L10n.string(aerialConsentRequest?.installing == true
                    ? "text.aerial_consent_install_title" : "text.aerial_consent_configure_title"),
                isPresented: Binding(
                    get: { aerialConsentRequest != nil },
                    set: { if !$0 { aerialConsentRequest = nil } }
                ),
                presenting: aerialConsentRequest
            ) { request in
                Button("text.cancel", role: .cancel) { aerialConsentRequest = nil }
                Button(L10n.string(request.installing
                    ? "text.aerial_consent_install_agree" : "text.aerial_consent_configure_agree")) {
                    aerialConsentRequest = nil
                    Task {
                        if request.installing {
                            await model.installAerial(flavor: request.flavor, expectedGeneration: request.generation)
                        } else {
                            await model.configureAerial(approvedFlavor: request.flavor, expectedGeneration: request.generation)
                        }
                    }
                }
            } message: { request in
                Text(aerialConsentMessage(request))
            }
            .confirmationDialog(
                rebootConfirmationTitle,
                isPresented: Binding(
                    get: { rebootTarget != nil },
                    set: { if !$0 { rebootTarget = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("text.restart", role: .destructive) {
                    guard let target = rebootTarget else { return }
                    rebootTarget = nil
                    Task { await model.reboot(target) }
                }
                Button("text.cancel", role: .cancel) { rebootTarget = nil }
            }
        }
    }

    private func requestAerialConsent(installing: Bool, flavor: AerialFlavor) {
        guard model.connectionState.isConnected, !model.isBusy, !model.aerialRefreshing,
              let device = model.currentDevice else { return }
        let edition = model.aerialInstallation?.flavor ?? (model.aerialSDK < 21 ? .lite : flavor)
        aerialConsentRequest = AerialConsentRequest(
            generation: model.aerialGeneration,
            device: device.name + " (" + device.host + ":" + String(device.port) + ")",
            flavor: edition,
            installing: installing
        )
    }

    private func aerialConsentMessage(_ request: AerialConsentRequest) -> String {
        [
            L10n.string("text.aerial_consent_target") + ": " + request.device,
            L10n.string("text.aerial_consent_edition") + ": " + request.flavor.rawValue,
            L10n.string(request.installing ? "text.aerial_consent_install_body" : "text.aerial_consent_configure_body"),
            L10n.string(request.flavor == .lite ? "text.aerial_consent_permissions_lite" : "text.aerial_consent_permissions"),
            L10n.string("text.aerial_consent_source"),
            L10n.string("text.aerial_consent_result")
        ].joined(separator: "\n\n")
    }

    @ViewBuilder
    private var aerialStatus: some View {
        if let installed = model.aerialInstallation {
            Text("\(installed.version) · \(installed.flavor.rawValue)")
        } else {
            Text(LocalizedStringKey(model.aerialChecked ? "text.aerial_not_installed" : model.aerialRefreshing ? "text.aerial_checking" : "text.aerial_unknown"))
        }
    }

    private var aerialDetails: some View {
        ScrollView {
            TVADBPanel {
                VStack(alignment: .leading, spacing: 12) {
                    Text("text.aerial").font(.headline)
                    aerialStatus.font(.subheadline)
                    if model.isBusy {
                        Text(model.operationText).font(.caption).foregroundStyle(.secondary)
                        if let progress = model.progress { ProgressView(value: progress) } else { ProgressView() }
                    }
                    if let phase = model.aerialUpdatePhase {
                        Text(phase).font(.caption).foregroundStyle(.secondary)
                        if let progress = model.aerialUpdateProgress {
                            ProgressView(value: progress)
                            Text(progress.formatted(.percent.precision(.fractionLength(0))))
                                .font(.caption).foregroundStyle(.secondary)
                        } else { ProgressView() }
                    }
                    if model.aerialInstallation != nil, let host = model.currentDevice?.host {
                        Text("text.aerial_address").font(.caption).foregroundStyle(.secondary)
                        Text("http://\(host):8790").font(.subheadline).textSelection(.enabled)
                    }
                    if model.aerialChecked, model.aerialSDK < 14 { Text("error.aerial_unsupported_sdk").font(.footnote) }
                    if let error = model.aerialError { Text(error).font(.footnote).foregroundStyle(.secondary) }
                    if let error = model.aerialUpdateError { Text(error).font(.footnote).foregroundStyle(.red) }
                    if model.aerialChecked, model.aerialInstallation == nil, model.aerialSDK >= 21 {
                        Picker("text.aerial_edition", selection: $aerialFlavor) {
                            Text("Standard").tag(AerialFlavor.standard)
                            Text("Guard").tag(AerialFlavor.guardVersion)
                        }.pickerStyle(.segmented)
                        Text("text.aerial_guard_hint").font(.caption).foregroundStyle(.secondary)
                    }
                    VStack(spacing: 0) {
                        if model.aerialInstallation == nil || model.aerialUpdateAvailable == true {
                            Button {
                                requestAerialConsent(installing: true, flavor: aerialFlavor)
                            } label: {
                                Label(LocalizedStringKey(model.aerialInstallation == nil ? "text.aerial_install" : "text.aerial_upgrade"), systemImage: "arrow.down.app")
                            }
                            .disabled(!model.aerialChecked || model.aerialRefreshing || model.isBusy || model.aerialSDK < 14)
                        }
                        if model.aerialGuardRetry, model.aerialInstallation == nil {
                            Divider()
                            Button {
                                requestAerialConsent(installing: true, flavor: .guardVersion)
                            } label: {
                                Label("text.aerial_retry_guard", systemImage: "arrow.clockwise")
                            }
                            .disabled(model.isBusy || model.aerialRefreshing)
                        }
                        if model.aerialInstallation != nil {
                            Divider()
                            Button {
                                requestAerialConsent(installing: false, flavor: aerialFlavor)
                            } label: {
                                Label("text.aerial_configure", systemImage: "play.fill")
                            }
                            .disabled(model.isBusy || model.aerialRefreshing)
                            Divider()
                            Button {
                                Task { await model.checkAerialUpdate() }
                            } label: {
                                Label("text.aerial_check_update", systemImage: "arrow.down.circle")
                            }
                            .disabled(model.isBusy || model.aerialRefreshing)
                            Divider()
                            Button {
                                Task { await model.checkAerialHomeStatus() }
                            } label: {
                                Label("text.aerial_home_status", systemImage: "house")
                            }
                            .disabled(model.isBusy || model.aerialRefreshing)
                        }
                        Divider()
                        Button {
                            Task { await model.refreshAerial() }
                        } label: {
                            Label("text.refresh", systemImage: "arrow.clockwise")
                        }
                        .disabled(model.aerialRefreshing || model.isBusy)
                        Divider()
                        AerialLinkButton()
                    }
                    .buttonStyle(AerialActionRowStyle())
                }.frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
            }
            .padding(16)
        }
        .navigationTitle("text.aerial")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { TVADBDisconnectToolbarItem(placement: .topBarTrailing) }
        .toolbar(.hidden, for: .tabBar)
        .alert("text.aerial_home_status", isPresented: Binding(get: { model.aerialHomeReport != nil }, set: { if !$0 { model.aerialHomeReport = nil } })) {
            Button("text.close", role: .cancel) { model.aerialHomeReport = nil }
        } message: { Text(model.aerialHomeReport ?? "") }
        .tvadbScreenBackground()
    }

    private var connectionHeader: some View {
        TVADBPanel {
            HStack(spacing: 13) {
                Image(systemName: "tv.fill")
                    .font(.title3)
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
                    .background(TVADBTheme.accentGradient, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(connectedName)
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Circle().fill(TVADBTheme.status).frame(width: 7, height: 7)
                        Text("text.adb_connected")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(TVADBTheme.status)
            }
            .padding(14)
        }
    }

    private func sectionTitle(_ title: LocalizedStringKey, subtitle: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.headline)
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var screenCaptureCard: some View {
        Button { capture() } label: {
            HStack(spacing: 14) {
                Image(systemName: "camera.viewfinder")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 52)
                    .background(TVADBTheme.accentGradient, in: RoundedRectangle(cornerRadius: 17, style: .continuous))
                    .shadow(color: TVADBTheme.accent.opacity(0.24), radius: 12, y: 6)

                VStack(alignment: .leading, spacing: 4) {
                    Text("text.capture_tv_screen")
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text("text.fits_the_screen_pinch_to_zoom")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 6)
                Image(systemName: "chevron.right")
                    .font(.caption.bold())
                    .foregroundStyle(TVADBTheme.accent)
                    .frame(width: 30, height: 30)
                    .background(TVADBTheme.accent.opacity(0.10), in: Circle())
            }
            .padding(14)
            .background(
                LinearGradient(
                    colors: [TVADBTheme.accent.opacity(0.12), TVADBTheme.cyan.opacity(0.06)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                in: RoundedRectangle(cornerRadius: 22, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(TVADBTheme.accent.opacity(0.12), lineWidth: 0.8)
            }
        }
        .buttonStyle(TVADBPressableStyle())
        .disabled(model.isBusy)
    }

    private var recordingCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 17).fill(TVADBTheme.accentGradient)
                    if model.isScreenRecording { ProgressView().tint(.white) }
                    else { Image(systemName: "record.circle.fill").font(.title2.weight(.semibold)).foregroundStyle(.white) }
                }.frame(width: 52, height: 52)
                VStack(alignment: .leading, spacing: 5) {
                    Text("text.record_tv_screen").font(.headline).foregroundStyle(.primary)
                    Text(model.isScreenRecording ? model.operationText : L10n.string("text.choose_a_duration_then_save_the_mp4"))
                        .font(.caption).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    recordingDurationButton(15)
                    recordingDurationButton(30)
                    recordingDurationButton(60)
                }
                VStack(spacing: 10) {
                    recordingDurationButton(15)
                    recordingDurationButton(30)
                    recordingDurationButton(60)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22))
        .overlay { RoundedRectangle(cornerRadius: 22).strokeBorder(TVADBTheme.accent.opacity(0.10), lineWidth: 0.8) }
        // Do not animate the card's geometry together with UIKit presentation
        // or tab transitions when recording state changes.
        .transaction { $0.animation = nil; $0.disablesAnimations = true }
    }

    private func recordingDurationButton(_ seconds: Int) -> some View {
        Button { recordScreen(duration: seconds) } label: {
            Text(L10n.format("text.record_duration_seconds", seconds))
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 12).padding(.vertical, 10)
                .frame(minWidth: 64, minHeight: 44)
                .frame(maxWidth: .infinity)
                .foregroundStyle(TVADBTheme.accent)
                .background(TVADBTheme.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .disabled(model.isBusy || isStartingRecording || !model.connectionState.isConnected)
        .accessibilityLabel(L10n.format("text.record_duration_accessibility", seconds))
    }

    private func compactTool(
        _ title: LocalizedStringKey,
        icon: String,
        tint: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 9) {
                Image(systemName: icon)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(tint)
                    .frame(width: 38, height: 38)
                    .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.82)
            }
            .frame(maxWidth: .infinity, minHeight: 78)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(tint.opacity(0.08), lineWidth: 0.7)
            }
        }
        .buttonStyle(TVADBPressableStyle())
        .disabled(model.isBusy)
    }

    private func toolGroup<Content: View>(
        _ title: LocalizedStringKey,
        subtitle: LocalizedStringKey,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 11) {
            sectionTitle(title, subtitle: subtitle)
            TVADBPanel {
                VStack(spacing: 0) { content() }
                    .padding(.vertical, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func actionRow(
        _ title: LocalizedStringKey,
        icon: String,
        tint: Color,
        showsChevron: Bool = true,
        isDestructive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(tint)
                    .frame(width: 38, height: 38)
                    .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(isDestructive ? Color.red : Color.primary)
                Spacer()
                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.caption.bold())
                        .foregroundStyle(.tertiary)
                }
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
        }
        .buttonStyle(TVADBPressableStyle(scale: 0.98))
        .disabled(model.isBusy)
    }

    private var connectedName: String {
        if case .connected(let name) = model.connectionState { return name }
        return model.currentDevice?.name ?? "Android TV"
    }

    private var rebootConfirmationTitle: String {
        switch rebootTarget {
        case "recovery": L10n.string("text.restart_into_recovery")
        case "bootloader": L10n.string("text.restart_into_bootloader")
        default: L10n.string("text.restart_the_tv")
        }
    }

    private func capture() {
        Task {
            if let data = await model.captureScreenshot(), let image = UIImage(data: data) {
                screenshot = image
                showScreenshot = true
            } else if model.errorMessage == nil {
                model.errorMessage = UserFacingFailure.text(.screenshot)
            }
        }
    }

    private func shell(_ command: String) {
        Task { _ = await model.executeShell(command) }
    }

    private func recordScreen(duration: Int) {
        guard !isStartingRecording, !model.isBusy else { return }
        isStartingRecording = true
        Task {
            defer { isStartingRecording = false }
            guard let url = await model.recordScreen(duration: duration) else { return }
            // MainTabView owns export presentation, even if recording moved
            // the user away from this tab.
            model.pendingScreenRecordingExportURL = url
        }
    }

}

private struct ScreenshotView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var resetID = 0
    let image: UIImage?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let image {
                    ZoomableScreenshotView(image: image, resetID: resetID)
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    ContentUnavailableView("text.no_screenshot", systemImage: "photo")
                        .foregroundStyle(.white)
                }
            }
            .safeAreaInset(edge: .bottom) {
                if image != nil {
                    Text("text.pinch_to_zoom_drag_to_pan_double_tap_to_magnify")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.white.opacity(0.78))
                        .padding(.horizontal, 13)
                        .padding(.vertical, 7)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 8)
                }
            }
            .navigationTitle("text.tv_screenshot")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("text.close") { dismiss() } }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        resetID += 1
                    } label: {
                        Label("text.fit_to_screen", systemImage: "arrow.down.right.and.arrow.up.left")
                    }
                    .disabled(image == nil)

                    Button {
                        if let image { UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil) }
                    } label: {
                        Label("text.save_to_photos", systemImage: "square.and.arrow.down")
                    }
                    .disabled(image == nil)
                }
            }
        }
    }
}

private struct ZoomableScreenshotView: UIViewRepresentable {
    let image: UIImage
    let resetID: Int

    func makeUIView(context: Context) -> ZoomableImageScrollView {
        let view = ZoomableImageScrollView()
        view.setImage(image)
        view.applyReset(id: resetID)
        return view
    }

    func updateUIView(_ uiView: ZoomableImageScrollView, context: Context) {
        uiView.setImage(image)
        uiView.applyReset(id: resetID)
    }
}

private final class ZoomableImageScrollView: UIScrollView, UIScrollViewDelegate {
    private let screenshotImageView = UIImageView()
    private var sourceImage: UIImage?
    private var lastBoundsSize: CGSize = .zero
    private var pendingFit = true
    private var appliedResetID = Int.min
    private var fitScale: CGFloat = 1

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        backgroundColor = .black
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        bouncesZoom = true
        decelerationRate = .fast

        screenshotImageView.contentMode = .scaleAspectFit
        screenshotImageView.isUserInteractionEnabled = true
        addSubview(screenshotImageView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setImage(_ image: UIImage) {
        guard sourceImage !== image else { return }
        sourceImage = image
        screenshotImageView.image = image
        setZoomScale(1, animated: false)
        screenshotImageView.frame = CGRect(origin: .zero, size: image.size)
        contentSize = image.size
        pendingFit = true
        setNeedsLayout()
    }

    func applyReset(id: Int) {
        guard appliedResetID != id else { return }
        appliedResetID = id
        pendingFit = true
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0, bounds.height > 0, sourceImage != nil else { return }

        if bounds.size != lastBoundsSize || pendingFit {
            lastBoundsSize = bounds.size
            updateZoomLimits(resetToFit: pendingFit)
        }
        updateCenteringInsets()
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        screenshotImageView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        updateCenteringInsets()
    }

    private func updateZoomLimits(resetToFit: Bool) {
        guard let image = sourceImage, image.size.width > 0, image.size.height > 0 else { return }
        fitScale = min(bounds.width / image.size.width, bounds.height / image.size.height)
        minimumZoomScale = max(fitScale * 0.55, 0.01)
        maximumZoomScale = max(fitScale * 8, 4)

        if resetToFit {
            setZoomScale(fitScale, animated: false)
        } else {
            zoomScale = min(max(zoomScale, minimumZoomScale), maximumZoomScale)
        }
        pendingFit = false
    }

    private func updateCenteringInsets() {
        let horizontal = max((bounds.width - contentSize.width) * 0.5, 0)
        let vertical = max((bounds.height - contentSize.height) * 0.5, 0)
        contentInset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
    }

    @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
        if abs(zoomScale - fitScale) > fitScale * 0.15 {
            setZoomScale(fitScale, animated: true)
            return
        }

        let targetScale = min(fitScale * 2.5, maximumZoomScale)
        let point = recognizer.location(in: screenshotImageView)
        let width = bounds.width / targetScale
        let height = bounds.height / targetScale
        zoom(
            to: CGRect(
                x: point.x - width * 0.5,
                y: point.y - height * 0.5,
                width: width,
                height: height
            ),
            animated: true
        )
    }
}

private struct DeviceInfoView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            List(Array(model.deviceInformation.values.enumerated()), id: \.offset) { _, item in
                LabeledContent(item.0, value: item.1)
            }
            .navigationTitle("text.device_information")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("text.done") { dismiss() } } }
        }
    }
}

private struct InstallHistoryView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var history: InstallHistoryStore

    var body: some View {
        NavigationStack {
            List(history.items) { item in
                VStack(alignment: .leading, spacing: 5) {
                    HStack { Text(item.filename).font(.headline); Spacer(); Text(item.result.localizedTitle).foregroundStyle(item.result == .success ? TVADBTheme.status : Color.red) }
                    Text(verbatim: "\(item.deviceName) · \(localizedSource(item.source))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let versionName = item.versionName,
                       !versionName.isEmpty,
                       versionName != "—" {
                        Text(verbatim: "v\(versionName)")
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    Text(item.date.formatted(date: .abbreviated, time: .standard)).font(.caption2).foregroundStyle(.secondary)
                }
            }
            .overlay { if history.items.isEmpty { ContentUnavailableView("text.no_installation_history", systemImage: "clock") } }
            .navigationTitle("text.installation_history")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("text.close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { Button("text.clear", role: .destructive) { history.clear() } }
            }
        }
    }

    private func localizedSource(_ source: String) -> String {
        L10n.matches(source, key: "text.files_app")
            ? L10n.string("text.files_app")
            : source
    }
}

private struct LogcatRecord: Identifiable, Hashable {
    let id = UUID()
    let raw: String
    let timestamp: String
    let pid: String?
    let level: Character?
    let tag: String?

    init(raw: String) {
        self.raw = raw
        let fields = raw.split(
            maxSplits: 5,
            omittingEmptySubsequences: true,
            whereSeparator: \.isWhitespace
        )
        if fields.count == 6,
           fields[0].contains("-"),
           fields[1].contains(":"),
           Int(fields[2]) != nil,
           fields[4].count == 1 {
            timestamp = "\(fields[0]) \(fields[1])"
            pid = String(fields[2])
            level = fields[4].first
            let body = String(fields[5])
            if let separator = body.firstIndex(of: ":") {
                tag = String(body[..<separator]).trimmingCharacters(in: .whitespaces)
            } else {
                tag = nil
            }
        } else {
            timestamp = ""
            pid = nil
            level = nil
            tag = nil
        }
    }
}

private struct LogcatExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }

    var text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        let data = configuration.file.regularFileContents ?? Data()
        text = String(decoding: data, as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

private struct LogcatView: View {
    private enum LevelFilter: String, CaseIterable {
        case all
        case verbose
        case debug
        case info
        case warning
        case error

        var title: LocalizedStringKey {
            switch self {
            case .all: "text.all"
            case .verbose: "text.verbose"
            case .debug: "text.debug"
            case .info: "text.info"
            case .warning: "text.warning"
            case .error: "text.error"
            }
        }

        func matches(_ level: Character?) -> Bool {
            switch self {
            case .all: true
            case .verbose: level == "V"
            case .debug: level == "D"
            case .info: level == "I"
            case .warning: level == "W"
            case .error: level == "E" || level == "F" || level == "A"
            }
        }
    }

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var model: AppModel
    @State private var records: [LogcatRecord] = []
    @State private var query = ""
    @State private var tagFilter = ""
    @State private var pidFilter = ""
    @AppStorage(TVADBPreferences.logLevelKey) private var levelFilterRaw = LevelFilter.all.rawValue
    @State private var isLive = true
    @State private var isRefreshing = false
    @State private var autoScroll = true
    @State private var showExporter = false
    @State private var exportDocument = LogcatExportDocument(text: "")

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                filters
                Divider()
                logContent
            }
            .navigationTitle("text.logcat")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "text.search_logs")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("text.close") { dismiss() } }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        isLive.toggle()
                    } label: {
                        Image(systemName: isLive ? "pause.fill" : "play.fill")
                    }
                    .accessibilityLabel(isLive ? Text("text.pause") : Text("text.resume"))

                    Menu {
                        Toggle("text.auto_scroll", isOn: $autoScroll)
                        Button {
                            exportDocument = LogcatExportDocument(text: filteredRecords.map(\.raw).joined(separator: "\n"))
                            showExporter = true
                        } label: {
                            Label("text.export_txt", systemImage: "square.and.arrow.up")
                        }
                        Button(role: .destructive) {
                            clear()
                        } label: {
                            Label("text.clear", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .fileExporter(
                isPresented: $showExporter,
                document: exportDocument,
                contentType: .plainText,
                defaultFilename: "TVDeck-logcat.txt"
            ) { result in
                if case .failure(let error) = result {
                    model.errorMessage = UserFacingFailure.message(
                        .exportLog,
                        underlying: error
                    )
                }
            }
            .task(id: isLive) {
                guard isLive else { return }
                await runLiveLoop()
            }
        }
    }

    private var filters: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                TextField("text.filter_by_tag", text: $tagFilter)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("text.pid", text: $pidFilter)
                    .keyboardType(.numberPad)
                    .frame(maxWidth: 110)
            }
            .textFieldStyle(.roundedBorder)

            HStack {
                Picker("text.log_level", selection: $levelFilterRaw) {
                    ForEach(LevelFilter.allCases, id: \.self) { level in
                        Text(level.title).tag(level.rawValue)
                    }
                }
                .pickerStyle(.menu)

                Spacer()

                if isRefreshing {
                    ProgressView().controlSize(.small)
                }
                Circle()
                    .fill(isLive ? TVADBTheme.status : Color.secondary)
                    .frame(width: 7, height: 7)
                Text(isLive ? L10n.string("text.live") : L10n.string("text.paused"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("\(filteredRecords.count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private var logContent: some View {
        if filteredRecords.isEmpty, !isRefreshing {
            ContentUnavailableView(
                "text.no_matching_logs",
                systemImage: "text.alignleft",
                description: Text("text.waiting_for_new_logs_or_adjust_the_search_and_filters")
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(filteredRecords) { record in
                            Text(record.raw)
                                .font(.system(size: 10.5, design: .monospaced))
                                .foregroundStyle(color(for: record.level))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                                .id(record.id)
                        }
                    }
                    .padding(12)
                }
                .background(Color.black)
                .onChange(of: filteredRecords.last?.id) { _, id in
                    guard autoScroll, let id else { return }
                    withAnimation(.easeOut(duration: 0.18)) {
                        proxy.scrollTo(id, anchor: .bottom)
                    }
                }
            }
        }
    }

    private var filteredRecords: [LogcatRecord] {
        records.filter { record in
            let matchesQuery = query.isEmpty || record.raw.localizedCaseInsensitiveContains(query)
            let matchesTag = tagFilter.isEmpty || (record.tag?.localizedCaseInsensitiveContains(tagFilter) ?? false)
            let matchesPID = pidFilter.isEmpty || record.pid == pidFilter
            return matchesQuery
                && matchesTag
                && matchesPID
                && selectedLevelFilter.matches(record.level)
        }
    }

    private var selectedLevelFilter: LevelFilter {
        LevelFilter(rawValue: levelFilterRaw) ?? .all
    }

    private func runLiveLoop() async {
        isRefreshing = true
        guard let session = await model.openLogcatStream() else {
            isRefreshing = false
            return
        }
        isRefreshing = false

        var pending = Data()
        do {
            try await withTaskCancellationHandler {
                while !Task.isCancelled, isLive {
                    guard let chunk = try await session.read() else { break }
                    appendLogcatChunk(chunk, pending: &pending)
                }
            } onCancel: {
                Task { await session.close() }
            }
            appendRemainingLogcatData(pending)
        } catch is CancellationError {
            // Pausing or closing the sheet intentionally cancels the stream.
        } catch {
            if !Task.isCancelled, isLive {
                model.reportLogcatStreamFailure(error)
            }
        }
        await session.close()
    }

    private func clear() {
        records.removeAll()
        Task {
            _ = await model.clearLogcat()
        }
    }

    private func appendLogcatChunk(
        _ chunk: Data,
        pending: inout Data
    ) {
        pending.append(chunk)
        guard let lastNewline = pending.lastIndex(
            where: { $0 == 0x0A || $0 == 0x0D }
        ) else {
            return
        }

        let complete = pending.prefix(through: lastNewline)
        pending.removeSubrange(...lastNewline)
        appendLogcatLines(String(decoding: complete, as: UTF8.self))
    }

    private func appendRemainingLogcatData(_ data: Data) {
        guard !data.isEmpty else { return }
        appendLogcatLines(String(decoding: data, as: UTF8.self))
    }

    private func appendLogcatLines(_ text: String) {
        let incoming = text
            .split(whereSeparator: \.isNewline)
            .map(String.init)
            .filter {
                !$0.isEmpty
                    && !$0.hasPrefix("--------- beginning of")
            }
        guard !incoming.isEmpty else { return }
        records.append(contentsOf: incoming.map(LogcatRecord.init(raw:)))

        let maximumRecords = 5_000
        if records.count > maximumRecords {
            records.removeFirst(records.count - maximumRecords)
        }
    }

    private func color(for level: Character?) -> Color {
        switch level {
        case "E", "F", "A": .red
        case "W": .orange
        case "I": .white
        case "D": Color(red: 0.48, green: 0.78, blue: 1)
        case "V": .secondary
        default: .white
        }
    }
}

private struct AerialActionRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 12) {
            configuration.label
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 14)
        .contentShape(Rectangle())
        .opacity(configuration.isPressed ? 0.6 : 1)
    }
}
