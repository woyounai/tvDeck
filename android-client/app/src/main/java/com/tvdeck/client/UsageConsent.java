package com.tvdeck.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.LocaleList;
import java.util.Locale;

final class UsageConsent {
    static final String VERSION="2026-11-06.1";
    static final String MAINLAND="mainland", INTERNATIONAL="international";

    static SharedPreferences preferences(Context context){
        return context.getSharedPreferences("usage-consent",Context.MODE_PRIVATE);
    }
    static String fromSystemCode(String code){
        if(code==null||code.isEmpty())return MAINLAND;
        return "CN".equals(code.toUpperCase(Locale.ROOT))?MAINLAND:INTERNATIONAL;
    }
    static String systemRegion(){
        LocaleList locales=Resources.getSystem().getConfiguration().getLocales();
        return fromSystemCode(locales.isEmpty()?"":locales.get(0).getCountry());
    }
    static boolean valid(String version,String region,String language,long acceptedAt){
        return VERSION.equals(version)
            && (MAINLAND.equals(region)||INTERNATIONAL.equals(region))
            && ("en".equals(language)||"zh-Hans".equals(language))
            && acceptedAt>0;
    }
    static boolean accepted(Context context){
        SharedPreferences saved=preferences(context);
        return valid(saved.getString("version",""),saved.getString("region",""),
            saved.getString("language",""),saved.getLong("acceptedAt",0))
            && systemRegion().equals(saved.getString("region",""));
    }
    static boolean accept(Context context,String region,String language){
        long now=System.currentTimeMillis();
        if(!systemRegion().equals(region)||!valid(VERSION,region,language,now))return false;
        return preferences(context).edit().putString("version",VERSION).putString("region",region)
            .putString("language",language).putLong("acceptedAt",now).commit();
    }
}
