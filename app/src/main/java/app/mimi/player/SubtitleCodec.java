package app.mimi.player;

import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Timestamp validation, ASR word grouping, and portable subtitle serialization. */
public final class SubtitleCodec {
    public static final class Cue {
        public double start,end;
        public String text,original="";
        public Cue(double start,double end,String text){this.start=start;this.end=end;this.text=text;}
        public JSONObject json(){return LibraryStore.obj("start",start,"end",end,"text",text,"original",original);}
    }
    public static List<Cue> fromWords(JSONObject response,double offset,double coreStart,double coreEnd) throws Exception {
        JSONArray words=response.optJSONArray("words");List<Cue> out=new ArrayList<>();
        if(words==null||words.length()==0){
            JSONArray segments=response.optJSONArray("segments");if(segments==null)throw new IllegalArgumentException("识别服务没有返回时间戳，请切换识别模型");
            for(int i=0;i<segments.length();i++){JSONObject s=segments.getJSONObject(i);double start=s.optDouble("start",-1)+offset,end=s.optDouble("end",-1)+offset;if(valid(start,end)&&end>coreStart&&start<coreEnd){String text=s.optString("text").trim();if(!text.isEmpty())out.add(new Cue(Math.max(coreStart,start),Math.min(coreEnd,end),text));}}
            return out;
        }
        StringBuilder text=new StringBuilder();double start=0,end=0;
        for(int i=0;i<words.length();i++){
            JSONObject word=words.getJSONObject(i);double from=word.optDouble("start",-1)+offset,to=word.optDouble("end",-1)+offset;
            String token=word.optString("word",word.optString("text")).trim();
            if(!Double.isFinite(from)||!Double.isFinite(to)||from<0||to<from||token.isEmpty()||(from+to)/2<coreStart||(from+to)/2>=coreEnd)continue;
            to=Math.max(from+.01,to);
            if(text.length()>0&&from-end>.65){out.add(new Cue(start,end,text.toString()));text.setLength(0);}
            if(text.length()==0)start=Math.max(coreStart,from);
            append(text,token);end=Math.min(coreEnd,to);
            boolean terminal=token.matches(".*[。！？!?]$")||(token.endsWith(".")&&text.length()>12);
            if((terminal&&text.length()>=4)||text.length()>=(compact(text)?42:110)||end-start>=8){out.add(new Cue(start,end,text.toString()));text.setLength(0);}
        }
        if(text.length()>0)out.add(new Cue(start,end,text.toString()));
        for(int i=1;i<out.size();i++){Cue last=out.get(i),previous=out.get(i-1);if(last.text.length()<(compact(last.text)?6:16)&&last.start-previous.end<.4&&!previous.text.matches(".*[。！？!?.]$")&&last.end-previous.start<10.5){StringBuilder joined=new StringBuilder(previous.text);append(joined,last.text);previous.text=joined.toString();previous.end=last.end;out.remove(i--);}}
        return out;
    }
    private static boolean compact(CharSequence text){for(int i=0;i<text.length();i++){Character.UnicodeScript script=Character.UnicodeScript.of(text.charAt(i));if(script==Character.UnicodeScript.HAN||script==Character.UnicodeScript.HIRAGANA||script==Character.UnicodeScript.KATAKANA||script==Character.UnicodeScript.HANGUL)return true;}return false;}
    private static boolean noWordSpace(char value){Character.UnicodeScript script=Character.UnicodeScript.of(value);return script==Character.UnicodeScript.HAN||script==Character.UnicodeScript.HIRAGANA||script==Character.UnicodeScript.KATAKANA||script==Character.UnicodeScript.THAI||script==Character.UnicodeScript.LAO||script==Character.UnicodeScript.MYANMAR;}
    private static void append(StringBuilder text,String token){if(text.length()>0&&!token.isEmpty()){char first=token.charAt(0),last=text.charAt(text.length()-1);if(Character.isLetterOrDigit(first)&&!noWordSpace(first)&&((Character.isLetterOrDigit(last)&&!noWordSpace(last))||",;:!?.".indexOf(last)>=0))text.append(' ');}text.append(token);}
    public static List<Cue> parse(String content,String format){
        String text=content.replace("\uFEFF","").replace("\r","");List<Cue> cues=new ArrayList<>();format=format.toLowerCase(Locale.ROOT);
        if(format.equals("lrc")){
            Matcher offsetMatcher=Pattern.compile("\\[offset:([+-]?\\d+)\\]",Pattern.CASE_INSENSITIVE).matcher(text);double offset=offsetMatcher.find()?Double.parseDouble(offsetMatcher.group(1))/1000:0;
            Pattern time=Pattern.compile("\\[(\\d+:\\d+(?:[.,]\\d+)?)\\]");
            for(String line:text.split("\n")){Matcher m=time.matcher(line);String body=plain(line.replaceAll("\\[[^]]*\\]","").replaceAll("<\\d+:\\d+(?:\\.\\d+)?>","").trim());while(m.find()){double at=Math.max(0,timestamp(m.group(1))-offset);cues.add(new Cue(at,at+20,body));}}
            cues.sort(Comparator.comparingDouble(c->c.start));List<Cue> merged=new ArrayList<>();for(Cue c:cues){if(!merged.isEmpty()&&merged.get(merged.size()-1).start==c.start)merged.get(merged.size()-1).text+="\n"+c.text;else merged.add(c);}cues=merged;for(int i=0;i+1<cues.size();i++)cues.get(i).end=cues.get(i+1).start;
        }else if(format.equals("ass")||format.equals("ssa")){
            String[] columns={"Layer","Start","End","Style","Name","MarginL","MarginR","MarginV","Effect","Text"};boolean events=false;
            for(String line:text.split("\n")){line=line.trim();if(line.startsWith("[")){events=line.equalsIgnoreCase("[Events]");continue;}if(events&&line.startsWith("Format:"))columns=line.substring(7).trim().split("\\s*,\\s*");
                if(line.startsWith("Dialogue:")){String[] fields=line.substring(line.indexOf(':')+1).split(",",columns.length);int start=index(columns,"Start"),end=index(columns,"End"),body=index(columns,"Text");if(fields.length==columns.length&&start>=0&&end>=0&&body>=0)cues.add(new Cue(timestamp(fields[start]),timestamp(fields[end]),plain(fields[body])));}}
        }else if(format.equals("sbv")){
            for(String block:text.split("\n\\s*\n")){String[] lines=block.split("\n");if(lines.length<2)continue;String[] time=lines[0].split(",");if(time.length==2)cues.add(new Cue(timestamp(time[0]),timestamp(time[1]),plain(String.join("\n",Arrays.copyOfRange(lines,1,lines.length)))));}
        }else if(format.equals("smi")||format.equals("sami")){
            Matcher sync=Pattern.compile("(?is)<sync\\b[^>]*?start\\s*=\\s*[\"']?(\\d+)[^>]*>(.*?)(?=<sync\\b|</body|</sami|$)").matcher(text);
            while(sync.find()){double start=Double.parseDouble(sync.group(1))/1000;String body=plain(sync.group(2).replaceAll("(?i)<br\\s*/?>","\n"));if(!cues.isEmpty())cues.get(cues.size()-1).end=start;if(!body.isEmpty())cues.add(new Cue(start,start+20,body));}
        }else if(format.equals("ttml")||format.equals("dfxp")){
            if(Pattern.compile("(?i)<!\\s*(DOCTYPE|ENTITY)").matcher(text).find())return cues;
            try{javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
                for(String feature:new String[]{"http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities"})try{factory.setFeature(feature,false);}catch(javax.xml.parsers.ParserConfigurationException ignored){}
                org.w3c.dom.Document document=factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(text)));org.w3c.dom.NodeList paragraphs=document.getElementsByTagNameNS("*","p");
                for(int i=0;i<paragraphs.getLength();i++){org.w3c.dom.Element p=(org.w3c.dom.Element)paragraphs.item(i);double start=xmlTime(p.getAttribute("begin")),end=p.hasAttribute("end")?xmlTime(p.getAttribute("end")):start+xmlTime(p.getAttribute("dur"));cues.add(new Cue(start,end,p.getTextContent().trim()));}
            }catch(Exception ignored){}
        }else{
            Pattern timing=Pattern.compile("([\\d:.,]+)\\s*-->\\s*([\\d:.,]+)");
            for(String block:text.split("\n\\s*\n")){String[] lines=block.split("\n");for(int i=0;i<lines.length;i++){Matcher m=timing.matcher(lines[i]);if(m.find()){cues.add(new Cue(timestamp(m.group(1)),timestamp(m.group(2)),plain(String.join("\n",Arrays.copyOfRange(lines,i+1,lines.length)))));break;}}}
        }
        cues.removeIf(c->!valid(c.start,c.end)||c.text.trim().isEmpty());cues.sort(Comparator.comparingDouble(c->c.start));return cues;
    }
    public static String vtt(List<Cue> cues,boolean bilingual){
        StringBuilder out=new StringBuilder("WEBVTT\n\nNOTE MIMI\n\n");int id=0;
        for(Cue c:cues){if(!valid(c.start,c.end)||c.text.trim().isEmpty())continue;out.append(++id).append('\n').append(stamp(c.start)).append(" --> ").append(stamp(c.end)).append('\n').append(escape(c.text));if(bilingual&&!c.original.isEmpty()&&!c.original.equals(c.text))out.append('\n').append(escape(c.original));out.append("\n\n");}return out.toString();
    }
    public static String lrc(List<Cue> cues,boolean bilingual){StringBuilder out=new StringBuilder("[re:MIMI]\n");for(int i=0;i<cues.size();i++){Cue c=cues.get(i);if(!valid(c.start,c.end)||c.text.trim().isEmpty())continue;out.append(lrcStamp(c.start)).append(c.text.replace('\n',' '));if(bilingual&&!c.original.isEmpty()&&!c.original.equals(c.text))out.append(" / ").append(c.original.replace('\n',' '));out.append('\n');if(i+1==cues.size()||cues.get(i+1).start-c.end>.5)out.append(lrcStamp(c.end)).append('\n');}return out.toString();}
    private static String lrcStamp(double time){long cs=Math.max(0,Math.round(time*100));return String.format(Locale.ROOT,"[%02d:%02d.%02d]",cs/6000,(cs/100)%60,cs%100);}
    public static JSONArray json(List<Cue> cues){JSONArray out=new JSONArray();for(Cue c:cues)out.put(c.json());return out;}
    public static List<Cue> fromJson(JSONArray array){List<Cue> out=new ArrayList<>();if(array==null)return out;for(int i=0;i<array.length();i++){JSONObject c=array.optJSONObject(i);if(c==null)continue;Cue cue=new Cue(c.optDouble("start",-1),c.optDouble("end",-1),c.optString("text"));cue.original=c.optString("original");if(valid(cue.start,cue.end))out.add(cue);}return out;}
    public static String stamp(double time){long ms=Math.max(0,Math.round(time*1000));return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000);}
    private static double timestamp(String value){try{double total=0;for(String s:value.trim().replace(',','.').split(":"))total=total*60+Double.parseDouble(s);return total;}catch(Exception e){return Double.NaN;}}
    private static boolean valid(double start,double end){return Double.isFinite(start)&&Double.isFinite(end)&&start>=0&&end>start;}
    private static int index(String[] values,String key){for(int i=0;i<values.length;i++)if(values[i].equalsIgnoreCase(key))return i;return -1;}
    private static double xmlTime(String value){try{if(value.endsWith("ms"))return Double.parseDouble(value.substring(0,value.length()-2))/1000;if(value.endsWith("s"))return Double.parseDouble(value.substring(0,value.length()-1));if(value.endsWith("m"))return Double.parseDouble(value.substring(0,value.length()-1))*60;if(value.endsWith("h"))return Double.parseDouble(value.substring(0,value.length()-1))*3600;return timestamp(value);}catch(Exception e){return Double.NaN;}}
    private static String plain(String text){return text.replaceAll("<[^>]*>","").replaceAll("\\{[^}]*\\}","").replaceAll("(?i)\\\\N","\n").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&nbsp;"," ").trim();}
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replaceAll("\n\\s*\n","\n");}
    private SubtitleCodec(){}
}
