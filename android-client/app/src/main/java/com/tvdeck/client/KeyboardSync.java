package com.tvdeck.client;

import java.io.Closeable;
import java.util.concurrent.*;

/** Serial text requests; local revisions never overwrite newer edits or a conflicting TV field. */
final class KeyboardSync implements Closeable {
    interface Endpoint {String read() throws Exception; String write(String text) throws Exception; void confirm() throws Exception; void close() throws Exception;}
    interface Observer {void remote(String text,long revision);void failure(Exception error);void confirmed();}
    private final Endpoint endpoint;private final Observer observer;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
    private String desired;private long revision,acknowledged;private boolean closed,paused,confirm,composing;
    KeyboardSync(Endpoint endpoint,Observer observer){this.endpoint=endpoint;this.observer=observer;}
    synchronized long edit(String text,boolean composing){this.composing=composing;if(!composing){desired=text;revision++;}return revision;}
    synchronized long revision(){return revision;}
    synchronized void composition(boolean active){composing=active;}
    synchronized void confirm(){confirm=true;}
    void start(){executor.scheduleWithFixedDelay(this::tick,0,200,TimeUnit.MILLISECONDS);}
    synchronized void resume(){paused=false;confirm=false;desired=null;acknowledged=revision;}
    private void tick(){
        String write;long version;boolean submit;
        synchronized(this){if(closed||paused)return;version=revision;write=!composing&&desired!=null&&revision!=acknowledged?desired:null;submit=confirm&&!composing;}
        try {
            String remote=write==null?endpoint.read():endpoint.write(write);
            if(remote!=null){
                synchronized(this){if(closed)return;if(write!=null&&version==revision)acknowledged=version;}
                observer.remote(remote,version);
                synchronized(this){submit=submit&&revision==acknowledged&&version==revision&&!composing;}
                if(submit){endpoint.confirm();observer.confirmed();close();return;}
            }
        } catch(Exception error){synchronized(this){if(closed)return;paused=true;}observer.failure(error);return;}
    }
    @Override public void close(){synchronized(this){if(closed)return;closed=true;}executor.shutdownNow();try{endpoint.close();}catch(Exception ignored){}}
}
