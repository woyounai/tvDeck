import SwiftUI
import UIKit

struct RemoteKeyboardPanel: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var sync = RemoteKeyboardSync()
    @State private var retryID = UUID()

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text("text.keyboard_sync_hint")
                    .font(.footnote).foregroundStyle(.secondary)
                Label(LocalizedStringKey(statusKey), systemImage: statusIcon)
                    .font(.footnote)
                SynchronizedTextEditor(
                    text: Binding(get: { sync.text }, set: { sync.edit($0) }),
                    isEnabled: sync.canEdit,
                    onCompositionChanged: { sync.compositionChanged($0) }
                )
                .frame(minHeight: 100, maxHeight: 170)
                .padding(10)
                .background(Color(.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 14))
                .accessibilityLabel("text.tv_keyboard")
                Button {
                    // Commit the phone's marked composition before flushing.
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                                     to: nil, from: nil, for: nil)
                    sync.requestConfirmation()
                } label: {
                    HStack {
                        if sync.isConfirming { ProgressView().tint(.white) }
                        else { Image(systemName: "checkmark") }
                        Text("text.keyboard_confirm")
                    }.frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(TVADBTheme.primaryButtonTint).controlSize(.large)
                .disabled(!sync.canConfirm)
                if sync.needsReload {
                    Button("text.keyboard_reload") { retryID = UUID() }
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .tvadbScreenBackground()
            .navigationTitle("text.tv_keyboard")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("text.close") { dismiss() }.disabled(sync.isConfirming)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(sync.isConfirming)
        .onChange(of: sync.didConfirm) { _, confirmed in if confirmed { dismiss() } }
        .task(id: retryID) {
            sync.reset()
            do {
                let session = try await model.openRemoteTextSession()
                await sync.run(
                    read: { try await session.readEditor() },
                    write: { try await session.setText($0, text: $1) },
                    confirm: { try await session.confirm($0) }
                )
                await session.close()
            } catch {
                if !Task.isCancelled { sync.fail() }
            }
        }
    }

    private var statusKey: String {
        switch sync.state {
        case .connecting: "text.keyboard_connecting"
        case .waiting: sync.hasBoundEditor ? "text.keyboard_waiting_retained" : "text.keyboard_waiting"
        case .ready: "text.keyboard_synced"
        case .syncing: "text.keyboard_syncing"
        case .unsupported: "text.keyboard_sync_unsupported"
        case .conflict: "text.keyboard_sync_conflict"
        case .failed: "text.keyboard_sync_failed"
        }
    }
    private var statusIcon: String {
        switch sync.state {
        case .ready: "checkmark.circle"
        case .connecting, .syncing: "arrow.triangle.2.circlepath"
        default: "info.circle"
        }
    }
}

/// Do not publish provisional pinyin/marked text. Remote refreshes must not
/// overwrite an in-progress phone IME composition or disturb the selection.
private struct SynchronizedTextEditor: UIViewRepresentable {
    @Binding var text: String
    var isEnabled: Bool
    var onCompositionChanged: (Bool) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator
        view.font = .preferredFont(forTextStyle: .body)
        view.backgroundColor = .clear
        view.autocorrectionType = .no
        view.autocapitalizationType = .none
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.parent = self
        if view.markedTextRange == nil, view.text != text { view.text = text }
        view.isEditable = isEnabled
    }
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: SynchronizedTextEditor
        init(parent: SynchronizedTextEditor) { self.parent = parent }
        func textViewDidChange(_ view: UITextView) {
            let composing = view.markedTextRange != nil
            parent.onCompositionChanged(composing)
            if !composing { parent.text = view.text }
        }
        func textViewDidEndEditing(_ view: UITextView) {
            parent.onCompositionChanged(false)
            parent.text = view.text
        }
    }
}
