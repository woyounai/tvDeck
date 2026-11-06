import SwiftUI

struct ShellView: View {
    @EnvironmentObject private var model: AppModel
    @State private var command = ""
    @State private var output = L10n.string("text.tvadb_shell_is_ready")
    @State private var history: [String] = []
    @State private var favorites: [String] = []
    @FocusState private var commandFieldIsFocused: Bool

    @State private var showCommonCommands = false

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(output)
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .textSelection(.enabled)
                    .padding()
            }
            .scrollDismissesKeyboard(.interactively)
            .onTapGesture {
                commandFieldIsFocused = false
            }
            .background(Color.black)
            .foregroundStyle(TVADBTheme.terminalInk)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                commandBar
            }
            .navigationTitle("text.adb_shell")
            .toolbar {
                TVADBDisconnectToolbarItem()
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("text.common_commands") {
                        commandFieldIsFocused = false
                        showCommonCommands = true
                    }
                    Menu {
                        if favorites.isEmpty {
                            Text("text.no_favorite_commands")
                        } else {
                            Section("text.favorite_commands") {
                                ForEach(favorites, id: \.self) { item in
                                    Button(item) {
                                        command = item
                                    }
                                }
                            }
                        }
                    } label: { Image(systemName: "star") }
                    Menu {
                        if history.isEmpty { Text("text.no_history") }
                        ForEach(history, id: \.self) { item in Button(item) { command = item } }
                    } label: { Image(systemName: "clock") }
                    Button { output = "" } label: { Image(systemName: "trash") }
                }
            }
            .sheet(isPresented: $showCommonCommands, onDismiss: {
                if !command.isEmpty { commandFieldIsFocused = true }
            }) {
                ShellCommandPicker { preset in
                    command = preset.command
                    showCommonCommands = false
                }
            }
            .onAppear { loadHistory() }
        }
    }

    private var commandBar: some View {
        VStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                Text("text.shell_keywords").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 62), spacing: 6)], spacing: 6) {
                    ForEach(ShellCommandKeywords.values, id: \.self) { keyword in
                        Button {
                            command = ShellCommandKeywords.inserting(keyword, into: command)
                            commandFieldIsFocused = true
                        } label: {
                            Text(keyword).font(.system(.caption, design: .monospaced).weight(.medium))
                                .frame(maxWidth: .infinity, minHeight: 30)
                                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L10n.format("text.insert_shell_keyword", keyword))
                    }
                }
            }
            .padding(10)
            .background(TVADBTheme.canvas, in: RoundedRectangle(cornerRadius: 12))
            HStack(spacing: 10) {
                TextField("text.enter_a_shell_command", text: $command)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .focused($commandFieldIsFocused)
                    .onSubmit {
                        commandFieldIsFocused = false
                    }
                Button {
                    toggleFavorite(command)
                } label: {
                    Image(
                        systemName: favorites.contains(
                            command.trimmingCharacters(in: .whitespacesAndNewlines)
                        ) ? "star.fill" : "star"
                    )
                }
                .buttonStyle(.bordered)
                .disabled(command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityLabel("text.toggle_command_favorite")
                Button(action: run) { Image(systemName: "paperplane.fill") }
                    .buttonStyle(.borderedProminent).tint(TVADBTheme.primaryButtonTint)
                    .disabled(command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isBusy)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.bar)
        .foregroundStyle(.primary)
    }

    private func run() {
        let value = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        command = ""
        output += "\n$ \(value)\n"
        history.removeAll { $0 == value }
        history.insert(value, at: 0)
        if history.count > 50 { history.removeLast(history.count - 50) }
        UserDefaults.standard.set(history, forKey: "tvadb.shellHistory")
        Task {
            if let response = await model.executeShell(value) {
                output += response
                if !response.hasSuffix("\n") { output += "\n" }
            }
        }
    }

    private func loadHistory() {
        history = UserDefaults.standard.stringArray(forKey: "tvadb.shellHistory") ?? []
        favorites = UserDefaults.standard.stringArray(
            forKey: "tvadb.shellFavorites"
        ) ?? []
    }

    private func toggleFavorite(_ value: String) {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        if let index = favorites.firstIndex(of: normalized) {
            favorites.remove(at: index)
        } else {
            favorites.insert(normalized, at: 0)
        }
        UserDefaults.standard.set(favorites, forKey: "tvadb.shellFavorites")
    }
}

