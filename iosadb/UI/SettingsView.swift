import SwiftUI

struct TVADBSettingsForm: View {
    @State private var showUsageNotice = false
    @EnvironmentObject private var model: AppModel
    @AppStorage(TVADBPreferences.autoConnectLastDeviceKey)
    private var autoConnectLastDevice = false
    @AppStorage(AppLanguage.storageKey)
    private var appLanguage = AppLanguage.system.rawValue
    @AppStorage(TVADBVisualTheme.storageKey)
    private var visualTheme = TVADBVisualTheme.cyberpunk.rawValue
    @AppStorage(AppAppearance.storageKey)
    private var appAppearance = AppAppearance.system.rawValue
    @AppStorage(AppInstallMode.storageKey)
    private var installMode = AppInstallMode.replace.rawValue
    @AppStorage(TVADBPreferences.openRemoteWhenRecordingKey)
    private var openRemoteWhenRecording = false
    @AppStorage(TVADBPreferences.loadApplicationIconsKey)
    private var loadApplicationIcons = true
    @AppStorage(TVADBPreferences.liveScreenControlModeKey)
    private var liveScreenControlMode = LiveScreenControlMode.mouse.rawValue
    @AppStorage(TVADBPreferences.remoteHapticsKey)
    private var remoteHaptics = true
    @State private var selectedAppIcon = ""
    @State private var isChangingAppIcon = false

