import Foundation

struct ShellCommandPreset: Identifiable {
    var category: Category
    var titleKey: String
    var command: String
    var id: String { command }

    enum Category: String, CaseIterable, Identifiable {
        case device, network, apps, performance, logs, mouse
        var id: String { rawValue }
        var titleKey: String { "text.shell_category_" + rawValue }
    }
}

enum ShellCommandCatalog {
    static let commands: [ShellCommandPreset] = [
        .init(category: .device, titleKey: "text.shell_preset_device_summary", command: "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk"),
        .init(category: .device, titleKey: "text.shell_preset_properties", command: "getprop"),
        .init(category: .device, titleKey: "text.shell_preset_architecture", command: "getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist"),
        .init(category: .device, titleKey: "text.shell_preset_uptime", command: "uptime"),
        .init(category: .device, titleKey: "text.shell_preset_display", command: "wm size; wm density"),
        .init(category: .network, titleKey: "text.shell_preset_addresses", command: "ip addr show"),
        .init(category: .network, titleKey: "text.shell_preset_routes", command: "ip route"),
        .init(category: .network, titleKey: "text.shell_preset_connectivity", command: "dumpsys connectivity | head -n 100"),
        .init(category: .network, titleKey: "text.shell_preset_proxy", command: "settings get global http_proxy"),
        .init(category: .apps, titleKey: "text.shell_preset_user_apps", command: "pm list packages -3"),
        .init(category: .apps, titleKey: "text.shell_preset_system_apps", command: "pm list packages -s"),
        .init(category: .apps, titleKey: "text.shell_preset_apk_paths", command: "pm list packages -f"),
        .init(category: .apps, titleKey: "text.shell_preset_foreground", command: "dumpsys window windows | grep -E \"mCurrentFocus|mFocusedApp\""),
        .init(category: .apps, titleKey: "text.shell_preset_activities", command: "dumpsys activity activities | head -n 100"),
        .init(category: .apps, titleKey: "text.shell_preset_services", command: "dumpsys activity services | head -n 100"),
        .init(category: .performance, titleKey: "text.shell_preset_memory", command: "dumpsys meminfo | head -n 100"),
        .init(category: .performance, titleKey: "text.shell_preset_storage", command: "df -h"),
        .init(category: .performance, titleKey: "text.shell_preset_cpu", command: "dumpsys cpuinfo | head -n 60"),
        .init(category: .performance, titleKey: "text.shell_preset_processes", command: "top -b -n 1 | head -n 40"),
        .init(category: .performance, titleKey: "text.shell_preset_battery", command: "dumpsys battery"),
        .init(category: .logs, titleKey: "text.shell_preset_recent_logs", command: "logcat -d -t 200"),
        .init(category: .logs, titleKey: "text.shell_preset_error_logs", command: "logcat -d -t 200 '*:E'"),
        .init(category: .mouse, titleKey: "text.shell_preset_permissions", command: "id; getenforce; ls -lZ /dev/uhid /dev/uinput"),
        .init(category: .mouse, titleKey: "text.shell_preset_mouse_device", command: "dumpsys input | grep -A 24 -B 2 'TVADB Virtual Mouse'"),
        .init(category: .mouse, titleKey: "text.shell_preset_input_devices", command: "getevent -lp"),
        .init(category: .mouse, titleKey: "text.shell_preset_mouse_processes", command: "ps -A | grep -E 'tvadb|uinput'"),
        .init(category: .mouse, titleKey: "text.shell_preset_input_methods", command: "ime list -s"),
        .init(category: .mouse, titleKey: "text.shell_preset_current_ime", command: "settings get secure default_input_method"),
    ]
}

/// Quick insertion at the end of the editor. Existing quoted arguments are
/// preserved; no attempt is made to interpret or execute shell syntax.
enum ShellCommandKeywords {
    static let values = ["pm", "am", "dumpsys", "getprop", "input", "settings", "logcat", "ls", "cat", "grep", "head", "tail", "|", "-n", "-s"]

    static func inserting(_ keyword: String, into command: String) -> String {
        guard values.contains(keyword) else { return command }
        guard !command.isEmpty else { return keyword + " " }
        guard let last = command.last, !last.isWhitespace else { return command + keyword + " " }
        if let start = command.lastIndex(where: { $0.isWhitespace }) {
            let fragment = String(command[command.index(after: start)...])
            if fragment.allSatisfy({ $0.isLetter || $0 == "-" }), keyword.hasPrefix(fragment) {
                return String(command[...start]) + keyword + " "
            }
        } else if command.allSatisfy({ $0.isLetter || $0 == "-" }), keyword.hasPrefix(command) {
            return keyword + " "
        }
        return command + " " + keyword + " "
    }
}
