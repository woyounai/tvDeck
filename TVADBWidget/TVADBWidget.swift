import SwiftUI
import WidgetKit

struct TVRemoteEntry: TimelineEntry {
    let date: Date
    let state: WidgetRemoteState?
}

struct TVRemoteProvider: TimelineProvider {
    func placeholder(in context: Context) -> TVRemoteEntry {
        TVRemoteEntry(date: .now, state: WidgetRemoteState(connectionID: UUID(), deviceID: UUID(),
            name: "Living Room TV", connectedAt: .now))
    }
    func getSnapshot(in context: Context, completion: @escaping (TVRemoteEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : TVRemoteEntry(date: .now, state: WidgetRemoteStore.load()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<TVRemoteEntry>) -> Void) {
        // No LAN scans/polling in a timeline. Connection changes and actions
        // explicitly reload it; the date is a connection record, not a live ping.
        completion(Timeline(entries: [TVRemoteEntry(date: .now, state: WidgetRemoteStore.load())], policy: .never))
    }
}

struct TVRemoteWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TVRemoteEntry
    private let cyan = Color(red: 0.19, green: 0.91, blue: 0.96)
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "tv").foregroundStyle(cyan)
                Text(entry.state?.name ?? String(localized: "widget.title"))
                    .font(.subheadline.bold()).lineLimit(1)
                Spacer(minLength: 0)
                Link(destination: URL(string: "tvadb://remote")!) {
                    Image(systemName: "arrow.up.forward.app").foregroundStyle(cyan)
                }.accessibilityLabel("widget.open_app")
            }
            if let state = entry.state {
                HStack(alignment: .center, spacing: 16) {
                    VStack(spacing: 3) {
                        key(.up, "chevron.up", "widget.up", state)
                        HStack(spacing: 3) {
                            key(.left, "chevron.left", "widget.left", state)
                            key(.ok, "checkmark", "widget.ok", state, primary: true)
                            key(.right, "chevron.right", "widget.right", state)
                        }
                        key(.down, "chevron.down", "widget.down", state)
                    }
                    Spacer(minLength: 0)
                    VStack(spacing: 6) {
                        HStack(spacing: 6) {
                            key(.back, "arrow.uturn.backward", "widget.back", state)
                            key(.home, "house", "widget.home", state)
                            key(.power, "power", "widget.power", state)
                        }
                        HStack(spacing: 6) {
                            key(.volumeDown, "speaker.minus", "widget.volume_down", state)
                            key(.mute, "speaker.slash", "widget.mute", state)
                            key(.volumeUp, "speaker.plus", "widget.volume_up", state)
                        }
                        if family == .systemLarge {
                            HStack(spacing: 6) {
                                key(.menu, "line.3.horizontal", "widget.menu", state)
                                key(.playPause, "playpause", "widget.play_pause", state)
                                key(.input, "rectangle.on.rectangle", "widget.input", state)
                            }
                        }
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                if family == .systemLarge || state.lastActionSucceeded == false {
                    Text(state.lastActionSucceeded == false ? "widget.failed" : "widget.connected_record")
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            } else {
                Spacer(minLength: 0)
                Text("widget.open_to_connect").font(.callout).foregroundStyle(.secondary)
                Link("widget.open_app", destination: URL(string: "tvadb://remote")!)
                    .font(.subheadline.bold()).foregroundStyle(cyan)
                Spacer(minLength: 0)
            }
        }
        .foregroundStyle(.white)
        .containerBackground(for: .widget) {
            LinearGradient(colors: [Color(red: 0.10, green: 0.06, blue: 0.20),
                                    Color(red: 0.035, green: 0.043, blue: 0.085)],
                           startPoint: .topTrailing, endPoint: .bottomLeading)
        }
        .widgetURL(URL(string: "tvadb://remote"))
    }
    private func key(_ command: WidgetRemoteCommand, _ image: String, _ title: LocalizedStringKey,
                     _ state: WidgetRemoteState, primary: Bool = false) -> some View {
        Button(intent: TVRemoteWidgetIntent(connection: state.connectionID.uuidString, command: command.rawValue)) {
            Image(systemName: image)
                .font(.system(size: family == .systemLarge ? 20 : 14, weight: .semibold))
                .frame(width: family == .systemLarge ? 36 : 30, height: family == .systemLarge ? 44 : 25)
                .foregroundStyle(primary ? .white : cyan)
                .background(primary ? Color.purple.opacity(0.8) : cyan.opacity(0.10), in: RoundedRectangle(cornerRadius: 9))
                .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(cyan.opacity(0.30), lineWidth: 0.5))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

@main
struct TVADBRemoteWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetRemoteStore.kind, provider: TVRemoteProvider()) { entry in
            TVRemoteWidgetView(entry: entry)
        }
        .configurationDisplayName("widget.title")
        .description("widget.description")
        .supportedFamilies([.systemMedium, .systemLarge])
        .disfavoredLocations([.iPhoneWidgetsOnMac], for: [.systemMedium, .systemLarge])
    }
}