    var body: some View {
        Form {
            Section("text.general") {
                Picker(selection: $appLanguage) {
                    ForEach(AppLanguage.allCases) { language in
                        Text(language.title).tag(language.rawValue)
                    }
                } label: {
                    Label("text.app_language", systemImage: "globe")
                }

                VStack(alignment: .leading, spacing: 10) {
                    Label("text.visual_theme", systemImage: "sparkles")
                    Picker("text.visual_theme", selection: $visualTheme) {
                        ForEach(TVADBVisualTheme.allCases) { theme in
                            Text(LocalizedStringKey(theme.titleKey)).tag(theme.rawValue)
                        }
                    }.pickerStyle(.segmented)
                    if visualTheme == TVADBVisualTheme.cyberpunk.rawValue {
                        Text("text.cyberpunk_theme_hint").font(.caption).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 4)
                VStack(alignment: .leading, spacing: 10) {
                    Label("text.appearance", systemImage: "circle.lefthalf.filled")
                    Picker("text.appearance", selection: $appAppearance) {
                        ForEach(AppAppearance.allCases) { appearance in
                            Text(appearance.title).tag(appearance.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                }
                .padding(.vertical, 4)

                VStack(alignment: .leading, spacing: 10) {
                    Label("text.app_icon", systemImage: "app")
                    HStack(spacing: 10) {
                        ForEach(AppIconChoice.allCases) { choice in
                            Button {
                                changeAppIcon(to: choice)
                            } label: {
                                VStack(spacing: 5) {
                                    Image(choice.previewName)
                                        .resizable()
                                        .frame(width: 52, height: 52)
                                        .clipShape(RoundedRectangle(cornerRadius: 12))
                                    Text(LocalizedStringKey(choice.titleKey))
                                        .font(.caption2)
                                    if selectedAppIcon == choice.iconName {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(TVADBTheme.accent)
                                    }
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.plain)
                            .disabled(isChangingAppIcon || !UIApplication.shared.supportsAlternateIcons)
                        }
                    }
                    Text("text.app_icon_change_hint")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section {
                Toggle(isOn: $autoConnectLastDevice) {
                    Label(
                        "text.auto_connect_last_device",
                        systemImage: "bolt.horizontal.circle"
                    )
                }
            } header: {
                Text("text.connect")
            } footer: {
                Text("text.auto_connect_last_device_footer")
            }

            Section {
                Toggle(isOn: $openRemoteWhenRecording) {
                    Label(
                        "text.open_remote_when_recording",
                        systemImage: "appletvremote.gen4"
                    )
                }
            } header: {
                Text("text.screen_recording")
            } footer: {
                Text("text.open_remote_when_recording_footer")
            }

            Section {
                Picker(
                    "text.default_live_screen_control",
                    selection: $liveScreenControlMode
                ) {
                    ForEach(LiveScreenControlMode.allCases) { mode in
                        Text(mode.title).tag(mode.rawValue)
                    }
                }

                NavigationLink {
                    RemoteControlOrderView()
                } label: {
                    Label(
                        "text.control_order",
                        systemImage: "slider.horizontal.3"
                    )
                }
            } header: {
                Text("text.remote_preferences")
            } footer: {
                Text("text.remote_preferences_footer")
            }

            Section { Toggle("text.remote_haptics", isOn: $remoteHaptics) }

            Section {
                Toggle(isOn: $loadApplicationIcons) {
                    Label(
                        "text.load_application_icons",
                        systemImage: "square.grid.2x2"
                    )
                }
                Picker("text.default_installation_method", selection: $installMode) {
                    ForEach(AppInstallMode.allCases) { mode in
                        Text(mode.title).tag(mode.rawValue)
                    }
                }
            } header: {
                Text("text.apps_ac7fd221")
            } footer: {
                Text("text.load_application_icons_footer")
            }

            Section("text.about") {
                Button("legal_title") { showUsageNotice = true }
                Text("text.app_intro")
                    .font(.subheadline)
                Text("text.dark_mode_follows_ios_root_recovery_bootloader_and_and_7724bac9")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .tvadbScreenBackground()
        .sheet(isPresented: $showUsageNotice) { UsageNoticeView(readOnly: true) }
        .onAppear { selectedAppIcon = UIApplication.shared.alternateIconName ?? "" }
        .onChange(of: loadApplicationIcons) { _, isEnabled in
            Task {
                await model.applicationIconLoadingDidChange(
                    isEnabled: isEnabled
                )
            }
        }
    }

    private func changeAppIcon(to choice: AppIconChoice) {
        guard !isChangingAppIcon, selectedAppIcon != choice.iconName else { return }
        isChangingAppIcon = true
        let name = choice.iconName.isEmpty ? nil : choice.iconName
        UIApplication.shared.setAlternateIconName(name) { error in
            DispatchQueue.main.async {
                isChangingAppIcon = false
                selectedAppIcon = UIApplication.shared.alternateIconName ?? ""
                if let error {
                    let details = error as NSError
                    #if targetEnvironment(simulator)
                    if details.domain == NSPOSIXErrorDomain, details.code == 35 {
                        model.errorMessage = L10n.string("error.simulator_app_icon")
                        return
                    }
                    #endif
                    model.errorMessage = L10n.format(
                        "error.change_app_icon",
                        "\(error.localizedDescription) (\(details.domain), \(details.code))"
                    )
                }
            }
        }
    }
}

private enum AppIconChoice: String, CaseIterable, Identifiable {
    case cyberpunk, pearl

    var id: String { rawValue }
    var iconName: String {
        switch self {
        case .cyberpunk: ""
        case .pearl: "Pearl"
        }
    }
    var previewName: String {
        switch self {
        case .cyberpunk: "AppIconCyberpunkPreview"
        case .pearl: "AppIconPearlPreview"
        }
    }
    var titleKey: String { "text.icon_" + rawValue }
}

private struct RemoteControlOrderView: View {
    @AppStorage(TVADBPreferences.remoteControlOrderKey)
    private var rawValue = TVADBPreferences.defaultRemoteControlOrderRawValue

    private var modes: [RemoteControlMode] {
        TVADBPreferences.remoteControlOrder(from: rawValue)
    }

    var body: some View {
        List {
            Section {
                ForEach(modes) { mode in
                    Label(mode.title, systemImage: mode.systemImage)
                }
                .onMove { source, destination in
                    var reorderedModes = modes
                    reorderedModes.move(
                        fromOffsets: source,
                        toOffset: destination
                    )
                    rawValue = TVADBPreferences.remoteControlOrderRawValue(
                        for: reorderedModes
                    )
                }
            } footer: {
                Text("text.drag_remote_modes_to_reorder")
            }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle(L10n.string("text.remote_preferences"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("text.reset") {
                    rawValue =
                        TVADBPreferences.defaultRemoteControlOrderRawValue
                }
            }
        }
    }
}

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppLanguage.storageKey)
    private var appLanguage = AppLanguage.system.rawValue

    var body: some View {
        NavigationStack {
            TVADBSettingsForm()
                .navigationTitle(L10n.string("text.settings"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("text.done") { dismiss() }
                    }
                }
        }
        .id(appLanguage)
    }
}
