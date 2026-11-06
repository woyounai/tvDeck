import SwiftUI

struct MainTabView: View {
    private enum Tab: Hashable {
        case files
        case apps
        case remote
        case shell
        case tools
    }

    @EnvironmentObject private var model: AppModel
    @AppStorage(TVADBPreferences.openRemoteWhenRecordingKey)
    private var openRemoteWhenRecording = false
    @State private var selectedTab: Tab = .files
    @State private var recordingExportItem: FileExportItem?

    var body: some View {
        TabView(selection: $selectedTab) {
            FileBrowserView()
                .tag(Tab.files)
                .tabItem {
                    Label("text.files", systemImage: selectedTab == .files ? "folder.fill" : "folder")
                }
            ApplicationsView(isActive: selectedTab == .apps)
                .tag(Tab.apps)
                .tabItem {
                    Label("text.apps", systemImage: selectedTab == .apps ? "square.grid.2x2.fill" : "square.grid.2x2")
                }
            RemoteControlView()
                .tag(Tab.remote)
                .tabItem {
                    Label("text.remote", systemImage: selectedTab == .remote ? "appletvremote.gen4.fill" : "appletvremote.gen4")
                }
            ShellView()
                .tag(Tab.shell)
                .tabItem {
                    Label("text.shell", systemImage: selectedTab == .shell ? "terminal.fill" : "terminal")
                }
            ToolsView()
                .tag(Tab.tools)
                .tabItem {
                    Label("text.tools", systemImage: selectedTab == .tools ? "wrench.and.screwdriver.fill" : "wrench.and.screwdriver")
                }
        }
        .tvadbScreenBackground()
        .sensoryFeedback(.selection, trigger: selectedTab)
        .onChange(of: model.showWidgetRemote, initial: true) { _, requested in
            if requested { selectedTab = .remote; model.showWidgetRemote = false }
        }
        .onChange(of: model.isScreenRecordingReady) { _, ready in
            if ready, openRemoteWhenRecording {
                var transaction = Transaction(animation: nil)
                transaction.disablesAnimations = true
                withTransaction(transaction) { selectedTab = .remote }
            }
        }
        .onChange(of: model.pendingScreenRecordingExportURL, initial: true) { _, url in
            if let url { recordingExportItem = FileExportItem(url: url) }
        }
        .sheet(item: $recordingExportItem, onDismiss: cleanupRecordingExport) { item in
            FileExportPicker(url: item.url)
        }
    }

    private func cleanupRecordingExport() {
        if let url = model.pendingScreenRecordingExportURL {
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }
        model.pendingScreenRecordingExportURL = nil
        recordingExportItem = nil
    }
}
