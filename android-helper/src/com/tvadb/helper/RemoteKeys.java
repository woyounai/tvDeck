package com.tvadb.helper;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Persistent ADB shell process: one acknowledged down/up pair per request. */
public final class RemoteKeys {
    private RemoteKeys() {}
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            Class<?> type;
            try { type = Class.forName("android.hardware.input.InputManagerGlobal"); }
            catch (ClassNotFoundException oldAndroid) {
                type = Class.forName("android.hardware.input.InputManager");
            }
            Object manager = type.getMethod("getInstance").invoke(null);
            Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            reply("TVADB_KEYS_READY_V1");
            String line;
            while ((line = input.readLine()) != null) {
                String id = "0";
                try {
                    String[] fields = line.split(" ");
                    if (fields.length != 3 || !"K".equals(fields[0])) {
                        throw new IllegalArgumentException();
                    }
                    id = Long.toString(Long.parseLong(fields[1]));
                    int key = Integer.parseInt(fields[2]);
                    if (key < 19 || key > 23) throw new IllegalArgumentException();
                    long now = SystemClock.uptimeMillis();
                    KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_KEYBOARD);
                    boolean accepted = false;
                    try { accepted = Boolean.TRUE.equals(inject.invoke(manager, down, 2)); }
                    finally {
                        KeyEvent up = new KeyEvent(now, SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_UP, key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                            0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                        accepted = Boolean.TRUE.equals(inject.invoke(manager, up, 2)) && accepted;
                    }
                    reply((accepted ? "OK " : "ERR ") + id);
                } catch (Throwable failure) { reply("ERR " + id); }
            }
        } catch (Throwable failure) { reply("TVADB_KEYS_UNSUPPORTED"); }
        finally { System.exit(0); }
    }
    private static void reply(String value) { System.out.println(value); System.out.flush(); }
}
