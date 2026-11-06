package com.tvdeck.client;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minimal bounded binary-XML metadata used only for APK split selection. */
final class ApkManifest {
    String packageName="",split="",configForSplit="";boolean feature;
    final Set<String> dependencies=new HashSet<>();
    static ApkManifest parse(byte[] data)throws IOException{
        if(data.length<8||data.length>16*1024*1024)throw new IOException("Manifest size");ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);if(u16(b,0)!=3)throw new IOException("Binary manifest required");
        List<String> strings=new ArrayList<>();ApkManifest result=new ApkManifest();int position=u16(b,2),end=b.getInt(4);if(end>data.length||end<position)throw new IOException("XML size");
        while(position+8<=end){int type=u16(b,position),header=u16(b,position+2),size=b.getInt(position+4);if(size<header||header<8||size>end-position)throw new IOException("XML chunk");
            if(type==1){int count=i32(b,position+8),flags=i32(b,position+16),start=i32(b,position+20);if(header<28||count<0||count>200000||(long)header+4L*count>size||start<0||start>=size)throw new IOException("String pool");boolean utf8=(flags&256)!=0;
                for(int i=0;i<count;i++){int offset=i32(b,position+header+4*i);long absolute=(long)position+start+offset;if(offset<0||absolute>=position+size)throw new IOException("String offset");int[] at={(int)absolute};String value;
                    if(utf8){length8(data,at,position+size);int length=length8(data,at,position+size);if((long)at[0]+length>=position+size)throw new IOException("UTF-8 string");value=new String(data,at[0],length,StandardCharsets.UTF_8);}
                    else{int length=length16(b,at,position+size);if((long)at[0]+length*2L+2>position+size)throw new IOException("UTF-16 string");value=new String(data,at[0],length*2,StandardCharsets.UTF_16LE);}strings.add(value);}
            }else if(type==0x102){if(header<16||size<36)throw new IOException("XML element");int ext=position+16;String tag=string(strings,i32(b,ext+4));int attrStart=u16(b,ext+8),attrSize=u16(b,ext+10),count=u16(b,ext+12);if(attrStart<20||attrSize<20||(long)ext+attrStart+(long)attrSize*count>position+size)throw new IOException("XML attributes");
                for(int i=0;i<count;i++){int at=ext+attrStart+i*attrSize;String name=string(strings,i32(b,at+4));int raw=i32(b,at+8),kind=data[at+15]&255,value=i32(b,at+16);String text=raw>=0?string(strings,raw):kind==3?string(strings,value):Integer.toString(value);
                    if(tag.equals("manifest")){if(name.equals("package"))result.packageName=text;else if(name.equals("split"))result.split=text;else if(name.equals("configForSplit"))result.configForSplit=text;else if(name.equals("isFeatureSplit"))result.feature=kind==18?value!=0:text.equals("true");}
                    if(tag.equals("uses-split")&&name.equals("name"))result.dependencies.add(text);
                }
            }
            position+=size;
        }
        if(!result.packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))throw new IOException("Package name");return result;
    }
    private static int u16(ByteBuffer b,int at)throws IOException{if(at<0||at+2>b.limit())throw new IOException("XML bounds");return b.getShort(at)&65535;}
    private static int i32(ByteBuffer b,int at)throws IOException{if(at<0||at+4>b.limit())throw new IOException("XML bounds");return b.getInt(at);}
    private static String string(List<String> strings,int index)throws IOException{if(index<0||index>=strings.size())throw new IOException("String index");return strings.get(index);}
    private static int length8(byte[] data,int[] at,int end)throws IOException{if(at[0]>=end)throw new IOException("String length");int n=data[at[0]++]&255;if((n&128)!=0){if(at[0]>=end)throw new IOException("String length");n=((n&127)<<8)|(data[at[0]++]&255);}return n;}
    private static int length16(ByteBuffer data,int[] at,int end)throws IOException{if(at[0]+2>end)throw new IOException("String length");int n=u16(data,at[0]);at[0]+=2;if((n&32768)!=0){if(at[0]+2>end)throw new IOException("String length");n=((n&32767)<<16)|u16(data,at[0]);at[0]+=2;}return n;}
}
