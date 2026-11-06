#!/usr/bin/env python3
"""Host protocol test with Android API doubles; not device permission testing."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ["JAVA_HOME"])
stubs = {
    "android/os/Looper.java": "package android.os; public class Looper { public static void prepareMainLooper() {} }",
    "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long uptimeMillis() { return System.nanoTime()/1000000; } }",
    "android/view/InputDevice.java": "package android.view; public class InputDevice { public static final int SOURCE_KEYBOARD=257; }",
    "android/view/InputEvent.java": "package android.view; public class InputEvent {}",
    "android/view/KeyCharacterMap.java": "package android.view; public class KeyCharacterMap { public static final int VIRTUAL_KEYBOARD=-1; }",
    "android/view/KeyEvent.java": """package android.view;
public class KeyEvent extends InputEvent {
 public static final int ACTION_DOWN=0, ACTION_UP=1, FLAG_FROM_SYSTEM=8;
 public final int action, key;
 public KeyEvent(long down, long time, int action, int key, int repeat, int meta,
   int device, int scan, int flags, int source) { this.action=action; this.key=key; }
}""",
    "android/hardware/input/InputManagerGlobal.java": """package android.hardware.input;
import android.view.*;
public class InputManagerGlobal {
 public static InputManagerGlobal getInstance() { return new InputManagerGlobal(); }
 public boolean injectInputEvent(InputEvent input, int mode) {
   KeyEvent event=(KeyEvent)input;
   if (mode != 2) throw new AssertionError("Must await injection");
   System.err.println(event.action+":"+event.key);
   return System.getenv("TVADB_TEST_DENY") == null;
 }
}""",
}
with tempfile.TemporaryDirectory() as temp:
    folder = Path(temp)
    sources = []
    for name, source in stubs.items():
        path = folder/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        sources.append(str(path))
    helper = str(root/"android-helper/src/com/tvadb/helper/RemoteKeys.java")
    subprocess.run([str(java_home/"bin/javac"), "-d", temp, *sources, helper], check=True)
    def run(requests, **extra):
        return subprocess.run([str(java_home/"bin/java"), "-cp", temp, "com.tvadb.helper.RemoteKeys"],
            input=requests, text=True, capture_output=True, check=True, timeout=10,
            env={**os.environ, **extra})
    result = run("".join(f"K {key} {key}\n" for key in range(19, 24)))
    assert result.stdout.splitlines() == ["TVADB_KEYS_READY_V1", *[f"OK {key}" for key in range(19, 24)]]
    assert result.stderr.splitlines() == [f"{action}:{key}" for key in range(19,24) for action in [0,1]]
    result = run("K 1 3\nK 2 24\ngarbage\nK 3 21\n")
    assert result.stdout.splitlines() == ["TVADB_KEYS_READY_V1", "ERR 1", "ERR 2", "ERR 0", "OK 3"]
    assert result.stderr.splitlines() == ["0:21", "1:21"]
    result = run("K 1 21\n", TVADB_TEST_DENY="1")
    assert result.stdout.splitlines() == ["TVADB_KEYS_READY_V1", "ERR 1"]
    assert result.stderr.splitlines() == ["0:21", "1:21"]
print("RemoteKeys protocol tests passed")
