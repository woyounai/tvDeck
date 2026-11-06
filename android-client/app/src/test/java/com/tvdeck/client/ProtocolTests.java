package com.tvdeck.client;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class ProtocolTests {
    @Test public void volumeRangeUsesDeviceValuesAndRejectsMalformedReplies() throws Exception {
        DeckModel.VolumeState state=DeckModel.parseVolumeState("[v] volume is 7 in range [0..15]");
        assertEquals(7,state.current);assertEquals(0,state.min);assertEquals(15,state.max);
        for(String reply:new String[]{"volume is 20 in range [0..15]","volume is 0 in range [0..0]","volume unknown"}) {
            try{DeckModel.parseVolumeState(reply);fail(reply);}catch(IOException expected){}
        }
    }
    @Test public void partialNextPictureHeaderReleasesOnlyCompletePreviousPicture() throws Exception {
        java.util.List<byte[]> nals=new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger slices=new java.util.concurrent.atomic.AtomicInteger(),frames=new java.util.concurrent.atomic.AtomicInteger();
        AnnexB parser=new AnnexB(nal->{nals.add(nal);int type=AnnexB.type(nal);if(type==1||type==5)slices.incrementAndGet();},()->{if(slices.getAndSet(0)>0)frames.incrementAndGet();});
        byte[] first={0,0,0,1,0x65,(byte)0x80,0x12};
        parser.append(first,first.length);assertEquals(0,nals.size());assertEquals(0,frames.get());
        for(byte value:new byte[]{0,0,1,0x41})parser.append(new byte[]{value},1);
        assertEquals(1,nals.size());assertEquals(0,frames.get());
        parser.append(new byte[]{(byte)0x80},1);
        assertEquals(1,frames.get());assertEquals(1,nals.size());assertArrayEquals(first,nals.get(0));
        parser.append(new byte[]{0x12},1);assertEquals(1,frames.get());
        byte[] extraSlice={0,0,0,1,0x41,0x40,0x12};parser.append(extraSlice,extraSlice.length);
        assertEquals(1,frames.get());assertEquals(2,nals.size());
        parser.append(new byte[]{0,0,1,9},4);
        assertEquals(2,frames.get());assertEquals(3,nals.size());assertArrayEquals(extraSlice,nals.get(2));
        parser.finish();assertEquals(4,nals.size());assertEquals(2,frames.get());
    }
    @Test public void pictureBoundaryRequiresACompleteFirstSliceBit(){
        assertFalse(AnnexB.startsPicture((byte)0x41,-1));
        assertFalse(AnnexB.startsPicture((byte)0x41,0x40));
        assertFalse(AnnexB.startsPicture((byte)0x06,0x80));
        assertTrue(AnnexB.startsPicture((byte)0x41,0x80));
        assertTrue(AnnexB.startsPicture((byte)0x65,0xff));
        assertTrue(AnnexB.startsPicture((byte)9,-1));
    }

    @Test public void unifiedScannerRoutesOnlySupportedQrTypes()throws Exception {
        String device="tvadb://connect?host=192.168.1.20&port=5555",aerial="http://192.168.1.20:8790/abc234xy/";
        Endpoints.Scan tv=Endpoints.scan(device,false);assertEquals(5555,tv.device.port);assertNull(tv.aerial);
        Endpoints.Scan web=Endpoints.scan(aerial,false);assertEquals(aerial,web.aerial.toASCIIString());assertNull(web.device);
        assertNotNull(Endpoints.scan(aerial,true).aerial);
        try{Endpoints.scan(device,true);fail("Aerial-only scanner accepted a device QR");}catch(Exception expected){}
        for(String invalid:new String[]{"https://example.com/","http://192.168.1.20:8790/","http://8.8.8.8:8790/abc234xy/","tvadb://connect?host=192.168.1.20&port=0"}){
            try{Endpoints.scan(invalid,false);fail("Unsupported QR accepted");}catch(Exception expected){}
        }
    }
    @Test public void aerialQrTrustBoundary() throws Exception {
        for(String host:new String[]{"192.168.1.20","10.0.0.1","172.16.0.1","172.31.255.254"}){
            String value="http://"+host+":8790/abc234xy/";assertEquals(value,Endpoints.aerial(value).toASCIIString());
        }
        assertEquals(8799,Endpoints.aerial("http://192.168.1.20:8799/abc234xy/").getPort());
        for(String bad:new String[]{"http://192.168.1.20:8800/abc234xy/","https://192.168.1.20:8790/abc234xy/","http://8.8.8.8:8790/abc234xy/","http://172.32.0.1:8790/abc234xy/","http://127.0.0.1:8790/abc234xy/","http://192.168.01.20:8790/abc234xy/","http://tv.local:8790/abc234xy/","http://192.168.1.20:5555/abc234xy/","http://192.168.1.20:8790/abc234xy","http://user@192.168.1.20:8790/abc234xy/","http://192.168.1.20:8790/abc234xy/?x=1","http://192.168.1.20:8790/abc234xy/#x","http://192.168.1.20:8790/abc234xy/api/state","http://192.168.1.20:8790/%61bc234xy/","http://192.168.1.20:8790/abc234x0/","http://192.168.1.20:8790/abc234x/","tvadb://connect?host=192.168.1.20&port=5555"}){
            try{Endpoints.aerial(bad);fail("Invalid QR accepted");}catch(Exception expected){}
        }
    }

    @Test public void endpointTrustBoundary() throws Exception {
        Endpoints valid=Endpoints.qr("tvadb://connect?host=192.168.1.20&port=5555");assertEquals(5555,valid.port);
        assertEquals("10.0.0.1",Endpoints.qr("tvdeck://connect?port=12345&host=10.0.0.1").host);
        for(String bad:new String[]{"https://connect?host=10.0.0.1&port=5555","tvadb://connect?host=8.8.8.8&port=5555","tvadb://connect?host=10.0.0.1&port=0","tvadb://connect?host=10.0.0.1&host=10.0.0.2","tvadb://connect?host=192.168.01.2&port=5555","tvadb://connect?host=10.0.0.1&port=5555#fragment"}) {
            try {Endpoints.qr(bad);fail(bad);}catch(Exception expected){}
        }
    }
    @Test public void shellQuotingPreservesArguments() {
        assertEquals("'a'\\''b; $(id)'",DeckModel.quote("a'b; $(id)"));
        assertEquals("''",DeckModel.quote(""));
        try {DeckModel.quote("a\0b");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void connectionUsesTvModelAndKeepsCustomName() {
        assertEquals("BRAVIA",DeckModel.resolvedDeviceName("Android TV","192.168.1.10","BRAVIA"));
        assertEquals("BRAVIA",DeckModel.resolvedDeviceName("192.168.1.10","192.168.1.10","BRAVIA"));
        assertEquals("Living room",DeckModel.resolvedDeviceName("Living room","192.168.1.10","BRAVIA"));
        assertEquals("Android TV",DeckModel.resolvedDeviceName("Android TV","192.168.1.10",""));
    }
    @Test public void syncPacketAndBounds() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();AdbFiles.number(out,0x89abcdef);
        assertEquals(0x89abcdef,AdbFiles.number(new ByteArrayInputStream(out.toByteArray())));
        try {AdbFiles.number(new ByteArrayInputStream(new byte[3]));fail();}catch(EOFException expected){}
        try {AdbFiles.bytes(new ByteArrayInputStream(new byte[0]),1024*1024+1);fail();}catch(IOException expected){}
        out.reset();AdbFiles.packet(out,"DATA",new byte[]{1,2,3});
        ByteArrayInputStream in=new ByteArrayInputStream(out.toByteArray());assertEquals("DATA",AdbFiles.id(in));assertEquals(3,AdbFiles.number(in));assertArrayEquals(new byte[]{1,2,3},AdbFiles.bytes(in,3));
    }
    @Test public void syncListDoneContainsOnlyMtime() throws Exception {
        ByteArrayOutputStream response=new ByteArrayOutputStream();
        response.write("DENT".getBytes("US-ASCII"));
        AdbFiles.number(response,0040000);AdbFiles.number(response,0);AdbFiles.number(response,1);
        byte[] name="Movies".getBytes("UTF-8");AdbFiles.number(response,name.length);response.write(name);
        response.write("DONE".getBytes("US-ASCII"));
        java.util.List<AdbFiles.Entry> entries=AdbFiles.listResponse(new ByteArrayInputStream(response.toByteArray()));
        assertEquals(1,entries.size());assertEquals("Movies",entries.get(0).name);assertTrue(entries.get(0).directory);assertEquals(1,entries.get(0).modifiedAt);
        assertTrue(AdbFiles.listResponse(new ByteArrayInputStream("DONE".getBytes("US-ASCII"))).isEmpty());
        try {AdbFiles.listResponse(new ByteArrayInputStream("NOPE".getBytes("US-ASCII")));fail("Invalid response accepted");}catch(IOException expected){}
    }
}
