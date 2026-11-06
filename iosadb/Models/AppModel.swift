import Foundation
import Combine
import UIKit

@MainActor
final class AppModel: ObservableObject {
    private enum InstallPackagePreparationError: Error {
        case invalidPackage
    }

    enum ConnectionState: Equatable {
        case disconnected
        case connecting
        case awaitingAuthorization
        case connected(String)

        var isConnected: Bool {
            if case .connected = self { return true }
            return false
        }

        var isConnecting: Bool {
            switch self {
            case .connecting, .awaitingAuthorization:
                true
            case .disconnected, .connected:
                false
            }
        }
    }

    @Published var connectionState: ConnectionState = .disconnected {
        didSet {
            if !connectionState.isConnected {
                TVADBWidgetController.clear()
                aerialGeneration = UUID()
                aerialChecked = false
                aerialInstallation = nil
                aerialError = nil
                aerialRefreshing = false
                aerialSetupReport = nil
                aerialHomeReport = nil
                aerialUpdateAvailable = nil
                aerialUpdatePhase = nil
                aerialUpdateProgress = nil
                aerialUpdateError = nil
                aerialGuardRetry = false
            }
        }
    }
    @Published private(set) var aerialChecked = false
    @Published private(set) var aerialInstallation: AerialInstallation?
    @Published private(set) var aerialSDK = 21
    @Published private(set) var aerialRefreshing = false
    @Published private(set) var aerialError: String?
    @Published var aerialSetupReport: String?
    @Published var aerialHomeReport: String?
    @Published private(set) var aerialUpdateAvailable: Bool?
    @Published private(set) var aerialUpdatePhase: String?
    @Published private(set) var aerialUpdateProgress: Double?
    @Published private(set) var aerialUpdateError: String?
    @Published private(set) var aerialGuardRetry = false
    private(set) var aerialGeneration = UUID()
    @Published var files: [RemoteFile] = []
    @Published var currentPath = "/sdcard"
    @Published var isBusy = false
    @Published var operationText = ""
    @Published var progress: Double?
    @Published var errorMessage: String?
    @Published var connectionFailureMessage: String?
    @Published var noticeMessage: String?
    @Published private(set) var isFileTransferActive = false
    @Published var showWidgetRemote = false
    @Published var currentDevice: SavedDevice?
    @Published var applications: [AndroidApp] = []
    @Published private(set) var isLoadingApplications = false
    @Published var deviceInformation = DeviceInformation()
    @Published var networkDiagnostics = DeviceInformation()
    @Published private(set) var mouseMoveUnavailable = false
    @Published private(set) var mouseClickUnavailable = false
    @Published private(set) var nativeMouseInputActive = false
    @Published private(set) var physicalHIDMouseActive = false
    @Published private(set) var mouseDragActive = false
    @Published private(set) var mouseControlIsCalibrating = false
    @Published private(set) var mousePreviewImage: UIImage?
    @Published private(set) var mousePreviewVideoSize: CGSize?
    @Published private(set) var mousePreviewHasVideoFrame = false
    @Published private(set) var mousePreviewUsesVideoStream = false
    @Published private(set) var mousePreviewIsRequested = false
    @Published private(set) var mousePreviewIsLoading = false
    @Published private(set) var mousePreviewUnavailable = false
    @Published private(set) var mousePreviewVideoFailure: String?
    @Published var pendingScreenRecordingExportURL: URL?
    @Published private(set) var isScreenRecording = false
    @Published private(set) var isScreenRecordingReady = false

    private let client = ADBMobileClient()
    private lazy var filesService = ADBFileService(client: client)
    private lazy var commandService = ADBCommandService(client: client)
    private let appPresentationStore = AppPresentationCacheStore()
    private var appPresentationCache: [String: CachedInstalledAppPresentation] = [:]
    private var appPresentationCacheDeviceKey: String?
    private var applicationListLoadedForDevice = false
    private var applicationListRequest: UUID?
    private var loadingAppPresentations: Set<String> = []
    private var mouseMoveInFlight = false
    private var pendingMousePosition: CGPoint?
    private var relativeMouseMotion = MouseMotionAccumulator()
    private var relativeMouseTask: Task<Void, Never>?
    private var relativeMouseGeneration = 0
    private var mousePreviewTask: Task<Void, Never>?
    private var mousePreviewSessionID: UUID?
    private var mousePreviewStream: ADBPersistentShellSession?
    let mousePreviewVideoHub = H264VideoFrameHub()
    private var pendingPackageURL: URL?
    private var attemptedAutomaticConnection = false
    private var manualDisconnect = false
    private var activeConnectionAttemptID: UUID?
    private var connectionMonitor: Task<Void, Never>?
    private var heartbeatInFlight = false
    private var backgroundConnectionTask: UIBackgroundTaskIdentifier = .invalid
    private var activeFileTransferID: UUID?
    private var activeFileTransferCancel: (() -> Void)?
    let deviceStore: DeviceStore
    let installHistory: InstallHistoryStore

    init(deviceStore: DeviceStore, installHistory: InstallHistoryStore) {
        self.deviceStore = deviceStore
        self.installHistory = installHistory
        ADBCommandService.cleanupStaleScreenshotTemporaryDirectories()
        ADBCommandService.cleanupStaleRecordingCache()
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains(
            "-TVADBSimulateConnectedDevice"
        ) {
            connectionState = .connected("Simulator Test Device")
            applicationListLoadedForDevice = true
        }
#endif
    }

    func connect(_ device: SavedDevice, loadContent: Bool = true, authorizationTimeout: TimeInterval = 60, retries: Int = 3) async {
        guard !isBusy else { return }
        manualDisconnect = false
        currentDevice = device
        await connect(host: device.host, portText: String(device.port), loadContent: loadContent, authorizationTimeout: authorizationTimeout, retries: retries)
    }

    func connect(host: String, portText: String, loadContent: Bool = true, authorizationTimeout: TimeInterval = 60, retries: Int = 3) async {
        guard !isBusy else { return }
        manualDisconnect = false
        connectionFailureMessage = nil
        guard let port = UInt16(portText),
              !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = UserFacingFailure.text(.invalidAddress)
            return
        }
        let attemptID = UUID()
        activeConnectionAttemptID = attemptID
        applications = []
        appPresentationCache.removeAll()
        appPresentationCacheDeviceKey = nil
        applicationListLoadedForDevice = false
        applicationListRequest = nil
        isLoadingApplications = false
        loadingAppPresentations.removeAll()
        mouseMoveUnavailable = false
        mouseClickUnavailable = false
        nativeMouseInputActive = false
        physicalHIDMouseActive = false
        mouseDragActive = false
        mouseMoveInFlight = false
        pendingMousePosition = nil
        cancelQueuedMouseMovement()
        isScreenRecording = false
        isScreenRecordingReady = false
        isBusy = true
        operationText = L10n.format("text.connecting_to_value_value", host, Int(port))
        connectionState = .connecting
        defer {
            if activeConnectionAttemptID == attemptID {
                activeConnectionAttemptID = nil
                isBusy = false
                operationText = ""
                if connectionState.isConnected { Task { await refreshAerial() } }
            }
        }

