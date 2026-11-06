import SwiftUI
import UniformTypeIdentifiers
import UIKit

enum TVADBTheme {
    private static func adaptive(dark: UIColor, light: UIColor) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }
    static var accent: Color {
        guard TVADBVisualTheme.selected == .cyberpunk else { return Color(red: 0.08, green: 0.45, blue: 0.97) }
        return adaptive(dark: UIColor(red: 0.19, green: 0.91, blue: 0.96, alpha: 1),
                        light: UIColor(red: 0.30, green: 0.18, blue: 0.68, alpha: 1))
    }
    static var deepBlue: Color {
        TVADBVisualTheme.selected == .cyberpunk
            ? adaptive(dark: UIColor(red: 0.68, green: 0.55, blue: 0.98, alpha: 1), light: UIColor(red: 0.35, green: 0.14, blue: 0.71, alpha: 1))
            : Color(red: 0.04, green: 0.28, blue: 0.70)
    }
    static var cyan: Color {
        TVADBVisualTheme.selected == .cyberpunk
            ? adaptive(dark: UIColor(red: 0.12, green: 0.89, blue: 0.96, alpha: 1), light: UIColor(red: 0.0, green: 0.40, blue: 0.52, alpha: 1))
            : Color(red: 0, green: 0.66, blue: 0.88)
    }
    static var magenta: Color { Color(red: 0.91, green: 0.16, blue: 0.61) }
    static var controlTint: Color { accent }
    static var primaryButtonTint: Color { TVADBVisualTheme.selected == .cyberpunk ? Color(red: 0.42, green: 0.21, blue: 0.79) : accent }
    static var terminalInk: Color { TVADBVisualTheme.selected == .cyberpunk ? Color(red: 0.38, green: 0.97, blue: 0.85) : Color(red: 0, green: 0.66, blue: 0.88) }
    static var status: Color {
        TVADBVisualTheme.selected == .cyberpunk
            ? adaptive(dark: UIColor(red: 0.30, green: 0.95, blue: 0.69, alpha: 1), light: UIColor(red: 0.02, green: 0.39, blue: 0.30, alpha: 1)) : cyan
    }
    static var canvas: Color {
        TVADBVisualTheme.selected == .cyberpunk
            ? adaptive(dark: UIColor(red: 0.035, green: 0.043, blue: 0.085, alpha: 1), light: UIColor(red: 0.95, green: 0.95, blue: 0.985, alpha: 1))
            : Color(.systemGroupedBackground)
    }
    static var panel: Color {
        adaptive(dark: UIColor(red: 0.065, green: 0.075, blue: 0.135, alpha: 0.95),
                 light: UIColor(red: 1, green: 1, blue: 1, alpha: 0.94))
    }
    static var cornerRadius: CGFloat { TVADBVisualTheme.selected == .cyberpunk ? 18 : 22 }
    static var accentGradient: LinearGradient {
        LinearGradient(colors: TVADBVisualTheme.selected == .cyberpunk
            ? [Color(red: 0.48, green: 0.18, blue: 0.72), Color(red: 0.14, green: 0.32, blue: 0.70)]
            : [accent, cyan], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

struct TVADBPressableStyle: ButtonStyle {
    var scale: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.82 : 1)
            .animation(.spring(response: 0.24, dampingFraction: 0.72), value: configuration.isPressed)
    }
}

struct TVADBPanel<Content: View>: View {
    @Environment(\.tvadbVisualTheme) private var theme
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .background {
                if theme == .cyberpunk {
                    RoundedRectangle(cornerRadius: TVADBTheme.cornerRadius).fill(TVADBTheme.panel)
                } else {
                    RoundedRectangle(cornerRadius: TVADBTheme.cornerRadius).fill(.regularMaterial)
                }
            }
            .overlay {
                RoundedRectangle(cornerRadius: TVADBTheme.cornerRadius)
                    .strokeBorder(LinearGradient(colors: [TVADBTheme.accent.opacity(theme == .cyberpunk ? 0.45 : 0.10), TVADBTheme.magenta.opacity(theme == .cyberpunk ? 0.20 : 0)], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 0.8)
            }
            .shadow(color: TVADBTheme.accent.opacity(theme == .cyberpunk ? 0.09 : 0.04), radius: 14, y: 5)
    }
}

