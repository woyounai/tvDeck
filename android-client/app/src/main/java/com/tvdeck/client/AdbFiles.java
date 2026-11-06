package com.tvdeck.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.muntashirakon.adb.AdbStream;

final class AdbFiles {
    static final class Entry {
        final String name; final boolean directory;boolean symbolic; final long size, modifiedAt;
        Entry(String name, boolean directory, long size, long modifiedAt) { this.name=name; this.directory=directory; this.size=size; this.modifiedAt=modifiedAt; }
    }
    static void number(OutputStream out, int value) throws IOException {
        for (int i=0;i<4;i++) out.write(value >>> (8*i) & 255);
    }
    static int number(InputStream in) throws IOException {
        int value=0;
        for (int i=0;i<4;i++) { int b=in.read(); if(b<0) throw new EOFException(); value|=b<<(8*i); }
        return value;
    }
    static byte[] bytes(InputStream in, int length) throws IOException {
        if(length<0 || length>1024*1024) throw new IOException("Invalid sync length");
        byte[] data=new byte[length]; int offset=0;
        while(offset<length) { int n=in.read(data,offset,length-offset); if(n<0) throw new EOFException(); offset+=n; }
        return data;
    }
    static String id(InputStream in) throws IOException { return new String(bytes(in,4),StandardCharsets.US_ASCII); }
    static void packet(OutputStream out,String id,byte[] value) throws IOException {
        out.write(id.getBytes(StandardCharsets.US_ASCII)); number(out,value.length); out.write(value);out.flush();
    }
    static List<Entry> list(AdbStream stream,String path,InputStream in) throws IOException {
        try(AdbStream owned=stream) {
            OutputStream out=stream.openOutputStream();
            packet(out,"LIST",path.getBytes(StandardCharsets.UTF_8));List<Entry> entries=listResponse(in);
            entries.sort(Comparator.comparing((Entry e)->!e.directory).thenComparing(e->e.name.toLowerCase(Locale.ROOT)));
            return entries;
        }
    }
    static List<Entry> listResponse(InputStream in) throws IOException {
        List<Entry> entries=new ArrayList<>();
        while(true) {
            String type=id(in);
            if(type.equals("DONE")) break;
            if(type.equals("FAIL")) throw new IOException(new String(bytes(in,number(in)),StandardCharsets.UTF_8));
            if(!type.equals("DENT")) throw new IOException("Invalid directory response");
            int mode=number(in), size=number(in), modifiedAt=number(in), length=number(in);
            if(length>4096) throw new IOException("Invalid directory response");
            String name=new String(bytes(in,length),StandardCharsets.UTF_8);
            if(name.equals(".")||name.equals("..")||name.indexOf('/')>=0) continue;
            Entry entry=new Entry(name,(mode & 0170000)==0040000,Integer.toUnsignedLong(size),Integer.toUnsignedLong(modifiedAt));entry.symbolic=(mode&0170000)==0120000;entries.add(entry);
            if(entries.size()>20000) throw new IOException("Directory too large");
        }
        return entries;
    }
    static void pull(AdbStream stream,String path,OutputStream destination,InputStream in) throws IOException {
        try(AdbStream owned=stream) {
            packet(stream.openOutputStream(),"RECV",path.getBytes(StandardCharsets.UTF_8));
            while(true) {
                String type=id(in);int length=number(in);
                if(type.equals("DONE")) return;
                if(type.equals("FAIL")) throw new IOException(new String(bytes(in,length),StandardCharsets.UTF_8));
                if(!type.equals("DATA")||length>65536) throw new IOException("Invalid file response");
                destination.write(bytes(in,length));
            }
        }
    }
    static void push(AdbStream stream,String path,InputStream source,int mode,InputStream in) throws IOException {
        try(AdbStream owned=stream) {
            OutputStream out=stream.openOutputStream();
            packet(out,"SEND",(path+","+mode).getBytes(StandardCharsets.UTF_8));
            byte[] buffer=new byte[32768];int length;
            while((length=source.read(buffer))!=-1) packet(out,"DATA",Arrays.copyOf(buffer,length));
            out.write("DONE".getBytes(StandardCharsets.US_ASCII));number(out,(int)(System.currentTimeMillis()/1000));out.flush();
            String type=id(in);int size=number(in);
            if(type.equals("FAIL")) throw new IOException(new String(bytes(in,size),StandardCharsets.UTF_8));
            if(!type.equals("OKAY")) throw new IOException("Upload failed");
        }
    }
}
