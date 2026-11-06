package com.tvdeck.client;

import java.util.Locale;
import java.util.regex.*;

final class LogFilter {
    private static final Pattern LINE=Pattern.compile("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+(\\d+)\\s+\\d+\\s+([VDIWEF])\\s+([^:]+):.*$");
    static String apply(String source,String search,String tag,String pid,String level){
        StringBuilder out=new StringBuilder();String query=search.toLowerCase(Locale.ROOT);
        for(String line:source.split("\n")){
            if(!line.toLowerCase(Locale.ROOT).contains(query))continue;
            if(!tag.isEmpty()||!pid.isEmpty()||!level.isEmpty()){
                Matcher match=LINE.matcher(line);if(!match.matches())continue;
                if(!pid.isEmpty()&&!pid.equals(match.group(1)))continue;
                if(!tag.isEmpty()&&!match.group(3).trim().toLowerCase(Locale.ROOT).contains(tag.toLowerCase(Locale.ROOT)))continue;
                if(!level.isEmpty()&&"VDIWEF".indexOf(match.group(2))<"VDIWEF".indexOf(level))continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