struct TVADBBlockingProgressOverlay: View {
    let message: String
    var progress: Double?

    var body: some View {
        ZStack {
            Color.black.opacity(0.12)

            TVADBPanel {
                VStack(spacing: 13) {
                    if let progress {
                        ProgressView(value: progress)
                            .frame(width: 128)
                    } else {
                        ProgressView()
                            .controlSize(.large)
                    }
                    Text(message)
                        .font(.subheadline.weight(.semibold))
                        .multilineTextAlignment(.center)
                }
                .frame(minWidth: 190)
                .padding(.horizontal, 24)
                .padding(.vertical, 20)
            }
            .padding(32)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(message))
        .transition(.opacity.combined(with: .scale(scale: 0.97)))
    }
}

/// Uses UIKit's document-picker import path instead of SwiftUI's
/// `fileImporter`. `asCopy: true` asks Files/iCloud/third-party providers to
/// finish downloading and copy the selected items locally before the delegate
/// callback fires.
struct TVADBFileImportPicker: UIViewControllerRepresentable {
    let contentTypes: [UTType]
    let allowsMultipleSelection: Bool
    let onPick: ([URL]) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onCancel: onCancel)
    }

    func makeUIViewController(
        context: Context
    ) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: contentTypes,
            asCopy: true
        )
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = allowsMultipleSelection
        picker.shouldShowFileExtensions = true
        return picker
    }

    func updateUIViewController(
        _ uiViewController: UIDocumentPickerViewController,
        context: Context
    ) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: ([URL]) -> Void
        private let onCancel: () -> Void

        init(
            onPick: @escaping ([URL]) -> Void,
            onCancel: @escaping () -> Void
        ) {
            self.onPick = onPick
            self.onCancel = onCancel
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard !urls.isEmpty else {
                onCancel()
                return
            }
            onPick(urls)
        }

        func documentPickerWasCancelled(
            _ controller: UIDocumentPickerViewController
        ) {
            onCancel()
        }
    }
}

struct TVADBInstallPackagePicker: View {
    let onPick: (URL) -> Void
    let onCancel: () -> Void

    var body: some View {
        TVADBFileImportPicker(
            contentTypes: [.data],
            allowsMultipleSelection: false,
            onPick: { urls in
                guard let url = urls.first else {
                    onCancel()
                    return
                }
                onPick(url)
            },
            onCancel: onCancel
        )
    }
}

struct TVADBDisconnectToolbarItem: ToolbarContent {
    var placement: ToolbarItemPlacement = .topBarLeading
    @EnvironmentObject private var model: AppModel

    var body: some ToolbarContent {
        ToolbarItem(placement: placement) {
            Button(role: .destructive) {
                model.disconnect()
            } label: {
                Color.red
                    .frame(width: 30, height: 30)
                    .mask(Text("⛓️‍💥").font(.system(size: 24)))
                    .accessibilityLabel("text.disconnect")
            }
            .buttonStyle(.plain)
        }
    }
}

struct TVADBStatusBanner: View {
    let message: String
    let isError: Bool
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isError ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .font(.title3)
                .foregroundStyle(isError ? Color.red : TVADBTheme.status)
            Text(message)
                .font(.subheadline.weight(.medium))
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.caption.bold())
                    .frame(width: 30, height: 30)
                    .background(.thinMaterial, in: Circle())
            }
            .buttonStyle(TVADBPressableStyle())
            .accessibilityLabel("text.close")
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder((isError ? Color.red : TVADBTheme.status).opacity(0.2))
        }
        .shadow(color: .black.opacity(0.16), radius: 20, y: 10)
    }
}