        do {
            for retry in 0...retries {
                do {
                    try await client.connect(attemptID: attemptID, host: host, port: port, authorizationTimeout: retry == 0 ? authorizationTimeout : min(8, authorizationTimeout)) { [self] in
                        await markAwaitingAuthorization(for: attemptID)
                    }
                    break
                } catch {
                    guard activeConnectionAttemptID == attemptID, !Task.isCancelled else { throw CancellationError() }
                    if retry == retries { throw error }
                    connectionState = .connecting
                    operationText = L10n.format("text.reconnecting_attempt", retry + 1, retries)
                    try await Task.sleep(for: .seconds(1))
                }
            }
            guard activeConnectionAttemptID == attemptID else { return }
            let banner = await client.banner
            let resolvedName = Self.deviceName(from: banner)
            await commandService.resetSessionCapabilities()
            guard activeConnectionAttemptID == attemptID else { return }
            connectionState = .connected(resolvedName)
            startConnectionMonitor()
            if var device = currentDevice {
                let details = try? await commandService.shell("getprop ro.product.brand; getprop ro.build.version.release")
                guard activeConnectionAttemptID == attemptID else { return }
                let lines = details?.split(whereSeparator: \.isNewline).map(String.init) ?? []
                device.name = resolvedName
                device.brand = lines.first ?? device.brand
                device.androidVersion = lines.dropFirst().first ?? device.androidVersion
                device.lastConnectedAt = Date()
                currentDevice = device
                deviceStore.upsert(device)
            } else {
                let device = SavedDevice(name: resolvedName, host: host, port: port, lastConnectedAt: Date())
                currentDevice = device
                deviceStore.upsert(device)
            }
            // DeviceStore may preserve an existing ID when deduplicating the
            // same endpoint. Publish that canonical identity to the widget.
            if let saved = deviceStore.devices.first(where: { $0.host == host && $0.port == port }) {
                currentDevice = saved
                TVADBWidgetController.publish(saved)
            }
            guard loadContent else { return }
            let cacheDeviceKey = "\(host.trimmingCharacters(in: .whitespacesAndNewlines)):\(port)"
            appPresentationCacheDeviceKey = cacheDeviceKey
            appPresentationCache = await appPresentationStore.load(deviceKey: cacheDeviceKey)
            applications = appPresentationCache.map { packageName, cached in
                AndroidApp(
                    packageName: packageName,
                    isSystem: cached.isSystem ?? false,
                    baseAPKPath: cached.baseAPKPath,
                    name: cached.presentation.name,
                    iconData: TVADBPreferences.loadApplicationIcons
                        ? cached.presentation.iconData
                        : nil,
                    versionName: cached.presentation.versionName
                )
            }.sorted {
                $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending
            }
            currentPath = "/sdcard"
            do {
                try await loadFiles()
            } catch {
                guard activeConnectionAttemptID == attemptID else { return }
                currentPath = "/"
                try await loadFiles()
            }
            guard activeConnectionAttemptID == attemptID else { return }
            if pendingPackageURL != nil {
                Task {
                    await self.installPendingPackageIfPossible()
                }
            }
        } catch is CancellationError {
            guard activeConnectionAttemptID == attemptID else { return }
            connectionState = .disconnected
        } catch {
            guard activeConnectionAttemptID == attemptID else { return }
            connectionState = .disconnected
            manualDisconnect = true
            connectionFailureMessage = UserFacingFailure.message(.connectDevice, underlying: error)
        }
    }

    private func markAwaitingAuthorization(for attemptID: UUID) {
        guard activeConnectionAttemptID == attemptID,
              connectionState == .connecting else {
            return
        }
        connectionState = .awaitingAuthorization
        operationText = L10n.string("text.waiting_for_android_authorization")
    }

    func cancelConnection() {
        guard let attemptID = activeConnectionAttemptID,
              connectionState.isConnecting else {
            return
        }
        activeConnectionAttemptID = nil
        manualDisconnect = true
        connectionMonitor?.cancel(); connectionMonitor = nil
        endBackgroundConnectionTask()
        connectionState = .disconnected
        currentDevice = nil
        isBusy = false
        operationText = ""
        Task {
            await client.cancelConnectionAttempt(attemptID: attemptID)
        }
    }

    @discardableResult
    func pairAndConnect(
        name: String,
        host: String,
        pairingPortText: String,
        connectPortText: String,
        code: String
    ) async -> Bool {
        guard !isBusy else { return false }
        guard let pairingPort = UInt16(pairingPortText),
              let connectPort = UInt16(connectPortText),
              !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = UserFacingFailure.text(.invalidPairingParameters)
            return false
        }

        isBusy = true
        operationText = L10n.format("text.securely_pairing_with_value_value", host, Int(pairingPort))
        do {
            _ = try await client.pair(host: host, port: pairingPort, code: code)
            noticeMessage = L10n.string("text.wireless_pairing_succeeded")
            let device = SavedDevice(
                name: name.isEmpty ? "Android TV" : name,
                host: host,
                port: connectPort,
                pairingPort: pairingPort,
                kind: .wirelessTLS
            )
            currentDevice = device
            deviceStore.upsert(device)
            isBusy = false
            operationText = ""
            await connect(device)
            return connectionState.isConnected
        } catch {
            isBusy = false
            operationText = ""
            connectionState = .disconnected
            errorMessage = UserFacingFailure.message(.wirelessPairing, underlying: error)
            return false
        }
    }

    func disconnect() {
        manualDisconnect = true
        connectionMonitor?.cancel(); connectionMonitor = nil
        endBackgroundConnectionTask()
        activeConnectionAttemptID = nil
        stopMousePreview(clearFrame: true)
        Task {
            await commandService.cleanupSessionArtifacts()
            await client.disconnect()
        }
        connectionState = .disconnected
        currentDevice = nil
        files = []
        applications = []
        appPresentationCache.removeAll()
        appPresentationCacheDeviceKey = nil
        applicationListLoadedForDevice = false
        applicationListRequest = nil
        isLoadingApplications = false
        loadingAppPresentations.removeAll()
        currentPath = "/sdcard"
        progress = nil
        mouseMoveUnavailable = false
        mouseClickUnavailable = false
        nativeMouseInputActive = false
        physicalHIDMouseActive = false
        mouseDragActive = false
        mouseMoveInFlight = false
        pendingMousePosition = nil
        cancelQueuedMouseMovement()
        isScreenRecording = false
        isScreenRecordingReady = false
    }

    private func startConnectionMonitor() {
        guard connectionMonitor == nil else { return }
        connectionMonitor = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
                guard let self else { return }
                await self.checkConnection()
            }
        }
    }

    private func checkConnection() async {
        guard UsageConsent.isAccepted, connectionState.isConnected, !isBusy, !manualDisconnect,
              !heartbeatInFlight, let device = currentDevice else { return }
        heartbeatInFlight = true
        defer { heartbeatInFlight = false }
        do { try await client.probeConnectionIfIdle() }
        catch is CancellationError { return }
        catch {
            guard !Task.isCancelled, !manualDisconnect, !isBusy,
                  connectionState.isConnected, currentDevice?.id == device.id else { return }
            stopMousePreview(clearFrame: true)
            await connect(device, loadContent: false, authorizationTimeout: 8, retries: 2)
        }
    }

    func connectionEnteredBackground() {
        guard UsageConsent.isAccepted else { return }
        guard connectionState.isConnected, backgroundConnectionTask == .invalid else { return }
        stopMousePreview(clearFrame: true)
        backgroundConnectionTask = UIApplication.shared.beginBackgroundTask(withName: "ADB connection") { [weak self] in
            Task { @MainActor in
                self?.connectionMonitor?.cancel()
                self?.connectionMonitor = nil
                self?.endBackgroundConnectionTask()
            }
        }
    }

    func connectionEnteredForeground() async {
        guard UsageConsent.isAccepted else { return }
        endBackgroundConnectionTask()
        if connectionState.isConnected {
            await checkConnection()
            startConnectionMonitor()
        } else {
            await autoConnectLastDeviceIfNeeded(force: true)
        }
    }

    private func endBackgroundConnectionTask() {
        guard backgroundConnectionTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundConnectionTask)
        backgroundConnectionTask = .invalid
    }

    func autoConnectLastDeviceIfNeeded(force: Bool = false) async {
        guard UsageConsent.isAccepted else { return }
        guard TVADBPreferences.autoConnectLastDevice,
              !manualDisconnect,
              connectionState == .disconnected,
              !isBusy else {
            return
        }
        if attemptedAutomaticConnection, !force {
            return
        }
        attemptedAutomaticConnection = true
        guard let device = deviceStore.devices.max(by: {
            ($0.lastConnectedAt ?? .distantPast)
                < ($1.lastConnectedAt ?? .distantPast)
        }), device.lastConnectedAt != nil else {
            return
        }
        await connect(device)
    }

    func handleIncomingPackage(_ url: URL) async {
        let supportedExtensions = ["apk", "apks", "xapk", "apkm"]
        guard supportedExtensions.contains(url.pathExtension.lowercased()) else {
            errorMessage = UserFacingFailure.text(.selectInstallPackage)
            return
        }
        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }
        do {
            let persistedURL = try await Self.persistIncomingPackage(url)
            if let previous = pendingPackageURL, previous != persistedURL {
                try? FileManager.default.removeItem(at: previous)
            }
            pendingPackageURL = persistedURL
            if connectionState.isConnected {
                await installPendingPackageIfPossible()
            } else {
                noticeMessage = L10n.string("text.install_package_ready_connect_a_device_to_continue")
            }
        } catch {
            errorMessage = UserFacingFailure.message(
                .selectInstallPackage,
                underlying: error
            )
        }
    }

    func refresh() async {
        guard connectionState.isConnected, !isBusy else { return }
        isBusy = true
        operationText = L10n.string("text.loading_folder")
        defer { isBusy = false; operationText = "" }
        do {
            try await loadFiles()
        } catch {
            errorMessage = UserFacingFailure.message(.readFileList, underlying: error)
        }
    }

    func open(_ directory: RemoteFile) async {
        guard directory.isDirectory, !isBusy else { return }
        currentPath = path(appending: directory.name)
        await refresh()
    }

    func goUp() async {
        guard currentPath != "/", !isBusy else { return }
        let parent = (currentPath as NSString).deletingLastPathComponent
        currentPath = parent.isEmpty ? "/" : parent
        await refresh()
    }

    func upload(url: URL) async {
        await upload(urls: [url])
    }

    func upload(urls: [URL]) async {
        guard !urls.isEmpty else { return }
        guard !isBusy else { return }
        await runCancellableFileTransfer {
            await self.performUpload(urls: urls)
        }
    }

    private func performUpload(urls: [URL]) async {
        errorMessage = nil
        isBusy = true
        operationText = L10n.string("text.preparing_upload")
        progress = nil
        defer {
            isBusy = false
            operationText = ""
            progress = nil
        }

        do {
            for (index, url) in urls.enumerated() {
                try Task.checkCancellation()
                let didAccess = url.startAccessingSecurityScopedResource()
                do {
                    let stagedURL = try await Self.stageImportedFile(url)
                    defer { try? FileManager.default.removeItem(at: stagedURL.deletingLastPathComponent()) }
                    let remotePath = path(appending: url.lastPathComponent)
                    operationText = String(
                        format: L10n.string("text.uploading_value"),
                        url.lastPathComponent
                    )
                    let completedItems = Double(index)
                    let itemCount = Double(urls.count)
                    try await filesService.upload(localURL: stagedURL, to: remotePath) { value in
                        await MainActor.run {
                            self.progress = (completedItems + value) / itemCount
                        }
                    }
                } catch {
                    if didAccess { url.stopAccessingSecurityScopedResource() }
                    throw error
                }
                if didAccess { url.stopAccessingSecurityScopedResource() }
            }
            try Task.checkCancellation()
            noticeMessage = urls.count == 1
                ? String(
                    format: L10n.string("text.uploaded_to_value"),
                    path(appending: urls[0].lastPathComponent)
                )
                : String(
                    format: L10n.string("text.uploaded_value_items"),
                    urls.count
                )
            try await loadFiles()
        } catch {
            if Task.isCancelled || error is CancellationError {
                noticeMessage = L10n.string("text.transfer_cancelled")
            } else {
                errorMessage = UserFacingFailure.message(.uploadFile, underlying: error)
            }
        }
    }

    func synchronizeLocalDirectoryToTV(
        _ localDirectory: URL
    ) async {
        guard !isBusy else { return }
        await runCancellableFileTransfer {
            await self.performSynchronizeLocalDirectoryToTV(
                localDirectory
            )
        }
    }

    private func performSynchronizeLocalDirectoryToTV(
        _ localDirectory: URL
    ) async {
        errorMessage = nil
        isBusy = true
        operationText = L10n.string(
            "text.syncing_iphone_folder_to_tv"
        )
        progress = 0
        defer {
            isBusy = false
            operationText = ""
            progress = nil
        }
        let didAccess = localDirectory
            .startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                localDirectory.stopAccessingSecurityScopedResource()
            }
        }
        do {
            let summary = try await filesService
                .synchronizeLocalDirectory(
                    localDirectory,
                    to: currentPath
                ) { value in
                    await MainActor.run {
                        self.progress = value
                    }
                }
            noticeMessage = L10n.format(
                "text.directory_sync_completed",
                summary.transferredFiles,
                summary.skippedFiles,
                summary.createdDirectories
            )
            try await loadFiles()
        } catch {
            if Task.isCancelled || error is CancellationError {
                noticeMessage = L10n.string("text.transfer_cancelled")
            } else {
                errorMessage = UserFacingFailure.message(
                    .syncDirectory,
                    underlying: error
                )
            }
        }
    }

    func refreshAerial() async {
        guard connectionState.isConnected, !aerialRefreshing else { return }
        let generation = aerialGeneration
        aerialRefreshing = true
        aerialError = nil
        defer { if generation == aerialGeneration { aerialRefreshing = false } }
        do {
            let status = try await commandService.aerialInstallation()
            guard generation == aerialGeneration, connectionState.isConnected else { return }
            aerialSDK = status.sdk
            aerialInstallation = status.installed
            aerialUpdateAvailable = nil
            aerialUpdateError = nil
            aerialChecked = true
        } catch {
            guard generation == aerialGeneration else { return }
            aerialChecked = false
            aerialError = L10n.string("error.aerial_detection")
        }
    }

    func checkAerialUpdate() async {
        guard connectionState.isConnected, let installed = aerialInstallation, !isBusy, !aerialRefreshing else { return }
        let generation = aerialGeneration
        aerialRefreshing = true
        aerialUpdateError = nil
        aerialUpdateProgress = nil
        aerialUpdatePhase = L10n.string("text.aerial_fetching_release")
        defer {
            if generation == aerialGeneration {
                aerialRefreshing = false
                aerialUpdatePhase = nil
                aerialUpdateProgress = nil
            }
        }
        do {
            let release = try await AerialRelease.latest(flavor: installed.flavor)
            aerialUpdatePhase = L10n.string("text.aerial_downloading")
            aerialUpdateProgress = 0
            let data = try await release.download { value in
                await MainActor.run {
                    if generation == self.aerialGeneration { self.aerialUpdateProgress = value }
                }
            }
            aerialUpdatePhase = L10n.string("text.aerial_verifying_release")
            aerialUpdateProgress = nil
            let inspection = try APKInspector.inspect(data: data, filename: installed.flavor.filename)
            guard inspection.packageName == installed.flavor.packageName,
                  let code = UInt64(inspection.versionCode) else { throw AerialIntegrationError.invalidDownload }
            aerialUpdatePhase = L10n.string("text.aerial_comparing_version")
            let current = try await commandService.aerialInstallation()
            guard generation == aerialGeneration else { return }
            guard let latestInstalled = current.installed, latestInstalled.flavor == installed.flavor else { throw AerialIntegrationError.deviceChanged }
            aerialInstallation = latestInstalled
            aerialUpdateAvailable = code > latestInstalled.code
            noticeMessage = L10n.string(code > latestInstalled.code ? "text.aerial_update_available" : "text.aerial_current")
        } catch {
            guard generation == aerialGeneration else { return }
            let detail: String
            switch error {
            case let failure as AerialIntegrationError: detail = failure.localizedDescription
            case let failure as URLError: detail = failure.localizedDescription
            case let failure as APKInspectionError: detail = failure.localizedDescription
            default: detail = L10n.string("error.aerial_update_unknown")
            }
            aerialUpdateError = [aerialUpdatePhase, detail].compactMap { $0 }.joined(separator: "\n")
        }
    }

    func checkAerialHomeStatus() async {
        guard connectionState.isConnected, let flavor = aerialInstallation?.flavor, !isBusy else { return }
        let generation = aerialGeneration
        do {
            let status = try await commandService.aerialHomeStatus(flavor: flavor)
            guard generation == aerialGeneration else { return }
            aerialHomeReport = [
                L10n.string("text.aerial_default_home") + ": " + L10n.string(status.defaultHome ? "text.yes" : "text.no"),
                flavor == .lite ? nil : L10n.string("text.aerial_home_guard") + ": " + L10n.string(status.guardEnabled ? "text.yes" : "text.no")
            ].compactMap { $0 }.joined(separator: "\n")
        } catch {
            guard generation == aerialGeneration else { return }
            errorMessage = L10n.string("error.aerial_home_status")
        }
    }

    func installAerial(flavor requested: AerialFlavor, expectedGeneration: UUID) async {
        guard expectedGeneration == aerialGeneration else {
            errorMessage = L10n.string("error.aerial_device_changed")
            return
        }
        guard UsageConsent.isAccepted, connectionState.isConnected, !isBusy, !aerialRefreshing else { return }
        let generation = aerialGeneration
        isBusy = true
        operationText = L10n.string("text.aerial_downloading")
        aerialSetupReport = nil
        progress = nil
        defer { isBusy = false; operationText = ""; progress = nil }
        do {
            let before = try await commandService.aerialInstallation()
            guard generation == aerialGeneration else { throw AerialIntegrationError.deviceChanged }
            guard before.sdk >= 14 else { throw AerialIntegrationError.unsupportedSDK }
            aerialGuardRetry = false
            let flavor = before.installed?.flavor ?? (before.sdk < 21 ? .lite : requested)
            guard flavor == requested else { throw AerialIntegrationError.deviceChanged }
            let release = try await AerialRelease.latest(flavor: flavor)
            let data = try await release.download { value in
                await MainActor.run { if generation == self.aerialGeneration { self.progress = value } }
            }
            guard generation == aerialGeneration, connectionState.isConnected else { throw AerialIntegrationError.deviceChanged }
            let inspection = try APKInspector.inspect(data: data, filename: flavor.filename)
            guard inspection.packageName == flavor.packageName, let code = UInt64(inspection.versionCode) else { throw AerialIntegrationError.invalidDownload }
            // Re-read after downloading: another installer may have updated the TV meanwhile.
            let current = try await commandService.aerialInstallation()
            guard generation == aerialGeneration else { throw AerialIntegrationError.deviceChanged }
            if let installed = current.installed {
                guard installed.flavor == flavor else { throw AerialIntegrationError.deviceChanged }
                if installed.code >= code {
                    aerialInstallation = installed; aerialChecked = true
                    aerialUpdateAvailable = false
                    try await finishAerialSetup(status: current, generation: generation)
                    return
                }
            }
            operationText = L10n.string("text.aerial_installing")
            progress = 0
            let result: String
            do {
                result = try await filesService.installAPK(data: data, filename: flavor.filename, packageName: flavor.packageName, packageABIs: inspection.abis, replace: true) { value in
                    await MainActor.run { self.progress = value }
                }
            } catch {
                if generation == aerialGeneration { aerialGuardRetry = flavor == .standard && current.installed == nil }
                throw error
            }
            guard generation == aerialGeneration else { throw AerialIntegrationError.deviceChanged }
            installHistory.add(filename: flavor.filename, deviceName: currentDevice?.name ?? "Android TV", source: "Aerial", versionName: inspection.versionName, result: .success, details: result)
            let after = try await commandService.aerialInstallation()
            guard generation == aerialGeneration else { return }
            guard let installed = after.installed, installed.flavor == flavor, installed.code >= code else { throw AerialIntegrationError.invalidStatus }
            aerialInstallation = installed; aerialSDK = after.sdk; aerialChecked = true
            aerialUpdateAvailable = false
            try await finishAerialSetup(status: after, generation: generation)
        } catch {
            errorMessage = UserFacingFailure.message(.installAPK, underlying: error)
        }
    }

    private func finishAerialSetup(status: (sdk: Int, installed: AerialInstallation?), generation: UUID) async throws {
        guard let installed = status.installed, generation == aerialGeneration else { throw AerialIntegrationError.deviceChanged }
        operationText = L10n.string("text.aerial_configuring")
        progress = 0
        let result = try await commandService.configureAerial(flavor: installed.flavor, sdk: status.sdk) { value in
            await MainActor.run { if generation == self.aerialGeneration { self.progress = value } }
        }
        guard generation == aerialGeneration else { throw AerialIntegrationError.deviceChanged }
        aerialSetupReport = result.report
        noticeMessage = L10n.string(result.launched ? result.warnings == 0 ? "text.aerial_setup_complete" : "text.aerial_setup_partial" : "error.aerial_launch_failed")
    }

    func configureAerial(approvedFlavor: AerialFlavor, expectedGeneration: UUID) async {
        guard expectedGeneration == aerialGeneration else {
            errorMessage = L10n.string("error.aerial_device_changed")
            return
        }
        guard UsageConsent.isAccepted, connectionState.isConnected, !isBusy, !aerialRefreshing else { return }
        let generation = aerialGeneration
        isBusy = true
        operationText = L10n.string("text.aerial_configuring")
        aerialSetupReport = nil
        defer { isBusy = false; operationText = ""; progress = nil }
        do {
            let status = try await commandService.aerialInstallation()
            guard generation == aerialGeneration, status.installed?.flavor == approvedFlavor else {
                throw AerialIntegrationError.deviceChanged
            }
            aerialInstallation = status.installed; aerialChecked = true; aerialSDK = status.sdk
            try await finishAerialSetup(status: status, generation: generation)
        } catch { errorMessage = UserFacingFailure.message(.installAPK, underlying: error) }
    }

    @discardableResult
    func installAPK(url: URL) async -> Bool {
        guard !isBusy else {
            errorMessage = L10n.string("error.operation_in_progress")
            return false
        }
        guard Self.isSupportedInstallPackage(url) else {
            errorMessage = L10n.string("error.select_supported_install_package")
            return false
        }
        var installed = false
        let installingText = String(
            format: L10n.string("text.installing_value"),
            url.lastPathComponent
        )
        await performFileRead(
            url: url,
            action: installingText,
            failure: .installAPK
        ) { [weak self] data in
            guard let self else { return }
            do {
                let fileExtension = url.pathExtension.lowercased()
                let result: String
                var installedVersionName: String?
                if fileExtension == "apk" {
                    let inspection = try APKInspector.inspect(
                        data: data,
                        filename: url.lastPathComponent
                    )
                    installedVersionName = inspection.versionName
                    result = try await self.filesService.installAPK(
                        data: data,
                        filename: url.lastPathComponent,
                        packageName: inspection.packageName,
                        packageABIs: inspection.abis,
                        replace: TVADBPreferences.replaceExistingApps
                    ) { value in
                        await MainActor.run { self.progress = value }
                    }
                } else if ["apks", "xapk", "apkm"].contains(fileExtension) {
                    let package = try ZipArchiveReader.extractPackage(
                        from: data,
                        fileExtension: fileExtension
                    )
                    var expectedPackageName = package.packageName
                    if let baseEntry = package.apkEntries.first(where: {
                        $0.name.lowercased() == "base.apk"
                    }) ?? package.apkEntries.first {
                        let inspection = try? APKInspector.inspect(
                            data: baseEntry.data,
                            filename: baseEntry.name
                        )
                        installedVersionName = inspection?.versionName
                        expectedPackageName = inspection?.packageName
                            ?? expectedPackageName
                    }
                    result = try await self.filesService.installAPKSet(
                        package: package,
                        expectedPackageName: expectedPackageName,
                        replace: TVADBPreferences.replaceExistingApps
                    ) { value in
                        await MainActor.run { self.progress = value }
                    }
                } else {
                    throw ADBClientError.remoteFailure(String(
                        format: L10n.string("text.unsupported_package_format_value"),
                        fileExtension
                    ))
                }
                self.installHistory.add(
                    filename: url.lastPathComponent,
                    deviceName: self.currentDevice?.name ?? "Android TV",
                    source: "text.files_app",
                    versionName: installedVersionName,
                    result: .success,
                    details: result
                )
                self.noticeMessage = String(
                    format: L10n.string("text.installed_value_successfully"),
                    url.lastPathComponent
                )
                installed = true
            } catch {
                let friendlyDetails = UserFacingFailure.message(.installAPK, underlying: error)
                self.installHistory.add(
                    filename: url.lastPathComponent,
                    deviceName: self.currentDevice?.name ?? "Android TV",
                    source: "text.files_app",
                    versionName: nil,
                    result: .failed,
                    details: friendlyDetails
                )
                throw error
            }
        }
        return installed
    }

    nonisolated static func isSupportedInstallPackage(_ url: URL) -> Bool {
        switch url.pathExtension.lowercased() {
        case "apk", "apks", "xapk", "apkm":
            true
        default:
            false
        }
    }

    func prepareInstallPackage(from sourceURL: URL) async -> URL? {
        guard !isBusy else {
            errorMessage = L10n.string("error.operation_in_progress")
            return nil
        }
        guard Self.isSupportedInstallPackage(sourceURL) else {
            errorMessage = L10n.string(
                "error.select_supported_install_package"
            )
            return nil
        }

        errorMessage = nil
        isBusy = true
        operationText = L10n.string("text.preparing_install_package")
        defer {
            isBusy = false
            operationText = ""
        }

        let didAccess = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let stagedURL = try await Self.stageImportedFile(
                sourceURL,
                directoryPrefix: "TVADB-Install"
            )
            do {
                try Self.validateInstallPackageFile(at: stagedURL)
                return stagedURL
            } catch {
                Self.cleanupPreparedInstallPackage(stagedURL)
                throw error
            }
        } catch is InstallPackagePreparationError {
            errorMessage = UserFacingFailure.text(.installInvalidPackage)
            return nil
        } catch {
            errorMessage = UserFacingFailure.message(
                .selectInstallPackage,
                underlying: error
            )
            return nil
        }
    }

    nonisolated static func cleanupPreparedInstallPackage(_ url: URL) {
        let directory = url.deletingLastPathComponent().standardizedFileURL
        let temporaryDirectory = FileManager.default.temporaryDirectory
            .standardizedFileURL
        guard directory.deletingLastPathComponent() == temporaryDirectory,
              directory.lastPathComponent.hasPrefix("TVADB-Install-") else {
            return
        }
        try? FileManager.default.removeItem(at: directory)
    }

    private func applicationFailure(for action: ApplicationAction) -> UserFacingFailure.Operation {
        switch action {
        case .launch: .launchApp
        case .forceStop: .forceStopApp
        case .clearCache: .clearCache
        case .clearData: .clearData
        case .uninstall: .uninstallApp
        }
    }

    func invalidateApplicationList() {
        applicationListLoadedForDevice = false
    }

    func loadApplicationsIfNeeded() async {
        guard !Task.isCancelled, connectionState.isConnected,
              !applicationListLoadedForDevice else { return }
        let request = UUID()
        applicationListRequest = request
        isLoadingApplications = true
        defer {
            if applicationListRequest == request {
                applicationListRequest = nil
                isLoadingApplications = false
            }
        }
        do {
            let packages = try await commandService.listApplications()
            try Task.checkCancellation()
            guard applicationListRequest == request, connectionState.isConnected else { return }
            applications = packages.map { app in
                guard let cached = appPresentationCache[app.packageName],
                      cached.matches(app) else { return app }
                var resolved = app
                resolved.name = cached.presentation.name
                resolved.iconData = TVADBPreferences.loadApplicationIcons ? cached.presentation.iconData : nil
                resolved.versionName = cached.presentation.versionName
                return resolved
            }
            applicationListLoadedForDevice = true
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled, applicationListRequest == request else { return }
            errorMessage = UserFacingFailure.message(.readAppList, underlying: error)
        }
    }

    func loadApplicationPresentations(for apps: [AndroidApp]) async {
        guard TVADBPreferences.loadApplicationIcons else { return }
        let deviceEndpoint = currentDevice.map { "\($0.host):\($0.port)" }
        var pending: [AndroidApp] = []

        for app in apps {
            guard !Task.isCancelled, connectionState.isConnected else { return }
            guard let currentApp = applications.first(where: {
                $0.packageName == app.packageName
            }), currentApp.name == nil || currentApp.iconData == nil else {
                continue
            }
            if let cached = appPresentationCache[app.packageName],
               cached.matches(app),
               cached.presentation.iconData != nil {
                apply(cached.presentation, toPackage: app.packageName)
                continue
            }
            guard !loadingAppPresentations.contains(app.packageName) else {
                continue
            }
            appPresentationCache.removeValue(forKey: app.packageName)
            loadingAppPresentations.insert(app.packageName)
            pending.append(app)
        }

        guard !pending.isEmpty else { return }
        defer {
            for app in pending {
                loadingAppPresentations.remove(app.packageName)
            }
        }

        // Apply small batches as soon as Android returns them. Apart from
        // avoiding oversized exec-out payloads, this makes the first visible
        // rows update quickly instead of holding every icon until all 40 have
        // been rendered and transferred.
        let batchSize = 8
        for start in stride(from: 0, to: pending.count, by: batchSize) {
            guard !Task.isCancelled, TVADBPreferences.loadApplicationIcons else { return }
            let end = min(start + batchSize, pending.count)
            let batch = Array(pending[start..<end])
            let helperPresentations = await commandService
                .inspectInstalledApplicationsWithHelper(batch)

            for app in batch {
                guard !Task.isCancelled,
                      connectionState.isConnected,
                      TVADBPreferences.loadApplicationIcons,
                      currentDevice.map({ "\($0.host):\($0.port)" }) == deviceEndpoint else {
                    return
                }

                guard let presentation = helperPresentations[app.packageName],
                      presentation.iconData != nil else {
                    // If PackageManager access is blocked, keep the package
                    // name and lightweight default icon. Never pull its APK.
                    continue
                }
                let cached = CachedInstalledAppPresentation(
                    formatVersion: 3,
                    baseAPKPath: app.baseAPKPath,
                    isSystem: app.isSystem,
                    cachedAt: Date(),
                    presentation: presentation
                )
                await cacheApplicationPresentation(cached, for: app)
            }
        }
    }

    func applicationIconLoadingDidChange(isEnabled: Bool) async {
        if !isEnabled {
            for index in applications.indices {
                applications[index].iconData = nil
            }
            return
        }
        guard connectionState.isConnected else { return }
        await loadApplicationPresentations(
            for: Array(applications.lazy.filter { !$0.isSystem }.prefix(40))
        )
    }

    private func cacheApplicationPresentation(
        _ cached: CachedInstalledAppPresentation,
        for app: AndroidApp
    ) async {
        appPresentationCache[app.packageName] = cached
        apply(cached.presentation, toPackage: app.packageName)
        if let deviceKey = appPresentationCacheDeviceKey {
            await appPresentationStore.save(
                cached,
                packageName: app.packageName,
                deviceKey: deviceKey
            )
        }
    }

    func inspectAPK(url: URL) async -> APKInspection? {
        guard !isBusy else {
            errorMessage = L10n.string("error.operation_in_progress")
            return nil
        }
        guard Self.isSupportedInstallPackage(url) else {
            errorMessage = L10n.string(
                "error.select_supported_install_package"
            )
            return nil
        }
        errorMessage = nil
        isBusy = true
        operationText = L10n.string("text.inspecting_apk")
        defer {
            isBusy = false
            operationText = ""
        }
        let didAccess = url.startAccessingSecurityScopedResource()
        defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
        do {
            let extensionName = url.pathExtension.lowercased()
            let filename = url.lastPathComponent
            return try await Task.detached(priority: .userInitiated) {
                let data = try Data(
                    contentsOf: url,
                    options: [.mappedIfSafe]
                )
                let apkData: Data
                if extensionName == "apk" {
                    apkData = data
                } else if ["apks", "xapk", "apkm"].contains(extensionName) {
                    let package = try ZipArchiveReader.extractPackage(
                        from: data,
                        fileExtension: extensionName
                    )
                    apkData = package.apkEntries.first(where: {
                        $0.name.lowercased() == "base.apk"
                    })?.data ?? package.apkEntries.first?.data ?? Data()
                } else {
                    throw InstallPackagePreparationError.invalidPackage
                }
                return try APKInspector.inspect(
                    data: apkData,
                    filename: filename
                )
            }.value
        } catch {
            errorMessage = UserFacingFailure.message(.inspectAPK, underlying: error)
            return nil
        }
    }

    func exportApplication(_ app: AndroidApp) async -> URL? {
        guard !isBusy else { return nil }
        errorMessage = nil
        isBusy = true
        operationText = String(
            format: L10n.string("text.exporting_value"),
            app.displayName
        )
        defer {
            isBusy = false
            operationText = ""
        }
        do {
            return try await commandService.exportApplication(packageName: app.packageName)
        } catch {
            errorMessage = UserFacingFailure.message(.exportAPK, underlying: error)
            return nil
        }
    }

    func applicationAction(_ app: AndroidApp, action: ApplicationAction) async {
        let processingText = String(
            format: L10n.string("text.processing_value"),
            app.packageName
        )
        await performOperation(
            text: processingText,
            failure: applicationFailure(for: action)
        ) {
            let output: String
            switch action {
            case .launch:
                output = try await self.commandService.launch(packageName: app.packageName)
            case .forceStop:
                try await self.commandService.forceStop(packageName: app.packageName)
                output = String(
                    format: L10n.string("text.stopped_value"),
                    app.packageName
                )
            case .clearCache:
                output = try await self.commandService.clearCache(packageName: app.packageName)
            case .clearData:
                output = try await self.commandService.clearData(packageName: app.packageName)
            case .uninstall:
                output = try await self.commandService.uninstall(packageName: app.packageName)
                self.applications.removeAll { $0.id == app.id }
            }
            self.noticeMessage = output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? L10n.string("text.completed")
                : output
        }
    }

    @Published var fastDirectionalControlAvailable: Bool?
    @Published private(set) var remoteVolume: ADBCommandService.VolumeState?

    func readRemoteVolume() async -> ADBCommandService.VolumeState? {
        guard connectionState.isConnected else { return nil }
        do {
            let state = try await commandService.volumeState()
            remoteVolume = state
            return state
        } catch {
            errorMessage = error.localizedDescription.contains("TV volume range unavailable")
                ? L10n.string("text.remote_volume_unsupported")
                : UserFacingFailure.message(.remoteControl, underlying: error)
            return nil
        }
    }

    func setRemoteVolume(_ target: Int) async -> ADBCommandService.VolumeState? {
        guard connectionState.isConnected else { return nil }
        do {
            let state = try await commandService.setVolume(target)
            remoteVolume = state
            return state
        } catch {
            errorMessage = error.localizedDescription.contains("TV volume range unavailable")
                ? L10n.string("text.remote_volume_unsupported")
                : UserFacingFailure.message(.remoteControl, underlying: error)
            return nil
        }
    }

    func tuneRemoteChannel(_ digits: String) async -> Bool {
        guard connectionState.isConnected else { return false }
        do {
            try await commandService.tuneChannel(digits)
            return true
        } catch {
            errorMessage = UserFacingFailure.message(.remoteControl, underlying: error)
            return false
        }
    }

    func prepareDirectionalControl() async {
        guard connectionState.isConnected else { return }
        fastDirectionalControlAvailable = await commandService.prepareRemoteKeys()
    }

    @discardableResult
    func sendRemoteKey(_ keyCode: Int) async -> Bool {
        do {
            try await commandService.sendKey(keyCode)
            if (19...23).contains(keyCode) {
                fastDirectionalControlAvailable = await commandService.hasFastRemoteKeys
            }
            return true
        } catch {
            if (19...23).contains(keyCode) { fastDirectionalControlAvailable = false }
            errorMessage = UserFacingFailure.message(.remoteControl, underlying: error)
            return false
        }
    }

    func televisionHasTextFocus() async -> Bool {
        guard connectionState.isConnected else { return false }
        return (try? await commandService.focusedTextEditor()) ?? false
    }

    func openRemoteTextSession() async throws -> RemoteTextSession {
        guard connectionState.isConnected else { throw ADBClientError.notConnected }
        return try await commandService.openRemoteTextSession()
    }

    func sendRemoteText(_ text: String) async -> Bool {
        do {
            try await commandService.sendText(text)
            return true
        } catch {
            errorMessage = UserFacingFailure.message(
                .keyboardInput,
                underlying: error
            )
            return false
        }
    }

    func televisionScreenRotation() async -> Int? {
        try? await commandService.screenRotation()
    }

    func synchronizeMousePosition(_ point: CGPoint) async {
        await commandService.synchronizeVirtualMousePosition(
            x: Int(point.x.rounded()),
            y: Int(point.y.rounded())
        )
    }

    func televisionScreenSize() async -> CGSize {
        do {
            let size = try await commandService.screenSize()
            return CGSize(width: size.width, height: size.height)
        } catch {
            UserFacingFailure.log(.readScreenSize, underlying: error)
            return CGSize(width: 1920, height: 1080)
        }
    }

    func centerMousePointer(in size: CGSize) async -> Bool {
        guard !mouseControlIsCalibrating else { return false }
        guard nativeMouseInputActive else { return true }
        mouseControlIsCalibrating = true
        defer { mouseControlIsCalibrating = false }
        pendingMousePosition = nil
        cancelQueuedMouseMovement()
        mouseDragActive = false
        do {
            return try await commandService.centerVirtualMouse(width: Int(size.width), height: Int(size.height))
        } catch {
            errorMessage = UserFacingFailure.message(.remoteControl, underlying: error)
            return false
        }
    }

    func retryMouseControl() async -> CGSize {
        mouseClickUnavailable = false
        mouseDragActive = false
        pendingMousePosition = nil
        cancelQueuedMouseMovement()
        await commandService.resetVirtualMouseCapability()
        return await prepareMouseControl()
    }

    func prepareMouseControl() async -> CGSize {
        let screenSize = await televisionScreenSize()
        let pointerPrepared = await commandService.prepareVirtualMouse(
            screenWidth: Int(screenSize.width.rounded()),
            screenHeight: Int(screenSize.height.rounded())
        )
        let usesCoordinateTouch =
            await commandService.isUsingCoordinateTouchMode()
        mouseMoveUnavailable = usesCoordinateTouch
        nativeMouseInputActive = pointerPrepared
        if pointerPrepared {
            physicalHIDMouseActive =
                await commandService.isUsingPhysicalHIDMouse()
        } else {
            physicalHIDMouseActive = false
        }

        if pointerPrepared {
            // Android keeps a newly registered pointer hidden until it receives
            // its first relative movement. A tiny nudge makes the cursor visible
            // immediately without changing the user's effective position.
            let center = CGPoint(x: screenSize.width / 2, y: screenSize.height / 2)
            let movedOut = (try? await commandService.moveMouse(
                x: Int(center.x.rounded()) + 1,
                y: Int(center.y.rounded())
            )) ?? false
            let movedBack = (try? await commandService.moveMouse(
                x: Int(center.x.rounded()),
                y: Int(center.y.rounded())
            )) ?? false
            nativeMouseInputActive = movedOut && movedBack
            if !nativeMouseInputActive {
                physicalHIDMouseActive = false
            }
        }

        return screenSize
    }

    func stopMouseControl() async {
        stopMousePreview()
        await stopMouseInput()
    }

    func prepareTouchscreenControl() async -> CGSize {
        let screenSize = await televisionScreenSize()
        await stopMouseInput()
        return screenSize
    }

    private func stopMouseInput() async {
        cancelQueuedMouseMovement()
        await commandService.stopVirtualMouse()
        nativeMouseInputActive = false
        physicalHIDMouseActive = false
        mouseMoveUnavailable = false
        mouseDragActive = false
        pendingMousePosition = nil
    }

    func startMousePreview() {
        stopMousePreview(clearFrame: true)
        guard connectionState.isConnected else { return }
        mousePreviewIsRequested = true
        mousePreviewVideoFailure = nil

        let sessionID = UUID()
        mousePreviewSessionID = sessionID
        mousePreviewVideoHub.beginSession(sessionID)
        mousePreviewIsLoading = true
        mousePreviewUnavailable = false

        mousePreviewTask = Task { [weak self] in
            guard let self else { return }
            let videoStreamStayedActive =
                await self.runH264MousePreview(sessionID: sessionID)
            guard !videoStreamStayedActive,
                  !Task.isCancelled,
                  self.mousePreviewSessionID == sessionID,
                  self.connectionState.isConnected else {
                return
            }
            await self.runScreenshotMousePreview(sessionID: sessionID)
        }
    }

    func stopMousePreview(clearFrame: Bool = true) {
        mousePreviewIsRequested = false
        mousePreviewVideoFailure = nil
        mousePreviewTask?.cancel()
        mousePreviewTask = nil
        let stream = mousePreviewStream
        mousePreviewStream = nil
        if let stream {
            Task {
                await stream.close()
            }
        }
        mousePreviewSessionID = nil
        mousePreviewVideoHub.clear()
        mousePreviewVideoSize = nil
        mousePreviewHasVideoFrame = false
        mousePreviewUsesVideoStream = false
        mousePreviewIsLoading = false
        mousePreviewUnavailable = false
        if clearFrame {
            mousePreviewImage = nil
        }
    }

    /// Returns `true` when the preview ended because the caller stopped it.
    /// Returns `false` when the device cannot provide a usable H.264 stream,
    /// allowing the caller to enter the PNG compatibility loop.
    private func runH264MousePreview(sessionID: UUID) async -> Bool {
        var triedNativeResolution = false
        var useHelper = true
        while !Task.isCancelled,
              mousePreviewSessionID == sessionID,
              connectionState.isConnected {
            var activeStream: ADBPersistentShellSession?
            var decoder: H264StreamDecoder?
            var receivedBytes = 0
            var firstFrameTimedOut = false
            var firstFrameTimeout: Task<Void, Never>?
            do {
                let stream: ADBPersistentShellSession
                if useHelper {
                    stream = try await commandService.openHardwareVideoStream()
                } else {
                    stream = try await commandService.openH264ScreenStream(useBoundedResolution: !triedNativeResolution)
                }
                guard !Task.isCancelled,
                      mousePreviewSessionID == sessionID else {
                    await stream.close()
                    return true
                }
                activeStream = stream
                mousePreviewStream = stream

                let hub = mousePreviewVideoHub
                let metadata = PreviewFrameMetadata()
                let streamDecoder = H264StreamDecoder {
                    [weak self] sample in
                    hub.enqueue(sample, sessionID: sessionID)
                    guard metadata.changed(to: sample.dimensions) else { return }
                    Task { @MainActor in
                        guard let self,
                              self.mousePreviewSessionID == sessionID,
                              self.connectionState.isConnected else {
                            return
                        }
                        self.mousePreviewVideoFailure = nil
                        self.mousePreviewImage = nil
                        self.mousePreviewVideoSize = sample.dimensions
                        self.mousePreviewHasVideoFrame = true
                        self.mousePreviewUsesVideoStream = true
                        self.mousePreviewIsLoading = false
                        self.mousePreviewUnavailable = false
                    }
                }
                decoder = streamDecoder

                firstFrameTimeout = Task {
                    try? await Task.sleep(for: .seconds(4))
                    guard !Task.isCancelled,
                          !streamDecoder.hasDecodedFrame else {
                        return
                    }
                    firstFrameTimedOut = true
                    await stream.close()
                }

                var packets = VideoPacketParser()
                while !Task.isCancelled,
                      mousePreviewSessionID == sessionID,
                      let chunk = try await stream.read() {
                    receivedBytes += chunk.count
                    if useHelper {
                        for packet in try packets.append(chunk) where packet.kind != 1 {
                            await streamDecoder.appendAccessUnit(packet.data)
                        }
                    } else {
                        await streamDecoder.append(chunk)
                    }
                }
                firstFrameTimeout?.cancel()
                if useHelper, !Task.isCancelled, mousePreviewSessionID == sessionID { try packets.finish() }
                let decodedFrame = streamDecoder.hasDecodedFrame
                let hasParameters = streamDecoder.hasVideoParameterSets
                if !decodedFrame, mousePreviewSessionID == sessionID, !Task.isCancelled {
                    mousePreviewVideoFailure = Self.videoPreviewFailure(bytes: receivedBytes, hasParameters: streamDecoder.hasVideoParameterSets, status: streamDecoder.failureStatus, timedOut: firstFrameTimedOut)
                }
                streamDecoder.invalidate()
                if mousePreviewStream === stream {
                    mousePreviewStream = nil
                }
                await stream.close()
                activeStream = nil

                guard !Task.isCancelled,
                      mousePreviewSessionID == sessionID,
                      connectionState.isConnected else {
                    return true
                }
                if useHelper {
                    mousePreviewVideoFailure = Self.videoPreviewFailure(bytes: receivedBytes, hasParameters: hasParameters, status: streamDecoder.failureStatus, timedOut: firstFrameTimedOut)
                    mousePreviewVideoHub.clear()
                    mousePreviewVideoSize = nil
                    mousePreviewHasVideoFrame = false
                    mousePreviewUsesVideoStream = false
                    useHelper = false
                    continue
                }
                if !decodedFrame && !triedNativeResolution {
                    triedNativeResolution = true
                    continue
                }
                guard decodedFrame else {
                    mousePreviewVideoHub.clear()
                    mousePreviewVideoSize = nil
                    mousePreviewHasVideoFrame = false
                    mousePreviewUsesVideoStream = false
                    return false
                }

                // Android screenrecord has a finite time limit. Reopen it
                // immediately while leaving the last decoded frame visible.
                try? await Task.sleep(for: .milliseconds(80))
            } catch {
                firstFrameTimeout?.cancel()
                let decodedFrame = decoder?.hasDecodedFrame ?? false
                if mousePreviewSessionID == sessionID, !Task.isCancelled, !decodedFrame {
                    mousePreviewVideoFailure = activeStream == nil
                        ? L10n.format("text.preview_open_failed", error.localizedDescription)
                        : Self.videoPreviewFailure(bytes: receivedBytes, hasParameters: decoder?.hasVideoParameterSets ?? false, status: decoder?.failureStatus, timedOut: firstFrameTimedOut)
                }
                decoder?.invalidate()
                if let activeStream {
                    if mousePreviewStream === activeStream {
                        mousePreviewStream = nil
                    }
                    await activeStream.close()
                }
                guard !Task.isCancelled,
                      mousePreviewSessionID == sessionID,
                      connectionState.isConnected else {
                    return true
                }
                if useHelper {
                    mousePreviewVideoHub.clear()
                    mousePreviewVideoSize = nil
                    mousePreviewHasVideoFrame = false
                    mousePreviewUsesVideoStream = false
                    useHelper = false
                    continue
                }
                if decodedFrame {
                    try? await Task.sleep(for: .milliseconds(120))
                    continue
                }
                if !triedNativeResolution {
                    triedNativeResolution = true
                    continue
                }
                mousePreviewVideoHub.clear()
                mousePreviewVideoSize = nil
                mousePreviewHasVideoFrame = false
                mousePreviewUsesVideoStream = false
                return false
            }
        }
        return true
    }

    private static func videoPreviewFailure(bytes: Int, hasParameters: Bool, status: OSStatus?, timedOut: Bool) -> String {
        if let status { return L10n.format("text.preview_decode_failed", Int(status)) }
        if bytes == 0 { return L10n.string("text.preview_no_data") }
        if !hasParameters { return L10n.format("text.preview_invalid_data", bytes) }
        return L10n.string(timedOut ? "text.preview_first_frame_timeout" : "text.preview_stream_ended")
    }

    private func runScreenshotMousePreview(sessionID: UUID) async {
        var consecutiveFailures = 0
        mousePreviewVideoHub.clear()
        mousePreviewVideoSize = nil
        mousePreviewHasVideoFrame = false
        mousePreviewUsesVideoStream = false

        while !Task.isCancelled,
              mousePreviewSessionID == sessionID,
              connectionState.isConnected {
            let captureStartedAt = Date()
            do {
                let frame = try await commandService.screenshot()
                guard !Task.isCancelled,
                      mousePreviewSessionID == sessionID,
                      let image = UIImage(data: frame) else {
                    break
                }
                mousePreviewImage = image
                mousePreviewIsLoading = false
                mousePreviewUnavailable = false
                consecutiveFailures = 0
            } catch {
                guard !Task.isCancelled,
                      mousePreviewSessionID == sessionID else {
                    break
                }
                consecutiveFailures += 1
                if consecutiveFailures >= 3 {
                    mousePreviewIsLoading = false
                    mousePreviewUnavailable = true
                }
            }

            let delay: Int
            if consecutiveFailures == 0 {
                let elapsed = Date().timeIntervalSince(captureStartedAt)
                delay = max(80, Int((0.25 - elapsed) * 1_000))
            } else {
                delay = 1_000
            }
            try? await Task.sleep(for: .milliseconds(delay))
        }

        guard mousePreviewSessionID == sessionID else { return }
        mousePreviewIsLoading = false
    }

    func enqueueRelativeMouseMove(_ delta: CGPoint) {
        guard nativeMouseInputActive, !mouseControlIsCalibrating else { return }
        relativeMouseMotion.add(x: delta.x, y: delta.y)
        guard relativeMouseTask == nil, relativeMouseMotion.hasWholeMovement else { return }
        let generation = relativeMouseGeneration
        relativeMouseTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled, let delta = relativeMouseMotion.take() {
                let delivered = await commandService.moveMouseBy(x: delta.x, y: delta.y)
                guard !Task.isCancelled else { return }
                if !delivered {
                    updateNativeMouseState(false)
                    mouseMoveUnavailable = true
                    mouseDragActive = false
                    relativeMouseMotion = MouseMotionAccumulator()
                    break
                }
            }
            if relativeMouseGeneration == generation { relativeMouseTask = nil }
        }
    }

    func cancelQueuedMouseMovement() {
        relativeMouseGeneration += 1
        relativeMouseTask?.cancel()
        relativeMouseTask = nil
        relativeMouseMotion = MouseMotionAccumulator()
    }

    private func updateNativeMouseState(_ active: Bool) {
        if nativeMouseInputActive != active { nativeMouseInputActive = active }
        if !active, physicalHIDMouseActive { physicalHIDMouseActive = false }
    }

    @discardableResult
    func moveMouse(to point: CGPoint) async -> Bool {
        await relativeMouseTask?.value
        guard !mouseMoveUnavailable, !mouseControlIsCalibrating else { return false }
        pendingMousePosition = point
        if mouseMoveInFlight {
            return true
        }

        mouseMoveInFlight = true
        defer { mouseMoveInFlight = false }

        while let target = pendingMousePosition {
            pendingMousePosition = nil
            do {
                let delivered = try await commandService.moveMouse(
                    x: Int(target.x.rounded()),
                    y: Int(target.y.rounded())
                )
                updateNativeMouseState(delivered)
            } catch {
                // Several TV firmwares allow coordinate taps but reject
                // synthetic mouse MOVE events. Keep taps available and handle
                // this silently instead of exposing the Java exception.
                mouseMoveUnavailable = true
                mouseDragActive = false
                pendingMousePosition = nil
                return false
            }
        }
        return true
    }

    func clickMouse(at point: CGPoint) async {
        await relativeMouseTask?.value
        if mouseClickUnavailable {
            await sendRemoteKey(23)
            return
        }
        do {
            let delivered = try await commandService.clickMouse(
                x: Int(point.x.rounded()),
                y: Int(point.y.rounded()),
                useTouchscreenSource: mouseMoveUnavailable
            )
            updateNativeMouseState(delivered)
        } catch {
            // Do not surface raw `input`/SecurityException text. If coordinate
            // taps are also blocked, fall back to DPAD_CENTER.
            mouseClickUnavailable = true
            await sendRemoteKey(23)
        }
    }

    func clickMouseAtAbsolutePosition(_ point: CGPoint) async {
        _ = await moveMouse(to: point)
        await clickMouse(at: point)
    }

    func tapTouchscreen(at point: CGPoint) async {
        do {
            try await commandService.tapTouchscreen(
                x: Int(point.x.rounded()),
                y: Int(point.y.rounded())
            )
        } catch {
            errorMessage = UserFacingFailure.message(
                .remoteControl,
                underlying: error
            )
        }
    }

    func swipeTouchscreen(
        from start: CGPoint,
        to end: CGPoint,
        durationMilliseconds: Int = 240
    ) async {
        do {
            try await commandService.swipeTouchscreen(
                from: start,
                to: end,
                durationMilliseconds: durationMilliseconds
            )
        } catch {
            errorMessage = UserFacingFailure.message(
                .remoteControl,
                underlying: error
            )
        }
    }

    func dragTouchscreen(
        from start: CGPoint,
        to end: CGPoint,
        durationMilliseconds: Int = 280
    ) async {
        do {
            try await commandService.dragTouchscreen(
                from: start,
                to: end,
                durationMilliseconds: durationMilliseconds
            )
        } catch {
            errorMessage = UserFacingFailure.message(
                .remoteControl,
                underlying: error
            )
        }
    }

    func longPressTouchscreen(
        at point: CGPoint,
        durationMilliseconds: Int = 750
    ) async {
        do {
            try await commandService.longPressTouchscreen(
                at: point,
                durationMilliseconds: durationMilliseconds
            )
        } catch {
            errorMessage = UserFacingFailure.message(
                .remoteControl,
                underlying: error
            )
        }
    }

    func doubleClickMouse(at point: CGPoint) async {
        await clickMouse(at: point)
        try? await Task.sleep(for: .milliseconds(120))
        await clickMouse(at: point)
    }

    @discardableResult
    func setMouseDragActive(
        _ active: Bool,
        reportsUnavailable: Bool = true
    ) async -> Bool {
        await relativeMouseTask?.value
        guard active != mouseDragActive else {
            return active ? mouseDragActive : true
        }
        do {
            let delivered = try await commandService.setMouseButton(
                .left,
                pressed: active
            )
            mouseDragActive = delivered && active
            if !delivered, reportsUnavailable {
                noticeMessage = L10n.string(
                    "text.mouse_drag_requires_system_mouse_input"
                )
            }
            return active ? mouseDragActive : delivered
        } catch {
            mouseDragActive = false
            if reportsUnavailable {
                noticeMessage = L10n.string(
                    "text.mouse_drag_requires_system_mouse_input"
                )
            }
            return false
        }
    }

    func rightClickMouse() async {
        await relativeMouseTask?.value
        let delivered =
            (try? await commandService.clickMouse(button: .right)) ?? false
        if !delivered {
            await sendRemoteKey(4)
        }
    }

    func middleClickMouse() async {
        await relativeMouseTask?.value
        let delivered =
            (try? await commandService.clickMouse(button: .middle)) ?? false
        if !delivered {
            await sendRemoteKey(3)
        }
    }

    func scrollMouse(amount: Int) async {
        await relativeMouseTask?.value
        guard amount != 0 else { return }
        let delivered =
            (try? await commandService.scrollMouse(amount: amount)) ?? false
        if !delivered {
            await sendRemoteKey(amount > 0 ? 19 : 20)
        }
    }

    func setShowTouches(_ enabled: Bool) async {
        do {
            try await commandService.setShowTouches(enabled)
            noticeMessage = enabled
                ? L10n.string("text.tv_tap_feedback_is_on")
                : L10n.string("text.tv_tap_feedback_is_off")
        } catch {
            errorMessage = UserFacingFailure.message(.changeTapFeedback, underlying: error)
        }
    }

    func executeShell(_ command: String) async -> String? {
        guard !command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        var result: String?
        await performOperation(
            text: L10n.string("text.running_command"),
            failure: .executeCommand
        ) {
            result = try await self.commandService.shell(command)
        }
        return result
    }

    func logcatSnapshot(maxLines: Int = 2_000) async -> String? {
        do {
            return try await commandService.logcatSnapshot(maxLines: maxLines)
        } catch {
            errorMessage = UserFacingFailure.message(.readLogcat, underlying: error)
            return nil
        }
    }

    func openLogcatStream() async -> ADBPersistentShellSession? {
        do {
            return try await commandService.openLogcatStream()
        } catch {
            errorMessage = UserFacingFailure.message(
                .readLogcat,
                underlying: error
            )
            return nil
        }
    }

    func reportLogcatStreamFailure(_ error: Error) {
        errorMessage = UserFacingFailure.message(
            .readLogcat,
            underlying: error
        )
    }

    func clearLogcat() async -> Bool {
        do {
            try await commandService.clearLogcat()
            return true
        } catch {
            errorMessage = UserFacingFailure.message(.clearLogcat, underlying: error)
            return false
        }
    }

    func captureScreenshot() async -> Data? {
        var result: Data?
        await performOperation(
            text: L10n.string("text.capturing_the_tv_screen"),
            failure: .screenshot
        ) {
            result = try await self.commandService.screenshot()
        }
        return result
    }

    func recordScreen(duration: Int) async -> URL? {
        guard !isBusy else { return nil }
        errorMessage = nil
        isBusy = true
        isScreenRecording = true
        isScreenRecordingReady = false
        operationText = String(
            format: L10n.string("text.recording_the_tv_screen_value_seconds"),
            duration
        )
        defer {
            isBusy = false
            isScreenRecording = false
            isScreenRecordingReady = false
            operationText = ""
        }
        do {
            return try await commandService.recordScreen(
                duration: duration,
                onReady: {
                    await MainActor.run {
                        self.isScreenRecordingReady = true
                    }
                }
            )
        } catch {
            errorMessage = UserFacingFailure.message(.screenRecording, underlying: error)
            return nil
        }
    }

    func loadDeviceInformation() async {
        await performOperation(
            text: L10n.string("text.loading_device_information"),
            failure: .readDeviceInfo
        ) {
            self.deviceInformation = try await self.commandService.deviceInformation()
        }
    }

    func loadNetworkDiagnostics() async {
        await performOperation(
            text: L10n.string("text.loading_network_diagnostics"),
            failure: .executeCommand
        ) {
            self.networkDiagnostics = try await self.commandService.networkDiagnostics()
        }
    }

    func reboot(_ target: String = "") async {
        await performOperation(
            text: L10n.string("text.sending_restart_command"),
            failure: .reboot
        ) {
            try await self.commandService.reboot(target: target)
            self.noticeMessage = L10n.string("text.restart_command_sent")
        }
    }

    func download(_ file: RemoteFile) async -> URL? {
        guard !isBusy else { return nil }
        return await runCancellableFileTransfer {
            await self.performDownload(file)
        }
    }

    private func performDownload(_ file: RemoteFile) async -> URL? {
        isBusy = true
        operationText = String(
            format: L10n.string("text.downloading_value"),
            file.name
        )
        progress = nil
        defer {
            isBusy = false
            operationText = ""
            progress = nil
        }
        do {
            let deviceKey: String
            if let device = currentDevice {
                deviceKey = "\(device.host):\(device.port)"
            } else {
                deviceKey = "current-device"
            }
            return try await filesService.download(
                file,
                from: path(appending: file.name),
                resumeIdentifier: deviceKey
            ) { value in
                await MainActor.run { self.progress = value }
            }
        } catch {
            if Task.isCancelled || error is CancellationError {
                noticeMessage = L10n.string("text.transfer_cancelled")
            } else {
                errorMessage = UserFacingFailure.message(
                    .downloadFile,
                    underlying: error
                )
            }
            return nil
        }
    }

    func cancelActiveFileTransfer() {
        guard isFileTransferActive else { return }
        operationText = L10n.string("text.cancelling_transfer")
        activeFileTransferCancel?()
    }

    private func runCancellableFileTransfer<T: Sendable>(
        _ operation: @escaping @MainActor @Sendable () async -> T
    ) async -> T {
        let transferID = UUID()
        let task = Task { @MainActor in
            await operation()
        }
        activeFileTransferID = transferID
        activeFileTransferCancel = {
            task.cancel()
        }
        isFileTransferActive = true

        let result = await task.value
        if activeFileTransferID == transferID {
            activeFileTransferID = nil
            activeFileTransferCancel = nil
            isFileTransferActive = false
        }
        return result
    }

    func createDirectory(named name: String) async {
        let cleaned = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, !cleaned.contains("/") else {
            errorMessage = UserFacingFailure.text(.invalidFolderName)
            return
        }
        await performOperation(
            text: L10n.string("text.creating_folder"),
            failure: .createFolder
        ) {
            try await self.filesService.createDirectory(self.path(appending: cleaned))
            try await self.loadFiles()
        }
    }

    func delete(_ file: RemoteFile) async {
        await performOperation(
            text: L10n.format("text.deleting_value", file.name),
            failure: .deleteFile
        ) {
            try await self.filesService.delete(self.path(appending: file.name))
            try await self.loadFiles()
        }
    }

    func delete(_ targets: [RemoteFile]) async {
        guard !targets.isEmpty else { return }
        await performOperation(
            text: L10n.format("text.deleting_value_items", targets.count),
            failure: .deleteFile
        ) {
            for file in targets {
                try await self.filesService.delete(self.path(appending: file.name))
            }
            try await self.loadFiles()
        }
    }

    func rename(_ file: RemoteFile, to newName: String) async {
        let cleaned = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, !cleaned.contains("/") else {
            errorMessage = UserFacingFailure.text(.invalidFileName)
            return
        }
        await performOperation(
            text: L10n.string("text.renaming"),
            failure: .renameFile
        ) {
            try await self.filesService.rename(
                from: self.path(appending: file.name),
                to: self.path(appending: cleaned)
            )
            try await self.loadFiles()
        }
    }

    private func performOperation(
        text: String,
        failure: UserFacingFailure.Operation,
        operation: () async throws -> Void
    ) async {
        guard !isBusy else { return }
        errorMessage = nil
        isBusy = true
        operationText = text
        progress = nil
        defer { isBusy = false; operationText = ""; progress = nil }
        do {
            try await operation()
        } catch {
            errorMessage = UserFacingFailure.message(failure, underlying: error)
        }
    }

    private func performFileRead(
        url: URL,
        action: String,
        failure: UserFacingFailure.Operation,
        operation: (Data) async throws -> Void
    ) async {
        await performOperation(text: action, failure: failure) {
            let didAccess = url.startAccessingSecurityScopedResource()
            defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
            let data = try await Task.detached(priority: .userInitiated) {
                try Data(contentsOf: url, options: [.mappedIfSafe])
            }.value
            try await operation(data)
        }
    }

    private nonisolated static func stageImportedFile(
        _ sourceURL: URL,
        directoryPrefix: String = "TVADB-Upload"
    ) async throws -> URL {
        try await Task.detached(priority: .userInitiated) {
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent(
                    "\(directoryPrefix)-\(UUID().uuidString)",
                    isDirectory: true
                )
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let filename = sourceURL.lastPathComponent.isEmpty ? "upload.bin" : sourceURL.lastPathComponent
            let destination = directory.appendingPathComponent(filename)
            do {
                try FileManager.default.copyItem(at: sourceURL, to: destination)
                return destination
            } catch {
                try? FileManager.default.removeItem(at: directory)
                throw error
            }
        }.value
    }

    private nonisolated static func validateInstallPackageFile(
        at url: URL
    ) throws {
        let values = try url.resourceValues(forKeys: [
            .isDirectoryKey,
            .fileSizeKey
        ])
        guard values.isDirectory != true,
              (values.fileSize ?? 0) > 0 else {
            throw InstallPackagePreparationError.invalidPackage
        }

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let signature = try handle.read(upToCount: 4) ?? Data()
        guard signature.count == 4,
              signature[signature.startIndex] == 0x50,
              signature[signature.startIndex + 1] == 0x4B else {
            throw InstallPackagePreparationError.invalidPackage
        }
    }

    private func installPendingPackageIfPossible() async {
        guard connectionState.isConnected,
              let url = pendingPackageURL else {
            return
        }
        let installed = await installAPK(url: url)
        if installed {
            pendingPackageURL = nil
            try? FileManager.default.removeItem(at: url)
        }
    }

    private nonisolated static func persistIncomingPackage(_ sourceURL: URL) async throws -> URL {
        try await Task.detached(priority: .userInitiated) {
            let root = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("IncomingPackages", isDirectory: true)
            try FileManager.default.createDirectory(
                at: root,
                withIntermediateDirectories: true
            )
            let extensionName = sourceURL.pathExtension.lowercased()
            let destination = root.appendingPathComponent(
                "\(UUID().uuidString).\(extensionName)"
            )
            try FileManager.default.copyItem(at: sourceURL, to: destination)
            return destination
        }.value
    }

    private func loadFiles() async throws {
        files = try await filesService.list(path: currentPath)
    }

    private func apply(_ presentation: InstalledAppPresentation, toPackage packageName: String) {
        guard let index = applications.firstIndex(where: { $0.packageName == packageName }) else {
            return
        }
        var app = applications[index]
        app.name = presentation.name
        app.iconData = TVADBPreferences.loadApplicationIcons
            ? presentation.iconData
            : nil
        app.versionName = presentation.versionName
        guard app != applications[index] else { return }
        applications[index] = app
    }

    private func path(appending component: String) -> String {
        if currentPath == "/" { return "/" + component }
        return currentPath + "/" + component
    }

    private static func deviceName(from banner: String) -> String {
        guard let modelRange = banner.range(of: "model:") else { return L10n.string("text.android_device") }
        let suffix = banner[modelRange.upperBound...]
        let model = suffix.prefix { $0 != ":" && $0 != ";" && $0 != "\0" }
        return model.isEmpty
            ? L10n.string("text.android_device")
            : String(model).replacingOccurrences(of: "_", with: " ")
    }

}

