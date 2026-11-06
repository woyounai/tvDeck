import SwiftUI
import UIKit

enum TVADBVisualTheme: String, CaseIterable, Identifiable {
    static let storageKey = "tvadb.visualTheme"
    case standard, cyberpunk
    var id: String { rawValue }
    var titleKey: String { self == .cyberpunk ? "text.theme_cyberpunk" : "text.theme_standard" }
    static var selected: Self {
        UserDefaults.standard.string(forKey: storageKey).flatMap(Self.init(rawValue:)) ?? .cyberpunk
    }
}
private struct TVADBVisualThemeKey: EnvironmentKey {
    static let defaultValue = TVADBVisualTheme.cyberpunk
}
extension EnvironmentValues {
    var tvadbVisualTheme: TVADBVisualTheme {
        get { self[TVADBVisualThemeKey.self] }
        set { self[TVADBVisualThemeKey.self] = newValue }
    }
}

struct TVADBBackdrop: View {
    @Environment(\.tvadbVisualTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        ZStack {
            TVADBTheme.canvas
            if theme == .cyberpunk {
                LinearGradient(colors: [TVADBTheme.magenta.opacity(colorScheme == .dark ? 0.13 : 0.055), .clear, TVADBTheme.cyan.opacity(0.06)],
                               startPoint: .topTrailing, endPoint: .bottomLeading)
                Canvas { context, size in
                    var grid = Path()
                    for x in stride(from: CGFloat(0), through: size.width, by: 32) {
                        grid.move(to: CGPoint(x: x, y: 0)); grid.addLine(to: CGPoint(x: x, y: size.height))
                    }
                    for y in stride(from: CGFloat(0), through: size.height, by: 32) {
                        grid.move(to: CGPoint(x: 0, y: y)); grid.addLine(to: CGPoint(x: size.width, y: y))
                    }
                    context.stroke(grid, with: .color(TVADBTheme.cyan.opacity(colorScheme == .dark ? 0.055 : 0.045)), lineWidth: 0.5)
                }
            }
        }.ignoresSafeArea().allowsHitTesting(false).accessibilityHidden(true)
    }
}

private struct TVADBScreenBackground: ViewModifier {
    @Environment(\.tvadbVisualTheme) private var theme
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background { TVADBBackdrop() }
            .tint(TVADBTheme.controlTint)
            // Let the full-bleed backdrop reach the status bar and navigation
            // title instead of covering its grid/glow with an opaque bar.
            .toolbarBackground(TVADBTheme.canvas, for: .tabBar)
            .toolbarBackground(theme == .cyberpunk ? .hidden : .automatic, for: .navigationBar)
            .toolbarBackground(theme == .cyberpunk ? .visible : .automatic, for: .tabBar)
    }
}
extension View {
    func tvadbScreenBackground() -> some View { modifier(TVADBScreenBackground()) }
}
