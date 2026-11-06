package com.tvdeck.client;

import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.HttpsURLConnection;

final class AerialIntegration {
    static final String API="https://api.github.com/repos/pengchujin/aerial-tv/releases/latest";
    static final int MAXIMUM=64*1024*1024;
    interface Shell {String run(String command)throws Exception;}
    static final class Installed {
        final String flavor,version;final long code;
        Installed(String flavor,String version,long code){this.flavor=flavor;this.version=version;this.code=code;}
    }
    static final class Status {final int sdk;final Installed installed;Status(int sdk,Installed installed){this.sdk=sdk;this.installed=installed;}}
    static String packageName(String flavor){if(!Arrays.asList("standard","guard","lite").contains(flavor))throw new IllegalArgumentException("Aerial edition");return flavor.equals("lite")?"app.orchard.tv.lite":"app.orchard.tv";}
    static Installed installed(String pkg,String dump)throws IOException{
        if(!pkg.equals("app.orchard.tv")&&!pkg.equals("app.orchard.tv.lite"))throw new IOException("Unexpected Aerial package");
        Matcher name=Pattern.compile("(?m)^\\s*versionName=([^\\r\\n]+)").matcher(dump),code=Pattern.compile("(?m)^\\s*versionCode=([0-9]+)(?:\\s|$)").matcher(dump);
        if(!name.find()||!code.find())throw new IOException("Cannot read Aerial version");String version=name.group(1).trim();if(version.isEmpty())throw new IOException("Cannot read Aerial version");
        try{return new Installed(pkg.endsWith(".lite")?"lite":version.contains("-guard")?"guard":"standard",version,Long.parseLong(code.group(1)));}catch(NumberFormatException error){throw new IOException("Invalid Aerial version",error);}
    }
    static Status status(Shell shell)throws Exception{
        int sdk;try{sdk=Integer.parseInt(shell.run("getprop ro.build.version.sdk").trim());}catch(NumberFormatException error){throw new IOException("Cannot read Android SDK",error);}if(sdk<1)throw new IOException("Invalid Android SDK");
        Set<String> packages=new HashSet<>();for(String line:shell.run("pm list packages app.orchard.tv").split("\\r?\\n")){if(line.trim().isEmpty())continue;if(!line.startsWith("package:"))throw new IOException("Cannot query installed packages");packages.add(line.substring(8).trim());}
        for(String pkg:new String[]{"app.orchard.tv","app.orchard.tv.lite"})if(packages.contains(pkg))return new Status(sdk,installed(pkg,shell.run("dumpsys package "+DeckModel.quote(pkg))));return new Status(sdk,null);
    }
    static final String LISTENER="app.orchard.tv/app.orchard.tv.guard.RevivalListener";
    static final String HOME_GUARD="app.orchard.tv/app.orchard.tv.guard.HomeGuardService";
    static final String RESOLVE_HOME="cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT";
    static boolean[] homeStatus(String flavor,String resolved,String accessibility){
        String[] lines=resolved.trim().split("\\r?\\n");
        return new boolean[]{lines[lines.length-1].trim().startsWith(packageName(flavor)+"/"),Arrays.asList(accessibility.trim().split(":")).contains(HOME_GUARD)};
    }
    static List<String> grantCommands(String flavor){
        packageName(flavor);if(flavor.equals("lite"))return Collections.singletonList("pm grant app.orchard.tv.lite android.permission.WRITE_SECURE_SETTINGS");
        List<String> commands=new ArrayList<>();commands.add("pm grant app.orchard.tv android.permission.ACCESS_FINE_LOCATION");commands.add("pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS");
        for(String operation:new String[]{"SYSTEM_ALERT_WINDOW","WRITE_SETTINGS","REQUEST_INSTALL_PACKAGES","GET_USAGE_STATS","android:get_usage_stats","ACCESS_RESTRICTED_SETTINGS","APP_AUTO_START","APP_ASSOC_START","AUTO_START","ASSOC_START"})commands.add("appops set app.orchard.tv "+operation+" allow");return commands;
    }
    static String mergedListeners(String raw)throws IOException{
        String value=raw.trim();if(value.contains("\n")||value.contains("\r")||value.indexOf(0)>=0)throw new IOException("Invalid notification listener setting");List<String> values=new ArrayList<>();
        if(!value.isEmpty()&&!value.equals("null"))for(String item:value.split(":")){if(item.isEmpty())continue;if(!item.contains("/")||item.contains(" "))throw new IOException("Invalid notification listener setting");values.add(item);}
        if(!values.contains(LISTENER))values.add(LISTENER);return String.join(":",values);
    }
    static String launchCommand(String flavor){packageName(flavor);return "am start -W -n "+(flavor.equals("lite")?"app.orchard.tv.lite/.HomeActivity":"app.orchard.tv/.MainActivity");}
    static boolean commandSucceeded(int code,String output){if(code!=0)return false;String lower=output.toLowerCase(Locale.ROOT);for(String error:new String[]{"error:","exception","unknown command","unknown operation","not found","permission denial"})if(lower.contains(error))return false;return true;}
    static boolean launchSucceeded(String output){return Pattern.compile("(?m)^\\s*Status:\\s*ok\\s*$").matcher(output).find();}
    static final class SetupResult {final String report;final int warnings;final boolean launched;SetupResult(String report,int warnings,boolean launched){this.report=report;this.warnings=warnings;this.launched=launched;}}
    private static final class Reply {final int code;final String text;Reply(int code,String text){this.code=code;this.text=text;}boolean succeeded(){return commandSucceeded(code,text);}}
    private static Reply step(Shell shell,String command,StringBuilder report)throws Exception{
        String output=shell.run(command+"; printf '\\n__AERIAL_SETUP_EXIT__%s\\n' \"$?\"");int marker=output.lastIndexOf("__AERIAL_SETUP_EXIT__");if(marker<0)throw new IOException("Missing Aerial setup exit status");int code;try{code=Integer.parseInt(output.substring(marker+"__AERIAL_SETUP_EXIT__".length()).trim());}catch(NumberFormatException error){throw new IOException("Invalid Aerial setup exit status",error);}String body=output.substring(0,marker);report.append("$ ").append(command).append('\n').append(body.length()>16384?body.substring(0,16384)+"\n[output truncated]\n":body).append("[exit ").append(code).append("]\n\n");return new Reply(code,body);
    }
    static SetupResult configure(Shell shell,String flavor,int sdk)throws Exception{
        if(sdk<14||!flavor.equals("lite")&&sdk<21)throw new IOException("Unsupported Android SDK");StringBuilder report=new StringBuilder();int warnings=0;
        try{
            for(String command:grantCommands(flavor))if(!step(shell,command,report).succeeded())warnings++;
            if(!flavor.equals("lite")){
                boolean fallback=sdk<26;if(!fallback)fallback=!step(shell,"cmd notification allow_listener "+LISTENER,report).succeeded();
                if(fallback){Reply current=step(shell,"settings get secure enabled_notification_listeners",report);if(current.code!=0)warnings++;else{String merged=null;try{merged=mergedListeners(current.text);}catch(IOException invalid){warnings++;}if(merged!=null&&!step(shell,"settings put secure enabled_notification_listeners "+DeckModel.quote(merged),report).succeeded())warnings++;}}
                if(sdk>=24){step(shell,launchCommand(flavor),report);Thread.sleep(5000);if(!step(shell,"cmd package compile -m speed -f app.orchard.tv",report).succeeded())warnings++;}
            }
            Reply launch=step(shell,launchCommand(flavor),report);return new SetupResult(report.toString(),warnings,launch.succeeded()&&launchSucceeded(launch.text));
        }catch(Exception error){report.append(error.getLocalizedMessage());return new SetupResult(report.toString(),warnings+1,false);}
    }
    static final class Release {
        final String version,url,digest;final int size;
        Release(String version,String url,int size,String digest,String flavor)throws Exception{
            packageName(flavor);URI uri=new URI(url);
            if(!version.matches("v[0-9]+(?:\\.[0-9]+)*")||size<1||size>MAXIMUM||!digest.matches("sha256:[a-fA-F0-9]{64}")||!url.equals("https://github.com/pengchujin/aerial-tv/releases/download/"+version+"/aerial-"+flavor+".apk")||!"github.com".equals(uri.getHost()))throw new IOException("Invalid official Aerial release");
            this.version=version;this.url=url;this.size=size;this.digest=digest.substring(7).toLowerCase(Locale.ROOT);
        }
        void verify(long bytes,String actual)throws IOException{if(bytes!=size||!digest.equals(actual))throw new IOException("Aerial download verification failed");}
    }
    static Release parse(String source,String flavor)throws Exception{
        JSONObject release=new JSONObject(source);if(release.getBoolean("draft")||release.getBoolean("prerelease"))throw new IOException("No stable Aerial release");JSONArray assets=release.getJSONArray("assets");
        for(int i=0;i<assets.length();i++){JSONObject asset=assets.getJSONObject(i);if(asset.getString("name").equals("aerial-"+flavor+".apk")){long size=asset.getLong("size");if(size>MAXIMUM||size<1)throw new IOException("Aerial package size limit");return new Release(release.getString("tag_name"),asset.getString("browser_download_url"),(int)size,asset.getString("digest"),flavor);}}throw new IOException("Aerial release asset unavailable");
    }
    private static HttpsURLConnection open(String address)throws Exception{
        URL url=new URL(address);
        for(int hop=0;hop<6;hop++){
            if(!url.getProtocol().equals("https")||!Arrays.asList("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com").contains(url.getHost())||url.getUserInfo()!=null||url.getPort()!=-1)throw new IOException("Invalid Aerial download redirect");
            HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","TVDeck");int status=connection.getResponseCode();
            if(status==200)return connection;
            if(status==301||status==302||status==303||status==307||status==308){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("Missing release redirect");url=new URL(url,location);continue;}
            connection.disconnect();throw new IOException("Aerial download HTTP "+status);
        }throw new IOException("Too many Aerial download redirects");
    }
    static Release latest(String flavor)throws Exception{
        HttpsURLConnection connection=open(API);try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){InstallBundle.copyBounded(in,out,1024*1024);return parse(out.toString("UTF-8"),flavor);}finally{connection.disconnect();}
    }
    static File download(Release release,File cache)throws Exception{return download(release,cache,value->{});}
    static File download(Release release,File cache,java.util.function.DoubleConsumer progress)throws Exception{
        File file=File.createTempFile("aerial-",".apk",cache);boolean success=false;HttpsURLConnection connection=null;
        try{
            connection=open(release.url);MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1){count+=n;if(count>release.size)throw new IOException("Aerial download size mismatch");digest.update(bytes,0,n);out.write(bytes,0,n);progress.accept((double)count/release.size);}}
            release.verify(count,Transfers.hex(digest.digest()));success=true;return file;
        }finally{if(connection!=null)connection.disconnect();if(!success)file.delete();}
    }
}
