package com.tvdeck.client;

import java.io.*;
import java.util.Arrays;

/** Length-delimited codec output; no compressed packets may be discarded. */
final class VideoPackets {
    static final int MAX_PACKET=4*1024*1024;
    static final class Failure extends IOException {
        final int stage,detail;
        Failure(int stage,int detail){this.stage=stage;this.detail=detail;}
    }
    static final class Packet {
        final int kind;final byte[] data;
        Packet(int kind,byte[] data){this.kind=kind;this.data=data;}
        int integer(int offset){return (data[offset]&255)<<24|(data[offset+1]&255)<<16|(data[offset+2]&255)<<8|data[offset+3]&255;}
    }
    private final DataInputStream input;private boolean info;
    VideoPackets(InputStream source)throws IOException {
        input=new DataInputStream(source);byte[] magic=new byte[8];input.readFully(magic);
        if(!Arrays.equals(magic,new byte[]{'T','V','A','D','B','V','0','1'}))throw new IOException();
    }
    Packet next()throws IOException {
        int kind=input.read();if(kind<0)return null;
        int count=input.readInt();
        if(kind<1||kind>4||count<1||count>MAX_PACKET||(kind==1&&count!=20)||(kind==4&&count!=8))throw new IOException();
        byte[] data=new byte[count];input.readFully(data);Packet packet=new Packet(kind,data);
        if(kind==4)throw new Failure(packet.integer(0),packet.integer(4));
        if(kind==1){
            if(info)throw new IOException();
            for(int offset=0;offset<16;offset+=4)if(packet.integer(offset)<1||packet.integer(offset)>16384)throw new IOException();
            if(packet.integer(16)<1||packet.integer(16)>30)throw new IOException();
            info=true;
        }else if(!info||AnnexB.find(data,0)!=0||data.length<=AnnexB.prefix(data,0))throw new IOException();
        return packet;
    }
}
