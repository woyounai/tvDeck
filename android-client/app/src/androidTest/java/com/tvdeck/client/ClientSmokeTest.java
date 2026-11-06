package com.tvdeck.client;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.lang.reflect.*;

public class ClientSmokeTest {
    @Test public void firstLaunchNoticeKeepsAVisibleHostWithoutOpeningDeviceFeatures() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        org.junit.Assume.assumeFalse(UsageConsent.accepted(context));
        android.app.Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(UsageNoticeActivity.class.getName(),null,false);
        MainActivity host=null;android.app.Activity notice=null;
        try{
            host=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            notice=instrumentation.waitForMonitorWithTimeout(monitor,5000);
            assertNotNull("Notice must appear after the host starts",notice);
            final MainActivity main=host;
            instrumentation.runOnMainSync(()->{
                assertFalse(main.isFinishing());
                assertTrue(main.findViewById(android.R.id.content).isLaidOut());
                try{
                    Field model=MainActivity.class.getDeclaredField("model");model.setAccessible(true);
                    assertNull("Device features must remain closed before consent",model.get(main));
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
            });
        }finally{
            final MainActivity main=host;final android.app.Activity shown=notice;
            instrumentation.runOnMainSync(()->{if(main!=null)main.finish();if(shown!=null)shown.finish();});
            instrumentation.removeMonitor(monitor);
        }
    }
    @Test public void usageNoticeIsFloatingWithoutLaunchArtwork() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        UsageNoticeActivity activity=(UsageNoticeActivity)instrumentation.startActivitySync(
            new Intent(instrumentation.getTargetContext(),UsageNoticeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                android.content.res.TypedArray theme=activity.obtainStyledAttributes(new int[]{android.R.attr.windowBackground,android.R.attr.windowIsFloating});
                try{
                    assertTrue("Notice must use a floating window",theme.getBoolean(1,false));
                    android.graphics.drawable.Drawable background=theme.getDrawable(0);
                    assertTrue("Notice must not inherit the launch artwork",background instanceof android.graphics.drawable.ColorDrawable);
                    assertEquals(255,android.graphics.Color.alpha(((android.graphics.drawable.ColorDrawable)background).getColor()));
                }finally{theme.recycle();}
                android.view.WindowManager.LayoutParams bounds=activity.getWindow().getAttributes();
                android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
                assertTrue(bounds.width>0&&bounds.width<metrics.widthPixels);
                assertTrue(bounds.height>0&&bounds.height<metrics.heightPixels);
                android.view.ViewGroup content=activity.findViewById(android.R.id.content);
                android.view.View panel=content.getChildAt(0);
                assertTrue(panel.getPaddingTop()>0&&panel.getPaddingBottom()>0);
                assertTrue(panel.getPaddingLeft()>0&&panel.getPaddingRight()>0);
                assertTrue(activity.getWindow().getDecorView().isShown());
                boolean titleVisible=false;
                for(String language:new String[]{"en","zh-Hans"}){
                    android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());
                    config.setLocales(android.os.LocaleList.forLanguageTags(language));
                    titleVisible|=hasText(activity.getWindow().getDecorView(),activity.createConfigurationContext(config).getString(R.string.legal_title));
                }
                assertTrue(titleVisible);
            });
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void aerialInstallProgressAppearsOnDevice() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeckModel model=((DeckApplication)activity.getApplication()).model();
        boolean connected=model.connected,busy=model.busy,working=model.aerialWorking;
        String operation=model.operation;double progress=model.aerialProgress;AerialIntegration.Status status=model.aerialStatus;
        try{
            instrumentation.runOnMainSync(()->{
                try{
                    model.connected=true;model.busy=true;model.aerialWorking=true;model.operation=activity.getString(R.string.aerial_downloading);model.aerialProgress=.42;
                    model.aerialStatus=new AerialIntegration.Status(29,new AerialIntegration.Installed("standard","1.78",101));
                    Field details=MainActivity.class.getDeclaredField("aerialDetails"),tab=MainActivity.class.getDeclaredField("tab");details.setAccessible(true);tab.setAccessible(true);details.setBoolean(activity,true);tab.setInt(activity,4);
                    Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(activity);
                    assertTrue(hasText(activity.getWindow().getDecorView(),activity.getString(R.string.aerial_downloading)+"  42%"));
                    assertTrue(hasProgress(activity.getWindow().getDecorView(),420));
                }catch(Exception error){throw new RuntimeException(error);}
            });
        }finally{
            instrumentation.runOnMainSync(()->{model.connected=connected;model.busy=busy;model.aerialWorking=working;model.operation=operation;model.aerialProgress=progress;model.aerialStatus=status;activity.finish();});
        }
    }
    private static boolean hasText(android.view.View view,String expected){
        if(view instanceof android.widget.TextView&&expected.contentEquals(((android.widget.TextView)view).getText()))return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),expected))return true;}
        return false;
    }
    private static boolean hasProgress(android.view.View view,int expected){
        if(view instanceof android.widget.ProgressBar){android.widget.ProgressBar bar=(android.widget.ProgressBar)view;if(bar.getVisibility()==android.view.View.VISIBLE&&!bar.isIndeterminate()&&bar.getProgress()==expected)return true;}
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasProgress(group.getChildAt(i),expected))return true;}
        return false;
    }
    static Context isolatedContext() {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context context=new ContextWrapper(base) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getNoBackupFilesDir(){File dir=new File(super.getNoBackupFilesDir(),"test-identity");dir.mkdirs();return dir;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("tests-"+name,mode);}
        };
        UsageConsent.accept(context,UsageConsent.systemRegion(),"en");
        return context;
    }
    @Test public void missingConsentBlocksConnectionAndDeviceCommands() throws Exception {
        Context context=isolatedContext();
        UsageConsent.preferences(context).edit().clear().commit();
        DeckModel model=new DeckModel(context);
        try {
            try {model.connect("127.0.0.1",1,null,0);fail("Connected without consent");}
            catch(java.io.IOException expected){assertEquals(context.getString(R.string.legal_required),expected.getMessage());}
            try {model.open("shell:echo test");fail("Opened a stream without consent");}
            catch(java.io.IOException expected){assertEquals(context.getString(R.string.legal_required),expected.getMessage());}
            try {model.read("shell:echo test",128,1);fail("Read without consent");}
            catch(java.io.IOException expected){assertEquals(context.getString(R.string.legal_required),expected.getMessage());}
            assertFalse(model.connected);
        } finally {
            UsageConsent.accept(context,UsageConsent.systemRegion(),"en");
        }
    }

    @Test public void obsoleteAerialConfirmationIsRejectedBeforeNetworkAccess() throws Exception {
        Context context=isolatedContext();
        DeckModel model=new DeckModel(context);
        model.connected=true;
        try {
            try {model.installAerial("standard","obsolete");fail("Stale install consent");}
            catch(java.io.IOException expected){assertEquals(context.getString(R.string.aerial_device_changed),expected.getMessage());}
            try {model.configureAerial("standard","obsolete");fail("Stale authorization consent");}
            catch(java.io.IOException expected){assertEquals(context.getString(R.string.aerial_device_changed),expected.getMessage());}
        } finally {model.connected=false;}
    }

    @Test public void installHistoryKeepsDetailsAndCanClear() throws Exception {
        DeckModel model=new DeckModel(isolatedContext());model.clearInstallationHistory();
        model.deviceName="Living room";model.rememberInstall("sample.apkm","example.app","2.3","Success");
        org.json.JSONObject item=model.installationHistory().getJSONObject(0);
        assertEquals("sample.apkm",item.getString("name"));assertEquals("Living room",item.getString("device"));
        assertEquals("2.3",item.getString("version"));assertEquals("files",item.getString("source"));
        model.clearInstallationHistory();assertEquals(0,model.installationHistory().length());
    }
    static int integer(InputStream in) throws Exception {return AdbFiles.number(in);}
    static byte[] packet(InputStream in) throws Exception {
        byte[] header=AdbFiles.bytes(in,24);int size=(header[12]&255)|((header[13]&255)<<8)|((header[14]&255)<<16)|((header[15]&255)<<24);
        byte[] payload=AdbFiles.bytes(in,size);byte[] all=new byte[header.length+payload.length];System.arraycopy(header,0,all,0,24);System.arraycopy(payload,0,all,24,payload.length);return all;
    }
    static int word(byte[] data,int offset) {return (data[offset]&255)|((data[offset+1]&255)<<8)|((data[offset+2]&255)<<16)|((data[offset+3]&255)<<24);}
    static void send(OutputStream out,String command,int a,int b,byte[] payload) throws Exception {
        byte[] id=command.getBytes(StandardCharsets.US_ASCII);int cmd=word(id,0),sum=0;for(byte value:payload)sum+=value&255;
        AdbFiles.number(out,cmd);AdbFiles.number(out,a);AdbFiles.number(out,b);AdbFiles.number(out,payload.length);AdbFiles.number(out,sum);AdbFiles.number(out,cmd^-1);out.write(payload);out.flush();
    }
    private static android.view.View previewControl(android.view.View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View result=previewControl(group.getChildAt(i),description);if(result!=null)return result;}}
        return null;
    }
    private static void previewMotion(android.view.View view,long down,long time,int action,float... xy){
        int count=xy.length/2;android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[count];
        android.view.MotionEvent.PointerCoords[] coordinates=new android.view.MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=android.view.MotionEvent.TOOL_TYPE_FINGER;coordinates[i]=new android.view.MotionEvent.PointerCoords();coordinates[i].x=xy[2*i];coordinates[i].y=xy[2*i+1];coordinates[i].pressure=1;coordinates[i].size=1;}
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,time,action,count,properties,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try{view.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    @Test public void pinchCancelsRemoteTouchAndNeverCommitsATvTap(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            int[] actions=new int[4];
            PreviewViewport view=new PreviewViewport(context,false,new PreviewViewport.Listener(){
                public void playback(boolean start){}
                public void fullscreen(boolean full){}
                public void mode(boolean mouse){}
                public void orientation(int orientation){}
                public void touch(int action,float x,float y,long time){if(action>=0&&action<4)actions[action]++;}
            });
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,1000,1000);view.setRequested(true);view.videoFrame(1920,1080);
            android.view.View canvas=view.getChildAt(0);long down=android.os.SystemClock.uptimeMillis();
            previewMotion(canvas,down,down,0,400,400);
            previewMotion(canvas,down,down+20,5|(1<<8),400,400,600,400);
            previewMotion(canvas,down,down+40,2,300,400,700,400);
            previewMotion(canvas,down,down+60,2,200,400,800,400);
            previewMotion(canvas,down,down+80,2,100,400,900,400);
            previewMotion(canvas,down,down+100,6|(1<<8),100,400,900,400);
            previewMotion(canvas,down,down+120,1,100,400);
            assertTrue(view.transform.zoom>1);assertTrue(actions[3]>0);assertEquals(0,actions[1]);
            previewMotion(canvas,down+200,down+200,0,500,400);previewMotion(canvas,down+200,down+220,1,500,400);
            assertEquals(1,actions[1]);
        });
    }
    @Test public void previewZoomAndFullscreenNeverRequestAnotherStream(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            int[] starts={0},fullscreens={0};PreviewViewport[] holder=new PreviewViewport[1];
            holder[0]=new PreviewViewport(context,false,new PreviewViewport.Listener(){
                public void playback(boolean start){if(start)starts[0]++;}
                public void fullscreen(boolean full){fullscreens[0]++;holder[0].setFullScreen(full);}
                public void mode(boolean mouse){}
                public void orientation(int orientation){}
                public void touch(int action,float x,float y,long time){}
            });
            PreviewViewport view=holder[0];
            // Use exact measure specs; geometry itself is also covered by host tests.
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(700,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,1000,700);
            view.setRequested(true);view.videoFrame(1920,1080);
            previewControl(view,context.getString(R.string.preview_zoom)).performClick();assertEquals(1.5f,view.transform.zoom,.01f);
            previewControl(view,context.getString(R.string.preview_reset_zoom)).performClick();assertEquals(1,view.transform.zoom,.01f);
            previewControl(view,context.getString(R.string.preview_fullscreen)).performClick();assertEquals(1,fullscreens[0]);
            assertEquals(android.view.View.VISIBLE,previewControl(view,context.getString(R.string.preview_show_controls)).getVisibility());
            previewControl(view,context.getString(R.string.preview_show_controls)).performClick();
            previewControl(view,context.getString(R.string.preview_hide_controls)).performClick();
            previewControl(view,context.getString(R.string.preview_exit_fullscreen)).performClick();assertEquals(2,fullscreens[0]);
            assertEquals(0,starts[0]);
            previewControl(view,context.getString(R.string.stop_preview)).performClick();assertEquals(0,starts[0]);
        });
    }
    @Test public void videoSurfaceFitsContainerWithoutStretching() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            android.widget.FrameLayout container=new android.widget.FrameLayout(context);
            android.view.SurfaceView surface=new android.view.SurfaceView(context);
            container.addView(surface,new android.widget.FrameLayout.LayoutParams(-1,-1));
            container.layout(0,0,1000,1200);
            MainActivity.fitPreviewSurface(surface,container,1920,1080);
            android.widget.FrameLayout.LayoutParams params=(android.widget.FrameLayout.LayoutParams)surface.getLayoutParams();
            assertEquals(1000,params.width);assertEquals(563,params.height);assertEquals(android.view.Gravity.CENTER,params.gravity);
            container.layout(0,0,500,300);
            MainActivity.fitPreviewSurface(surface,container,1920,1080);
            assertEquals(500,params.width);assertEquals(281,params.height);
            MainActivity.fitPreviewSurface(surface,container,1080,1920);
            assertEquals(169,params.width);assertEquals(300,params.height);
            MainActivity.fitPreviewSurface(surface,container,0,0);
            assertEquals(169,params.width);assertEquals(300,params.height);
        });
    }
    @Test public void manualDisconnectInvalidatesQueuedReconnectWithoutOpeningTransport() throws Exception {
        DeckModel model=new DeckModel(isolatedContext());
        try{
            java.lang.reflect.Field generation=DeckModel.class.getDeclaredField("connectionGeneration"),manager=DeckModel.class.getDeclaredField("manager");
            generation.setAccessible(true);manager.setAccessible(true);
            long previous=((java.util.concurrent.atomic.AtomicLong)generation.get(model)).get();
            model.disconnect();
            java.lang.reflect.Method connect=DeckModel.class.getDeclaredMethod("connect",String.class,int.class,String.class,int.class,int.class,int.class,long.class);
            connect.setAccessible(true);connect.invoke(model,"127.0.0.1",5555,null,0,8,2,previous);
            assertFalse(model.connected);assertNull(manager.get(model));assertEquals("",model.devices.token());
        }finally{model.worker.shutdownNow();model.previewWorker.shutdownNow();model.inputWorker.shutdownNow();model.timer.shutdownNow();}
    }
    @Test public void appRefreshIgnoresCachedPackagesMissingFromCurrentRows() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            android.widget.TextView title=new android.widget.TextView(context);
            java.util.Map<String,android.widget.TextView> titles=new java.util.LinkedHashMap<>();titles.put("com.test.kept",title);
            java.util.Map<String,android.widget.ImageView> icons=new java.util.LinkedHashMap<>();icons.put("com.test.kept",new android.widget.ImageView(context));
            java.util.Map<String,DeckModel.AppPresentation> cached=new java.util.LinkedHashMap<>();
            cached.put("com.test.removed",new DeckModel.AppPresentation("Removed","1",new byte[0]));
            cached.put("com.test.kept",new DeckModel.AppPresentation("Kept","2",new byte[0]));
            MainActivity.applyAppPresentations(titles,icons,cached);
            assertEquals("Kept",title.getText().toString());assertEquals("com.test.kept · v2",title.getContentDescription());
            cached.remove("com.test.kept");MainActivity.applyAppPresentations(titles,icons,cached);
            assertEquals("Kept",title.getText().toString());
        });
    }
    @Test public void shellFillsPageAndStartsAtLatestOutput() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeckModel model=((DeckApplication)activity.getApplication()).model();boolean connected=model.connected,busy=model.busy;
        java.lang.reflect.Field tab=MainActivity.class.getDeclaredField("tab"),output=MainActivity.class.getDeclaredField("shellOutput"),root=MainActivity.class.getDeclaredField("root"),content=MainActivity.class.getDeclaredField("content");
        java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("render");
        for(java.lang.reflect.Field field:new java.lang.reflect.Field[]{tab,output,root,content})field.setAccessible(true);render.setAccessible(true);
        try{
            instrumentation.runOnMainSync(()->{
                try{
                    StringBuilder lines=new StringBuilder();for(int i=0;i<200;i++)lines.append("line ").append(i).append('\n');
                    model.connected=true;model.busy=true;tab.setInt(activity,3);output.set(activity,lines.toString());render.invoke(activity);
                    android.view.View layout=(android.view.View)root.get(activity);
                    layout.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(2200,android.view.View.MeasureSpec.EXACTLY));layout.layout(0,0,1080,2200);
                    android.widget.LinearLayout page=(android.widget.LinearLayout)content.get(activity);
                    android.widget.ScrollView terminal=(android.widget.ScrollView)page.getChildAt(0);
                    assertTrue(terminal.isFillViewport());assertEquals(android.view.View.GONE,page.getChildAt(1).getVisibility());
                    assertTrue(terminal.getHeight()>page.getHeight()*0.7);assertTrue(terminal.getScrollY()>0);
                    assertEquals(terminal.getChildAt(0).getBottom()-terminal.getHeight(),terminal.getScrollY());
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
            });
        }finally{instrumentation.runOnMainSync(()->{model.connected=connected;model.busy=busy;activity.finish();});}
    }
    @Test public void devicesAndWidgetSessionStayIsolated() throws Exception {
        Context context=isolatedContext();context.getSharedPreferences("devices",0).edit().clear().commit();
        DeviceStore store=new DeviceStore(context);DeviceStore.Device device=store.endpoint("192.168.1.5",5555);device.name="Living room";device.favorite=true;store.save(device);
        assertEquals(device.id,new DeviceStore(context).all().get(0).id);
        String old=store.publish(device),fresh=store.publish(device);assertNotEquals(old,fresh);
        assertTrue(DeckWidget.allowed(23));assertTrue(DeckWidget.allowed(26));assertTrue(DeckWidget.allowed(82));assertFalse(DeckWidget.allowed(-1));
        store.remove(device.id);assertEquals("",store.token());assertNull(store.find(device.id));
    }
    @Test public void ownApkManifestAndAnalysisParse() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(InstallBundle bundle=new InstallBundle(context,android.net.Uri.fromFile(new File(context.getApplicationInfo().sourceDir)))){
            assertEquals("com.tvdeck.client",bundle.apks.get(0).manifest.packageName);
            assertEquals(1,bundle.select(new String[]{"x86_64"},420,"en").size());
            assertTrue(bundle.analysis().contains("com.tvdeck.client"));
            assertTrue(bundle.analysis().contains(context.getString(R.string.permissions_label)));
            assertFalse(bundle.analysis().contains("Certificate SHA-256:"));
        }
    }
    @Test public void packageArchivesExtractAndRejectTraversal() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File archive=File.createTempFile("bundle-test-",".apks",context.getCacheDir());
        try{
            try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new FileOutputStream(archive))){zip.setLevel(0);zip.putNextEntry(new java.util.zip.ZipEntry("splits/base.apk"));try(InputStream in=new FileInputStream(context.getApplicationInfo().sourceDir)){Transfers.copy(in,zip);}zip.closeEntry();zip.putNextEntry(new java.util.zip.ZipEntry("metadata.json"));zip.write("{}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            try(InstallBundle bundle=new InstallBundle(context,android.net.Uri.fromFile(archive))){assertEquals(1,bundle.apks.size());assertEquals("com.tvdeck.client",bundle.apks.get(0).manifest.packageName);}
            try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new FileOutputStream(archive))){zip.putNextEntry(new java.util.zip.ZipEntry("../escape.apk"));zip.write(1);zip.closeEntry();}
            try(InstallBundle ignored=new InstallBundle(context,android.net.Uri.fromFile(archive))){fail("Traversal accepted");}catch(IOException expected){}
        }finally{archive.delete();}
    }
    private static android.view.View findText(android.view.View view,String value){
        if(view instanceof android.widget.Button&&value.contentEquals(((android.widget.Button)view).getText()))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View match=findText(group.getChildAt(i),value);if(match!=null)return match;}}return null;
    }
    @Test public void tabLayoutKeepsInsetsAndContentPositionWhileLoading() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeckModel model=((DeckApplication)activity.getApplication()).model();
        java.lang.reflect.Field root=MainActivity.class.getDeclaredField("root"),tab=MainActivity.class.getDeclaredField("tab"),status=MainActivity.class.getDeclaredField("status");
        java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("render");
        root.setAccessible(true);tab.setAccessible(true);status.setAccessible(true);render.setAccessible(true);
        try{
            instrumentation.runOnMainSync(()->{
                try{
                    android.widget.LinearLayout layout=(android.widget.LinearLayout)root.get(activity);
                    layout.setPadding(12,80,12,60);
                    model.connected=true;model.busy=true;
                    int contentTop=-1;
                    for(int page:new int[]{0,3,4,0,3}){
                        tab.setInt(activity,page);render.invoke(activity);
                        assertSame(layout,root.get(activity));assertEquals(80,layout.getPaddingTop());assertEquals(60,layout.getPaddingBottom());
                        layout.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY));
                        layout.layout(0,0,1080,1920);
                        if(contentTop<0)contentTop=layout.getChildAt(3).getTop();
                        assertEquals(contentTop,layout.getChildAt(3).getTop());
                    }
                    model.operation="";activity.changed();
                    assertEquals(android.view.View.INVISIBLE,((android.view.View)status.get(activity)).getVisibility());
                    model.operation=context.getString(R.string.connecting);activity.changed();
                    assertEquals(android.view.View.VISIBLE,((android.view.View)status.get(activity)).getVisibility());
                    model.operation="";model.busy=false;activity.changed();
                    assertEquals(android.view.View.INVISIBLE,((android.view.View)status.get(activity)).getVisibility());
                    layout.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY));
                    layout.layout(0,0,1080,1920);
                    assertEquals(contentTop,layout.getChildAt(3).getTop());
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
            });
        }finally{instrumentation.runOnMainSync(()->{model.connected=false;model.busy=false;activity.finish();});}
    }
    @Test public void fileTabDefersLoadUntilIdleAndCancelsWhenLeaving() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeckModel model=((DeckApplication)activity.getApplication()).model();
        java.lang.reflect.Field tab=MainActivity.class.getDeclaredField("tab"),pending=MainActivity.class.getDeclaredField("fileLoadPending"),list=MainActivity.class.getDeclaredField("fileList");
        java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("render");
        tab.setAccessible(true);pending.setAccessible(true);list.setAccessible(true);render.setAccessible(true);
        CountDownLatch release=new CountDownLatch(1);
        Future<?> blocked=model.worker.submit(()->{try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        try{
            instrumentation.runOnMainSync(()->{
                try{
                    model.connected=true;model.busy=true;tab.setInt(activity,0);render.invoke(activity);
                    Object first=list.get(activity);assertTrue(pending.getBoolean(activity));
                    render.invoke(activity);assertNotSame(first,list.get(activity));assertTrue(pending.getBoolean(activity));
                    tab.setInt(activity,3);render.invoke(activity);assertFalse(pending.getBoolean(activity));assertNull(list.get(activity));
                    java.lang.reflect.Field appPending=MainActivity.class.getDeclaredField("pendingAppLoad");appPending.setAccessible(true);
                    tab.setInt(activity,1);render.invoke(activity);assertNotNull(appPending.get(activity));
                    render.invoke(activity);assertNotNull(appPending.get(activity));
                    tab.setInt(activity,3);render.invoke(activity);assertNull(appPending.get(activity));
                    tab.setInt(activity,0);render.invoke(activity);assertTrue(pending.getBoolean(activity));
                    model.busy=false;activity.changed();assertTrue(model.busy);assertFalse(pending.getBoolean(activity));
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
            });
        }finally{
            instrumentation.runOnMainSync(()->{model.connected=false;model.listener=null;activity.finish();});
            release.countDown();blocked.get(5,TimeUnit.SECONDS);
            model.worker.submit(()->{}).get(5,TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();
        }
    }
    @Test public void aerialEntryScansAndOpensValidatedPageWithoutAdb() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        android.app.Instrumentation.ActivityMonitor scanner=instrumentation.addMonitor(ScanActivity.class.getName(),new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true);
        java.util.concurrent.atomic.AtomicReference<String> opened=new java.util.concurrent.atomic.AtomicReference<>();
        android.app.Instrumentation.ActivityMonitor browser=new android.app.Instrumentation.ActivityMonitor(){@Override public android.app.Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_VIEW.equals(intent.getAction())){opened.set(intent.getDataString());return new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null);}return null;}};
        instrumentation.addMonitor(browser);
        try{
            instrumentation.runOnMainSync(()->{android.view.View entry=findText(activity.getWindow().getDecorView(),context.getString(R.string.scan_aerial_qr));assertNotNull(entry);entry.performClick();});assertEquals(1,scanner.getHits());
            String url="http://192.168.1.20:8790/abc234xy/";
            instrumentation.runOnMainSync(()->activity.onActivityResult(22,android.app.Activity.RESULT_OK,new Intent().putExtra("aerial_url",url)));assertEquals(url,opened.get());
        }finally{instrumentation.removeMonitor(scanner);instrumentation.removeMonitor(browser);instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void aerialDetectionUsesRealAdbTransport() throws Exception {
        Context context=isolatedContext();CountDownLatch done=new CountDownLatch(1);ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            Future<?> remote=executor.submit(()->{try(Socket socket=server.accept()){
                socket.setSoTimeout(10000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();packet(in);send(out,"CNXN",0x01000000,65536,"device::ro.product.model=Aerial Test;\0".getBytes(StandardCharsets.UTF_8));
                String[] responses={"21\n","package:app.orchard.tv\n","versionCode=99 minSdk=21\nversionName=1.77-guard\n"};
                String[] commands={"shell:getprop ro.build.version.sdk","shell:pm list packages app.orchard.tv","shell:dumpsys package 'app.orchard.tv'"};
                for(int index=0;index<21;index++){
                    byte[] open;do{open=packet(in);}while(word(open,0)!=word("OPEN".getBytes(StandardCharsets.US_ASCII),0));int local=word(open,4);String service=new String(open,24,open.length-25,StandardCharsets.UTF_8),body;
                    if(index<6){assertEquals(commands[index%3],service);body=responses[index%3];}
                    else{assertTrue(service.contains("__AERIAL_SETUP_EXIT__"));body="";int code=0;
                        if(service.startsWith("shell:settings get"))body="com.vendor/.Notify$Inner\n";
                        if(service.startsWith("shell:settings put")){assertTrue(service.contains("'com.vendor/.Notify$Inner:"+AerialIntegration.LISTENER+"'"));}
                        if(service.contains("ACCESS_RESTRICTED_SETTINGS")){body="Unknown operation string: ACCESS_RESTRICTED_SETTINGS\n";code=1;}
                        if(service.startsWith("shell:am start"))body="Status: ok\n";
                        body+="\n__AERIAL_SETUP_EXIT__"+code+"\n";
                    }
                    send(out,"OKAY",index+1,local,new byte[0]);send(out,"WRTE",index+1,local,body.getBytes(StandardCharsets.UTF_8));packet(in);send(out,"CLSE",index+1,local,new byte[0]);
                }
                done.await(10,TimeUnit.SECONDS);
            }catch(Exception error){throw new RuntimeException(error);}});
            DeckModel model=new DeckModel(context);
            try{model.connect("127.0.0.1",server.getLocalPort(),null,0);model.refreshAerial();long deadline=android.os.SystemClock.elapsedRealtime()+8000;while(model.aerialRefreshing&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);AerialIntegration.Status status=model.aerialStatus;assertNotNull(status);assertEquals(21,status.sdk);assertEquals(99,status.installed.code);assertEquals("guard",status.installed.flavor);String summary=model.configureAerial("guard",model.devices.token());assertEquals(context.getString(R.string.aerial_setup_partial),summary);assertTrue(model.aerialSetupReport.contains("Status: ok"));assertTrue(model.aerialSetupReport.contains("com.vendor/.Notify$Inner"));done.countDown();remote.get(10,TimeUnit.SECONDS);}finally{done.countDown();model.disconnect();}
        }finally{executor.shutdownNow();}
    }
    @Test public void aerialOfficialReleaseJsonSelectsEdition() throws Exception {
        String hash="sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        org.json.JSONObject asset=new org.json.JSONObject().put("name","aerial-guard.apk").put("browser_download_url","https://github.com/pengchujin/aerial-tv/releases/download/v1.77/aerial-guard.apk").put("size",4).put("digest",hash);
        org.json.JSONObject release=new org.json.JSONObject().put("tag_name","v1.77").put("draft",false).put("prerelease",false).put("assets",new org.json.JSONArray().put(asset));
        assertEquals(4,AerialIntegration.parse(release.toString(),"guard").size);
        try{AerialIntegration.parse(release.toString(),"standard");fail();}catch(IOException expected){}
        release.put("prerelease",true);try{AerialIntegration.parse(release.toString(),"guard");fail();}catch(IOException expected){}
    }
    static final class RoutedFactory extends javax.net.SocketFactory {
        int calls;boolean fail;
        @Override public Socket createSocket(String host,int port)throws IOException{calls++;if(fail)throw new IOException("route blocked");return new Socket(host,port);}
        @Override public Socket createSocket(InetAddress host,int port)throws IOException{return createSocket(host.getHostAddress(),port);}
        @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{throw new IOException("Unexpected overload");}
        @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{throw new IOException("Unexpected overload");}
    }
    @Test public void adbAndPairingUseInjectedNetworkFactory()throws Exception{
        Context context=isolatedContext();RoutedFactory factory=new RoutedFactory();AdbIdentity identity=new AdbIdentity(context);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            try(io.github.muntashirakon.adb.AdbConnection connection=new io.github.muntashirakon.adb.AdbConnection.Builder("127.0.0.1",server.getLocalPort()).setSocketFactory(factory).setApi(35).setPrivateKey(identity.getPrivateKey()).setCertificate(identity.getCertificate()).build()){assertEquals(1,factory.calls);}
        }finally{identity.close();}
        AdbIdentity pairedIdentity=new AdbIdentity(context);factory.fail=true;
        io.github.muntashirakon.adb.AbsAdbConnectionManager manager=new io.github.muntashirakon.adb.AbsAdbConnectionManager(){
            @Override protected java.security.PrivateKey getPrivateKey(){return pairedIdentity.getPrivateKey();}
            @Override protected java.security.cert.Certificate getCertificate(){return pairedIdentity.getCertificate();}
            @Override protected String getDeviceName(){return "TVDeck Test";}
            @Override protected javax.net.SocketFactory getSocketFactory(String host){return factory;}
        };
        try{try{manager.pair("127.0.0.1",12345,"000000");fail();}catch(IOException expected){assertEquals("route blocked",expected.getMessage());}assertEquals(2,factory.calls);}finally{manager.close();}
    }
    @Test public void mainScreenStarts() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        android.app.Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull(activity);assertFalse(activity.isFinishing());instrumentation.runOnMainSync(activity::finish);
    }
    @Test public void identitySurvivesReload() throws Exception {
        Context context=isolatedContext();
        AdbIdentity first=new AdbIdentity(context),second=new AdbIdentity(context);
        assertArrayEquals(first.getCertificate().getEncoded(),second.getCertificate().getEncoded());first.close();second.close();
    }
    @Test public void binaryAdbShellRoundTrip() throws Exception {
        Context context=isolatedContext();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch readComplete=new CountDownLatch(1);
            char[] padding=new char[60000];java.util.Arrays.fill(padding,'x');String expected="TVDeck ✓\n"+new String(padding);
            AdbIdentity identity=new AdbIdentity(context);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<String> remote=executor.submit(()->{try(Socket socket=server.accept()) {
                socket.setSoTimeout(10000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();packet(in);
                byte[] token=new byte[20];for(int i=0;i<20;i++)token[i]=(byte)i;send(out,"AUTH",1,0,token);
                byte[] signed=packet(in);assertEquals(2,word(signed,4));
                javax.crypto.Cipher rsa=javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding");rsa.init(javax.crypto.Cipher.DECRYPT_MODE,identity.getCertificate().getPublicKey());byte[] decoded=rsa.doFinal(java.util.Arrays.copyOfRange(signed,24,signed.length));
                assertArrayEquals(token,java.util.Arrays.copyOfRange(decoded,decoded.length-20,decoded.length));
                send(out,"CNXN",0x01000000,65536,"device::ro.product.model=TVDeck Test;\0".getBytes(StandardCharsets.UTF_8));
                byte[] open=packet(in);int local=word(open,4);String service=new String(open,24,open.length-25,StandardCharsets.UTF_8);
                send(out,"OKAY",1,local,new byte[0]);send(out,"WRTE",1,local,expected.getBytes(StandardCharsets.UTF_8));packet(in);send(out,"CLSE",1,local,new byte[0]);readComplete.await(10,TimeUnit.SECONDS);return service;
            }});
            DeckModel model=new DeckModel(context);
            try {model.connect("127.0.0.1",server.getLocalPort(),null,0);assertEquals(expected,model.shell("echo test"));readComplete.countDown();assertEquals("shell:echo test",remote.get(10,TimeUnit.SECONDS));}
            finally {readComplete.countDown();model.disconnect();identity.close();executor.shutdownNow();}
        }
    }
}
