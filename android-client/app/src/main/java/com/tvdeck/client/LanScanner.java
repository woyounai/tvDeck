package com.tvdeck.client;

import android.content.Context;
import android.net.*;
import java.io.Closeable;
import java.net.*;
import java.util.concurrent.*;

/** Explicit, bounded scan of the active private IPv4 LAN only. */
final class LanScanner implements Closeable {
    interface Observer {void found(String host);void finished();}
    private final ExecutorService pool=Executors.newFixedThreadPool(16);
    private volatile boolean closed;
    static boolean privateAddress(byte[] b){return b.length==4&&((b[0]&255)==10||((b[0]&255)==172&&(b[1]&255)>=16&&(b[1]&255)<=31)||((b[0]&255)==192&&(b[1]&255)==168));}
    void start(Context context,Observer observer)throws Exception{
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);Network selected=null;LinkAddress local=null;
        for(Network candidate:LanRouting.networks(manager)){LinkProperties properties=manager.getLinkProperties(candidate);if(properties==null)continue;for(LinkAddress address:properties.getLinkAddresses())if(privateAddress(address.getAddress().getAddress())&&address.getPrefixLength()<=24){selected=candidate;local=address;break;}if(selected!=null)break;}
        final Network network=selected;
        if(local==null)throw new java.io.IOException("No scan-eligible private IPv4 subnet");byte[] bytes=local.getAddress().getAddress();String prefix=(bytes[0]&255)+"."+(bytes[1]&255)+"."+(bytes[2]&255)+".";int self=bytes[3]&255;CountDownLatch remaining=new CountDownLatch(254);
        for(int i=1;i<=254;i++){final int last=i;pool.execute(()->{try{if(closed||last==self)return;String host=prefix+last;try(Socket socket=new Socket()){network.bindSocket(socket);socket.connect(new InetSocketAddress(host,5555),300);if(!closed)observer.found(host);}}catch(Exception ignored){}finally{remaining.countDown();}});}
        new Thread(()->{try{remaining.await(20,TimeUnit.SECONDS);if(!closed)observer.finished();}catch(InterruptedException ignored){}finally{pool.shutdown();}},"tvdeck-lan-scan").start();
    }
    public void close(){closed=true;pool.shutdownNow();}
}
