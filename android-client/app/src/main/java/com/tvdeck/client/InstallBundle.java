package com.tvdeck.client;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
import java.util.zip.*;

final class InstallBundle implements Closeable {
    static final class Apk {final File file;final ApkManifest manifest;Apk(File file,ApkManifest manifest){this.file=file;this.manifest=manifest;}}
    String report="";final String filename;
    final File directory;final List<Apk> apks=new ArrayList<>();final List<File> obbs=new ArrayList<>();private final Context context;
    InstallBundle(Context context,Uri uri)throws Exception{
        this.context=context;String name=uri.getLastPathSegment();try(android.database.Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}catch(Exception ignored){}filename=name==null?"package.apk":name;directory=new File(context.getCacheDir(),"install-"+UUID.randomUUID());if(!directory.mkdirs())throw new IOException("Cannot stage package");
        try{File source=new File(directory,"source.zip");try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(source)){if(in==null)throw new IOException("Package unavailable");copyBounded(in,out,8L*1024*1024*1024);}
            boolean apk;try(ZipFile zip=new ZipFile(source)){apk=zip.getEntry("AndroidManifest.xml")!=null;}
            if(apk){File file=new File(directory,"base.apk");if(!source.renameTo(file))throw new IOException("Cannot stage APK");add(file);}
            else{long total=0;int entries=0;try(ZipFile zip=new ZipFile(source)){Enumeration<? extends ZipEntry> archive=zip.entries();while(archive.hasMoreElements()){ZipEntry entry=archive.nextElement();if(++entries>20000)throw new IOException("Too many archive entries");String path=archivePath(entry.getName());if(entry.isDirectory())continue;String lower=path.toLowerCase(Locale.ROOT);if(!lower.endsWith(".apk")&&!lower.endsWith(".obb"))continue;File file=new File(directory,path);if(!file.getParentFile().mkdirs()&&!file.getParentFile().isDirectory())throw new IOException("Archive directory");if(file.exists())throw new IOException("Duplicate archive entry");try(InputStream input=zip.getInputStream(entry);OutputStream out=new FileOutputStream(file)){long copied=copyBounded(input,out,8L*1024*1024*1024-total);total+=copied;}if(lower.endsWith(".apk"))add(file);else obbs.add(file);}}source.delete();}
            if(apks.isEmpty())throw new IOException("No APKs in package");
        }catch(Exception error){close();throw error;}
    }
    static String archivePath(String value)throws IOException{String path=value.replace('\\','/');if(path.startsWith("/")||path.indexOf(0)>=0||path.matches("^[A-Za-z]:.*"))throw new IOException("Unsafe archive path");for(String part:path.split("/",-1))if(part.equals("..")||part.equals("."))throw new IOException("Unsafe archive path");return path;}
    static long copyBounded(InputStream in,OutputStream out,long maximum)throws IOException{long count=0;byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1){count+=n;if(count>maximum)throw new IOException("Archive size limit exceeded");out.write(bytes,0,n);}return count;}
    private void add(File file)throws Exception{try(ZipFile zip=new ZipFile(file)){ZipEntry manifest=zip.getEntry("AndroidManifest.xml");if(manifest==null||manifest.getSize()>16*1024*1024)throw new IOException("Invalid APK manifest");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=zip.getInputStream(manifest)){copyBounded(in,out,16*1024*1024);}apks.add(new Apk(file,ApkManifest.parse(out.toByteArray())));}}
    List<Apk> select(String[] abis,int density,String language)throws Exception{
        Apk base=null;for(Apk apk:apks)if(apk.manifest.split.isEmpty()){if(base==null||apk.file.getName().equals("base.apk")||apk.file.getName().equals("base-master.apk"))base=apk;}
        if(base==null)throw new IOException("Missing base APK");
        int bases=0;for(Apk apk:apks)if(apk.manifest.split.isEmpty())bases++;if(bases>1)throw new IOException("Multiple standalone APK variants: select a single compatible APK explicitly");List<Apk> selected=new ArrayList<>();selected.add(base);Set<String> features=new HashSet<>();features.add("");
        for(Apk apk:apks){if(!apk.manifest.packageName.equals(base.manifest.packageName))throw new IOException("Mixed package archive");if(apk.manifest.feature){selected.add(apk);features.add(apk.manifest.split);}}
        Map<String,List<Apk>> abiGroups=new HashMap<>(),densityGroups=new HashMap<>();
        for(Apk apk:apks){if(selected.contains(apk)||apk.manifest.split.isEmpty())continue;String split=apk.manifest.split.toLowerCase(Locale.ROOT),owner=apk.manifest.configForSplit;if(!features.contains(owner))continue;
            String abi=abi(split);if(abi!=null){abiGroups.computeIfAbsent(owner,k->new ArrayList<>()).add(apk);continue;}
            if(density(split)>0){densityGroups.computeIfAbsent(owner,k->new ArrayList<>()).add(apk);continue;}
            String suffix=split.substring(split.lastIndexOf('.')+1);if(suffix.matches("[a-z]{2,3}(-r[a-z]{2})?")&&!suffix.equals(language.toLowerCase(Locale.ROOT)))continue;
            selected.add(apk);
        }
        for(List<Apk> group:abiGroups.values()){Apk best=null;for(String supported:abis){for(Apk apk:group)if(supported.replace('-','_').equals(abi(apk.manifest.split.toLowerCase(Locale.ROOT)))){best=apk;break;}if(best!=null)break;}if(best==null)throw new IOException("No compatible ABI split");selected.add(best);}
        for(List<Apk> group:densityGroups.values()){Apk best=Collections.min(group,Comparator.comparingDouble(apk->Math.abs(Math.log((double)density(apk.manifest.split.toLowerCase(Locale.ROOT))/density))));selected.add(best);}
        Set<String> names=new HashSet<>();for(Apk apk:selected)names.add(apk.manifest.split);for(Apk apk:selected)if(!names.containsAll(apk.manifest.dependencies))throw new IOException("Missing feature split dependency");return selected;
    }
    static String abi(String split){for(String abi:new String[]{"arm64_v8a","armeabi_v7a","x86_64","x86"})if(split.endsWith("."+abi)||split.endsWith("_"+abi)||split.equals(abi))return abi;return null;}
    static int density(String split){String[] names={"ldpi","mdpi","tvdpi","hdpi","xhdpi","xxhdpi","xxxhdpi"};int[] values={120,160,213,240,320,480,640};for(int i=0;i<names.length;i++)if(split.endsWith("."+names[i])||split.endsWith("_"+names[i]))return values[i];return 0;}
    String analysis()throws Exception{
        StringBuilder out=new StringBuilder();PackageManager pm=context.getPackageManager();
        for(Apk apk:apks){if(!apk.manifest.split.isEmpty())continue;PackageInfo info=pm.getPackageArchiveInfo(apk.file.getAbsolutePath(),PackageManager.GET_PERMISSIONS);if(info==null)throw new IOException("Cannot inspect APK");ApplicationInfo app=info.applicationInfo;app.sourceDir=apk.file.getAbsolutePath();app.publicSourceDir=app.sourceDir;
            out.append(context.getString(R.string.app_name_label)).append(": ").append(pm.getApplicationLabel(app)).append('\n').append(context.getString(R.string.package_name_label)).append(": ").append(info.packageName).append('\n').append(context.getString(R.string.permissions_label)).append(':');
            if(info.requestedPermissions==null||info.requestedPermissions.length==0)out.append(' ').append(context.getString(R.string.none));else for(String permission:info.requestedPermissions)out.append("\n• ").append(permission);
        }
        return out.toString();
    }
    List<Uri> uris(List<Apk> selected){List<Uri> result=new ArrayList<>();for(Apk apk:selected)result.add(Uri.fromFile(apk.file));return result;}
    @Override public void close(){delete(directory);}
    private static void delete(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);}file.delete();}
}
