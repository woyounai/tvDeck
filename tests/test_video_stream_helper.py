#!/usr/bin/env python3
"""Validate helper packet bounds on the host; does not exercise Android capture APIs."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java = Path(os.environ['JAVA_HOME']) / 'bin'
android = Path(os.environ['ANDROID_JAR'])
source = r'''
package com.tvadb.helper;
import java.io.*;
import java.nio.*;
import java.util.*;
public final class VideoStreamTest {
    public static void main(String[] args) throws Exception {
        if(!VideoStream.useSecureDisplay(29,"REL"))throw new AssertionError("Android 10 capture");
        if(!VideoStream.useSecureDisplay(30,"REL"))throw new AssertionError("Android 11 capture");
        if(VideoStream.useSecureDisplay(30,"S"))throw new AssertionError("Android 12 preview boundary");
        if(VideoStream.useSecureDisplay(31,"REL"))throw new AssertionError("Android 12 boundary");
        VideoStream.validateOptions(128,100000,1);
        VideoStream.validateOptions(1280,4000000,30);
        for(int[] values:new int[][]{{127,100000,1},{1281,100000,1},{128,99999,1},
            {128,4000001,1},{128,100000,0},{128,100000,31}}) {
            try {VideoStream.validateOptions(values[0],values[1],values[2]);throw new AssertionError();}
            catch(IllegalArgumentException expected) {}
        }
        final int[] writes={0};
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(){
            @Override public synchronized void write(byte[] data,int offset,int count){writes[0]++;super.write(data,offset,count);}
            @Override public synchronized void write(int value){writes[0]++;super.write(value);}
        };
        DataOutputStream output=new DataOutputStream(bytes);
        ByteBuffer source=ByteBuffer.wrap(new byte[]{9,0,0,0,1,101,8});source.position(1);source.limit(6);
        VideoStream.write(output,VideoStream.FRAME,source);
        if(writes[0]!=1)throw new AssertionError("Packet split across transport writes");
        if(source.position()!=1||source.limit()!=6)throw new AssertionError("Mutated codec buffer");
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        if(input.readByte()!=3||input.readInt()!=5)throw new AssertionError("Invalid header");
        byte[] payload=new byte[5];input.readFully(payload);
        if(!Arrays.equals(payload,new byte[]{0,0,0,1,101})||input.read()!=-1)throw new AssertionError("Invalid payload");
        for(int count:new int[]{0,VideoStream.MAX_PACKET+1}) {
            bytes.reset();
            try {VideoStream.write(output,VideoStream.FRAME,ByteBuffer.allocate(count));throw new AssertionError();}
            catch(IOException expected) {}
            if(bytes.size()!=0)throw new AssertionError("Wrote invalid header");
        }
        VideoStream.write(output,VideoStream.CONFIG,ByteBuffer.allocate(VideoStream.MAX_PACKET));
        if(bytes.size()!=VideoStream.MAX_PACKET+5)throw new AssertionError("Rejected maximum packet");
        System.out.println("VideoStream packet tests passed");
    }
}
'''
with tempfile.TemporaryDirectory() as temp:
    folder = Path(temp)
    test = folder / 'VideoStreamTest.java'
    test.write_text(source)
    helpers = root / 'android-helper/src/com/tvadb/helper'
    subprocess.run([str(java/'javac'), '--release', '8', '-cp', str(android), '-d', temp,
                    str(helpers/'AppPresentationHelper.java'), str(helpers/'VideoStream.java'), str(test)], check=True)
    subprocess.run([str(java/'java'), '-cp', os.pathsep.join([temp,str(android)]),
                    'com.tvadb.helper.VideoStreamTest'], check=True, timeout=10)
