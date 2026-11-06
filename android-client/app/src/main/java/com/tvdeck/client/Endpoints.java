package com.tvdeck.client;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

final class Endpoints {
    final String host;final int port;
    Endpoints(String host,int port) {this.host=host;this.port=port;}
    static final class Scan {
        final Endpoints device;final URI aerial;
        Scan(Endpoints device,URI aerial){this.device=device;this.aerial=aerial;}
    }
    static Scan scan(String payload,boolean aerialOnly)throws Exception {
        try{return new Scan(null,aerial(payload));}
        catch(Exception invalid){if(aerialOnly)throw invalid;return new Scan(qr(payload),null);}
    }
    static URI aerial(String payload) throws Exception {
        URI uri=new URI(payload);String host=uri.getHost();
        if(!"http".equals(uri.getScheme())||host==null||(uri.getPort()<8790||uri.getPort()>8799)||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().matches("/[abcdefghjkmnpqrstuvwxyz23456789]{8}/"))throw new IllegalArgumentException("Aerial QR");
        qr("tvadb://connect?host="+host+"&port=8790");
        if(!payload.equals("http://"+host+":"+uri.getPort()+uri.getRawPath()))throw new IllegalArgumentException("Aerial QR");
        return uri;
    }
    static int port(String value) {
        if(!value.matches("[0-9]{1,5}"))throw new IllegalArgumentException("Port");
        int port=Integer.parseInt(value);if(port<1||port>65535)throw new IllegalArgumentException("Port");return port;
    }
    static Endpoints qr(String payload) throws Exception {
        URI uri=new URI(payload);
        if(!("tvadb".equals(uri.getScheme())||"tvdeck".equals(uri.getScheme()))||!"connect".equals(uri.getHost())||uri.getPort()!=-1||uri.getRawUserInfo()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty())throw new IllegalArgumentException("QR");
        Map<String,String> values=new HashMap<>();String query=uri.getRawQuery();if(query==null)throw new IllegalArgumentException("QR");
        for(String pair:query.split("&")) {String[] parts=pair.split("=",2);if(parts.length!=2||values.put(parts[0],URLDecoder.decode(parts[1],"UTF-8"))!=null)throw new IllegalArgumentException("QR");}
        if(values.size()!=2||!values.containsKey("host")||!values.containsKey("port"))throw new IllegalArgumentException("QR");
        String host=values.get("host");String[] parts=host.split("\\.",-1);if(parts.length!=4)throw new IllegalArgumentException("Host");
        int[] octets=new int[4];for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException("Host");octets[i]=Integer.parseInt(parts[i]);if(octets[i]>255)throw new IllegalArgumentException("Host");}
        if(!(octets[0]==10||octets[0]==192&&octets[1]==168||octets[0]==172&&octets[1]>=16&&octets[1]<=31))throw new IllegalArgumentException("Host");
        return new Endpoints(host,port(values.get("port")));
    }
}
