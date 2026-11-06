import AppIntents

/// LiveActivityIntent executes in the containing app process, reusing its ADB
/// identity/transport without exporting private keys to the widget extension.
struct TVRemoteWidgetIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "widget.intent_title"
    static var openAppWhenRun: Bool = false
    static var isDiscoverable: Bool = false

    @Parameter(title: "widget.connection") var connection: String
    @Parameter(title: "widget.command") var command: Int

    init() {}
    init(connection: String, command: Int) {
        self.connection = connection
        self.command = command
    }
    func perform() async throws -> some IntentResult {
        try await execute()
        return .result()
    }
    private func execute() async throws {
#if TVADB_WIDGET
        // The system routes LiveActivityIntent to the app, never send from an
        // extension with a different ADB identity if that routing is unavailable.
        throw WidgetRemoteIntentError.openApp
#else
        try await TVADBWidgetController.send(connection: connection, command: command)
#endif
    }
}

enum WidgetRemoteIntentError: Error, CustomLocalizedStringResourceConvertible {
    case openApp, busy, failed
    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .openApp: "widget.open_to_connect"
        case .busy: "widget.busy"
        case .failed: "widget.failed"
        }
    }
}
