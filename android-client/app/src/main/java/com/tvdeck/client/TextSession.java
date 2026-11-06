package com.tvdeck.client;

import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import io.github.muntashirakon.adb.AdbStream;

final class TextSession implements Closeable {
    final DeckModel model;final AdbStream stream;int sequence;String token,text;
    TextSession(DeckModel model,AdbStream stream) {this.model=model;this.stream=stream;}
    synchronized void reset(){token=null;text=null;}
    synchronized String get() throws Exception {return request("GET");}
    synchronized String set(String desired) throws Exception {
        if(desired.getBytes(StandardCharsets.UTF_8).length>8192) throw new IOException("Text exceeds 8192 UTF-8 bytes");
        if(token==null) throw new IOException(model.context.getString(R.string.text_unsupported));
        return request("SET",token,encode(text),encode(desired));
    }
    synchronized String confirm() throws Exception {return request("CONFIRM",token,encode(text));}
    private String request(String command,String... args) throws Exception {
        int id=++sequence;StringBuilder wire=new StringBuilder(command+" "+id);for(String arg:args) wire.append(' ').append(arg);
        DeckModel.write(stream,wire.toString());String response=model.line(stream);String[] parts=response.split(" ",-1);
        if(parts.length<2||!parts[1].equals(String.valueOf(id))) throw new IOException("Invalid text response");
        if(parts[0].equals("STATE")&&parts.length==4) {if(token!=null&&!token.equals(parts[2]))throw new IOException(model.context.getString(R.string.text_conflict));token=parts[2];text=decode(parts[3]);return text;}
        if(parts[0].equals("NONE")){if(token!=null)throw new IOException(model.context.getString(R.string.text_conflict));return null;}
        if(parts[0].equals("DONE")) return text;
        if(parts[0].equals("CONFLICT")) throw new IOException(model.context.getString(R.string.text_conflict));
        throw new IOException(model.context.getString(R.string.text_unsupported));
    }
    static String encode(String text) {return text.isEmpty()?"-":Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);}
    static String decode(String text) {return text.equals("-")?"":new String(Base64.decode(text,Base64.DEFAULT),StandardCharsets.UTF_8);}
    public void close() throws IOException {stream.close();}
}
