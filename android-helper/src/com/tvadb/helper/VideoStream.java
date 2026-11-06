package com.tvadb.helper;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shell-owned, hardware-only AVC capture. Each packet contains a complete codec output buffer. */
public final class VideoStream {
    static final byte[] MAGIC = {'T','V','A','D','B','V','0','1'};
    static final int INFO=1, CONFIG=2, FRAME=3, ERROR=4;
    static final int UNSUPPORTED=1, NO_ENCODER=2, ENCODER_FAILED=3, CAPTURE_FAILED=4;
    static final int MAX_PACKET=4*1024*1024;

    private VideoStream() {}

    public static void main(String[] args) {
        final Capture capture=new Capture();
        final DataOutputStream output=new DataOutputStream(new FileOutputStream(FileDescriptor.out));
        int stage=UNSUPPORTED;
        try {
            output.write(MAGIC);output.flush();
            if(args.length!=3)throw new IllegalArgumentException("Expected edge, bitrate and fps");
            int edge=Integer.parseInt(args[0]),bitrate=Integer.parseInt(args[1]),fps=Integer.parseInt(args[2]);
            validateOptions(edge,bitrate,fps);
            // shortcut: legacy SurfaceControl capture is verified on API 29–30; other TVs use the native fallback.
            if(Build.VERSION.SDK_INT<29||Build.VERSION.SDK_INT>30)throw new UnsupportedOperationException();
            if(Looper.myLooper()==null)Looper.prepareMainLooper();
            Context context=AppPresentationHelper.createSystemContext();
            Display display=((DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            if(display==null||display.getRotation()!=Surface.ROTATION_0)throw new UnsupportedOperationException();
            Point size=new Point();display.getRealSize(size);
            if(size.x<1||size.y<1||size.x>16384||size.y>16384)throw new UnsupportedOperationException();
            int layerStack=(Integer)Display.class.getDeclaredMethod("getLayerStack").invoke(display);
            capture.prepareMethods();
            final Thread owner=Thread.currentThread();
            Thread stop=new Thread(()->{
                try {while(System.in.read()!=-1){} }catch(IOException ignored){}
                capture.running=false;
                try{output.close();}catch(IOException ignored){}
                // A wedged vendor codec must not leave an invisible capture process after the client closes.
                try{owner.join(2000);}catch(InterruptedException ignored){}
                if(owner.isAlive())android.os.Process.killProcess(android.os.Process.myPid());
            },"tvadb-video-owner");
            stop.setDaemon(true);stop.start();
            Runtime.getRuntime().addShutdownHook(new Thread(capture::close,"tvadb-video-cleanup"));
            stage=NO_ENCODER;
            MediaCodecInfo selected=null;int width=0,height=0,rate=0;
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if(!info.isEncoder()||!info.isHardwareAccelerated()||info.isSoftwareOnly())continue;
                boolean avc=false;for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase("video/avc"))avc=true;
                if(!avc)continue;
                MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType("video/avc");
                boolean surface=false;for(int color:caps.colorFormats)if(color==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)surface=true;
                if(!surface)continue;
                MediaCodecInfo.VideoCapabilities video=caps.getVideoCapabilities();
                double scale=Math.min(1.0,(double)edge/Math.max(size.x,size.y));
                int wa=Math.max(16,video.getWidthAlignment()),ha=Math.max(16,video.getHeightAlignment());
                int w=Math.max(wa,(int)(size.x*scale)/wa*wa),h=Math.max(ha,(int)(size.y*scale)/ha*ha);
                if(!video.isSizeSupported(w,h))continue;
                int max=(int)Math.floor(video.getSupportedFrameRatesFor(w,h).getUpper());
                int requested=Math.min(fps,max);
                if(requested<1||!video.areSizeAndRateSupported(w,h,requested))continue;
                selected=info;width=w;height=h;rate=requested;break;
            }
            if(selected==null)throw new UnsupportedOperationException();
            if(!capture.running)throw new EOFException();
            stage=ENCODER_FAILED;
            capture.codec=MediaCodec.createByCodecName(selected.getName());
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);format.setInteger(MediaFormat.KEY_FRAME_RATE,rate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,rate);
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,100000L);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
            format.setInteger(MediaFormat.KEY_PRIORITY,0);
            capture.codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            capture.surface=capture.codec.createInputSurface();capture.codec.start();capture.started=true;
            if(!capture.running)throw new EOFException();
            stage=CAPTURE_FAILED;
            capture.mirror(size.x,size.y,width,height,layerStack);
            output.writeByte(INFO);output.writeInt(20);output.writeInt(size.x);output.writeInt(size.y);
            output.writeInt(width);output.writeInt(height);output.writeInt(rate);output.flush();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(capture.running) {
                int index=capture.codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual=capture.codec.getOutputFormat();
                    for(String key:new String[]{"csd-0","csd-1"})if(actual.containsKey(key))write(output,CONFIG,actual.getByteBuffer(key));
                }else if(index>=0) {
                    try {
                        if((info.flags&MediaCodec.BUFFER_FLAG_PARTIAL_FRAME)!=0)throw new IOException("Incomplete codec access unit");
                        if(info.size>0){ByteBuffer data=capture.codec.getOutputBuffer(index).duplicate();data.position(info.offset);data.limit(info.offset+info.size);write(output,(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0?CONFIG:FRAME,data);}
                        if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)break;
                    }finally{capture.codec.releaseOutputBuffer(index,false);}
                }
            }
        }catch(Throwable error) {
            if(capture.running)try{
                int detail=error instanceof MediaCodec.CodecException?((MediaCodec.CodecException)error).getErrorCode():0;
                output.writeByte(ERROR);output.writeInt(8);output.writeInt(stage);output.writeInt(detail);output.flush();
            }catch(IOException ignored){}
        }finally{
            capture.running=false;capture.close();
            try{output.close();}catch(IOException ignored){}
            System.exit(0);
        }
    }

    // Match scrcpy's legacy capture rule; Android 12 removes shell access to secure displays.
    static boolean useSecureDisplay(int sdk,String codename) {
        return sdk<30||(sdk==30&&!"S".equals(codename));
    }

    static void validateOptions(int edge,int bitrate,int fps) {
        if(edge<128||edge>1280||bitrate<100000||bitrate>4000000||fps<1||fps>30)throw new IllegalArgumentException("Invalid video limits");
    }

    static void write(DataOutputStream output,int kind,ByteBuffer source)throws IOException {
        if(source==null)return;
        ByteBuffer data=source.duplicate();int count=data.remaining();
        if(count<1||count>MAX_PACKET)throw new IOException("Video packet exceeds limit");
        // One write per packet avoids tiny header writes becoming separate ADB payloads.
        byte[] bytes=new byte[count+5];bytes[0]=(byte)kind;
        bytes[1]=(byte)(count>>>24);bytes[2]=(byte)(count>>>16);bytes[3]=(byte)(count>>>8);bytes[4]=(byte)count;
        data.get(bytes,5,count);output.write(bytes);output.flush();
    }

    private static final class Capture {
        volatile boolean running=true;
        private final AtomicBoolean closed=new AtomicBoolean();
        MediaCodec codec;Surface surface;IBinder display;boolean started;
        Class<?> control;Method open,close,setSurface,setProjection,setStack,destroy;
        void prepareMethods()throws Exception {
            control=Class.forName("android.view.SurfaceControl");
            control.getDeclaredMethod("createDisplay",String.class,boolean.class);
            open=control.getDeclaredMethod("openTransaction");close=control.getDeclaredMethod("closeTransaction");
            setSurface=control.getDeclaredMethod("setDisplaySurface",IBinder.class,Surface.class);
            setProjection=control.getDeclaredMethod("setDisplayProjection",IBinder.class,int.class,Rect.class,Rect.class);
            setStack=control.getDeclaredMethod("setDisplayLayerStack",IBinder.class,int.class);
            destroy=control.getDeclaredMethod("destroyDisplay",IBinder.class);
        }
        void mirror(int sourceWidth,int sourceHeight,int width,int height,int stack)throws Exception {
            display=(IBinder)control.getDeclaredMethod("createDisplay",String.class,boolean.class).invoke(null,"TVADB Preview",useSecureDisplay(Build.VERSION.SDK_INT,Build.VERSION.CODENAME));
            open.invoke(null);
            try {
                setSurface.invoke(null,display,surface);
                setProjection.invoke(null,display,0,new Rect(0,0,sourceWidth,sourceHeight),new Rect(0,0,width,height));
                setStack.invoke(null,display,stack);
            }finally{close.invoke(null);}
        }
        void close() {
            if(!closed.compareAndSet(false,true))return;
            running=false;
            if(display!=null&&destroy!=null)try{destroy.invoke(null,display);}catch(Exception ignored){}
            display=null;
            if(surface!=null)try{surface.release();}catch(Exception ignored){}
            surface=null;
            if(codec!=null){if(started)try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}
            codec=null;
        }
    }
}
