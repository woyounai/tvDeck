package com.tvdeck.client;

import android.content.*;
import android.os.*;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import io.github.muntashirakon.adb.AdbStream;

final class DeckModel {
    interface Work<T> { T get() throws Exception; }
    interface Listener { void changed(); void error(String message); }
    final Context context;
    final DeviceStore devices;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final ExecutorService previewWorker=Executors.newSingleThreadExecutor();
    final ExecutorService inputWorker=Executors.newSingleThreadExecutor();
    final Handler main=new Handler(Looper.getMainLooper());
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    volatile Listener listener;
    volatile boolean connected, busy;
    private volatile long lastAdbActivity;
    private static final long HEARTBEAT_IDLE_MILLIS=60_000;
    volatile String operation="", host="", deviceName="Android TV";
    volatile int port=5555;
    volatile AerialIntegration.Status aerialStatus;
    volatile Boolean aerialUpdateAvailable;
    volatile boolean aerialRefreshing;
    volatile String aerialError;
    volatile String aerialSetupReport;
    volatile boolean aerialGuardRetry;
    volatile boolean aerialWorking;
    volatile double aerialProgress=Double.NaN;
    volatile boolean installWorking;
    volatile double installProgress;
    private void installProgress(double value){if(value<1&&Math.floor(value*100)==Math.floor(installProgress*100))return;installProgress=value;notifyChanged();}
    private void aerialProgress(double value){if(!Double.isNaN(value)&&!Double.isNaN(aerialProgress)&&value<1&&Math.abs(value-aerialProgress)<0.01)return;aerialProgress=value;notifyChanged();}

    private volatile AdbIdentity manager;
    private final java.util.concurrent.atomic.AtomicLong connectionGeneration=new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean manuallyDisconnected=true;
    private AdbStream keyStream, mouseStream;
    private boolean mouseAssociated;
    private int keySequence;
    private final String session=UUID.randomUUID().toString();
    private final Set<String> uploaded=new HashSet<>();
    static final class AppPresentation {
        final String name,version;final byte[] icon;
        AppPresentation(String name,String version,byte[] icon){this.name=name;this.version=version;this.icon=icon;}
    }
    static final class AppDetails {
        final String name,packageName,version,path;final boolean system;
        AppDetails(String name,String packageName,boolean system,String version,String path){this.name=name;this.packageName=packageName;this.system=system;this.version=version;this.path=path;}
    }
    final Map<String,AppPresentation> presentations=new ConcurrentHashMap<>();

