package com.tvdeck.client;

import java.io.*;
import java.util.*;

/** Bounded incremental Annex-B NAL framing; compressed reference pictures are never discarded. */
final class AnnexB {
    interface Consumer {void nal(byte[] bytes) throws Exception;}
    interface Boundary {void nextPicture() throws Exception;}
    private final ByteArrayOutputStream pending=new ByteArrayOutputStream();
    private final Consumer consumer;
    private final Boundary boundary;
    AnnexB(Consumer consumer){this(consumer,()->{});}
    AnnexB(Consumer consumer,Boundary boundary){this.consumer=consumer;this.boundary=boundary;}
    void append(byte[] data,int count)throws Exception{
        if(pending.size()+count>4*1024*1024)throw new IOException("H.264 NAL exceeds limit");pending.write(data,0,count);
        byte[] all=pending.toByteArray();int start=find(all,0),next;
        while(start>=0&&(next=find(all,start+prefix(all,start)))>=0){consumer.nal(Arrays.copyOfRange(all,start,next));start=next;}
        // The preceding NAL is complete; a partial next header can already prove the picture boundary.
        if(start>=0){int header=start+prefix(all,start);if(header<all.length&&startsPicture(all[header],header+1<all.length?all[header+1]&255:-1))boundary.nextPicture();}
        if(start>0){pending.reset();pending.write(all,start,all.length-start);}else if(start<0&&all.length>4){pending.reset();pending.write(all,all.length-4,4);}
    }
    void finish()throws Exception{byte[] all=pending.toByteArray();if(find(all,0)==0)consumer.nal(all);pending.reset();}
    static boolean startsPicture(byte header,int firstPayloadByte){
        int type=header&31;
        return type==7||type==8||type==9||((type==1||type==5)&&firstPayloadByte>=0&&(firstPayloadByte&128)!=0);
    }
    static int prefix(byte[] b,int p){return b[p+2]==1?3:4;}
    static int find(byte[] b,int start){for(int i=start;i+2<b.length;i++)if(b[i]==0&&b[i+1]==0&&(b[i+2]==1||i+3<b.length&&b[i+2]==0&&b[i+3]==1))return i;return -1;}
    static int type(byte[] nal){return nal[prefix(nal,0)]&31;}
    static boolean firstSlice(byte[] nal)throws IOException {
        int at=prefix(nal,0)+1;ByteArrayOutputStream rbsp=new ByteArrayOutputStream();int zeros=0;
        for(;at<nal.length;at++){int value=nal[at]&255;if(zeros>=2&&value==3){zeros=0;continue;}rbsp.write(value);zeros=value==0?zeros+1:0;}
        byte[] bytes=rbsp.toByteArray();int bits=0;while(bits<bytes.length*8&&((bytes[bits/8]>>(7-bits%8))&1)==0){if(++bits>31)throw new IOException("Invalid slice");}
        if(bits>=bytes.length*8)throw new IOException("Truncated slice");return bits==0;
    }
}
