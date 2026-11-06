#!/usr/bin/env python3
"""Production helper regression tests with Android API doubles, not a TV."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ["JAVA_HOME"])
STUBS = {'android/os/Looper.java': 'package android.os; public class Looper { public static void prepareMainLooper() '
                           '{} }',
 'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long '
                                'uptimeMillis() { return System.nanoTime()/1000000; } }',
 'android/view/InputDevice.java': 'package android.view; public class InputDevice { public static final int '
                                  'SOURCE_KEYBOARD=257; }',
 'android/view/InputEvent.java': 'package android.view; public class InputEvent {}',
 'android/view/KeyCharacterMap.java': 'package android.view; public class KeyCharacterMap { public static '
                                      'final int VIRTUAL_KEYBOARD=-1; }',
 'android/view/KeyEvent.java': 'package android.view;\n'
                               'public class KeyEvent extends InputEvent {\n'
                               ' public static final int KEYCODE_ENTER=66,ACTION_DOWN=0, ACTION_UP=1, '
                               'FLAG_FROM_SYSTEM=8;\n'
                               ' public final int action, key;\n'
                               ' public KeyEvent(long down, long time, int action, int key, int repeat, int '
                               'meta,\n'
                               '   int device, int scan, int flags, int source) { this.action=action; '
                               'this.key=key; }\n'
                               '}',
 'android/hardware/input/InputManagerGlobal.java': 'package android.hardware.input;\n'
                                                   'import android.view.*;\n'
                                                   'public class InputManagerGlobal {\n'
                                                   ' public static InputManagerGlobal getInstance() { return '
                                                   'new InputManagerGlobal(); }\n'
                                                   ' public boolean injectInputEvent(InputEvent input, int '
                                                   'mode) {\n'
                                                   '   KeyEvent event=(KeyEvent)input;\n'
                                                   '   if (mode != 2) throw new AssertionError("Must await '
                                                   'injection");\n'
                                                   '   System.err.println(event.action+":"+event.key);\n'
                                                   '   return System.getenv("TVADB_TEST_DENY") == null;\n'
                                                   ' }\n'
                                                   '}',
 'android/os/Bundle.java': 'package android.os; public class Bundle { public CharSequence value; public void '
                           'putCharSequence(String key, CharSequence value){this.value=value;} }',
 'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public '
                          'static final int SDK_INT=35; } }',
 'android/os/HandlerThread.java': 'package android.os; public class HandlerThread { public '
                                  'HandlerThread(String s){} public void start(){} public Looper '
                                  'getLooper(){return new Looper();} public void quitSafely(){} }',
 'android/util/Base64.java': 'package android.util; public class Base64 { public static final int '
                             'NO_WRAP=2,DEFAULT=0; public static String encodeToString(byte[] data,int '
                             'flags){return java.util.Base64.getEncoder().encodeToString(data);} public '
                             'static byte[] decode(String value,int flags){return '
                             'java.util.Base64.getDecoder().decode(value);} }',
 'android/accessibilityservice/AccessibilityServiceInfo.java': 'package android.accessibilityservice; public '
                                                               'class AccessibilityServiceInfo { public int '
                                                               'flags; public static final int '
                                                               'FLAG_RETRIEVE_INTERACTIVE_WINDOWS=64,FLAG_REPORT_VIEW_IDS=16; '
                                                               '}',
 'android/app/IUiAutomationConnection.java': 'package android.app; public interface IUiAutomationConnection '
                                             '{}',
 'android/app/UiAutomationConnection.java': 'package android.app; public class UiAutomationConnection '
                                            'implements IUiAutomationConnection {}',
 'android/app/UiAutomation.java': 'package android.app;\n'
                                  'import android.os.*;import android.accessibilityservice.*;import '
                                  'android.view.accessibility.*;import java.util.*;\n'
                                  'public class UiAutomation {\n'
                                  ' public UiAutomation(Looper l,IUiAutomationConnection c){}\n'
                                  ' public void connect(int flags){if(flags!=1)throw new AssertionError();}\n'
                                  ' public void disconnect(){}\n'
                                  ' public AccessibilityServiceInfo getServiceInfo(){return new '
                                  'AccessibilityServiceInfo();}\n'
                                  ' public void setServiceInfo(AccessibilityServiceInfo i){}\n'
                                  ' public List<AccessibilityWindowInfo> getWindows(){\n'
                                  '  return Arrays.asList(new AccessibilityWindowInfo(true),new '
                                  'AccessibilityWindowInfo(false));\n'
                                  ' }\n'
                                  ' public AccessibilityNodeInfo getRootInActiveWindow(){return '
                                  'AccessibilityNodeInfo.IME;}\n'
                                  '}',
 'android/view/accessibility/AccessibilityWindowInfo.java': 'package android.view.accessibility;\n'
                                                            'public class AccessibilityWindowInfo {\n'
                                                            ' public static final int '
                                                            'TYPE_INPUT_METHOD=2,TYPE_APPLICATION=1;\n'
                                                            ' private boolean ime;\n'
                                                            ' public AccessibilityWindowInfo(boolean '
                                                            'ime){this.ime=ime;}\n'
                                                            ' public int getType(){return ime && '
                                                            'System.getenv("TVADB_TEST_MISLABELED_IME")==null?2:1;}\n'
                                                            ' public int getId(){return ime?2:1;}\n'
                                                            ' public boolean isActive(){return true;}public '
                                                            'boolean isFocused(){return true;}\n'
                                                            ' public AccessibilityNodeInfo getRoot(){return '
                                                            'ime?AccessibilityNodeInfo.IME:AccessibilityNodeInfo.APP;}\n'
                                                            '}',
 'android/view/accessibility/AccessibilityNodeInfo.java': 'package android.view.accessibility;\n'
                                                          'import android.os.Bundle;import java.util.*;\n'
                                                          'public class AccessibilityNodeInfo {\n'
                                                          ' public static final int '
                                                          'FOCUS_INPUT=1,ACTION_SET_TEXT=2097152;\n'
                                                          ' public static final String '
                                                          'ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE="text";\n'
                                                          ' public static AccessibilityNodeInfo APP=new '
                                                          'AccessibilityNodeInfo(false);\n'
                                                          ' public static final AccessibilityNodeInfo '
                                                          'IME=new AccessibilityNodeInfo(true);\n'
                                                          ' private boolean ime;private boolean '
                                                          'stale=false;private int generation=42;\n'
                                                          ' private String '
                                                          'text=System.getenv().getOrDefault("TVADB_TEST_TEXT","existing '
                                                          'TV");\n'
                                                          ' public AccessibilityNodeInfo(boolean '
                                                          'ime){this.ime=ime;}\n'
                                                          ' public AccessibilityNodeInfo findFocus(int '
                                                          'type){\n'
                                                          '  if(ime)return IME;\n'
                                                          '  '
                                                          'if(System.getenv("TVADB_TEST_NO_FOCUS")!=null)return '
                                                          'null;\n'
                                                          '  '
                                                          'if(System.getenv("TVADB_TEST_CANDIDATE_FOCUS")!=null)return '
                                                          'IME;\n'
                                                          '  '
                                                          'if(text.isEmpty()&&System.getenv("TVADB_TEST_CLEAR_LOSES_FOCUS")!=null)return '
                                                          'null;\n'
                                                          '  return this;\n'
                                                          ' }\n'
                                                          ' public List<AccessibilityNodeInfo> '
                                                          'findAccessibilityNodeInfosByViewId(String id){\n'
                                                          '  return '
                                                          'System.getenv("TVADB_TEST_DUPLICATE_IDS")==null?Arrays.asList(this):Arrays.asList(this,this);\n'
                                                          ' }\n'
                                                          ' public boolean refresh(){return !stale;}\n'
                                                          ' public boolean isFocused(){return ime || '
                                                          '!(text.isEmpty()&&System.getenv("TVADB_TEST_CLEAR_LOSES_FOCUS")!=null);}\n'
                                                          ' public boolean isEditable(){return true;}\n'
                                                          ' public boolean isShowingHintText(){return '
                                                          '!ime&&text.isEmpty()&&System.getenv("TVADB_TEST_HINT")!=null;}\n'
                                                          ' public boolean isPassword(){return '
                                                          '!ime&&System.getenv("TVADB_TEST_PASSWORD")!=null;}\n'
                                                          ' public CharSequence getText(){return '
                                                          'ime?"CANDIDATE_WORD":isShowingHintText()?"SEARCH_HINT":text;}\n'
                                                          ' public int getWindowId(){return ime?2:1;}public '
                                                          'int hashCode(){return generation;}\n'
                                                          ' public String getPackageName(){return '
                                                          'ime?"com.tv.ime":"app";}\n'
                                                          ' public String getViewIdResourceName(){return '
                                                          'ime?"candidate":"id";}\n'
                                                          ' public boolean performAction(int action,Bundle '
                                                          'arguments){\n'
                                                          '  '
                                                          'if(System.getenv("TVADB_TEST_DENY")!=null)return '
                                                          'false;\n'
                                                          '  '
                                                          'text=arguments.value.toString();if(text.isEmpty())generation++;\n'
                                                          '  '
                                                          'if(text.isEmpty()&&System.getenv("TVADB_TEST_RECREATE")!=null){\n'
                                                          '   AccessibilityNodeInfo replacement=new '
                                                          'AccessibilityNodeInfo(false);\n'
                                                          '   '
                                                          'replacement.text=text;replacement.generation=generation;APP=replacement;stale=true;\n'
                                                          '  }\n'
                                                          '  return true;\n'
                                                          ' }\n'
                                                          ' public boolean performAction(int action){return '
                                                          'false;}\n'
                                                          ' public static class AccessibilityAction {public '
                                                          'static final AccessibilityAction '
                                                          'ACTION_IME_ENTER=new AccessibilityAction();public '
                                                          'int getId(){return 1;}}\n'
                                                          '}'}
# Model a real focus move after the first GET, separately from an IME
# temporarily owning focus. A visible IME alone must not authorize old edits.
node_source = STUBS['android/view/accessibility/AccessibilityNodeInfo.java']
node_source = node_source.replace(' private boolean ime;',
    ' public static int focusQueries=0; public static boolean focusLost=false;'
    ' public static final AccessibilityNodeInfo BUTTON=new AccessibilityNodeInfo(false);'
    ' private boolean ime;')
node_source = node_source.replace('  if(ime)return IME;',
    '  if(ime)return IME;'
    '  if(System.getenv("TVADB_TEST_BLUR_AFTER_BIND")!=null && ++focusQueries>1){'
    '   focusLost=true; return System.getenv("TVADB_TEST_FOCUS_BUTTON")!=null?BUTTON:null; }')
node_source = node_source.replace('return ime || !(text.isEmpty()',
    'return this==BUTTON || ime || !focusLost && !(text.isEmpty()')
node_source = node_source.replace('public boolean isEditable(){return true;}',
    'public boolean isEditable(){return this!=BUTTON;}')
STUBS['android/view/accessibility/AccessibilityNodeInfo.java'] = node_source
window_source = STUBS['android/view/accessibility/AccessibilityWindowInfo.java']
window_source = window_source.replace('return true;',
    'return !ime || System.getenv("TVADB_TEST_NO_ACTIVE_IME")==null;')
STUBS['android/view/accessibility/AccessibilityWindowInfo.java'] = window_source

def encoded(value):
    return base64.b64encode(value.encode()).decode() if value else "-"
with tempfile.TemporaryDirectory() as temp:
    folder = Path(temp)
    sources=[]
    for name, source in STUBS.items():
        path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source);sources.append(str(path))
    subprocess.run([str(java_home/"bin/javac"),"-d",temp,*sources,str(root/"android-helper/src/com/tvadb/helper/TextSync.java")],check=True)
    def run(commands, **env):
        return subprocess.run([str(java_home/"bin/java"),"-cp",temp,"com.tvadb.helper.TextSync",encoded("com.tv.ime\ncom.other.ime")],input=commands,text=True,capture_output=True,check=True,timeout=10,env={**os.environ,**env})
    token=encoded("1:app:id")
    initial=encoded("existing TV")
    desired=encoded("你好 👋 '100%s'\nnew line")
    commands=f"GET 1\nSET 2 {token} {initial} {desired}\nSET 3 {token} {initial} -\nSET 4 {token} {desired} -\nGET 5\nCONFIRM 6 {token} -\n"
    expected=["TVADB_TEXT_READY_V1",f"STATE 1 {token} {initial}",f"STATE 2 {token} {desired}",f"CONFLICT 3 {token} {desired}",f"STATE 4 {token} -",f"STATE 5 {token} -","DONE 6"]
    for env in [{},{"TVADB_TEST_HINT":"1"},{"TVADB_TEST_CLEAR_LOSES_FOCUS":"1","TVADB_TEST_HINT":"1"},{"TVADB_TEST_MISLABELED_IME":"1","TVADB_TEST_HINT":"1"},{"TVADB_TEST_RECREATE":"1","TVADB_TEST_CLEAR_LOSES_FOCUS":"1","TVADB_TEST_HINT":"1"}]:
        result=run(commands,**env)
        wants = expected[:-1] + ["NONE 6"] if "TVADB_TEST_CLEAR_LOSES_FOCUS" in env else expected
        assert result.stdout.splitlines()==wants,result.stdout
        assert "CANDIDATE_WORD" not in result.stdout and "SEARCH_HINT" not in result.stdout
        assert result.stderr.splitlines()==([] if "TVADB_TEST_CLEAR_LOSES_FOCUS" in env else ["0:66","1:66"])
    # Moving to another button must stop reads/writes/Enter even if an IME
    # window is still marked active. Without an active IME, blur also stops.
    blurred_commands=f"GET 1\nGET 2\nSET 3 {token} {initial} {desired}\nCONFIRM 4 {token} {initial}\n"
    for extra in [{"TVADB_TEST_FOCUS_BUTTON":"1"}, {"TVADB_TEST_NO_ACTIVE_IME":"1"}]:
        result=run(blurred_commands,TVADB_TEST_BLUR_AFTER_BIND="1",**extra)
        assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1",f"STATE 1 {token} {initial}","NONE 2","NONE 3","NONE 4"],result.stdout
        assert not result.stderr,result.stderr
    # Genuine temporary IME focus can retain text for recovery, but it cannot
    # authorize a global Enter directed at whichever widget now owns focus.
    result=run(f"GET 1\nGET 2\nCONFIRM 3 {token} {initial}\n",TVADB_TEST_BLUR_AFTER_BIND="1")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1",f"STATE 1 {token} {initial}",f"STATE 2 {token} {initial}","NONE 3"],result.stdout
    assert not result.stderr,result.stderr
    # If focus resolves into the IME, do not use the active-root fallback.
    result=run("GET 1\n",TVADB_TEST_CANDIDATE_FOCUS="1")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1","NONE 1"]
    result=run("GET 1\n",TVADB_TEST_TEXT="",TVADB_TEST_HINT="1")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1",f"STATE 1 {token} -"]
    duplicate_token=encoded("1:42:app:id")
    result=run("GET 1\n",TVADB_TEST_DUPLICATE_IDS="1")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1",f"STATE 1 {duplicate_token} {initial}"]
    result=run(f"GET 1\nSET 2 {duplicate_token} {initial} -\n",TVADB_TEST_DUPLICATE_IDS="1",TVADB_TEST_RECREATE="1",TVADB_TEST_CLEAR_LOSES_FOCUS="1")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1",f"STATE 1 {duplicate_token} {initial}","NONE 2"]
    result=run("GET 1\n",TVADB_TEST_PASSWORD="1",TVADB_TEST_TEXT="secret")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1","UNSUPPORTED 1"]
    assert "secret" not in result.stdout
    result=run("GET 1\n",TVADB_TEST_NO_FOCUS="1")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1","NONE 1"]
    result=run(f"SET 1 {token} {initial} {desired}\n",TVADB_TEST_DENY="1")
    assert result.stdout.splitlines()==["TVADB_TEXT_READY_V1","UNSUPPORTED 1"]
print("TextSync helper candidate exclusion, hints, clear/refocus, identity and Unicode tests passed")
