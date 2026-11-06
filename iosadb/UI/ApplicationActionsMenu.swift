import SwiftUI
import UIKit

/// The same tap-to-open popup menu, hosted by a real UIKit button instead of
/// SwiftUI Menu reparenting UIKit views into a UIHostingController root.
struct ApplicationActionsMenu: UIViewRepresentable {
    enum Selection { case command(ApplicationAction), details, export }
    let app: AndroidApp
    let onSelect: (Selection) -> Void
    @Environment(\.isEnabled) private var isEnabled

    func makeCoordinator() -> Coordinator { Coordinator(onSelect: onSelect) }
    func makeUIView(context: Context) -> MenuButton {
        let button = MenuButton(type: .system)
        button.setImage(UIImage(systemName: "ellipsis.circle"), for: .normal)
        button.setPreferredSymbolConfiguration(.init(textStyle: .title3), forImageIn: .normal)
        button.showsMenuAsPrimaryAction = true
        configure(button, coordinator: context.coordinator)
        return button
    }
    func updateUIView(_ button: MenuButton, context: Context) {
        context.coordinator.onSelect = onSelect
        configure(button, coordinator: context.coordinator)
    }
    private func configure(_ button: MenuButton, coordinator: Coordinator) {
        button.isEnabled = isEnabled
        button.tintColor = UIColor(TVADBTheme.controlTint)
        button.accessibilityLabel = L10n.format("text.more_actions_for_value", app.displayName)
        // Metadata refreshes should not replace an already-open menu. Only
        // rebuild when its structure or localized titles actually change.
        let signature = "\(app.isSystem):\(L10n.locale.identifier)"
        guard coordinator.menuSignature != signature else { return }
        coordinator.menuSignature = signature
        func command(_ key: String, _ icon: String, _ selection: Selection,
                     destructive: Bool = false) -> UIAction {
            UIAction(title: L10n.string(key), image: UIImage(systemName: icon),
                     attributes: destructive ? .destructive : []) { [weak coordinator, weak button] _ in
                // Alerts and export pickers must wait for menu dismissal,
                // rather than compete with its UIKit reparenting animation.
                button?.afterMenuDismissal { coordinator?.onSelect(selection) }
            }
        }
        var actions: [UIMenuElement] = [
            command("text.launch", "play.fill", .command(.launch)),
            command("text.app_details", "info.circle", .details),
            command("text.force_stop", "stop.fill", .command(.forceStop)),
            command("text.clear_cache", "eraser", .command(.clearCache)),
            command("text.export_apk", "square.and.arrow.up", .export),
            command("text.clear_data", "externaldrive.badge.xmark", .command(.clearData), destructive: true)
        ]
        if !app.isSystem {
            actions.append(command("text.uninstall", "trash", .command(.uninstall), destructive: true))
        }
        button.menu = UIMenu(children: actions)
    }
    final class MenuButton: UIButton {
        private var menuVisible = false
        private var pendingAction: (() -> Void)?

        func afterMenuDismissal(_ action: @escaping () -> Void) {
            if menuVisible { pendingAction = action }
            else { DispatchQueue.main.async(execute: action) }
        }
        override func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
            willDisplayMenuFor configuration: UIContextMenuConfiguration,
            animator: UIContextMenuInteractionAnimating?) {
            super.contextMenuInteraction(interaction, willDisplayMenuFor: configuration, animator: animator)
            menuVisible = true
        }
        override func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
            willEndFor configuration: UIContextMenuConfiguration,
            animator: UIContextMenuInteractionAnimating?) {
            super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
            let finish = { [weak self] in
                guard let self else { return }
                self.menuVisible = false
                let action = self.pendingAction
                self.pendingAction = nil
                if let action { DispatchQueue.main.async(execute: action) }
            }
            if let animator { animator.addCompletion(finish) }
            else { finish() }
        }
    }
    final class Coordinator {
        var onSelect: (Selection) -> Void
        var menuSignature: String?
        init(onSelect: @escaping (Selection) -> Void) { self.onSelect = onSelect }
    }
}
