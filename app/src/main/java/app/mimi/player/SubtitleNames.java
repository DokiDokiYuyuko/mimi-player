package app.mimi.player;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class SubtitleNames {
    static String stem(String original,int cue){
        String value=original.replaceFirst("\\.[^.]+$","").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();if(value.isEmpty())value="字幕";
        if(value.getBytes(StandardCharsets.UTF_8).length>180){String suffix="~"+LibraryStore.id(value.toLowerCase(Locale.ROOT)).substring(0,8);while(value.getBytes(StandardCharsets.UTF_8).length>170)value=value.substring(0,value.offsetByCodePoints(value.length(),-1));value+=suffix;}
        return value+(cue>0?String.format(Locale.ROOT,".cue%02d",cue):"");
    }
    private SubtitleNames(){}
}
