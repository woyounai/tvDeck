import SwiftUI

struct UsageNoticeView: View {
    var readOnly = false
    @Environment(\.dismiss) private var dismiss
    @State private var region = UsageConsent.Region.system
    @Environment(\.scenePhase) private var scenePhase
    @State private var language = L10n.locale.language.languageCode?.identifier == "zh" ? "zh-Hans" : "en"
    @State private var acknowledged = false
    @State private var declined = false
    @State private var saveFailed = false

    private func text(_ key: String) -> String {
        let bundle = Bundle.main.path(forResource: language, ofType: "lproj")
            .flatMap(Bundle.init(path:)) ?? .main
        return bundle.localizedString(forKey: key, value: nil, table: nil)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(text("legal_intro"))
                    Text("\(text("legal_version")) · \(UsageConsent.version)")
                        .font(.caption).foregroundStyle(.secondary)
                    Picker(text("legal_language"), selection: $language) {
                        Text(verbatim: "简体中文").tag("zh-Hans")
                        Text(verbatim: "English").tag("en")
                    }
                    section("legal_scope_title", "legal_scope")
                    section("legal_risk_title", "legal_risk")
                    section("legal_security_title", "legal_security")
                    section("legal_compatibility_title", "legal_compatibility")
                    section("legal_rights_title", region == .mainland ? "legal_rights_cn" : "legal_rights_intl")
                    section("legal_acceptance_title", "legal_acceptance")
                    Link(text("legal_feedback"), destination: URL(string: "https://github.com/woyounai/tvDeck/issues")!)
                    if readOnly, let record = UsageConsent.savedRecord {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(text("legal_record_hint")).font(.caption)
                            Text("\(record.version) · \(text(record.region.titleKey)) · \(record.language)")
                            Text(record.acceptedAt.formatted(date: .abbreviated, time: .shortened))
                        }
                        .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: 740, alignment: .leading)
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .safeAreaInset(edge: .bottom) {
                if !readOnly {
                    VStack(spacing: 12) {
                        Toggle(text("legal_ack"), isOn: $acknowledged)
                        if declined { Text(text("legal_declined")).font(.caption).foregroundStyle(.secondary) }
                        if saveFailed { Text(text("legal_store_failed")).font(.caption).foregroundStyle(.red) }
                        HStack {
                            Button(text("legal_decline")) {
                                acknowledged = false
                                declined = true
                            }
                            .buttonStyle(.bordered)
                            Spacer()
                            Button(text("legal_agree")) {
                                do { try UsageConsent.accept(region: region, language: language) }
                                catch { saveFailed = true }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(!acknowledged)
                        }
                    }
                    .frame(maxWidth: 740)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 20)
                    .frame(maxWidth: .infinity)
                    .background(.regularMaterial)
                }
            }
            .navigationTitle(text("legal_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if readOnly {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(text("text.close")) { dismiss() }
                    }
                }
            }
        }
        .padding(8)
        .onChange(of: region) { _, _ in acknowledged = false }
        .onChange(of: language) { _, _ in acknowledged = false }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { region = .system }
        }
    }

    private func section(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(text(title)).font(.headline)
            Text(text(body))
        }
    }
}
