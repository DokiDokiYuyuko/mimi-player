package app.mimi.player;

import java.util.*;
import java.util.regex.*;

public final class CueSheet {
    public static final class Track {
        public int number;
        public String file="",title="",performer="";
        public long startMs=-1,endMs=-1;
    }
    public String title="",performer="";
    public final List<Track> tracks=new ArrayList<>();
    private static final Pattern LINE=Pattern.compile("^([A-Z]+)\\s+(.+)$",Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE=Pattern.compile("^(?:\"([^\"]+)\"|([^\\s]+))\\s+.*$");
    private static final Pattern INDEX=Pattern.compile("^01\\s+(\\d+):(\\d{1,2}):(\\d{1,2})$");
    public static CueSheet parse(String content){
        CueSheet sheet=new CueSheet();String file="";Track current=null;
        for(String raw:content.replace("\uFEFF","").split("\\r?\\n")){
            Matcher line=LINE.matcher(raw.trim());if(!line.matches())continue;String command=line.group(1).toUpperCase(Locale.ROOT),value=line.group(2).trim();
            switch(command){
                case "FILE":Matcher f=FILE.matcher(value);if(f.matches())file=f.group(1)!=null?f.group(1):f.group(2);current=null;break;
                case "TRACK":String[] fields=value.split("\\s+");if(fields.length>=2&&fields[1].equalsIgnoreCase("AUDIO")){try{current=new Track();current.number=Integer.parseInt(fields[0]);current.file=file;sheet.tracks.add(current);}catch(NumberFormatException ignored){current=null;}}else current=null;break;
                case "TITLE":if(current==null)sheet.title=unquote(value);else current.title=unquote(value);break;
                case "PERFORMER":if(current==null)sheet.performer=unquote(value);else current.performer=unquote(value);break;
                case "INDEX":if(current!=null){Matcher index=INDEX.matcher(value);if(index.matches()){long minutes=Long.parseLong(index.group(1)),seconds=Long.parseLong(index.group(2)),frames=Long.parseLong(index.group(3));if(seconds<60&&frames<75)current.startMs=(minutes*60+seconds)*1000+Math.round(frames*1000.0/75);}}break;
                default:break;
            }
        }
        sheet.tracks.removeIf(t->t.file.isEmpty()||t.startMs<0);
        for(int i=0;i<sheet.tracks.size();i++){Track t=sheet.tracks.get(i);if(t.title.isEmpty())t.title=String.format(Locale.ROOT,"曲目 %02d",t.number);if(t.performer.isEmpty())t.performer=sheet.performer;for(int j=i+1;j<sheet.tracks.size();j++){Track next=sheet.tracks.get(j);if(next.file.equals(t.file)&&next.startMs>t.startMs){t.endMs=next.startMs;break;}}}
        return sheet;
    }
    public static String resolve(String cueRelative,String audioReference){
        String ref=audioReference.replace('\\','/');if(ref.startsWith("/")||ref.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*"))return null;
        int slash=cueRelative.lastIndexOf('/');String parent=slash<0?"":cueRelative.substring(0,slash+1);Deque<String> parts=new ArrayDeque<>();
        for(String p:(parent+ref).split("/")){if(p.isEmpty()||p.equals("."))continue;if(p.equals("..")){if(parts.isEmpty())return null;parts.removeLast();}else parts.addLast(p);}
        return String.join("/",parts);
    }
    private static String unquote(String value){return value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value;}
}
