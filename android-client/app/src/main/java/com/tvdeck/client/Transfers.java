package com.tvdeck.client;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Verified chunk checkpoints never append bytes to an unverified user file. */
final class Transfers {
    static final int CHUNK=1024*1024;
    private final DeckModel model;private final Context context;private final File cache;
    Transfers(DeckModel model){this.model=model;context=model.context;cache=new File(context.getNoBackupFilesDir(),"transfers");cache.mkdirs();}
    static String hash(InputStream in)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);return hex(digest.digest());}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private String key(String path,String hash)throws Exception{return hash(new ByteArrayInputStream((model.host+":"+model.port+"\n"+path+"\n"+hash).getBytes("UTF-8")));}
    private String remoteHash(String path)throws Exception{String result=model.checked("sha256sum "+DeckModel.quote(path));String hash=result.trim().split("\\s+")[0];if(!hash.matches("[a-fA-F0-9]{64}"))throw new IOException("SHA-256 unavailable");return hash.toLowerCase(Locale.ROOT);}
    private long remoteSize(String path)throws Exception{String size=model.checked("stat -c %s "+DeckModel.quote(path)).trim();try{long n=Long.parseLong(size);if(n<0)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IOException("Invalid remote size");}}
    private File snapshot(Uri uri)throws Exception{
        File file=File.createTempFile("source-",".part",cache);
        try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
            if(in==null)throw new IOException("Source unavailable");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(cache.getUsableSpace()<n+16*1024*1024L)throw new IOException("Insufficient local storage");out.write(buffer,0,n);}return file;
        }catch(Exception e){file.delete();throw e;}
    }
    void upload(Uri source,String target,boolean replace)throws Exception{
        File file=snapshot(source);String digest;try(InputStream in=new FileInputStream(file)){digest=hash(in);}String id=key(target,digest),part=target+".tvdeck-"+id.substring(0,16)+".part",chunk=part+".chunk";
        SharedPreferences state=context.getSharedPreferences("transfer-checkpoints",0);long offset=state.getLong(id,0);
        try {
            if(offset<0||offset>file.length()||offset%CHUNK!=0)offset=0;
            if(offset>0){try{String remote=model.checked("head -c "+offset+" "+DeckModel.quote(part)+" | sha256sum").split("\\s+")[0];try(InputStream in=new FileInputStream(file)){if(!remote.equals(hash(new LimitedInputStream(in,offset))))offset=0;}}catch(Exception error){offset=0;}if(offset==0)state.edit().putLong(id,0).commit();}
            if(offset==0)model.checked("rm -f -- "+DeckModel.quote(part)+" && touch "+DeckModel.quote(part));
            try(RandomAccessFile in=new RandomAccessFile(file,"r")){
                in.seek(offset);byte[] bytes=new byte[CHUNK];int n;
                while((n=in.read(bytes))!=-1){byte[] data=Arrays.copyOf(bytes,n);model.push(chunk,new ByteArrayInputStream(data),0600);
                    model.checked("dd if="+DeckModel.quote(chunk)+" of="+DeckModel.quote(part)+" bs=65536 seek="+(offset/65536)+" conv=notrunc 2>/dev/null");offset+=n;state.edit().putLong(id,offset).commit();}
            }
            if(remoteSize(part)!=file.length()||!remoteHash(part).equals(digest))throw new IOException("Uploaded file verification failed");
            String guard=replace?"":"if [ -e "+DeckModel.quote(target)+" ]; then echo 'Destination exists'; exit 1; fi; ";
            model.checked(guard+"mv -f -- "+DeckModel.quote(part)+" "+DeckModel.quote(target));state.edit().remove(id).apply();
        } finally {file.delete();try{model.checked("rm -f -- "+DeckModel.quote(chunk));}catch(Exception ignored){}}
    }
    void download(String source,Uri destination)throws Exception{
        String digest=remoteHash(source);long total=remoteSize(source);String id=key(source,digest);File part=new File(cache,id+".download");
        long offset=part.exists()?part.length():0;offset=Math.min(total,offset/CHUNK*CHUNK);
        try(RandomAccessFile file=new RandomAccessFile(part,"rw")){
            file.setLength(offset);file.seek(offset);
            while(offset<total){byte[] bytes=model.read("exec:dd if="+DeckModel.quote(source)+" bs=65536 skip="+(offset/65536)+" count=16 2>/dev/null",CHUNK,45);
                int expected=(int)Math.min(CHUNK,total-offset);if(bytes.length!=expected)throw new IOException("File changed or truncated during transfer");if(cache.getUsableSpace()<bytes.length+16*1024*1024L)throw new IOException("Insufficient local storage");file.write(bytes);offset+=bytes.length;}
        }
        try(InputStream in=new FileInputStream(part)){if(!hash(in).equals(digest)){part.delete();throw new IOException("Downloaded file verification failed");}}
        try(InputStream in=new FileInputStream(part);OutputStream out=context.getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("Destination unavailable");copy(in,out);}
        part.delete();
    }
    void uploadTree(Uri tree,String destination)throws Exception{Set<String> visited=new HashSet<>();uploadDirectory(tree,DocumentsContract.getTreeDocumentId(tree),destination,0,visited);}
    private void uploadDirectory(Uri tree,String id,String remote,int depth,Set<String> visited)throws Exception{
        if(depth>32||!visited.add(id)||visited.size()>20000)throw new IOException("Directory depth or cycle limit");model.checked("mkdir -p -- "+DeckModel.quote(remote));
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(cursor==null)throw new IOException("Cannot list local directory");while(cursor.moveToNext()){String child=cursor.getString(0),name=safeName(cursor.getString(1)),mime=cursor.getString(2);Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,child);String target=remote+"/"+name;if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))uploadDirectory(tree,child,target,depth+1,visited);else upload(uri,target,true);}
        }
    }
    void downloadTree(String source,Uri tree)throws Exception{downloadDirectory(source,tree,DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)),0,new int[]{0});}
    void downloadSelection(Map<String,Boolean> paths,Uri tree)throws Exception{
        Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        for(Map.Entry<String,Boolean> entry:paths.entrySet()){String name=safeName(entry.getKey().substring(entry.getKey().lastIndexOf('/')+1));Uri child=findOrCreate(tree,root,name,entry.getValue());if(entry.getValue())downloadDirectory(entry.getKey(),tree,child,0,new int[]{0});else download(entry.getKey(),child);}
    }
    private void downloadDirectory(String source,Uri tree,Uri parent,int depth,int[] count)throws Exception{
        if(depth>32)throw new IOException("Directory too deep");for(AdbFiles.Entry entry:model.files(source)){if(++count[0]>20000)throw new IOException("Directory too large");if(entry.symbolic)throw new IOException("Symbolic links are not followed");String name=safeName(entry.name);Uri child=findOrCreate(tree,parent,name,entry.directory);String remote=source+(source.endsWith("/")?"":"/")+name;if(entry.directory)downloadDirectory(remote,tree,child,depth+1,count);else download(remote,child);}
    }
    private Uri findOrCreate(Uri tree,Uri parent,String name,boolean directory)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){if(cursor==null)throw new IOException("Destination unavailable");while(cursor.moveToNext())if(name.equals(cursor.getString(1))){if(directory!=DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)))throw new IOException("File/directory collision");return DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));}}
        Uri child=DocumentsContract.createDocument(context.getContentResolver(),parent,directory?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream",name);if(child==null)throw new IOException("Cannot create destination");return child;
    }
    static String safeName(String name)throws IOException{if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf(0)>=0)throw new IOException("Invalid file name");return name;}
    static void copy(InputStream in,OutputStream out)throws IOException{byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
    static final class LimitedInputStream extends FilterInputStream {long remaining;LimitedInputStream(InputStream in,long count){super(in);remaining=count;}public int read(byte[] b,int off,int len)throws IOException{if(remaining==0)return -1;int n=super.read(b,off,(int)Math.min(len,remaining));if(n<0)throw new EOFException();remaining-=n;return n;}public int read()throws IOException{if(remaining==0)return -1;int b=super.read();if(b<0)throw new EOFException();remaining--;return b;}}
}
