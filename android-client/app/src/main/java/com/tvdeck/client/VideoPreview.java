package com.tvdeck.client;

import android.media.*;
import android.os.Build;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import io.github.muntashirakon.adb.AdbStream;

final class VideoPreview implements Closeable {
    interface Observer {void frame(int width,int height);void fallback(Exception reason);}
    private final DeckModel model;private final Surface surface;private final Observer observer;
    private volatile boolean closed,first;
    private volatile Exception decoderFailure;
    private volatile boolean firstFrameTimedOut;
    private long receivedBytes;
    volatile int sourceWidth=1920,sourceHeight=1080;
    private volatile AdbStream stream;
    private volatile MediaCodec codec;private Thread outputThread;private byte[] sps,pps;private final ByteArrayOutputStream unit=new ByteArrayOutputStream();
    private boolean hasSlice;private long presentation;private int encodedWidth,encodedHeight;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    VideoPreview(DeckModel model,Surface surface,Observer observer){this.model=model;this.surface=surface;this.observer=observer;}
    void start(){if(closed)return;try{worker.execute(this::run);}catch(RejectedExecutionException error){if(!closed)throw error;}}
    private void run(){
        Exception failure=null;
        try {
            if(closed)return;
            if(runHardware())return;
            int[] source=model.screenSize();sourceWidth=source[0];sourceHeight=source[1];
            for(boolean bounded:new boolean[]{true,false}) {
                if(closed)return;first=false;firstFrameTimedOut=false;receivedBytes=0;decoderFailure=null;sps=null;pps=null;unit.reset();hasSlice=false;
                try {
                    setDimensions(source,bounded);stream=model.openVideo(source[0],source[1],bounded);
                    ScheduledFuture<?> timeout=model.timer.schedule(()->{if(!first){firstFrameTimedOut=true;closeStream();}},4,TimeUnit.SECONDS);
                    try {
                        AnnexB parser=new AnnexB(this::nal,this::flushUnit);InputStream input=model.input(stream);byte[] buffer=new byte[32768];int count;
                        while(!closed&&(count=input.read(buffer))!=-1){receivedBytes+=count;parser.append(buffer,count);}
                        parser.finish();flushUnit();awaitFirstFrame();
                    } finally {timeout.cancel(false);closeStream();releaseCodec();}
                    if(closed)return;
                    if(first){runContinuation(source,bounded);return;}
                    failure=noFrameFailure();
                } catch(Exception error){failure=decoderFailure!=null?new IOException(model.context.getString(R.string.preview_decode_failed,decoderFailure.toString()),decoderFailure):firstFrameTimedOut?noFrameFailure():error;closeStream();releaseCodec();}
            }
            if(!closed)observer.fallback(failure==null?noFrameFailure():failure);
        } catch(Exception error){if(!closed)observer.fallback(error);}
        finally{releaseCodec();worker.shutdown();}
    }
    // Try the native video path after any helper failure; PNG remains the final fallback.
    private boolean runHardware()throws Exception {
        ScheduledFuture<?> timeout=null;
        try {
            int sdk=Integer.parseInt(model.shell("getprop ro.build.version.sdk").trim());
            if(sdk<29||sdk>30)return false;
            stream=model.openHardwareVideo();
            if(closed)return true;
            timeout=model.timer.schedule(()->{if(!first){firstFrameTimedOut=true;closeStream();}},4,TimeUnit.SECONDS);
            VideoPackets packets=new VideoPackets(model.input(stream));VideoPackets.Packet packet;
            while(!closed&&(packet=packets.next())!=null){
                if(packet.kind==1){sourceWidth=packet.integer(0);sourceHeight=packet.integer(4);encodedWidth=packet.integer(8);encodedHeight=packet.integer(12);}
                else {
                    receivedBytes+=packet.data.length;
                    AnnexB parser=new AnnexB(this::nal);parser.append(packet.data,packet.data.length);parser.finish();
                    if(packet.kind==3)flushUnit();
                }
            }
            if(!closed)throw noFrameFailure();
            return true;
        }catch(Exception error){
            return closed;
        }finally{if(timeout!=null)timeout.cancel(false);closeStream();releaseCodec();}
    }
    private void runContinuation(int[] source,boolean bounded)throws Exception{
        // A TV screenrecord process has a finite lifetime; renew only within this manual session.
        while(!closed){first=false;firstFrameTimedOut=false;receivedBytes=0;decoderFailure=null;sps=null;pps=null;unit.reset();hasSlice=false;setDimensions(source,bounded);stream=model.openVideo(source[0],source[1],bounded);
            ScheduledFuture<?> timeout=model.timer.schedule(()->{if(!first){firstFrameTimedOut=true;closeStream();}},4,TimeUnit.SECONDS);
            try{AnnexB parser=new AnnexB(this::nal,this::flushUnit);byte[] bytes=new byte[32768];int count;InputStream in=model.input(stream);while(!closed&&(count=in.read(bytes))!=-1){receivedBytes+=count;parser.append(bytes,count);}parser.finish();flushUnit();awaitFirstFrame();if(!first&&!closed)throw noFrameFailure();}
            finally{timeout.cancel(false);closeStream();releaseCodec();}
        }
    }
    private IOException noFrameFailure(){
        if(decoderFailure!=null)return new IOException(model.context.getString(R.string.preview_decode_failed,decoderFailure.toString()),decoderFailure);
        if(receivedBytes==0)return new IOException(model.context.getString(R.string.preview_no_data));
        if(sps==null||pps==null)return new IOException(model.context.getString(R.string.preview_invalid_data,receivedBytes));
        return new IOException(model.context.getString(firstFrameTimedOut?R.string.preview_first_frame_timeout:R.string.preview_stream_ended));
    }
    private void setDimensions(int[] source,boolean bounded){
        int[] size=DeckModel.videoSize(source[0],source[1],bounded);
        encodedWidth=size[0];encodedHeight=size[1];
    }
    private void awaitFirstFrame()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(!closed&&!first&&codec!=null&&decoderFailure==null&&System.nanoTime()<deadline)Thread.sleep(10);
        if(decoderFailure!=null)throw decoderFailure;
    }
    private static MediaCodec decoder()throws IOException {
        if(Build.VERSION.SDK_INT>=29)for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if(info.isEncoder()||!info.isHardwareAccelerated())continue;
            for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase("video/avc"))return MediaCodec.createByCodecName(info.getName());
        }
        return MediaCodec.createDecoderByType("video/avc");
    }
    private void nal(byte[] nal)throws Exception {
        int type=AnnexB.type(nal);
        if(type==7||type==8||type==9){if(hasSlice)flushUnit();if(type==7)sps=nal;if(type==8)pps=nal;}
        if(type==1||type==5){if(hasSlice&&AnnexB.firstSlice(nal))flushUnit();hasSlice=true;}
        if(unit.size()+nal.length>4*1024*1024)throw new IOException("H.264 access unit exceeds limit");unit.write(nal);
    }
    private void flushUnit()throws Exception {
        if(!hasSlice){return;}byte[] bytes=unit.toByteArray();unit.reset();hasSlice=false;
        if(sps==null||pps==null)return;
        if(codec==null){
            codec=decoder();MediaFormat format=MediaFormat.createVideoFormat("video/avc",encodedWidth,encodedHeight);
            format.setByteBuffer("csd-0",ByteBuffer.wrap(sps));format.setByteBuffer("csd-1",ByteBuffer.wrap(pps));format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024);
            if(Build.VERSION.SDK_INT>=30)format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
            codec.configure(format,surface,null,0);codec.start();
            MediaCodec decoder=codec;outputThread=new Thread(()->output(decoder),"tvdeck-video-output");outputThread.start();
        }
        int slot=-1;
        while(!closed&&(slot=codec.dequeueInputBuffer(10000))<0){if(decoderFailure!=null)throw decoderFailure;}
        if(closed)return;
        ByteBuffer input=codec.getInputBuffer(slot);if(input==null||input.capacity()<bytes.length)throw new IOException("Decoder input too small");input.clear();input.put(bytes);codec.queueInputBuffer(slot,0,bytes.length,presentation+=33333,0);
    }
    private void output(MediaCodec decoder){
        try {
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!closed&&codec==decoder){
                int slot=decoder.dequeueOutputBuffer(info,10000);if(slot<0)continue;int newest=slot;
                while((slot=decoder.dequeueOutputBuffer(info,0))>=0){decoder.releaseOutputBuffer(newest,false);newest=slot;}
                decoder.releaseOutputBuffer(newest,!closed);
                MediaFormat format=decoder.getOutputFormat();if(!first){first=true;observer.frame(format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT));}
            }
        } catch(Exception error){if(!closed&&codec==decoder){decoderFailure=error;closeStream();}}
    }
    private void releaseCodec(){MediaCodec decoder=codec;codec=null;if(outputThread!=null){try{outputThread.join(250);}catch(InterruptedException ignored){}outputThread=null;}if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}try{decoder.release();}catch(Exception ignored){}}}
    private void closeStream(){AdbStream current=stream;stream=null;if(current!=null)try{current.close();}catch(IOException ignored){}}
    public void close(){
        closed=true;
        // Closing an ADB stream writes to the socket; never block a Surface/UI callback on it.
        if(stream!=null)try{model.timer.execute(this::closeStream);}catch(RejectedExecutionException ignored){}
        worker.shutdown();
    }
}
