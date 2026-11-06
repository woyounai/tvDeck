import SwiftUI
import UIKit

/// Apply the preference at the window boundary so UIKit controllers and
/// already-presented SwiftUI sheets use the same traits as the main page.
struct TVADBAppearanceController: UIViewRepresentable {
    let appearance: AppAppearance

    func makeUIView(context: Context) -> AppearanceView {
        let view = AppearanceView()
        view.isUserInteractionEnabled = false
        view.accessibilityElementsHidden = true
        view.style = interfaceStyle
        return view
    }

    func updateUIView(_ view: AppearanceView, context: Context) {
        view.style = interfaceStyle
        view.apply()
    }

    private var interfaceStyle: UIUserInterfaceStyle {
        switch appearance {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }

    final class AppearanceView: UIView {
        var style: UIUserInterfaceStyle = .unspecified
        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }
        func apply() {
            guard let window, window.overrideUserInterfaceStyle != style else { return }
            UIView.performWithoutAnimation {
                // .unspecified clears our previous override and follows the
                // real system style, rather than a cached sheet color scheme.
                window.overrideUserInterfaceStyle = style
            }
        }
    }
}
