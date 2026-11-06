package com.tvdeck.client;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConnectionService extends Service {
    static final String CHANNEL="tvdeck-connection";
    private final AtomicBoolean widgetBusy=new AtomicBoolean();
    private DeckModel model;
    private final Handler heartbeat=new Handler(Looper.getMainLooper());
    private final Runnable maintain=new Runnable(){public void run(){
        if(!model.busy&&model.connected&&model.heartbeatDue()){
            model.run("",()->{if(model.heartbeatDue())model.keepConnection();return true;},ok->{
                if(model.connected)startForeground(10,notification());else stopSelf();
            });
        }else if(!model.busy&&!model.connected){stopSelf();return;}
        heartbeat.postDelayed(this,15000);
    }};
    public static void hold(Context context){if(!UsageConsent.accepted(context))return;context.startForegroundService(new Intent(context,ConnectionService.class));}
    @Override public void onCreate(){super.onCreate();model=((DeckApplication)getApplication()).model();NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,getString(R.string.connection_service),NotificationManager.IMPORTANCE_LOW));startForeground(10,notification());}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ConnectionService.class).setAction("disconnect"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("TVDeck").setContentText(model.connected?model.host+":"+model.port:getString(R.string.connecting)).setContentIntent(open).addAction(new Notification.Action.Builder(null,getString(R.string.disconnect),stop).build()).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(!UsageConsent.accepted(this)){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&"disconnect".equals(intent.getAction())){new Thread(()->{model.disconnect();stopSelf();},"tvdeck-stop").start();return START_NOT_STICKY;}
        startForeground(10,notification());
        heartbeat.removeCallbacks(maintain);
        if(intent==null&&!model.connected&&!model.busy){
            model.run(getString(R.string.connecting),()->{model.restoreConnection();return true;},ok->{if(!model.connected)stopSelf();});
        }
        heartbeat.postDelayed(maintain,15000);
        if(intent!=null&&"widget-key".equals(intent.getAction())){
            if(model.busy||!widgetBusy.compareAndSet(false,true))return START_STICKY;
            String deviceId=intent.getStringExtra("device"),token=intent.getStringExtra("token");int key=intent.getIntExtra("key",-1);
            model.worker.execute(()->{boolean acquired=false;try{
                if(model.busy)return;
                DeviceStore store=model.devices;
                if(!DeckWidget.allowed(key)||deviceId==null||token==null||!deviceId.equals(store.selectedId())||!token.equals(store.token()))return;
                DeviceStore.Device target=store.find(deviceId);if(target==null)return;
                if(model.connected&&(!model.host.equals(target.host)||model.port!=target.port))return;
                model.busy=true;acquired=true;
                if(!model.connected)model.connect(target.host,target.port,null,0,8,0);
                if(model.connected&&model.host.equals(target.host)&&model.port==target.port)model.key(key);
            }catch(Exception error){new Handler(Looper.getMainLooper()).post(()->android.widget.Toast.makeText(this,getString(R.string.operation_failed)+": "+error.getLocalizedMessage(),android.widget.Toast.LENGTH_LONG).show());}finally{if(acquired)model.busy=false;widgetBusy.set(false);model.notifyChanged();DeckWidget.updateAll(this);if(model.connected)startForeground(10,notification());else stopSelf();}});
        }
        return START_STICKY;
    }
    @Override public void onDestroy(){heartbeat.removeCallbacks(maintain);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