private struct ShellCommandPicker: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var category = ShellCommandPreset.Category.device
    @State private var page = 0
    var select: (ShellCommandPreset) -> Void

    private var commands: [ShellCommandPreset] {
        ShellCommandCatalog.commands.filter { $0.category == category }
    }
    private var pageSize: Int { verticalSizeClass == .compact ? 2 : 6 }
    private var pageCount: Int { max(1, (commands.count + pageSize - 1) / pageSize) }
    private var visibleCommands: [ShellCommandPreset] {
        Array(commands.dropFirst(min(page, pageCount - 1) * pageSize).prefix(pageSize))
    }
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                if verticalSizeClass != .compact {
                    Text("text.shell_presets_hint").font(.footnote).foregroundStyle(.secondary)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 8) {
                        ForEach(ShellCommandPreset.Category.allCases) { value in
                            Button { category = value; page = 0 } label: {
                                Text(LocalizedStringKey(value.titleKey))
                                    .font(.subheadline.weight(.medium))
                                    .frame(maxWidth: .infinity, minHeight: 34)
                                    .background(category == value ? TVADBTheme.controlTint.opacity(0.18) : Color.secondary.opacity(0.08),
                                                in: RoundedRectangle(cornerRadius: 9))
                            }.buttonStyle(.plain)
                            .accessibilityAddTraits(category == value ? .isSelected : [])
                        }
                    }
                } else {
                    HStack {
                        Button { moveCategory(-1) } label: { Image(systemName: "chevron.left") }
                            .accessibilityLabel("text.shell_previous_category")
                        Spacer()
                        Text(LocalizedStringKey(category.titleKey)).font(.subheadline.bold())
                        Spacer()
                        Button { moveCategory(1) } label: { Image(systemName: "chevron.right") }
                            .accessibilityLabel("text.shell_next_category")
                    }.buttonStyle(.bordered)
                }
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    ForEach(visibleCommands) { preset in
                        Button { select(preset) } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(LocalizedStringKey(preset.titleKey)).font(.subheadline.weight(.medium)).lineLimit(2)
                                Text(preset.command).font(.system(.caption2, design: .monospaced))
                                    .foregroundStyle(.secondary).lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
                            .padding(10)
                            .background(TVADBTheme.canvas, in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.primary)
                        .accessibilityLabel(Text(L10n.string(preset.titleKey) + ", " + preset.command))
                    }
                }
                if pageCount > 1 {
                    HStack {
                        Button { page = max(0, page - 1) } label: { Image(systemName: "chevron.left") }
                            .disabled(page == 0).accessibilityLabel("text.shell_previous_page")
                        Spacer()
                        Text("\(min(page + 1, pageCount)) / \(pageCount)").font(.caption).monospacedDigit()
                        Spacer()
                        Button { page = min(pageCount - 1, page + 1) } label: { Image(systemName: "chevron.right") }
                            .disabled(page >= pageCount - 1).accessibilityLabel("text.shell_next_page")
                    }.buttonStyle(.bordered)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .tvadbScreenBackground()
            .navigationTitle("text.common_commands")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("text.close") { dismiss() } } }
            .onChange(of: verticalSizeClass) { _, _ in page = 0 }
        }
        .presentationDetents([.large])
    }
    private func moveCategory(_ step: Int) {
        let categories = ShellCommandPreset.Category.allCases
        guard let index = categories.firstIndex(of: category) else { return }
        category = categories[(index + step + categories.count) % categories.count]
        page = 0
    }
}
