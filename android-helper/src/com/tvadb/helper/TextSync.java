package com.tvadb.helper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** An install-free shell automation connection, scoped to the keyboard sheet. */
public final class TextSync {
    private static UiAutomation automation;
    private static final Set<String> imePackages = new HashSet<String>();
    private static Editor retainedEditor;
    private static final class Editor {
        final AccessibilityNodeInfo node;
        final String token;
        final String resourceId;
        Editor(AccessibilityNodeInfo node, String token) {
            this.node = node; this.token = token; this.resourceId = node.getViewIdResourceName();
        }
    }
    private TextSync() {}
    public static void main(String[] args) {
        HandlerThread thread = new HandlerThread("TVADBTextSync");
        try {
            if (args.length != 1) throw new IllegalArgumentException("IME package list required");
            for (String name : decode(args[0]).split("\n")) {
                if (!name.isEmpty()) imePackages.add(name);
            }
            if (imePackages.isEmpty()) throw new IllegalArgumentException("IME packages unavailable");
            thread.start();
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection")
                .getDeclaredConstructor().newInstance();
            automation = (UiAutomation) UiAutomation.class
                .getConstructor(Looper.class, connectionType).newInstance(thread.getLooper(), connection);
            // Preserve existing accessibility services (Android 7+).
            UiAutomation.class.getMethod("connect", int.class).invoke(automation, 1);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            reply("TVADB_TEXT_READY_V1");
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            String line;
            while ((line = input.readLine()) != null) {
                if (line.length() > 50000) { reply("ERROR 0"); continue; }
                String[] parts = line.split(" ");
                String id = parts.length > 1 ? parts[1] : "0";
                try { handle(parts, id); }
                catch (Throwable error) { reply("ERROR " + id); }
            }
        } catch (Throwable error) { reply("TVADB_TEXT_UNSUPPORTED"); }
        finally {
            if (automation != null) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation); }
                catch (Throwable ignored) {}
            }
            thread.quitSafely();
            System.exit(0);
        }
    }

    private static boolean isIme(AccessibilityWindowInfo window, AccessibilityNodeInfo root) {
        return window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            || (root != null && imePackages.contains(String.valueOf(root.getPackageName())));
    }
    private static boolean valid(AccessibilityNodeInfo node, AccessibilityNodeInfo root, int windowId) {
        return root.getPackageName() != null && node != null && node.refresh() && node.isEditable()
            && node.getWindowId() == windowId
            && String.valueOf(node.getPackageName()).equals(String.valueOf(root.getPackageName()))
            && !imePackages.contains(String.valueOf(node.getPackageName()));
    }
    private static String identity(AccessibilityNodeInfo node, AccessibilityNodeInfo root) {
        String viewId = node.getViewIdResourceName();
        if (viewId != null && !viewId.isEmpty()) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
            // A unique resource ID survives an EditText being recreated after
            // clear. Duplicate IDs retain strict source identity to avoid edits
            // landing in another row's field.
            if (matches != null && matches.size() == 1 && matches.get(0).equals(node)) {
                return encode(node.getWindowId() + ":" + node.getPackageName() + ":" + viewId);
            }
        }
        return encode(node.getWindowId() + ":" + node.hashCode() + ":"
            + node.getPackageName() + ":" + viewId);
    }
    private static Editor focused() {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        boolean activeIme = false;
        int activeApplication = -1;
        boolean otherInputFocus = false;
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (isIme(window, root)) {
                activeIme |= window.isActive() || window.isFocused();
                continue;
            }
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || root == null
                || (!window.isActive() && !window.isFocused())) continue;
            activeApplication = window.getId();
            AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (valid(node, root, window.getId()) && node.isFocused()) {
                retainedEditor = new Editor(node, identity(node, root));
                return retainedEditor;
            }
            // A focused non-editor (e.g. a results button) is a real focus
            // change, even while an IME window is still visible.
            otherInputFocus |= node != null && node.isFocused();
        }
        // Candidate selection can temporarily take input focus. Keep the last
        // real app field only while its app is still foreground; never fall
        // back to getRootInActiveWindow(), which may be the IME candidate UI.
        if (retainedEditor != null && activeIme && !otherInputFocus) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || root == null
                    || isIme(window, root) || window.getId() != retainedEditor.node.getWindowId()) continue;
                boolean foreground = activeApplication == window.getId()
                    || (activeApplication == -1 && activeIme);
                if (!foreground) continue;
                if (valid(retainedEditor.node, root, window.getId())
                    && retainedEditor.token.equals(identity(retainedEditor.node, root))) return retainedEditor;
                // Reacquire only an unambiguous instance of the SAME app field
                // after clear recreated it, never a new candidate or list row.
                if (retainedEditor.resourceId != null && !retainedEditor.resourceId.isEmpty()) {
                    List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(retainedEditor.resourceId);
                    if (matches != null && matches.size() == 1
                        && valid(matches.get(0), root, window.getId())) {
                        Editor replacement = new Editor(matches.get(0), identity(matches.get(0), root));
                        if (replacement.token.equals(retainedEditor.token)) {
                            retainedEditor = replacement;
                            return retainedEditor;
                        }
                    }
                }
            }
        }
        return null;
    }
    private static String text(AccessibilityNodeInfo node) {
        // Accessibility getText can return the hint after clearing an EditText.
        // Hints are not user text and must not be sent back as search content.
        if (android.os.Build.VERSION.SDK_INT >= 26 && node.isShowingHintText()) return "";
        return node.getText() == null ? "" : node.getText().toString();
    }
    private static void state(String prefix, String id, Editor editor) {
        reply(prefix + " " + id + " " + editor.token + " " + encode(text(editor.node)));
    }
    private static void handle(String[] p, String id) throws Exception {
        if (p.length < 2 || !id.matches("[0-9]+")) throw new IllegalArgumentException();
        Editor editor = focused();
        if (editor == null) { reply("NONE " + id); return; }
        AccessibilityNodeInfo node = editor.node;
        if (node.isPassword() || text(node).getBytes("UTF-8").length > 8192) {
            reply("UNSUPPORTED " + id); return;
        }
        if ("GET".equals(p[0]) && p.length == 2) { state("STATE", id, editor); return; }
        if (p.length < 4) throw new IllegalArgumentException();
        if (!editor.token.equals(p[2]) || !text(node).equals(decode(p[3]))) {
            state("CONFLICT", id, editor); return;
        }
        if ("SET".equals(p[0]) && p.length == 5) {
            String desired = decode(p[4]);
            if (desired.getBytes("UTF-8").length > 8192) throw new IllegalArgumentException();
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, desired);
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                reply("UNSUPPORTED " + id); return;
            }
            // Some widgets publish the updated accessibility text asynchronously.
            for (int attempt = 0; attempt < 25; attempt++) {
                Editor current = focused();
                if (current == null) { Thread.sleep(20); continue; }
                if (current.node.isPassword()) { reply("UNSUPPORTED " + id); return; }
                if (!editor.token.equals(current.token)) { state("CONFLICT", id, current); return; }
                if (text(current.node).equals(desired)) { state("STATE", id, current); return; }
                Thread.sleep(20);
            }
            if (focused() == null) { reply("NONE " + id); return; }
            reply("ERROR " + id); // do not pretend that an unobserved write synchronized
        } else if ("CONFIRM".equals(p[0]) && p.length == 4) {
            // Retention is for text recovery only. Enter must never be sent
            // while a candidate or a non-editor owns the actual input focus.
            if (!node.refresh() || !node.isFocused()) { reply("NONE " + id); return; }
            if (android.os.Build.VERSION.SDK_INT >= 30 && node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                reply("DONE " + id);
                return;
            }
            Class<?> type;
            try { type = Class.forName("android.hardware.input.InputManagerGlobal"); }
            catch (ClassNotFoundException old) { type = Class.forName("android.hardware.input.InputManager"); }
            Object manager = type.getMethod("getInstance").invoke(null);
            Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
            long now = SystemClock.uptimeMillis();
            boolean accepted = false;
            try {
                accepted = Boolean.TRUE.equals(inject.invoke(manager, new KeyEvent(now, now,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD), 2));
            } finally {
                accepted = Boolean.TRUE.equals(inject.invoke(manager, new KeyEvent(now,
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD), 2)) && accepted;
            }
            reply((accepted ? "DONE " : "ERROR ") + id);
        } else throw new IllegalArgumentException();
    }
    private static String encode(String value) {
        if (value.isEmpty()) return "-";
        try { return Base64.encodeToString(value.getBytes("UTF-8"), Base64.NO_WRAP); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
    private static String decode(String value) throws Exception {
        return "-".equals(value) ? "" : new String(Base64.decode(value, Base64.DEFAULT), "UTF-8");
    }
    private static void reply(String value) { System.out.println(value); System.out.flush(); }
}
