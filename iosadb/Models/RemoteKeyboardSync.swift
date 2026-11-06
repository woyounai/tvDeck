import Foundation
import Combine

@MainActor
final class RemoteKeyboardSync: ObservableObject {
    enum State: Equatable { case connecting, waiting, ready, syncing, unsupported, conflict, failed }
    @Published private(set) var text = ""
    @Published private(set) var state: State = .connecting
    @Published private(set) var isConfirming = false
    @Published private(set) var didConfirm = false
    private var baseline: RemoteEditorSnapshot?
    private var isComposing = false
    private var hasLocalEdits = false
    private var synchronizationPaused = false
    private var awaitingEditor = false
    private var unacknowledgedWrite: String?

    var hasBoundEditor: Bool { baseline != nil }
    var needsReload: Bool { synchronizationPaused || state == .failed }
    var canEdit: Bool {
        !isConfirming && (state == .ready || state == .syncing
            || (state == .waiting && hasBoundEditor))
    }
    var canConfirm: Bool { hasBoundEditor && !isConfirming && (state == .ready || state == .syncing) }
    func edit(_ value: String) {
        if (canEdit || state == .failed || synchronizationPaused), text != value {
            text = value
            hasLocalEdits = true
        }
    }
    func compositionChanged(_ composing: Bool) { isComposing = composing }
    func requestConfirmation() { if canConfirm { isConfirming = true } }
    func fail() { state = .failed; isConfirming = false }
    func reset() {
        baseline = nil; state = .connecting; isConfirming = false; didConfirm = false
        isComposing = false; hasLocalEdits = false
        synchronizationPaused = false; awaitingEditor = false
        unacknowledgedWrite = nil
    }
    private func pause(_ reason: State) {
        synchronizationPaused = true
        isConfirming = false
        state = reason
    }

    /// A transient NONE is not a disconnected ADB session. Preserve both the
    /// bound editor and draft while polling; conflicts pause writes without
    /// closing the channel or automatically targeting an IME candidate field.
    func run(read: () async throws -> RemoteEditorReply,
             write: (RemoteEditorSnapshot, String) async throws -> RemoteEditorReply,
             confirm: (RemoteEditorSnapshot) async throws -> RemoteEditorReply) async {
        do {
            while !Task.isCancelled {
                if synchronizationPaused || isComposing {
                    try await Task.sleep(for: .milliseconds(100))
                    continue
                }
                if let previous = baseline, text != previous.text, !awaitingEditor {
                    let desired = text
                    state = .syncing
                    let reply = try await write(previous, desired)
                    guard !Task.isCancelled else { return }
                    switch reply {
                    case .state(let current) where current.token == previous.token && current.text == desired:
                        baseline = current
                        hasLocalEdits = text != desired
                        unacknowledgedWrite = nil
                        state = .ready
                    case .noEditor:
                        // Clearing can recreate the app EditText or briefly
                        // hand focus to the TV keyboard. Keep the phone editable.
                        unacknowledgedWrite = desired
                        awaitingEditor = true
                        isConfirming = false
                        state = .waiting
                    case .unsupported: pause(.unsupported)
                    default: pause(.conflict)
                    }
                    continue
                }
                if isConfirming, let previous = baseline {
                    let reply = try await confirm(previous)
                    guard !Task.isCancelled else { return }
                    guard reply == .done else { pause(.conflict); continue }
                    didConfirm = true; isConfirming = false
                    return
                }
                let reply = try await read()
                guard !Task.isCancelled else { return }
                if isComposing { continue }
                if isConfirming, let previous = baseline, reply != .state(previous) {
                    pause(.conflict); continue
                }
                switch reply {
                case .state(let current):
                    if let previous = baseline, hasLocalEdits || unacknowledgedWrite != nil {
                        if current.token == previous.token,
                           current.text == text || current.text == unacknowledgedWrite {
                            // The previous write applied but its field temporarily
                            // disappeared before the helper could acknowledge it.
                            baseline = current
                        } else if current != previous {
                            pause(.conflict); continue
                        }
                    } else {
                        baseline = current; text = current.text
                    }
                    hasLocalEdits = text != current.text
                    awaitingEditor = false
                    unacknowledgedWrite = nil
                    state = .ready
                case .noEditor:
                    awaitingEditor = true
                    isConfirming = false
                    state = .waiting
                case .unsupported:
                    if baseline != nil, hasLocalEdits || unacknowledgedWrite != nil {
                        pause(.unsupported); continue
                    }
                    baseline = nil; text = ""; state = .unsupported
                default: pause(.conflict)
                }
                try await Task.sleep(for: .milliseconds(200))
            }
        } catch is CancellationError { }
        catch { if !Task.isCancelled { fail() } }
    }
}
