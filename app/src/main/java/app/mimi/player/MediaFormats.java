package app.mimi.player;

import java.util.*;

/** Import classification; actual codec support is established by probing/decoding, not this list. */
public final class MediaFormats {
    private static final Set<String> AUDIO=new HashSet<>(Arrays.asList(
        "mp3","mp2","flac","wav","wave","m4a","m4b","alac","aac","ogg","oga","opus","aif","aiff","aifc",
        "wma","asf","ape","wv","tta","tak","mka","mkv","mp4","webm","weba","caf","dsf","dff","dsd","ac3","eac3","dts","amr","3gp","mpc","spx","au","snd"));
    private static final Set<String> SUBTITLES=new HashSet<>(Arrays.asList("vtt","lrc","srt","ass","ssa","ttml","dfxp","sbv","smi","sami"));
    private static final Set<String> ARCHIVES=new HashSet<>(Arrays.asList("zip","7z","rar"));
    public static String extension(String name){int i=name.lastIndexOf('.');return i<0?"":name.substring(i+1).toLowerCase(Locale.ROOT);}
    public static boolean audio(String name){return AUDIO.contains(extension(name));}
    public static boolean subtitle(String name){return SUBTITLES.contains(extension(name));}
    public static boolean archive(String name){return ARCHIVES.contains(extension(name));}
    public static boolean image(String name){return Arrays.asList("jpg","jpeg","png","webp","bmp").contains(extension(name));}
    public static boolean cue(String name){return extension(name).equals("cue");}
    public static boolean partial(String name){return Arrays.asList("part","crdownload","download","tmp").contains(extension(name));}
    public static boolean splitArchive(String name){return name.matches("(?i).*(?:\\.(?:7z|zip)\\.\\d{3}|\\.part\\d+\\.rar|\\.r\\d{2})$");}
    private MediaFormats(){}
}
