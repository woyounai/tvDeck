package com.tvdeck.client;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ParityTests {
    @Test public void heartbeatWaitsForSixtySecondsWithoutAdbActivity(){
        assertFalse(DeckModel.heartbeatDue(69_999,10_000));
        assertTrue(DeckModel.heartbeatDue(70_000,10_000));
        assertFalse(DeckModel.heartbeatDue(70_000,69_999));
    }

    @Test public void mouseSwipeRequiresEnoughTravelAndAClearAxis(){
        assertEquals(0,MainActivity.mouseSwipeKey(13,0,14));
        assertEquals(0,MainActivity.mouseSwipeKey(20,18,14));
        assertEquals(22,MainActivity.mouseSwipeKey(24,18,14));
        assertEquals(21,MainActivity.mouseSwipeKey(-14,0,14));
        assertEquals(20,MainActivity.mouseSwipeKey(0,-14,14));
        assertEquals(19,MainActivity.mouseSwipeKey(0,14,14));
    }

    @Test public void screenshotPacingIncludesCaptureTimeAndKeepsAThrottle(){
        assertEquals(250,DeckModel.screenshotPreviewDelay(0));
        assertEquals(150,DeckModel.screenshotPreviewDelay(100));
        assertEquals(80,DeckModel.screenshotPreviewDelay(250));
        assertEquals(80,DeckModel.screenshotPreviewDelay(1500));
        assertEquals(250,DeckModel.screenshotPreviewDelay(-10));
    }

    @Test public void connectionRetriesStopAfterThreeAndNeverReplaySuccess() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        assertEquals("connected",DeckModel.retryConnection(()->{if(attempts.incrementAndGet()<4)throw new java.io.IOException("offline");return "connected";}));
        assertEquals(4,attempts.get());attempts.set(0);
        try{DeckModel.retryConnection(()->{attempts.incrementAndGet();throw new java.io.IOException("offline");});fail();}catch(java.io.IOException expected){}
        assertEquals(4,attempts.get());attempts.set(0);
        assertEquals("connected",DeckModel.retryConnection(()->{attempts.incrementAndGet();return "connected";}));assertEquals(1,attempts.get());
        try{DeckModel.retryConnection(()->{attempts.incrementAndGet();throw new InterruptedException("cancelled");});fail();}catch(InterruptedException expected){assertTrue(Thread.interrupted());}
        assertEquals(2,attempts.get());
    }
    @Test public void closedPreviewCanBeStoppedAgainAndCannotRestart(){
        VideoPreview preview=new VideoPreview(null,null,new VideoPreview.Observer(){
            public void frame(int width,int height){fail("Closed preview produced a frame");}
            public void fallback(Exception reason){fail("Closed preview started fallback");}
        });
        preview.close();preview.close();preview.start();
    }
    @Test public void videoEncoderAndDecoderShareAlignedSizes(){
        assertArrayEquals(new int[]{1280,720},DeckModel.videoSize(1920,1080,true));
        assertArrayEquals(new int[]{720,1280},DeckModel.videoSize(1080,1920,true));
        assertArrayEquals(new int[]{3840,2160},DeckModel.videoSize(3840,2160,false));
        assertArrayEquals(new int[]{16,16},DeckModel.videoSize(10,10,true));
    }

    @Test public void logFiltersCombinePidTagAndMinimumLevel(){
        String lines="11-01 12:30:01.123  42  43 W ActivityManager: Failed test\n11-01 12:30:01.124  142  43 E ActivityManager: Failed test\n11-01 12:30:01.125  42  43 D ActivityManager: Failed test\n";
        String filtered=LogFilter.apply(lines,"failed","activity","42","W");assertTrue(filtered.contains(" W "));assertFalse(filtered.contains(" E "));assertFalse(filtered.contains(" D "));
        assertEquals("",LogFilter.apply(lines,"missing","","",""));
    }

    @Test public void scanOnlyTargetsPrivateIPv4(){
        assertTrue(LanScanner.privateAddress(new byte[]{10,0,0,1}));assertTrue(LanScanner.privateAddress(new byte[]{(byte)172,16,0,1}));
        assertFalse(LanScanner.privateAddress(new byte[]{(byte)172,32,0,1}));assertFalse(LanScanner.privateAddress(new byte[]{8,8,8,8}));assertFalse(LanScanner.privateAddress(new byte[16]));
    }

    @Test public void archiveAndTransferRejectTraversal() throws Exception {
        for(String path:new String[]{"../base.apk","x/../../a.apk","/base.apk","C:\\base.apk","x/./a.apk"}) {
            try{InstallBundle.archivePath(path);fail(path);}catch(java.io.IOException expected){}
        }
        assertEquals("splits/base.apk",InstallBundle.archivePath("splits/base.apk"));
        for(String name:new String[]{"..","a/b","a\\b",""}){try{Transfers.safeName(name);fail(name);}catch(java.io.IOException expected){}}
        try{InstallBundle.copyBounded(new java.io.ByteArrayInputStream(new byte[9]),new java.io.ByteArrayOutputStream(),8);fail();}catch(java.io.IOException expected){}
    }
    @Test public void malformedManifestsFailClosed() throws Exception {
        java.util.Random random=new java.util.Random(13);
        for(int n=0;n<500;n++){byte[] bytes=new byte[random.nextInt(256)];random.nextBytes(bytes);try{ApkManifest.parse(bytes);fail();}catch(java.io.IOException expected){}}
    }

    @Test public void annexBHandlesFragmentedStartCodes() throws Exception {
        byte[] wire={0,0,0,1,103,1,2,0,0,1,104,3,0,0,0,1,101,(byte)128};
        List<byte[]> nals=new ArrayList<>();AnnexB parser=new AnnexB(nals::add);
        for(byte b:wire)parser.append(new byte[]{b},1);parser.finish();
        assertEquals(3,nals.size());assertEquals(7,AnnexB.type(nals.get(0)));assertTrue(AnnexB.firstSlice(nals.get(2)));
    }
    @Test public void keyboardCompositionAndConfirmationAreOrdered() throws Exception {
        AtomicReference<String> remote=new AtomicReference<>("TV");List<String> calls=Collections.synchronizedList(new ArrayList<>());CountDownLatch initial=new CountDownLatch(1),done=new CountDownLatch(1);
        KeyboardSync sync=new KeyboardSync(new KeyboardSync.Endpoint(){public String read(){return remote.get();}public String write(String text){calls.add("write");remote.set(text);return text;}public void confirm(){calls.add("confirm");}public void close(){}},new KeyboardSync.Observer(){public void remote(String text,long rev){initial.countDown();}public void failure(Exception e){fail(e.toString());}public void confirmed(){done.countDown();}});
        try {sync.start();assertTrue(initial.await(2,TimeUnit.SECONDS));sync.edit("pinyin",true);Thread.sleep(250);assertEquals("TV",remote.get());sync.edit("中文",false);sync.confirm();assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(Arrays.asList("write","confirm"),calls);}finally{sync.close();}
    }
    @Test public void keyboardConflictPausesWrites() throws Exception {
        AtomicInteger writes=new AtomicInteger();CountDownLatch paused=new CountDownLatch(1);
        KeyboardSync sync=new KeyboardSync(new KeyboardSync.Endpoint(){public String read(){return "before";}public String write(String text)throws Exception{writes.incrementAndGet();throw new java.io.IOException("conflict");}public void confirm(){fail();}public void close(){}},new KeyboardSync.Observer(){public void remote(String t,long r){}public void failure(Exception e){paused.countDown();}public void confirmed(){fail();}});
        try{sync.edit("draft",false);sync.start();assertTrue(paused.await(2,TimeUnit.SECONDS));Thread.sleep(450);assertEquals(1,writes.get());}finally{sync.close();}
    }
}