    DeckModel(Context context) { this.context=context.getApplicationContext();devices=new DeviceStore(this.context); }
    void notifyChanged() { main.post(()->{if(listener!=null) listener.changed();}); }
    <T> void run(String title,Work<T> work,Consumer<T> success) {
        if(busy) return;
        busy=true;operation=title;notifyChanged();
        worker.execute(()->{
            try { T result=work.get(); main.post(()->{busy=false;operation="";if(listener!=null) {listener.changed();success.accept(result);}else if(result instanceof Closeable)try{((Closeable)result).close();}catch(IOException ignored){} }); }
            catch(Exception error) { if(connected&&manager!=null&&!manager.isConnected())try{keepConnection();}catch(Exception ignored){}main.post(()->{busy=false;operation="";if(listener!=null) {listener.changed();listener.error(error.getLocalizedMessage()==null?error.getClass().getSimpleName():error.getLocalizedMessage());} }); }
        });
    }
    void connect(String address,int endpointPort,String pairingCode,int pairingPort) throws Exception {
        connect(address,endpointPort,pairingCode,pairingPort,60);
    }
    void connect(String address,int endpointPort,String pairingCode,int pairingPort,int timeoutSeconds) throws Exception {
        connect(address,endpointPort,pairingCode,pairingPort,timeoutSeconds,3);
    }
    void connect(String address,int endpointPort,String pairingCode,int pairingPort,int timeoutSeconds,int retries) throws Exception {
        connect(address,endpointPort,pairingCode,pairingPort,timeoutSeconds,retries,-1);
    }
    private void connect(String address,int endpointPort,String pairingCode,int pairingPort,int timeoutSeconds,int retries,long expectedGeneration) throws Exception {
        if(!UsageConsent.accepted(context))throw new IOException(context.getString(R.string.legal_required));
        long generation;
        synchronized(connectionGeneration){
            if(expectedGeneration>=0&&(manuallyDisconnected||connectionGeneration.get()!=expectedGeneration))return;
            generation=connectionGeneration.incrementAndGet();manuallyDisconnected=false;
        }
        clearConnection();
        if(manager==null) manager=new AdbIdentity(context);
        manager.setTimeout(timeoutSeconds,TimeUnit.SECONDS);
        if(pairingCode!=null && !manager.pair(address,pairingPort,pairingCode)) throw new IOException(context.getString(R.string.pair_failed));
        int[] attempts={0};
        try{
            retryConnection(()->{
                if(generation!=connectionGeneration.get())throw new InterruptedException(context.getString(R.string.not_connected));
                if(attempts[0]++>0){operation=context.getString(R.string.reconnecting_attempt,attempts[0]-1,retries);notifyChanged();manager.disconnect();manager.setTimeout(Math.min(8,timeoutSeconds),TimeUnit.SECONDS);Thread.sleep(1000);}
                if(!manager.connect(address,endpointPort))throw new IOException(context.getString(R.string.connect_failed));
                if(generation!=connectionGeneration.get()){manager.disconnect();throw new InterruptedException(context.getString(R.string.not_connected));}
                return true;
            },retries);
        }catch(Exception error){if(generation==connectionGeneration.get()){disconnect();}throw error;}
        host=address;port=endpointPort;connected=true;
        String reportedName="";
        try { reportedName=shell("getprop ro.product.model").trim().replace('_',' '); } catch(Exception ignored) { }
        if(generation!=connectionGeneration.get())throw new InterruptedException(context.getString(R.string.not_connected));
        manager.setTimeout(60,TimeUnit.SECONDS);
        DeviceStore.Device device=devices.endpoint(host,port);device.connectedAt=System.currentTimeMillis();
        device.name=resolvedDeviceName(device.name,host,reportedName);
        deviceName=device.name;
        devices.save(device);devices.publish(device);DeckWidget.updateAll(context);
    }
    static String resolvedDeviceName(String savedName,String host,String modelName){
        String saved=savedName==null?"":savedName.trim();if(!saved.isEmpty()&&!saved.equals("Android TV")&&!saved.equals(host))return saved;
        String model=modelName==null?"":modelName.trim().replace('_',' ');return model.isEmpty()?"Android TV":model;
    }
    void disconnect() {
        synchronized(connectionGeneration){manuallyDisconnected=true;connectionGeneration.incrementAndGet();}
        clearConnection();
    }
    private void clearConnection(){
        stopMouse();connected=false;devices.invalidate();aerialStatus=null;aerialUpdateAvailable=null;aerialRefreshing=false;aerialError=null;aerialSetupReport=null;aerialGuardRetry=false;DeckWidget.updateAll(context);
        close(keyStream);keyStream=null;uploaded.clear();presentations.clear();
        if(manager!=null) try { manager.disconnect(); } catch(IOException ignored) { }
        notifyChanged();
    }
    // Retry only connection establishment. Never replay a device command after a lost reply.
    static <T> T retryConnection(Work<T> attempt)throws Exception{return retryConnection(attempt,3);}
    private static <T> T retryConnection(Work<T> attempt,int retries)throws Exception{
        for(int retry=0;;retry++){
            try{return attempt.get();}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
            catch(Exception error){if(retry==retries)throw error;}
        }
    }
    static boolean heartbeatDue(long now,long lastActivity){
        return now-lastActivity>=HEARTBEAT_IDLE_MILLIS;
    }
    boolean heartbeatDue(){
        return heartbeatDue(SystemClock.elapsedRealtime(),lastAdbActivity);
    }
    private void markAdbActivity(){
        lastAdbActivity=SystemClock.elapsedRealtime();
    }
    void keepConnection()throws Exception{
        if(manuallyDisconnected)return;
        String address=host;int endpointPort=port;long generation=connectionGeneration.get();
        try{
            String reply=new String(read("shell:echo TVDECK_KEEPALIVE",128,8),StandardCharsets.UTF_8);
            if(!reply.trim().equals("TVDECK_KEEPALIVE"))throw new IOException("Invalid ADB heartbeat reply");
        }
        catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
        catch(Exception error){
            if(manuallyDisconnected)return;
            operation=context.getString(R.string.connecting);notifyChanged();
            connect(address,endpointPort,null,0,8,2,generation);
        }
    }
    void restoreConnection()throws Exception{
        DeviceStore.Device device=devices.find(devices.selectedId());
        if(device!=null&&!devices.token().isEmpty())connect(device.host,device.port,null,0,8);
    }
    void refreshAerial(){
        if(!connected||aerialRefreshing)return;String token=devices.token();aerialRefreshing=true;aerialError=null;notifyChanged();
        worker.execute(()->{try{AerialIntegration.Status status=AerialIntegration.status(this::shell);if(connected&&token.equals(devices.token())){aerialStatus=status;aerialUpdateAvailable=null;}}
            catch(Exception error){if(token.equals(devices.token())){aerialStatus=null;aerialError=context.getString(R.string.aerial_detection_failed);}}
            finally{if(token.equals(devices.token())){aerialRefreshing=false;notifyChanged();}}});
    }
    boolean checkAerialUpdate()throws Exception{
        String token=devices.token();if(!connected||token.isEmpty())throw new IOException(context.getString(R.string.not_connected));
        AerialIntegration.Status status=AerialIntegration.status(this::shell);
        if(status.installed==null)throw new IOException(context.getString(R.string.aerial_detection_failed));
        String flavor=status.installed.flavor;
        AerialIntegration.Release release=AerialIntegration.latest(flavor);
        File apk=AerialIntegration.download(release,context.getCacheDir());
        try(InstallBundle bundle=new InstallBundle(context,Uri.fromFile(apk))){
            if(bundle.apks.size()!=1||!bundle.apks.get(0).manifest.packageName.equals(AerialIntegration.packageName(flavor)))throw new IOException(context.getString(R.string.aerial_download_failed));
            android.content.pm.PackageInfo info=context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
            if(info==null)throw new IOException(context.getString(R.string.aerial_download_failed));
            long code=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():Integer.toUnsignedLong(info.versionCode);
            if(!connected||!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));
            AerialIntegration.Status current=AerialIntegration.status(this::shell);
            if(!connected||!token.equals(devices.token())||current.installed==null||!current.installed.flavor.equals(flavor))throw new IOException(context.getString(R.string.aerial_device_changed));
            aerialStatus=current;aerialUpdateAvailable=code>current.installed.code;return aerialUpdateAvailable;
        }finally{apk.delete();}
    }
    boolean[] aerialHomeStatus()throws Exception{
        if(!connected||aerialStatus==null||aerialStatus.installed==null)throw new IOException(context.getString(R.string.aerial_detection_failed));
        String token=devices.token();
        String flavor=aerialStatus.installed.flavor;
        String resolved=shell(AerialIntegration.RESOLVE_HOME);
        String accessibility=flavor.equals("lite")?"":shell("settings get secure enabled_accessibility_services");
        if(!connected||!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));
        return AerialIntegration.homeStatus(flavor,resolved,accessibility);
    }
    String installAerial(String requested,String expectedToken)throws Exception{
        if(!UsageConsent.accepted(context))throw new IOException(context.getString(R.string.legal_required));
        if(!connected||expectedToken.isEmpty()||!expectedToken.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));
        aerialWorking=true;aerialProgress(Double.NaN);
        try{return installAerialPackage(requested,expectedToken);}finally{aerialWorking=false;aerialProgress(Double.NaN);}
    }
    private String installAerialPackage(String requested,String expectedToken)throws Exception{
        aerialSetupReport=null;
        String token=expectedToken;if(!connected||!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));
        AerialIntegration.Status before=AerialIntegration.status(this::shell);if(before.sdk<14)throw new IOException(context.getString(R.string.aerial_unsupported_sdk));aerialGuardRetry=false;String flavor=before.installed==null?(before.sdk<21?"lite":requested):before.installed.flavor;
        if(!flavor.equals(requested))throw new IOException(context.getString(R.string.aerial_device_changed));
        operation=context.getString(R.string.aerial_downloading);notifyChanged();AerialIntegration.Release release=AerialIntegration.latest(flavor);File apk=AerialIntegration.download(release,context.getCacheDir(),this::aerialProgress);
        try{
            if(!connected||!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));
            try(InstallBundle bundle=new InstallBundle(context,Uri.fromFile(apk))){
                if(bundle.apks.size()!=1||!bundle.apks.get(0).manifest.packageName.equals(AerialIntegration.packageName(flavor)))throw new IOException(context.getString(R.string.aerial_download_failed));
                android.content.pm.PackageInfo info=context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);if(info==null)throw new IOException(context.getString(R.string.aerial_download_failed));long code=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():Integer.toUnsignedLong(info.versionCode);
                AerialIntegration.Status current=AerialIntegration.status(this::shell);
                if(!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));
                if(current.installed!=null){if(!current.installed.flavor.equals(flavor))throw new IOException(context.getString(R.string.aerial_device_changed));if(current.installed.code>=code){aerialStatus=current;aerialUpdateAvailable=false;return configureAerial(token,current);}}
                operation=context.getString(R.string.aerial_installing);aerialProgress(Double.NaN);String result;try{result=install(Collections.singletonList(Uri.fromFile(apk)),true);}catch(Exception error){if(token.equals(devices.token()))aerialGuardRetry=flavor.equals("standard")&&current.installed==null;throw error;}rememberInstall("Aerial "+info.versionName,result);
                if(!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));AerialIntegration.Status after=AerialIntegration.status(this::shell);
                if(!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));if(after.installed==null||!after.installed.flavor.equals(flavor)||after.installed.code<code)throw new IOException(context.getString(R.string.aerial_detection_failed));aerialStatus=after;aerialUpdateAvailable=false;return configureAerial(token,after);
            }
        }finally{apk.delete();}
    }
    String configureAerial(String approvedFlavor,String expectedToken)throws Exception{
        if(!UsageConsent.accepted(context))throw new IOException(context.getString(R.string.legal_required));
        if(!connected||expectedToken.isEmpty()||!expectedToken.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));
        aerialWorking=true;
        aerialProgress(Double.NaN);
        try{
            aerialSetupReport=null;
            AerialIntegration.Status status=AerialIntegration.status(this::shell);
            if(status.installed==null||!approvedFlavor.equals(status.installed.flavor))
                throw new IOException(context.getString(R.string.aerial_device_changed));
            return configureAerial(expectedToken,status);
        }finally{
            aerialWorking=false;
            aerialProgress(Double.NaN);
        }
    }
    private String configureAerial(String token,AerialIntegration.Status status)throws Exception{
        if(status.installed==null||!connected||!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));if(status.sdk<14)throw new IOException(context.getString(R.string.aerial_unsupported_sdk));
        operation=context.getString(R.string.aerial_configuring);aerialProgress(0);int[] completed={0};int total=AerialIntegration.grantCommands(status.installed.flavor).size()+(status.installed.flavor.equals("lite")?1:6);AerialIntegration.SetupResult result=AerialIntegration.configure(command->{if(!connected||!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));String output=shell(command);aerialProgress(Math.min(0.99,(double)++completed[0]/total));return output;},status.installed.flavor,status.sdk);
        if(!token.equals(devices.token()))throw new IOException(context.getString(R.string.aerial_device_changed));aerialSetupReport=result.report;aerialStatus=status;if(result.launched)aerialProgress(1);
        return context.getString(result.launched?(result.warnings==0?R.string.aerial_setup_complete:R.string.aerial_setup_partial):R.string.aerial_launch_failed);
    }
    private static void close(AdbStream stream) { if(stream!=null) try {stream.close();} catch(IOException ignored) { } }
    AdbStream open(String service) throws Exception {
        if(!UsageConsent.accepted(context))throw new IOException(context.getString(R.string.legal_required));
        if(!connected) throw new IOException(context.getString(R.string.not_connected));
        AdbStream stream=manager.openStream(service);
        markAdbActivity();
        return stream;
    }
    InputStream input(AdbStream stream) {return stream.openInputStream();}
    byte[] read(String service,int limit,int timeoutSeconds) throws Exception {
        if(!UsageConsent.accepted(context))throw new IOException(context.getString(R.string.legal_required));
        if(!connected)throw new IOException(context.getString(R.string.not_connected));
        long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try(AdbStream stream=manager.openStream(service,timeoutSeconds,TimeUnit.SECONDS)) {
            long remaining=expires-System.nanoTime();
            if(remaining<=0)throw new IOException(context.getString(R.string.timeout));
            java.util.concurrent.atomic.AtomicBoolean expired=new java.util.concurrent.atomic.AtomicBoolean();
            ScheduledFuture<?> deadline=timer.schedule(()->{expired.set(true);close(stream);},remaining,TimeUnit.NANOSECONDS);
            try {
                InputStream in=input(stream);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
                while((n=in.read(buffer))!=-1) { if(out.size()+n>limit) throw new IOException(context.getString(R.string.output_too_large));out.write(buffer,0,n); }
                if(expired.get()||System.nanoTime()-expires>=0)throw new IOException(context.getString(R.string.timeout));
                if(!connected||!manager.isConnected())throw new IOException(context.getString(R.string.not_connected));
                markAdbActivity();
                return out.toByteArray();
            } finally {deadline.cancel(false);}
        }
    }
    String shell(String command) throws Exception {return new String(read("shell:"+command,4*1024*1024,45),StandardCharsets.UTF_8);}
    String checked(String command) throws Exception {
        String marker="__TVDECK_EXIT__";
        String output=shell(command+"; printf '\\n"+marker+"%s\\n' \"$?\"");
        int end=output.lastIndexOf(marker);if(end<0)throw new IOException(output);
        String status=output.substring(end+marker.length()).trim();String result=output.substring(0,end).trim();
        if(!status.equals("0")||result.startsWith("Failure")||result.contains("Exception"))throw new IOException(result.isEmpty()?"Command failed ("+status+")":result);
        return result;
    }
    static String quote(String value) {
        if(value.indexOf('\0')>=0) throw new IllegalArgumentException("NUL");
        return "'"+value.replace("'","'\\''")+"'";
    }
    List<AdbFiles.Entry> files(String path) throws Exception {AdbStream stream=open("sync:");return AdbFiles.list(stream,path,input(stream));}
    void pull(String remote,OutputStream out) throws Exception {AdbStream stream=open("sync:");AdbFiles.pull(stream,remote,out,input(stream));}
    void push(String remote,InputStream in,int mode) throws Exception {AdbStream stream=open("sync:");AdbFiles.push(stream,remote,in,mode,input(stream));}
    String mutateFile(String action,String path,String target) throws Exception {
        switch(action) {
            case "mkdir": return checked("mkdir -- "+quote(path));
            case "rename": return checked("mv -n -- "+quote(path)+" "+quote(target));
            case "delete": return checked("rm -rf -- "+quote(path));
            default: throw new IllegalArgumentException();
        }
    }
    String apps(boolean system) throws Exception { return apps(system?1:0); }
    String apps(int filter) throws Exception { return shell("pm list packages -f "+(filter==0?"-3":filter==1?"-s":"")); }
    AppDetails appDetails(String pkg)throws Exception{
        if(!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))throw new IllegalArgumentException("Package");
        AppPresentation app=presentations.get(pkg);if(app==null)app=loadPresentations(Collections.singletonList(pkg)).get(pkg);
        String systemPackages=shell("pm list packages -s "+quote(pkg)),paths=shell("pm path "+quote(pkg));
        boolean system=Arrays.asList(systemPackages.split("\\r?\\n")).contains("package:"+pkg);
        String path="";for(String line:paths.split("\\r?\\n"))if(line.startsWith("package:")){path=line.substring(8);if(path.endsWith("/base.apk"))break;}
        return new AppDetails(app==null?pkg:app.name,pkg,system,app==null?"":app.version,path);
    }
    Map<String,AppPresentation> loadPresentations(List<String> packages) throws Exception {
        String path=helper("TVADBIconHelper.dex",0600);
        for(int offset=0;offset<packages.size();offset+=8) {
            StringBuilder command=new StringBuilder("CLASSPATH="+quote(path)+" app_process / com.tvadb.helper.AppPresentationHelper");
            for(String pkg:packages.subList(offset,Math.min(offset+8,packages.size())))command.append(' ').append(quote(pkg));
            String response=shell(command.toString()+" 2>/dev/null");
            for(String line:response.split("\\r?\\n")) {
                String[] values=line.split("\\t",-1);
                if(values.length!=5||!values[0].equals("A"))continue;
                try {String pkg=TextSession.decode(values[1]);String name=TextSession.decode(values[2]);String version=TextSession.decode(values[3]);byte[] icon=android.util.Base64.decode(values[4],android.util.Base64.DEFAULT);if(icon.length<256*1024)presentations.put(pkg,new AppPresentation(name,version,icon));}catch(IllegalArgumentException ignored){}
            }
        }
        return presentations;
    }
    String appAction(String action,String pkg) throws Exception {
        if(!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) throw new IllegalArgumentException("Package");
        String p=quote(pkg);
        switch(action) {
            case "details":return shell("dumpsys package "+p);
            case "launch":return checked("monkey -p "+p+" -c android.intent.category.LAUNCHER 1");
            case "stop":return checked("am force-stop "+p);
            case "cache":return checked("pm clear --cache-only "+p);
            case "clear":return checked("pm clear "+p);
            case "uninstall":String result=checked("pm uninstall "+p);presentations.remove(pkg);return result;
            case "paths":return shell("pm path "+p);
            default:throw new IllegalArgumentException();
        }
    }
    String install(List<Uri> uris) throws Exception {return install(uris,context.getSharedPreferences("settings",0).getBoolean("replace-install",true));}
    String install(List<Uri> uris,boolean replace) throws Exception {return install(uris,replace,value->{});}
    String install(List<Uri> uris,boolean replace,Consumer<Double> progress) throws Exception {
        if(uris.isEmpty()||uris.size()>100)throw new IOException("Invalid APK selection");
        List<String> paths=new ArrayList<>();String installSession=null;String flags=replace?" -r":"";
        long totalBytes=0;for(Uri uri:uris)if("file".equals(uri.getScheme()))totalBytes+=new File(uri.getPath()).length();
        final long expectedBytes=totalBytes;final long[] sent={0};
        try {
            for(int i=0;i<uris.size();i++) {String remote="/data/local/tmp/tvdeck-install-"+session+"-"+i+".apk";paths.add(remote);try(InputStream in=context.getContentResolver().openInputStream(uris.get(i))){if(in==null)throw new IOException("File unavailable");push(remote,new FilterInputStream(in){@Override public int read(byte[] bytes,int offset,int length)throws IOException{int count=super.read(bytes,offset,length);if(count>0&&expectedBytes>0){sent[0]+=count;progress.accept(0.85*sent[0]/expectedBytes);}return count;}},0600);}if(expectedBytes==0)progress.accept(0.85*(i+1)/uris.size());}
            if(paths.size()==1){String result=shell("pm install"+flags+" "+quote(paths.get(0)));if(!result.contains("Success"))throw new IOException(result);progress.accept(1.0);return result;}
            String created=shell("pm install-create"+flags);java.util.regex.Matcher match=java.util.regex.Pattern.compile("\\[([0-9]+)\\]").matcher(created);if(!match.find())throw new IOException(created);installSession=match.group(1);
            for(int i=0;i<paths.size();i++){String result=shell("pm install-write "+installSession+" split"+i+" "+quote(paths.get(i)));if(!result.contains("Success"))throw new IOException(result);progress.accept(0.85+0.1*(i+1)/paths.size());}
            String result=shell("pm install-commit "+installSession);if(!result.contains("Success"))throw new IOException(result);installSession=null;progress.accept(1.0);return result;
        } finally {
            if(installSession!=null)try{shell("pm install-abandon "+installSession);}catch(Exception ignored){}
            for(String path:paths)try{shell("rm -f -- "+quote(path));}catch(Exception ignored){}
        }
    }
    String installBundle(InstallBundle bundle)throws Exception{
        installWorking=true;installProgress=0;notifyChanged();
        String pkg=bundle.apks.get(0).manifest.packageName;
        String version="";for(InstallBundle.Apk apk:bundle.apks)if(apk.manifest.split.isEmpty()){android.content.pm.PackageInfo info=context.getPackageManager().getPackageArchiveInfo(apk.file.getAbsolutePath(),0);if(info!=null)version=info.versionName;break;}
        try{return installBundleContents(bundle,pkg,version);}catch(Exception error){rememberInstall(bundle.filename,pkg,version,"Failed: "+error.getMessage());throw error;}finally{installWorking=false;notifyChanged();}
    }
    private String installBundleContents(InstallBundle bundle,String pkg,String version)throws Exception{
        String[] abis=shell("getprop ro.product.cpu.abilist; getprop ro.product.cpu.abi").trim().split("[\\r\\n,]+");String densityOutput=shell("wm density"),locale=shell("getprop persist.sys.locale").trim();int density=320;java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("[0-9]+").matcher(densityOutput);while(matcher.find())density=Integer.parseInt(matcher.group());
        List<InstallBundle.Apk> selected=bundle.select(abis,density,locale.isEmpty()?Locale.getDefault().getLanguage():locale.split("[-_]")[0]);
        for(File obb:bundle.obbs)if(!obb.getName().matches("(main|patch)\\.[0-9]+\\."+java.util.regex.Pattern.quote(pkg)+"\\.obb"))throw new IOException("OBB package mismatch");
        String result=install(bundle.uris(selected),context.getSharedPreferences("settings",0).getBoolean("replace-install",true),value->installProgress(value*(bundle.obbs.isEmpty()?1:0.8)));
        try{int completed=0;for(File obb:bundle.obbs){String name=obb.getName();if(!name.matches("(main|patch)\\.[0-9]+\\."+java.util.regex.Pattern.quote(pkg)+"\\.obb"))throw new IOException("OBB package mismatch");String directory="/sdcard/Android/obb/"+pkg;checked("mkdir -p -- "+quote(directory));new Transfers(this).upload(Uri.fromFile(obb),directory+"/"+name,true);installProgress(0.8+0.2*++completed/bundle.obbs.size());}}
        catch(Exception error){throw new IOException(context.getString(R.string.obb_failed),error);}
        rememberInstall(bundle.filename,pkg,version,result);return result;
    }
    void rememberInstall(String name,String result){rememberInstall(name,"","",result);}
    void rememberInstall(String name,String pkg,String version,String result){SharedPreferences preferences=context.getSharedPreferences("install-history",0);try{org.json.JSONArray old=installationHistory(),items=new org.json.JSONArray();items.put(new org.json.JSONObject().put("name",name).put("package",pkg).put("version",version).put("device",deviceName).put("source",name.startsWith("Aerial ")?"Aerial":"files").put("result",result).put("at",System.currentTimeMillis()));for(int i=0;i<Math.min(old.length(),199);i++)items.put(old.get(i));preferences.edit().putString("items",items.toString()).apply();}catch(org.json.JSONException ignored){}}
    org.json.JSONArray installationHistory(){try{return new org.json.JSONArray(context.getSharedPreferences("install-history",0).getString("items","[]"));}catch(org.json.JSONException ignored){return new org.json.JSONArray();}}
    void clearInstallationHistory(){context.getSharedPreferences("install-history",0).edit().remove("items").apply();}
    void exportApks(List<String> paths,OutputStream destination) throws Exception {
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(destination)) {
            int index=0;for(String path:paths){String name=path.substring(path.lastIndexOf('/')+1);zip.putNextEntry(new java.util.zip.ZipEntry((index++)+"-"+name));pull(path,zip);zip.closeEntry();}
        }
    }
    int[] screenSize() throws Exception {
        String result=shell("wm size");java.util.regex.Matcher match=java.util.regex.Pattern.compile("([0-9]+)x([0-9]+)").matcher(result);int w=1920,h=1080;while(match.find()){w=Integer.parseInt(match.group(1));h=Integer.parseInt(match.group(2));}return new int[]{w,h};
    }
    static int[] videoSize(int width,int height,boolean bounded){
        if(!bounded)return new int[]{width,height};
        double scale=Math.min(1,1280.0/Math.max(width,height));
        return new int[]{Math.max(16,(int)(width*scale)/16*16),Math.max(16,(int)(height*scale)/16*16)};
    }
    AdbStream openHardwareVideo()throws Exception {
        String path=helper("TVADBVideoStream.dex",0600);
        return open("exec:CLASSPATH="+quote(path)+" app_process / com.tvadb.helper.VideoStream 1280 1500000 30 2>/dev/null");
    }
    AdbStream openVideo(int width,int height,boolean bounded)throws Exception {
        int[] size=videoSize(width,height,bounded);int w=size[0],h=size[1];
        return open("exec:screenrecord --output-format=h264 --bit-rate 2000000 "+(bounded?"--size "+w+"x"+h+" ":"")+"-");
    }
    static long screenshotPreviewDelay(long elapsedMillis){return Math.max(80,250-Math.max(0,elapsedMillis));}
    byte[] screenshot() throws Exception {return read("exec:screencap -p",24*1024*1024,15);}
    String diagnostic(boolean network) throws Exception {
        return shell(network?"ip addr show; ip route; dumpsys connectivity | head -n 80":"getprop ro.product.model; getprop ro.build.version.release; getprop ro.product.cpu.abi; wm size; wm density; df -h; dumpsys battery");
    }
    String reboot(String target) throws Exception {try(AdbStream stream=open("reboot:"+target)) { return ""; }}
    String helper(String asset,int mode) throws Exception {
        String path="/data/local/tmp/tvdeck-"+session+"-"+asset;
        if(!uploaded.contains(asset)) {
            try(InputStream source=context.getAssets().open(asset)) {push(path,source,mode);}
            uploaded.add(asset);
        }
        return path;
    }
    synchronized void key(int code) throws Exception {
        if(code<0||code>400) throw new IllegalArgumentException("Key code");
        if(code<19 || code>23) {shell("input keyevent "+code);return;}
        if(keyStream==null) {
            try {
                String path=helper("TVADBRemoteKeys.dex",0600);
                keyStream=open("exec:CLASSPATH="+quote(path)+" app_process / com.tvadb.helper.RemoteKeys");
                if(!line(keyStream).equals("TVADB_KEYS_READY_V1")) throw new IOException("Key helper unavailable");
            } catch(Exception error) {close(keyStream);keyStream=null;shell("input keyevent "+code);return;}
        }
        int id=++keySequence;
        write(keyStream,"K "+id+" "+code);
        if(!line(keyStream).equals("OK "+id)) {close(keyStream);keyStream=null;throw new IOException("Key rejected");}
        markAdbActivity();
    }
    static final class VolumeState {
        final int current,min,max;
        VolumeState(int current,int min,int max){this.current=current;this.min=min;this.max=max;}
    }
    VolumeState volumeState() throws Exception {
        return parseVolumeState(shell("media volume --stream 3 --get"));
    }
    static VolumeState parseVolumeState(String result) throws IOException {
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)\\]").matcher(result);
        if(!match.find())throw new IOException("TV volume range unavailable");
        VolumeState state=new VolumeState(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3)));
        if(state.min>state.current||state.current>state.max||state.min==state.max)throw new IOException("Invalid TV volume range");
        return state;
    }
    VolumeState setVolume(int target) throws Exception {
        VolumeState state=volumeState();
        if(target<state.min||target>state.max)throw new IllegalArgumentException("Volume outside TV range");
        shell("media volume --stream 3 --set "+target);
        return volumeState();
    }
    void tuneChannel(String digits) throws Exception {
        if(!digits.matches("[0-9]{1,4}"))throw new IllegalArgumentException("Invalid channel number");
        for(int i=0;i<digits.length();i++)key(7+digits.charAt(i)-'0');
        key(66);
    }
    synchronized void prepareMouse() throws Exception {
        if(mouseStream!=null) return;
        String abi=shell("getprop ro.product.cpu.abi").trim();
        String arch=abi.contains("arm64")?"arm64":abi.contains("armeabi")?"armv7":abi.contains("x86_64")?"x86_64":"x86";
        String path=helper("TVADBVirtualMouse-"+arch+".bin",0700);
        String sdk=shell("getprop ro.build.version.sdk").trim();
        if(sdk.matches("[0-9]+")&&Integer.parseInt(sdk)>=35){
            String helper=helper("TVADBIconHelper.dex",0600);
            String association=shell("CLASSPATH="+quote(helper)+" app_process / com.tvadb.helper.MouseDisplayAssociation add 'tvadb:virtual-mouse' 0 2>/dev/null");
            if(!association.contains("TVADB_MOUSE_ASSOCIATION_READY"))throw new IOException("TV rejected mouse display association");mouseAssociated=true;
        }
        try {
            mouseStream=open("exec:exec "+quote(path));
            String reply=line(mouseStream);
            if(!reply.equals("TVADB_UHID_READY")&&!reply.equals("TVADB_UINPUT_READY"))throw new IOException(reply);
        }catch(Exception failure){stopMouse();throw failure;}
    }
    synchronized void centerMousePad() throws Exception {
        int[] size=screenSize();
        mouse("H "+size[0]/2+" "+size[1]/2);
        if(!line(mouseStream).equals("TVADB_MOUSE_CENTERED"))throw new IOException("Mouse centering failed");
    }
    boolean mousePadTap(int x,int y) throws Exception {
        String output=shell("input touchscreen tap "+x+" "+y+" 2>&1; echo TVDECK_TAP_STATUS=$?");
        return output.trim().endsWith("TVDECK_TAP_STATUS=0");
    }
    synchronized void mouse(String command) throws Exception {prepareMouse();try{write(mouseStream,command);markAdbActivity();}catch(Exception error){close(mouseStream);mouseStream=null;throw error;}}
    synchronized void stopMouse() {close(mouseStream);mouseStream=null;if(mouseAssociated){mouseAssociated=false;try{String helper=helper("TVADBIconHelper.dex",0600);shell("CLASSPATH="+quote(helper)+" app_process / com.tvadb.helper.MouseDisplayAssociation remove 'tvadb:virtual-mouse' 2>/dev/null");}catch(Exception ignored){}}}
    void touch(int x1,int y1,int x2,int y2,int duration,boolean swipe) throws Exception {
        shell(swipe?"input touchscreen swipe "+x1+" "+y1+" "+x2+" "+y2+" "+duration:"input touchscreen tap "+x1+" "+y1);
    }
    static void write(AdbStream stream,String line) throws IOException {OutputStream out=stream.openOutputStream();out.write((line+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    String line(AdbStream stream) throws Exception {
        ScheduledFuture<?> deadline=timer.schedule(()->close(stream),5,TimeUnit.SECONDS);
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=input(stream);int b;
            while((b=in.read())!=-1 && b!='\n') {if(out.size()>=32768) throw new IOException("Response too long");out.write(b);}
            if(b==-1) throw new EOFException();return out.toString("UTF-8").trim();
        } finally {deadline.cancel(false);}
    }
    TextSession textSession() throws Exception {
        String ime=shell("ime list -a -s; settings get secure default_input_method");Set<String> packages=new TreeSet<>();
        for(String line:ime.split("\\r?\\n")) {String pkg=line.split("/")[0].trim();if(pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) packages.add(pkg);}
        if(packages.isEmpty()) throw new IOException(context.getString(R.string.text_unsupported));
        String excluded=android.util.Base64.encodeToString(String.join("\n",packages).getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);
        String path=helper("TVADBTextSync.dex",0600);
        AdbStream stream=open("exec:CLASSPATH="+quote(path)+" app_process / com.tvadb.helper.TextSync "+quote(excluded));
        if(!line(stream).equals("TVADB_TEXT_READY_V1")) {close(stream);throw new IOException(context.getString(R.string.text_unsupported));}
        return new TextSession(this,stream);
    }
}
