package com.tvdeck.client;

import android.content.Context;
import android.net.*;
import java.util.*;
import javax.net.SocketFactory;

/** Route only on-link private TV addresses through a physical LAN, without rebinding the app. */
final class LanRouting {
    static byte[] ipv4(String host){
        String[] parts=host.split("\\.",-1);if(parts.length!=4)return null;byte[] bytes=new byte[4];
        for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return null;int value=Integer.parseInt(parts[i]);if(value>255)return null;bytes[i]=(byte)value;}return bytes;
    }
    static boolean matches(byte[] target,byte[] local,int prefix){
        if(target==null||local==null||target.length!=4||local.length!=4||prefix<1||prefix>32||!LanScanner.privateAddress(target)||!LanScanner.privateAddress(local))return false;
        for(int bit=0;bit<prefix;bit++)if(((target[bit/8]^local[bit/8])&(1<<(7-bit%8)))!=0)return false;return true;
    }
    static boolean physical(NetworkCapabilities caps){return caps!=null&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));}
    static List<Network> networks(ConnectivityManager manager){
        List<Network> result=new ArrayList<>();Network active=manager.getActiveNetwork();if(active!=null&&physical(manager.getNetworkCapabilities(active)))result.add(active);
        for(Network network:manager.getAllNetworks())if(!result.contains(network)&&physical(manager.getNetworkCapabilities(network)))result.add(network);return result;
    }
    static SocketFactory socketFactory(Context context,String host){
        byte[] target=ipv4(host);if(target==null||!LanScanner.privateAddress(target))return SocketFactory.getDefault();
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);Network best=null;int prefix=0;
        for(Network network:networks(manager)){LinkProperties properties=manager.getLinkProperties(network);if(properties==null)continue;for(LinkAddress address:properties.getLinkAddresses())if(address.getPrefixLength()>prefix&&matches(target,address.getAddress().getAddress(),address.getPrefixLength())){best=network;prefix=address.getPrefixLength();}}
        return best==null?SocketFactory.getDefault():best.getSocketFactory();
    }
}
