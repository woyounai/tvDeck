package com.tvdeck.client;

import android.content.*;
import org.json.*;
import java.util.*;

final class DeviceStore {
    static final class Device {
        String id=UUID.randomUUID().toString(),name="Android TV",host="",group="",note="";int port=5555;boolean favorite;long connectedAt;
        JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("name",name).put("host",host).put("port",port).put("group",group).put("note",note).put("favorite",favorite).put("connectedAt",connectedAt);}
        static Device parse(JSONObject j){Device d=new Device();d.id=j.optString("id",d.id);d.name=j.optString("name","Android TV");d.host=j.optString("host","");d.port=j.optInt("port",5555);d.group=j.optString("group","");d.note=j.optString("note","");d.favorite=j.optBoolean("favorite");d.connectedAt=j.optLong("connectedAt");return d;}
    }
    private final SharedPreferences preferences;
    DeviceStore(Context context){preferences=context.getSharedPreferences("devices",0);}
    synchronized List<Device> all(){List<Device> devices=new ArrayList<>();try{JSONArray array=new JSONArray(preferences.getString("records","[]"));for(int i=0;i<array.length();i++){Device d=Device.parse(array.getJSONObject(i));if(!d.host.isEmpty()&&d.port>0&&d.port<=65535)devices.add(d);}}catch(JSONException ignored){}if(devices.isEmpty()){String host=preferences.getString("host","");if(!host.isEmpty()){Device d=new Device();d.host=host;d.port=preferences.getInt("port",5555);devices.add(d);}}devices.sort(Comparator.comparing((Device d)->!d.favorite).thenComparing(Comparator.comparingLong((Device d)->d.connectedAt).reversed()));return devices;}
    synchronized Device find(String id){for(Device d:all())if(d.id.equals(id))return d;return null;}
    synchronized Device endpoint(String host,int port){for(Device d:all())if(d.host.equals(host)&&d.port==port)return d;Device d=new Device();d.host=host;d.port=port;return d;}
    synchronized void save(Device device){List<Device> devices=all();devices.removeIf(d->d.id.equals(device.id)||d.host.equals(device.host)&&d.port==device.port);devices.add(device);write(devices);}
    synchronized void remove(String id){List<Device> devices=all();devices.removeIf(d->d.id.equals(id));write(devices);if(selectedId().equals(id))invalidate();}
    private void write(List<Device> devices){JSONArray array=new JSONArray();try{for(Device d:devices)array.put(d.json());}catch(JSONException error){throw new IllegalStateException(error);}preferences.edit().putString("records",array.toString()).remove("host").remove("port").apply();}
    synchronized String publish(Device device){String token=UUID.randomUUID().toString();preferences.edit().putString("selected",device.id).putString("token",token).apply();return token;}
    synchronized void invalidate(){preferences.edit().remove("selected").remove("token").apply();}
    String selectedId(){return preferences.getString("selected","");}
    String token(){return preferences.getString("token","");}
}
