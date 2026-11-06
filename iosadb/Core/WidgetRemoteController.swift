import Foundation
import WidgetKit

/// One app model for the foreground UI and background widget intents alike.
@MainActor
enum TVADBAppRuntime {
    static let model = AppModel(deviceStore: DeviceStore(), installHistory: InstallHistoryStore())
}

@MainActor
enum TVADBWidgetController {
    private static var sending = false

    static func publish(_ device: SavedDevice) {
        WidgetRemoteStore.save(WidgetRemoteState(connectionID: UUID(), deviceID: device.id,
            name: device.name, connectedAt: .now))
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetRemoteStore.kind)
    }
    static func clear() {
        WidgetRemoteStore.save(nil)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetRemoteStore.kind)
    }
    static func send(connection: String, command: Int) async throws {
        guard UsageConsent.isAccepted else { throw WidgetRemoteIntentError.openApp }
        guard WidgetRemoteStore.accepts(connection: connection, command: command, state: WidgetRemoteStore.load()),
              let target = WidgetRemoteStore.load() else { throw WidgetRemoteIntentError.openApp }
        guard !sending else { throw WidgetRemoteIntentError.busy }
        sending = true
        defer { sending = false }
        let model = TVADBAppRuntime.model
        guard !model.isBusy, !model.connectionState.isConnecting else { throw WidgetRemoteIntentError.busy }
        // A cold-launched app may restore exactly this previously connected TV.
        // Never select another saved/last-used device on behalf of a stale tile.
        if !model.connectionState.isConnected {
            guard let device = model.deviceStore.devices.first(where: { $0.id == target.deviceID }) else {
                clear()
                throw WidgetRemoteIntentError.openApp
            }
            await model.connect(device, loadContent: false, authorizationTimeout: 8, retries: 0)
        }
        guard model.connectionState.isConnected, model.currentDevice?.id == target.deviceID,
              var current = WidgetRemoteStore.load(), current.deviceID == target.deviceID else {
            throw WidgetRemoteIntentError.openApp
        }
        // Block a competing UI connection attempt while the key is in flight.
        model.isBusy = true
        let success = await model.sendRemoteKey(command)
        model.isBusy = false
        // A manual disconnect or a device switch invalidates this result.
        if WidgetRemoteStore.load()?.connectionID == current.connectionID {
            current.lastActionSucceeded = success
            WidgetRemoteStore.save(current)
            WidgetCenter.shared.reloadTimelines(ofKind: WidgetRemoteStore.kind)
        }
        if !success { throw WidgetRemoteIntentError.failed }
    }
}
