package com.tvdeck.client;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.widget.RemoteViews;

public final class DeckWidget extends AppWidgetProvider {
    static final int[] KEYS={19,21,23,22,20,3,4,24,25,164,85,178,26,82};
    static final int[] VIEWS={R.id.widget_up,R.id.widget_left,R.id.widget_ok,R.id.widget_right,R.id.widget_down,R.id.widget_home,R.id.widget_back,R.id.widget_volume_up,R.id.widget_volume_down,R.id.widget_mute,R.id.widget_play,R.id.widget_input,R.id.widget_power,R.id.widget_menu};
    static boolean allowed(int key){for(int allowed:KEYS)if(key==allowed)return true;return false;}
    static void updateAll(Context context){AppWidgetManager manager=AppWidgetManager.getInstance(context);int[] ids=manager.getAppWidgetIds(new ComponentName(context,DeckWidget.class));for(int id:ids)update(context,manager,id);}
    private static void update(Context context,AppWidgetManager manager,int id){
        DeviceStore store=new DeviceStore(context);DeviceStore.Device device=store.find(store.selectedId());String token=store.token();RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.remote_widget);
        views.setTextViewText(R.id.widget_title,device==null?"TVDeck":device.name);
        PendingIntent open=PendingIntent.getActivity(context,id,new Intent(context,MainActivity.class).putExtra("remote",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_title,open);views.setOnClickPendingIntent(R.id.widget_open,open);views.setOnClickPendingIntent(R.id.widget_disconnected,open);
        boolean ready=UsageConsent.accepted(context)&&device!=null&&!token.isEmpty();
        views.setViewVisibility(R.id.widget_controls,ready?android.view.View.VISIBLE:android.view.View.GONE);
        views.setViewVisibility(R.id.widget_disconnected,ready?android.view.View.GONE:android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_extra,manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)>=220?android.view.View.VISIBLE:android.view.View.GONE);
        for(int i=0;i<KEYS.length;i++){
            views.setBoolean(VIEWS[i],"setEnabled",ready);
            if(device!=null){Intent command=new Intent(context,ConnectionService.class).setAction("widget-key").setData(Uri.parse("tvdeck-widget://"+id+"/"+KEYS[i]+"/"+token)).putExtra("device",device.id).putExtra("token",token).putExtra("key",KEYS[i]);
                views.setOnClickPendingIntent(VIEWS[i],PendingIntent.getForegroundService(context,KEYS[i]+id*100,command,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));}
        }
        manager.updateAppWidget(id,views);
    }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,android.os.Bundle options){update(context,manager,id);}
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){for(int id:ids)update(context,manager,id);}
}
