import Foundation

@main struct RemoteTextInputTests {
    static func main() throws {
        // Unknown focus must not disable explicit sending on OEM firmware.
        assert(!RemoteTextInput.hasEditor("unsupported OEM dump"))
        assert(RemoteTextInput.canSend(text: "hello", isConnected: true, isSending: false))
        assert(RemoteTextInput.canSend(text: " ", isConnected: true, isSending: false))
        assert(!RemoteTextInput.canSend(text: "", isConnected: true, isSending: false))
        assert(!RemoteTextInput.canSend(text: "hello", isConnected: false, isSending: false))
        assert(!RemoteTextInput.canSend(text: "hello", isConnected: true, isSending: true))
        assert(RemoteTextInput.hasEditor("mCurAttribute: inputType=0x1 imeOptions=0x0"))
        assert(RemoteTextInput.hasEditor("mCurAttribute=EditorInfo{\n inputType=0x81}"))
        for dump in ["", "permission denied", "mCurAttribute=null", "mCurAttribute=null\nold inputType=0x1", "mCurAttribute: null\nold inputType=0x1", "mCurAttribute: inputType=0x0\nmOther: inputType=0x1", "mCurAttribute: inputType=0x0", "mCurClient=null mCurAttribute: inputType=0x1"] {
            assert(!RemoteTextInput.hasEditor(dump))
        }
        assert(try! RemoteTextInput.commands(for: "hello world") == ["input text 'hello%sworld' 2>&1"])
        assert(try! RemoteTextInput.commands(for: "%s") == ["input text '%' 2>&1", "input text 's' 2>&1"])
        assert(try! RemoteTextInput.commands(for: "a'b") == ["input text 'a'\"'\"'b' 2>&1"])
        for text in ["你好", "😀", "hello\nworld", "", String(repeating: "a", count: 4097)] {
            do { _ = try RemoteTextInput.commands(for: text); fatalError("accepted unsupported input") }
            catch RemoteTextInput.InputError.unsupportedText { }
        }
        // Execute generated commands against a fake input utility; verifies that
        // shell syntax stays data and Android's %s expansion round-trips exactly.
        let original = "Hello  100%s ' \" ; $(echo injected) `echo injected` & | < > \\"
        let commands = try RemoteTextInput.commands(for: original)
        let process = Process()
        let pipe = Pipe()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = ["-c", "input() { printf '%s\\n' \"$2\"; }; " + commands.joined(separator: "; ")]
        process.standardOutput = pipe
        try process.run()
        let output = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        assert(process.terminationStatus == 0)
        let decoded = String(decoding: output, as: UTF8.self)
            .split(separator: "\n").map { $0.replacingOccurrences(of: "%s", with: " ") }.joined()
        assert(decoded == original)
        print("RemoteTextInput tests passed")
    }
}
