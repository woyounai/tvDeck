import Foundation

@main struct SyncAndPreviewTests {
    @MainActor static func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(4))
        while !condition() {
            precondition(ContinuousClock.now < deadline, "Timed out")
            try await Task.sleep(for: .milliseconds(10))
        }
    }
    @MainActor static func main() async throws {
        let connectedAt = ContinuousClock.now
        let activity = ADBActivity(now: connectedAt)
        assert(!activity.heartbeatDue(now: connectedAt.advanced(by: .seconds(59))))
        assert(activity.heartbeatDue(now: connectedAt.advanced(by: .seconds(60))))
        activity.record(now: connectedAt.advanced(by: .seconds(50)))
        activity.record(now: connectedAt.advanced(by: .seconds(10)))
        assert(!activity.heartbeatDue(now: connectedAt.advanced(by: .seconds(109))))
        assert(activity.heartbeatDue(now: connectedAt.advanced(by: .seconds(110))))

        assert(RemoteControlOrder.normalized("gestures,buttons") == ["screen", "mouse"])
        assert(RemoteControlOrder.normalized("mouse,gestures,screen,mouse,buttons") == ["mouse", "screen"])
        assert(RemoteControlOrder.normalized(nil) == ["screen", "mouse"])
        assert(TVRemoteKey.left.rawValue == 21 && TVRemoteKey.right.rawValue == 22)
        assert(TVRemoteKey.zero.rawValue == 7 && TVRemoteKey.nine.rawValue == 16)
        assert(TVRemoteKey.input.rawValue == 178 && TVRemoteKey.channelUp.rawValue == 166)
        assert(ShellCommandKeywords.inserting("dumpsys", into: "dum") == "dumpsys ")
        assert(ShellCommandKeywords.inserting("grep", into: "pm list packages | gr") == "pm list packages | grep ")
        assert(ShellCommandKeywords.inserting("|", into: "pm list packages") == "pm list packages | ")
        assert(ShellCommandKeywords.inserting("head", into: "echo '中文' ") == "echo '中文' head ")
        assert(ShellCommandKeywords.inserting("pm", into: "") == "pm ")
        assert(ShellCommandKeywords.inserting("not a preset", into: "getprop") == "getprop")
        let mailbox = LatestFrameBuffer<Int>()
        let session = UUID()
        mailbox.beginSession(session)
        var scheduled = 0
        for frame in 0..<10000 { if mailbox.offer(frame, session: session) { scheduled += 1 } }
        assert(scheduled == 1 && mailbox.latest == 9999)
        assert(mailbox.takePending() == 9999 && mailbox.takePending() == nil)
        assert(mailbox.latest == 9999) // a newly attached display gets only latest
        let replacement = UUID()
        mailbox.beginSession(replacement)
        assert(!mailbox.offer(10000, session: session))
        assert(mailbox.latest == nil)
        assert(mailbox.offer(1, session: replacement))
        mailbox.clear()
        assert(mailbox.latest == nil && mailbox.takePending() == nil)
        let landscape = LivePreviewProfile.size(width: 3840, height: 2160)
        assert(landscape.width == 1280 && landscape.height == 720)
        let portrait = LivePreviewProfile.size(width: 1080, height: 1920)
        assert(portrait.width == 720 && portrait.height == 1280)

        func integer(_ value: UInt32) -> Data {
            Data([UInt8(truncatingIfNeeded: value >> 24), UInt8(truncatingIfNeeded: value >> 16), UInt8(truncatingIfNeeded: value >> 8), UInt8(truncatingIfNeeded: value)])
        }
        func packet(_ kind: UInt8, _ data: Data) -> Data { Data([kind]) + integer(UInt32(data.count)) + data }
        let magic = Data("TVADBV01".utf8)
        let info = packet(1, [1920, 1080, 1280, 720, 30].reduce(Data()) { $0 + integer(UInt32($1)) })
        let frame = packet(3, Data([0, 0, 0, 1, 0x65, 0x80]))
        var videoPackets = VideoPacketParser()
        var kinds: [UInt8] = []
        for byte in magic + info + frame + frame {
            kinds += try videoPackets.append(Data([byte])).map(\.kind)
        }
        assert(kinds == [1, 3, 3]); try videoPackets.finish()
        for invalid in [magic + frame, magic + info + info, magic + Data([3]) + integer(4194305),
                        magic + Data([0]) + integer(1), magic + packet(1, Data(repeating: 0, count: 20)),
                        magic + info + packet(3, Data([0, 0, 1]))] {
            var parser = VideoPacketParser()
            do { _ = try parser.append(invalid); fatalError("Invalid video packet accepted") } catch { }
        }
        var truncated = VideoPacketParser()
        _ = try truncated.append(magic + info + frame.dropLast())
        do { try truncated.finish(); fatalError("Truncated packet accepted") } catch { }
        var failed = VideoPacketParser()
        do {
            _ = try failed.append(magic + packet(4, integer(3) + integer(UInt32(bitPattern: -2147479551))))
            fatalError("Remote error ignored")
        } catch VideoPacketParser.Failure.remote(let stage, let detail) {
            assert(stage == 3 && detail == -2147479551)
        }

        let token = Data("window:field".utf8).base64EncodedString()
        let unicode = "你好 👋\n100%s 'quoted'"
        let wire = try RemoteEditorProtocol.encode(unicode)
        let parsed = try RemoteEditorProtocol.parse("STATE 1 \(token) \(wire)", sequence: 1)
        assert(parsed == .state(RemoteEditorSnapshot(token: token, text: unicode)))
        assert(try! RemoteEditorProtocol.parse("STATE 2 \(token) -", sequence: 2) == .state(RemoteEditorSnapshot(token: token, text: "")))
        do { _ = try RemoteEditorProtocol.parse("STATE 8 \(token) -", sequence: 2); fatalError("stale ACK accepted") } catch { }
        do { _ = try RemoteEditorProtocol.encode(String(repeating: "a", count: 8193)); fatalError("oversized text accepted") } catch { }

        let sync = RemoteKeyboardSync()
        var remote = RemoteEditorSnapshot(token: token, text: "existing TV text")
        var writes: [String] = []
        var events: [String] = []
        let loop = Task { @MainActor in
            await sync.run(read: { .state(remote) }, write: { expected, desired in
                guard expected == remote else { return .conflict(remote) }
                events.append("write")
                try await Task.sleep(for: .milliseconds(30))
                remote.text = desired; writes.append(desired)
                return .state(remote)
            }, confirm: { expected in
                assert(expected == remote)
                events.append("confirm")
                return .done
            })
        }
        try await waitUntil { sync.state == .ready }
        assert(sync.text == "existing TV text")
        sync.edit(unicode)
        try await waitUntil { writes.last == unicode }
        sync.edit("") // deleting all text must send a real empty value
        try await waitUntil { writes.last == "" }
        remote.text = "TV changed externally"
        try await waitUntil { sync.text == remote.text }
        sync.edit("final phone edit")
        sync.requestConfirmation()
        try await waitUntil { sync.didConfirm }
        await loop.value
        assert(remote.text == "final phone edit")
        assert(events.suffix(2).elementsEqual(["write", "confirm"]))
        assert(writes.filter { $0 == "final phone edit" }.count == 1)

        let conflict = RemoteKeyboardSync()
        let conflictLoop = Task { @MainActor in
            await conflict.run(read: { .state(remote) }, write: { _, _ in
                .conflict(RemoteEditorSnapshot(token: "different", text: "changed TV"))
            }, confirm: { _ in fatalError("must not confirm on conflict") })
        }
        try await waitUntil { conflict.state == .ready }
        conflict.edit("preserved phone draft")
        try await waitUntil { conflict.state == .conflict }
        conflictLoop.cancel()
        await conflictLoop.value
        assert(conflict.text == "preserved phone draft")
        assert(!conflict.canEdit && !conflict.canConfirm)
        let composing = RemoteKeyboardSync()
        var composingRemote = RemoteEditorSnapshot(token: token, text: "before composition")
        let composingLoop = Task { @MainActor in
            await composing.run(read: { .state(composingRemote) }, write: { expected, desired in
                if expected != composingRemote { return .conflict(composingRemote) }
                composingRemote.text = desired; return .state(composingRemote)
            }, confirm: { _ in fatalError("composition conflict must not confirm") })
        }
        try await waitUntil { composing.state == .ready }
        composing.compositionChanged(true)
        composingRemote.text = "TV changed while composing"
        try await Task.sleep(for: .milliseconds(350))
        assert(composing.text == "before composition")
        composing.compositionChanged(false)
        composing.edit("committed 中文")
        try await waitUntil { composing.state == .conflict }
        composingLoop.cancel()
        await composingLoop.value
        assert(composing.text == "committed 中文")

        let confirmRace = RemoteKeyboardSync()
        var reads = 0
        var pendingRead: CheckedContinuation<RemoteEditorReply, Never>?
        let raceLoop = Task { @MainActor in
            await confirmRace.run(read: {
                reads += 1
                if reads == 1 { return .state(remote) }
                return await withCheckedContinuation { pendingRead = $0 }
            }, write: { _, _ in fatalError("no edit expected") },
            confirm: { _ in fatalError("must not confirm a lost/replaced field") })
        }
        try await waitUntil { pendingRead != nil }
        confirmRace.requestConfirmation()
        pendingRead?.resume(returning: .noEditor)
        try await waitUntil { confirmRace.state == .conflict }
        raceLoop.cancel()
        await raceLoop.value
        assert(!confirmRace.isConfirming)
        let clear = RemoteKeyboardSync()
        var clearRemote = RemoteEditorSnapshot(token: token, text: "delete me")
        var temporarilyMissing = false
        var clearFinished = false
        var clearWrites: [String] = []
        let clearLoop = Task { @MainActor in
            await clear.run(read: { temporarilyMissing ? .noEditor : .state(clearRemote) },
                write: { expected, desired in
                    guard expected == clearRemote else { return .conflict(clearRemote) }
                    clearRemote.text = desired; clearWrites.append(desired)
                    if desired.isEmpty { temporarilyMissing = true; return .noEditor }
                    return .state(clearRemote)
                }, confirm: { _ in fatalError("clearing must not auto-confirm") })
            clearFinished = true
        }
        try await waitUntil { clear.state == .ready }
        clear.edit("")
        try await waitUntil { clear.state == .waiting }
        assert(clear.text.isEmpty && clear.canEdit && !clear.canConfirm)
        try await Task.sleep(for: .milliseconds(350))
        assert(!clearFinished && !clear.didConfirm)
        // Keep typing while the original app field temporarily disappears.
        clear.edit("继续输入")
        temporarilyMissing = false
        try await waitUntil { clearRemote.text == "继续输入" && clear.state == .ready }
        assert(clearWrites == ["", "继续输入"])
        clear.edit("")
        try await waitUntil { clear.state == .waiting }
        temporarilyMissing = false
        try await waitUntil { clear.state == .ready }
        assert(clear.text.isEmpty && clear.canEdit && clear.canConfirm)
        assert(!clearFinished && !clear.didConfirm)
        clearLoop.cancel()
        await clearLoop.value
        let restored = RemoteKeyboardSync()
        var restoredRemote = RemoteEditorSnapshot(token: token, text: "original")
        var missing = false
        var restoredWrites: [String] = []
        let restoredLoop = Task { @MainActor in
            await restored.run(read: { missing ? .noEditor : .state(restoredRemote) },
                write: { expected, desired in
                    assert(expected == restoredRemote)
                    restoredRemote.text = desired
                    restoredWrites.append(desired)
                    if desired.isEmpty { missing = true; return .noEditor }
                    return .state(restoredRemote)
                }, confirm: { _ in fatalError("no confirmation requested") })
        }
        try await waitUntil { restored.state == .ready }
        restored.edit("")
        try await waitUntil { restored.state == .waiting }
        // Re-entering the pre-clear value is still a NEW local edit, even
        // though it equals the old, unacknowledged baseline.
        restored.edit("original")
        missing = false
        try await waitUntil { restoredRemote.text == "original" && restored.state == .ready }
        assert(restored.text == "original" && restoredWrites == ["", "original"])
        restoredLoop.cancel()
        await restoredLoop.value
        print("Text synchronization and bounded preview tests passed")
    }
}