private struct CachedInstalledAppPresentation: Codable, Sendable {
    let formatVersion: Int
    let baseAPKPath: String?
    let isSystem: Bool?
    let cachedAt: Date
    let presentation: InstalledAppPresentation

    func matches(_ app: AndroidApp) -> Bool {
        formatVersion == 3 && baseAPKPath == app.baseAPKPath
    }
}

private actor AppPresentationCacheStore {
    private let maximumAge: TimeInterval = 30 * 24 * 60 * 60

    func load(deviceKey: String) -> [String: CachedInstalledAppPresentation] {
        let directory = cacheDirectory(deviceKey: deviceKey)
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return [:]
        }
        let cutoff = Date().addingTimeInterval(-maximumAge)
        var records: [String: CachedInstalledAppPresentation] = [:]
        for file in files where file.pathExtension == "json" {
            guard let data = try? Data(contentsOf: file),
                  let envelope = try? JSONDecoder().decode(CacheEnvelope.self, from: data),
                  envelope.record.cachedAt >= cutoff else {
                try? FileManager.default.removeItem(at: file)
                continue
            }
            records[envelope.packageName] = envelope.record
        }
        return records
    }

    func save(
        _ record: CachedInstalledAppPresentation,
        packageName: String,
        deviceKey: String
    ) {
        do {
            let directory = cacheDirectory(deviceKey: deviceKey)
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )
            let data = try JSONEncoder().encode(CacheEnvelope(
                packageName: packageName,
                record: record
            ))
            try data.write(
                to: directory.appendingPathComponent(safeFilename(packageName) + ".json"),
                options: .atomic
            )
        } catch {
            // The cache is an optimization only. A write failure must never
            // prevent the application list from receiving its presentation.
        }
    }

    private func cacheDirectory(deviceKey: String) -> URL {
        let root = (try? FileManager.default.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? FileManager.default.temporaryDirectory
        return root
            .appendingPathComponent("TVADBAppPresentations", isDirectory: true)
            .appendingPathComponent(stableHash(deviceKey), isDirectory: true)
    }

    private func safeFilename(_ value: String) -> String {
        value.map {
            $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" || $0 == "_"
                ? String($0)
                : "_"
        }.joined()
    }

    private func stableHash(_ value: String) -> String {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return String(format: "%016llx", hash)
    }

    private struct CacheEnvelope: Codable {
        let packageName: String
        let record: CachedInstalledAppPresentation
    }
}
