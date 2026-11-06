package com.tvdeck.client;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class VideoPacketsTest {
    private static byte[] packet(int kind,byte[] data)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(kind);out.writeInt(data.length);out.write(data);return bytes.toByteArray();
    }
    private static byte[] ints(int... values)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        for(int value:values)out.writeInt(value);return bytes.toByteArray();
    }
    private static VideoPackets reader(byte[]... records)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write("TVADBV01".getBytes("US-ASCII"));
        for(byte[] record:records)bytes.write(record);
        // Every read returns at most one byte, including split length fields.
        return new VideoPackets(new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())){
            @Override public int read(byte[] buffer,int offset,int count)throws IOException{return super.read(buffer,offset,Math.min(1,count));}
        });
    }
    @Test public void completePacketsAndSignedErrors()throws Exception {
        byte[] info=packet(1,ints(1920,1080,1280,720,30)),frame=packet(3,new byte[]{0,0,0,1,101,(byte)128});
        VideoPackets packets=reader(info,frame,frame);
        assertEquals(1280,packets.next().integer(8));assertEquals(3,packets.next().kind);assertEquals(3,packets.next().kind);assertNull(packets.next());
        try{reader(packet(4,ints(3,-2147479551))).next();fail();}
        catch(VideoPackets.Failure error){assertEquals(3,error.stage);assertEquals(-2147479551,error.detail);}
    }
    @Test public void rejectsMalformedPackets()throws Exception {
        byte[] info=packet(1,ints(1920,1080,1280,720,30));
        for(byte[][] records:new byte[][][]{
            {new byte[]{3,0,64,0,1}}, {new byte[]{3,-1,-1,-1,-1}}, {packet(0,new byte[]{1})},
            {packet(1,ints(0,1080,1280,720,30))}, {packet(1,ints(1920,1080,1280,720,31))},
            {packet(3,new byte[]{0,0,1,101})}, {info,info}, {info,packet(3,new byte[]{0,0,1})},
            {info,new byte[]{3,0,0,0,5,0,0,1,101}}
        }){
            try{VideoPackets packets=reader(records);while(packets.next()!=null){}fail("Invalid packet accepted");}catch(IOException expected){}
        }
    }
}
