package app.mimi.player;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

public final class LibraryStore {
    private static LibraryStore instance;
    private final Context context;
    private final AtomicFile file;
    private JSONObject data;
    private long contentRevision=0;
    private final Object projectChanges = new Object();
    Object projectChanges() { return projectChanges; }
    public synchronized long contentRevision(){return contentRevision;}
    public static synchronized LibraryStore get(Context context) {
        if (instance == null) instance = new LibraryStore(context.getApplicationContext());
        return instance;
    }
    private LibraryStore(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getFilesDir(), "library.json"));
        try { data = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)); }
        catch (Exception e) { data = new JSONObject(); put(data,"projects",new JSONArray()); put(data,"theme","light"); put(data,"repeat",0); }
        if(data.optInt("captionScaleVersion",0)<2){
            int previous=data.optInt("captionSize",24);
            put(data,"captionSize",previous>=32?24:previous>=28?22:20);
            put(data,"captionScaleVersion",2);
            save();
        }
    }
    static JSONObject obj(Object... values) {
        JSONObject result=new JSONObject(); for(int i=0;i<values.length;i+=2)put(result,values[i].toString(),values[i+1]);return result;
    }
    static void put(JSONObject object,String key,Object value) { try { object.put(key,value); } catch(JSONException e) { throw new IllegalArgumentException(e); } }
    static String id(String text) {
        return idBytes(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String idBytes(byte[] value) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder s=new StringBuilder();for(int i=0;i<12;i++)s.append(String.format(Locale.ROOT,"%02x",bytes[i]));return s.toString(); }
        catch(Exception e){ throw new IllegalStateException(e); }
    }
    public synchronized JSONObject snapshot() { try{return new JSONObject(data.toString());}catch(Exception e){throw new IllegalStateException(e);} }
    public synchronized JSONObject snapshot(boolean hidden) {
        JSONArray visible = new JSONArray();
        for (int i = 0; i < projects().length(); i++) {
            JSONObject project = projects().optJSONObject(i);
            if (isHidden(project.optString("id")) == hidden) visible.put(project);
        }
        JSONObject scoped = obj("projects", visible);
        for (String key : new String[]{"theme", "repeat", "speed", "sleepUntil", "captionSize", "clickSounds"}) {
            if (data.has(key)) put(scoped, key, data.opt(key));
        }
        JSONObject recent = hidden ? data.optJSONObject("hiddenLast") : last();
        if (recent != null && isHidden(recent.optString("projectId")) == hidden) put(scoped, "last", recent);
        try { return new JSONObject(scoped.toString()); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized boolean isHidden(String projectId) {
        JSONObject history = data.optJSONObject("hiddenProjects");
        return history != null && history.has(projectId);
    }

    synchronized boolean ownsCover(String projectId, String name) {
        JSONObject work = project(projectId);
        return work != null && work.optString("cover").equals("https://appassets.androidplatform.net/covers/" + name);
    }

    void setHidden(String projectId, boolean hidden) throws Exception {
        synchronized (projectChanges) {
            synchronized (this) {
                JSONObject work = project(projectId);
                if (work == null) throw new IOException(HiddenAccess.UNAVAILABLE);
                if (isHidden(projectId) == hidden) return;
                JSONObject previous = new JSONObject(data.toString());
                String signature = trackSignature(work);
                Set<String> sources = sourceKeys(work);
                for (int i = 0; i < projects().length(); i++) {
                    JSONObject candidate = projects().getJSONObject(i);
                    if (candidate == work || (!signature.isEmpty() && signature.equals(trackSignature(candidate)))
                            || (!sources.isEmpty() && sources.equals(sourceKeys(candidate)))) {
                        recordVisibility(candidate, hidden);
                    }
                }
                updateLastPlayed();
                try { save(); }
                catch (RuntimeException failure) { data = previous; throw failure; }
                contentRevision++;
            }
        }
    }

    private void recordVisibility(JSONObject work, boolean hidden) {
        JSONObject history = data.optJSONObject("hiddenProjects");
        if (history == null) {
            history = new JSONObject();
            put(data, "hiddenProjects", history);
        }
        String projectId = work.optString("id");
        if (!hidden) {
            history.remove(projectId);
            return;
        }
        JSONObject prior = history.optJSONObject(projectId);
        put(history, projectId, obj("tracks", recordKeys(trackIds(work), prior, "tracks"),
                "sources", recordKeys(sourceKeys(work), prior, "sources")));
    }

    private static JSONArray recordKeys(Set<String> current, JSONObject prior, String field) {
        JSONArray previous = prior == null ? null : prior.optJSONArray(field);
        for (int i = 0; previous != null && i < previous.length(); i++) current.add(previous.optString(i));
        JSONArray keys = new JSONArray();
        for (String key : current) keys.put(key);
        return keys;
    }

    private static Set<String> trackIds(JSONObject work) {
        Set<String> ids = new TreeSet<>();
        JSONArray tracks = work.optJSONArray("tracks");
        for (int i = 0; tracks != null && i < tracks.length(); i++) ids.add(tracks.optJSONObject(i).optString("id"));
        return ids;
    }

    private static Set<String> sourceKeys(JSONObject work) {
        Set<String> sources = new HashSet<>();
        JSONArray tracks = work.optJSONArray("tracks");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            sources.add(sourceKey(Uri.parse(track.optString("originalUri", track.optString("uri")))));
        }
        sources.remove("");
        return sources;
    }

    static String sourceKey(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) return "file:" + new File(uri.getPath()).getCanonicalPath();
            if ("content".equals(uri.getScheme())) return "document:" + uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException | IOException ignored) {
            // Other content providers may have no SAF document ID.
        }
        return uri.toString();
    }

    private static String trackSignature(JSONObject work) {
        return String.join(":", trackIds(work));
    }

    private String privateImportMatch(JSONObject incoming, boolean exact) {
        JSONObject history = data.optJSONObject("hiddenProjects");
        if (history == null || history.length() == 0) return "";
        Set<String> tracks = trackIds(incoming), sources = sourceKeys(incoming);
        Iterator<String> keys = history.keys();
        while (keys.hasNext()) {
            String candidate = keys.next();
            JSONObject recorded = history.optJSONObject(candidate);
            if (recorded != null && (matchesKeys(tracks, recorded.optJSONArray("tracks"), exact)
                    || matchesKeys(sources, recorded.optJSONArray("sources"), exact))) return candidate;
        }
        return "";
    }

    private static boolean matchesKeys(Set<String> keys, JSONArray recorded, boolean exact) {
        if (recorded == null || (exact && recorded.length() != keys.size())) return false;
        int matches = 0;
        for (int i = 0; i < recorded.length(); i++) if (keys.contains(recorded.optString(i))) matches++;
        return matches > 0 && (!exact || matches == keys.size());
    }

    private void updateLastPlayed() {
        String[] keys = {"last", "hiddenLast"};
        long[] times = {-1, -1};
        for (String key : keys) data.remove(key);
        for (int i = 0; i < projects().length(); i++) {
            JSONObject work = projects().optJSONObject(i), resume = work.optJSONObject("resume");
            int scope = isHidden(work.optString("id")) ? 1 : 0;
            long time = work.optLong("lastPlayed");
            if (resume != null && time > times[scope]) {
                put(data, keys[scope], resume);
                times[scope] = time;
            }
        }
    }

    public synchronized JSONArray projects(){return data.optJSONArray("projects");}
    public synchronized JSONObject project(String id) { for(int i=0;i<projects().length();i++)if(projects().optJSONObject(i).optString("id").equals(id))return projects().optJSONObject(i);return null; }
    public synchronized JSONObject track(String pid,String tid) {
        JSONObject p=project(pid);if(p==null)return null;JSONArray tracks=p.optJSONArray("tracks");for(int i=0;i<tracks.length();i++)if(tracks.optJSONObject(i).optString("id").equals(tid))return tracks.optJSONObject(i);return null;
    }
    public synchronized void save() {
        FileOutputStream out=null;
        try {out=file.startWrite();out.write(data.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw new IllegalStateException("无法保存资料库",e);}
    }
    public synchronized void preference(String key,Object value) { put(data,key,value);save(); }
    public synchronized int repeat(){return data.optInt("repeat",0);}
    public synchronized JSONObject last(){
        JSONObject last = data.optJSONObject("last");
        return last != null && !isHidden(last.optString("projectId")) ? last : null;
    }
    synchronized boolean clickSounds() { return data.optBoolean("clickSounds", true); }
    synchronized String theme() { return data.optString("theme", "light"); }
    synchronized double speed() { return data.optDouble("speed", 1); }
    synchronized long sleepUntil() { return data.optLong("sleepUntil"); }
    public synchronized void remember(String pid,String tid,long position) {
        JSONObject p=project(pid);if(p==null||track(pid,tid)==null)return;
        JSONObject resume=obj("projectId",pid,"trackId",tid,"position",Math.max(0,position));
        put(p,"resume",resume);put(p,"lastPlayed",System.currentTimeMillis());put(data,isHidden(pid)?"hiddenLast":"last",resume);save();
    }
    public synchronized void favorite(String pid) {JSONObject p=project(pid);if(p==null)return;put(p,"favorite",!p.optBoolean("favorite"));save();}
    public synchronized void remove(String pid) {
        JSONArray list=projects();for(int i=list.length()-1;i>=0;i--)if(list.optJSONObject(i).optString("id").equals(pid))list.remove(i);
        JSONArray marks=data.optJSONArray("bookmarks");for(int i=marks==null?-1:marks.length()-1;i>=0;i--)if(marks.optJSONObject(i).optString("projectId").equals(pid))marks.remove(i);
        updateLastPlayed();save();contentRevision++;
    }
    public synchronized void subtitlePref(String pid,String tid,String selected,double delay) {
        JSONObject t=track(pid,tid);if(t==null)return;put(t,"selectedSub",selected);put(t,"delay",delay);save();
    }
    private static class Entry {String name,relative,mime;Uri uri,parent;long size,modified;Entry(String n,String r,String m,Uri u,Uri p,long s){name=n;relative=r;mime=m;uri=u;parent=p;size=s;} }
    private void walk(Uri tree,String document,String prefix,List<Entry> out,int depth) throws Exception {
        if(depth>40||out.size()>20000)throw new IOException("文件夹层级或文件数量过多");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,document);
        try(Cursor c=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)){
            if(c==null)throw new IOException("无法读取文件夹，请重新导入");
            while(c.moveToNext()){
                String key=c.getString(0),name=c.getString(1),mime=c.getString(2);if(name.startsWith("."))continue;
                String rel=prefix+name;Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,key);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))walk(tree,key,rel+"/",out,depth+1);
                else {Entry entry=new Entry(name,rel,mime,uri,DocumentsContract.buildDocumentUriUsingTree(tree,document),c.getLong(3));entry.modified=c.isNull(4)?0:c.getLong(4);out.add(entry);}
            }
        }
    }
    static boolean isAudio(String name){return MediaFormats.audio(name);}
    static boolean isSub(String name){return MediaFormats.subtitle(name);}
    static String stem(String name){int n=name.lastIndexOf('.');return (n>=0?name.substring(0,n):name).toLowerCase(Locale.ROOT);}
    static byte[] readBytes(InputStream input,int limit) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer,0,Math.min(buffer.length,limit-out.size())))>0)out.write(buffer,0,n);return out.toByteArray();}
    static int natural(String a,String b) {
        java.util.regex.Matcher ma=java.util.regex.Pattern.compile("\\d+|\\D+").matcher(a.toLowerCase(Locale.ROOT)),mb=java.util.regex.Pattern.compile("\\d+|\\D+").matcher(b.toLowerCase(Locale.ROOT));
        while(ma.find()&&mb.find()){String x=ma.group(),y=mb.group();int c;if(Character.isDigit(x.charAt(0))&&Character.isDigit(y.charAt(0)))c=new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));else c=x.compareTo(y);if(c!=0)return c;}return a.length()-b.length();
    }
    private String fileIdentity(Entry e) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(Long.toString(e.size).getBytes(StandardCharsets.UTF_8));
        try(InputStream in=context.getContentResolver().openInputStream(e.uri)){if(in==null)throw new IOException();byte[] b=new byte[65536];int total=0,n;while(total<b.length&&(n=in.read(b,total,b.length-total))>0)total+=n;digest.update(b,0,total);}
        byte[] bytes=digest.digest();StringBuilder out=new StringBuilder();for(int i=0;i<12;i++)out.append(String.format(Locale.ROOT,"%02x",bytes[i]));return out.toString();
    }
    private String cover(byte[] bytes) {
        if(bytes==null||bytes.length>20*1024*1024)return "";
        try {String key = idBytes(bytes);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/640);options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)return "";
            File dir=new File(context.getFilesDir(),"covers");dir.mkdirs();File target=new File(dir,key+".webp");try(FileOutputStream out=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.WEBP,85,out);}bitmap.recycle();return "https://appassets.androidplatform.net/covers/"+key+".webp";
        }catch(Exception e){return "";}
    }
    public int importTree(Uri tree,String mode) throws Exception {
        if("file".equals(tree.getScheme())){
            File root=new File(tree.getPath());if(!root.getCanonicalPath().startsWith(new File(context.getFilesDir(),"imports").getCanonicalPath()+File.separator))throw new IOException("作品目录无效");JSONObject manifest=SubtitleJobs.readJson(new File(root,".mimi-import.json"));return importLocal(root,manifest==null?root.getName():manifest.optString("name",root.getName()).replaceFirst("\\.[^.]+$",""),mode);
        }
        List<Entry> files=new ArrayList<>();String rootId=DocumentsContract.getTreeDocumentId(tree);
        String rootName="作品";
        try(Cursor c=context.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree,rootId),new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())rootName=c.getString(0);}
        walk(tree,rootId,"",files,0);files.sort((a,b)->natural(a.relative,b.relative));
        return importEntries(tree,rootName,mode,files);
    }
    public int importLocal(File root,String name,String mode) throws Exception {List<Entry> files=new ArrayList<>();walkLocal(root,root,files,0);files.sort((a,b)->natural(a.relative,b.relative));return importEntries(Uri.fromFile(root),name,mode,files);}
    private void walkLocal(File root,File folder,List<Entry> out,int depth) throws Exception {
        if(depth>40||out.size()>20000)throw new IOException("作品文件数量或层级过多");File[] files=folder.listFiles();if(files==null)throw new IOException("无法读取作品目录");for(File f:files){if(f.getName().startsWith(".")||java.nio.file.Files.isSymbolicLink(f.toPath()))continue;if(f.isDirectory())walkLocal(root,f,out,depth+1);else {Entry entry=new Entry(f.getName(),root.toPath().relativize(f.toPath()).toString().replace('\\','/'),"",Uri.fromFile(f),Uri.fromFile(folder),f.length());entry.modified=f.lastModified();out.add(entry);}}
    }
    public int importSingle(Uri uri) throws Exception {
        String name=ArchiveImporter.displayName(context,uri);if(MediaFormats.splitArchive(name))throw new IOException("这是分卷作品包，请先在文件管理器中合并解压，再导入文件夹");if(MediaFormats.partial(name))throw new IOException("文件尚未下载完成，请完成下载后再导入");if(!isAudio(name))throw new IOException("请选择音频、作品文件夹或 ZIP / 7z / RAR 作品包");long size=0;try(Cursor c=context.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst())size=c.getLong(0);}return importEntries(uri,name.replaceFirst("\\.[^.]+$",""),"single",Arrays.asList(new Entry(name,name,"",uri,Uri.EMPTY,size)));
    }
    private int importEntries(Uri tree,String rootName,String mode,List<Entry> files) throws Exception {
        String archiveSource="";if("file".equals(tree.getScheme())){JSONObject manifest=SubtitleJobs.readJson(new File(tree.getPath(),".mimi-import.json"));if(manifest!=null)archiveSource=manifest.optString("source");}String identity=archiveSource.isEmpty()?tree.toString():"archive:"+archiveSource;
        Map<String,List<Entry>> groups=new LinkedHashMap<>();
        for(Entry e:files)if(isAudio(e.name)||e.mime.startsWith("audio/")){
            String group="";
            if("library".equals(mode)){
                String[] parts=e.relative.split("/");int last=parts.length-1;
                for(int i=0;i<last;i++)if(parts[i].matches("(?i).*(RJ|BJ|VJ)\\d{5,}.*")){last=i+1;break;}
                while(last>0&&parts[last-1].matches("(?i)(mp3|flac|wav|音声|音频|voice|audio|本編|特典|おまけ|disc\\s*\\d+|cd\\s*\\d+)"))last--;
                group=String.join("/",Arrays.copyOf(parts,last));
            }
            groups.computeIfAbsent(group,k->new ArrayList<>()).add(e);
        }
        JSONArray issues=new JSONArray();for(Entry e:files){if(MediaFormats.partial(e.name))issues.put(obj("name",e.relative,"reason","下载未完成"));else if(MediaFormats.splitArchive(e.name))issues.put(obj("name",e.relative,"reason","分卷作品包，请先合并解压"));else if(MediaFormats.archive(e.name))issues.put(obj("name",e.relative,"reason","请使用作品包导入"));}
        if(groups.isEmpty())throw new IOException(issues.length()>0?"没有可播放的音频；此目录包含未完成文件或作品包，请完成下载或使用作品包导入":"此文件夹没有可播放的音频");
        List<JSONObject> imported=new ArrayList<>();
        for(Map.Entry<String,List<Entry>> group:groups.entrySet()){
            String name=group.getKey().isEmpty()?rootName:group.getKey().substring(group.getKey().lastIndexOf('/')+1);
            JSONArray tracks=new JSONArray();String artwork="",album="",artist="";
            for(Entry e:group.getValue()){
                String tid=fileIdentity(e),title=e.name.replaceFirst("\\.[^.]+$","");long duration=0;
                MediaMetadataRetriever meta=new MediaMetadataRetriever();
                try{
                    meta.setDataSource(context,e.uri);
                    String read=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);if(read!=null&&!read.trim().isEmpty())title=read;
                    read=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(read!=null)duration=Long.parseLong(read);
                    if(album.isEmpty()){read=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);if(read!=null)album=read;}
                    if(artist.isEmpty()){read=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);if(read!=null)artist=read;}
                    if(artwork.isEmpty())artwork=cover(meta.getEmbeddedPicture());
                }catch(Exception ignored){}finally{try{meta.release();}catch(Exception ignored){}}
                JSONObject audioInfo=new JSONObject();String mediaError="";
                try{audioInfo=MediaTools.probe(context,e.uri);if(duration<=0)duration=audioInfo.optLong("duration");if(title.equals(e.name.replaceFirst("\\.[^.]+$",""))&&!audioInfo.optString("title").isEmpty())title=audioInfo.optString("title");if(album.isEmpty())album=audioInfo.optString("album");if(artist.isEmpty())artist=audioInfo.optString("artist");}catch(Exception ex){if(duration<=0)mediaError=ex.getMessage();}
                JSONArray subs=new JSONArray();String key=stem(e.name),exportKey=SubtitleNames.stem(e.name,0).toLowerCase(Locale.ROOT);
                String projectPrefix=group.getKey().isEmpty()?"":group.getKey()+"/";
                List<Entry> matching=new ArrayList<>();for(Entry s:files){boolean inProject=s.relative.startsWith(projectPrefix)&&(!mode.equals("library")||!projectPrefix.isEmpty()||!s.relative.contains("/")||s.relative.matches("(?i)(subtitles?|subs?|字幕)/.*"));if(inProject&&isSub(s.name)&&(stem(s.name).equals(key)||stem(s.name).startsWith(key+".")||stem(s.name).startsWith(key+"[")||stem(s.name).startsWith(exportKey+".mimi.")||stem(s.name).startsWith(exportKey+".cue")))matching.add(s);}
                final String parent=e.relative.substring(0,e.relative.lastIndexOf('/')+1);
                matching.sort(Comparator.comparingInt(s->(s.relative.startsWith(parent)?0:10)+(s.name.contains(".mimi.zh")?0:s.name.toLowerCase(Locale.ROOT).endsWith(".vtt")?1:2)));
                for(Entry s:matching)subs.put(obj("id",id(s.uri.toString()),"name",s.name,"uri",s.uri.toString(),"format",s.name.substring(s.name.lastIndexOf('.')+1),"language",s.name.matches("(?i).*(?:\\.zh(?:[.-]|$)|中文|中字|简中|繁中|\\.chs\\.).*")?"zh":s.name.contains(".mimi.bilingual")?"bilingual":s.name.contains(".mimi.original")?"original":""));
                String folder=e.relative.substring(0,e.relative.lastIndexOf('/')+1);if(!group.getKey().isEmpty()&&folder.startsWith(group.getKey()+"/"))folder=folder.substring(group.getKey().length()+1);
                boolean duplicate=false;for(int n=0;n<tracks.length();n++)if(tracks.optJSONObject(n).optString("id").equals(tid))duplicate=true;if(duplicate)tid=id(tid+"#"+e.relative);
                tracks.put(obj("id",tid,"name",e.name,"title",title,"uri",e.uri.toString(),"parentUri",e.parent.toString(),"relative",e.relative,"size",e.size,"modified",e.modified,"folder",folder,"duration",duration,"subtitles",subs,"audioInfo",audioInfo,"mediaError",mediaError,"format",e.name.substring(e.name.lastIndexOf('.')+1).toUpperCase(Locale.ROOT)));
            }
            tracks=expandCueTracks(tracks,files,issues);
            for(int n=0;n<tracks.length();n++){JSONObject item=tracks.optJSONObject(n);if(!item.optString("mediaError").isEmpty())addIssue(issues,item.optString("relative"),item.optString("mediaError"));}
            if(!album.isEmpty())name=album;
            String pid=id(identity+"#"+group.getKey());
            if(artwork.isEmpty())for(Entry image:files)if(MediaFormats.image(image.name)&&(group.getKey().isEmpty()?(!mode.equals("library")||!image.relative.contains("/")):image.relative.startsWith(group.getKey()+"/"))){try(InputStream in=context.getContentResolver().openInputStream(image.uri)){if(image.size<15*1024*1024&&in!=null)artwork=cover(readBytes(in,15*1024*1024));}if(!artwork.isEmpty())break;}
            String category=(name+artist+rootName+group.getKey()).matches(".*(碧蓝航线|アズールレーン).*")?"碧蓝航线":"";
            if((name+artist+group.getKey()).contains("碧蓝航线"))category="碧蓝航线";
            if((tree+group.getKey()+name).matches(".*(東方|东方|こいし).*"))category="东方 Project";
            JSONArray groupIssues=new JSONArray();for(int n=0;n<issues.length();n++){JSONObject issue=issues.optJSONObject(n);if(group.getKey().isEmpty()||issue.optString("name").startsWith(group.getKey()+"/"))groupIssues.put(issue);}
            imported.add(obj("id",pid,"name",name,"root",tree.toString(),"archiveUri",archiveSource,"group",group.getKey(),"importMode",mode,"cover",artwork,"artist",artist,"category",category,"tracks",tracks,"importIssues",groupIssues));
        }
        synchronized (projectChanges) {
            synchronized (this) {
                Set<String> incomingIds = new HashSet<>();
                int publicCount = 0;
                for (JSONObject work : imported) {
                    String incoming = work.optString("id");
                    incomingIds.add(incoming);
                    JSONObject prior = project(incoming);
                    boolean hidden = isHidden(incoming);
                    if (!hidden) {
                        String exact = privateImportMatch(work, true);
                        if (!exact.isEmpty()) {
                            hidden = true;
                            if (prior == null) {
                                prior = project(exact);
                                // Keep the existing source and metadata when another import finds the same work.
                                if (prior != null) continue;
                                put(work, "id", exact);
                                incomingIds.add(exact);
                            }
                        } else {
                            hidden = !privateImportMatch(work, false).isEmpty();
                        }
                    }
                    if (prior != null) mergeExisting(work, prior);
                    String projectId = work.optString("id");
                    for (int i = projects().length() - 1; i >= 0; i--) {
                        if (projects().optJSONObject(i).optString("id").equals(projectId)) projects().remove(i);
                    }
                    projects().put(work);
                    if (hidden) recordVisibility(work, true);
                    else publicCount++;
                }
                for (int i = projects().length() - 1; i >= 0; i--) {
                    JSONObject existing = projects().optJSONObject(i);
                    if (existing.optString("root").equals(tree.toString()) && existing.optString("importMode").equals(mode)
                            && !incomingIds.contains(existing.optString("id")) && !isHidden(existing.optString("id"))) projects().remove(i);
                }
                updateLastPlayed();
                save();
                contentRevision++;
                return publicCount;
            }
        }
    }
    public String readSubtitle(String pid,String tid,String subId) throws Exception {
        JSONObject t=track(pid,tid);if(t==null)throw new IOException("曲目不存在");JSONArray subs=t.optJSONArray("subtitles");JSONObject selected=null;
        for(int i=0;i<subs.length();i++)if(subs.optJSONObject(i).optString("id").equals(subId))selected=subs.optJSONObject(i);
        if(selected==null)throw new IOException("字幕不存在");
        byte[] bytes;try(InputStream in=context.getContentResolver().openInputStream(Uri.parse(selected.optString("uri")))){if(in==null)throw new IOException("无法读取字幕");bytes=readBytes(in,15*1024*1024+1);}
        if(bytes.length>15*1024*1024)throw new IOException("字幕文件超过 15 MB");
        String content=TextCodec.decode(bytes),format=selected.optString("format","vtt");
        boolean clip=t.has("clipStart")&&!selected.optBoolean("relative",false);
        List<SubtitleCodec.Cue> cues=SubtitleCodec.parse(content,format);
        if(clip){double start=t.optLong("clipStart")/1000.0,end=start+t.optLong("duration")/1000.0;List<SubtitleCodec.Cue> sliced=new ArrayList<>();for(SubtitleCodec.Cue c:cues)if(c.end>start&&c.start<end)sliced.add(new SubtitleCodec.Cue(Math.max(0,c.start-start),Math.min(end,c.end)-start,c.text));cues=sliced;}
        return SubtitleCodec.vtt(cues,false);
    }
    public JSONObject subtitlePayload(String pid,String tid,String subId) throws Exception {
        String content=readSubtitle(pid,tid,subId);JSONObject result=obj("content",content);JSONObject track=track(pid,tid);JSONArray subs=track.optJSONArray("subtitles");
        for(int i=0;subs!=null&&i<subs.length();i++){JSONObject sub=subs.getJSONObject(i);String job=sub.optString("jobId");if(sub.optString("id").equals(subId)&&sub.optString("language").equals("bilingual")&&sub.optBoolean("generated")&&job.matches("[a-f0-9]{24}")){JSONObject saved=SubtitleJobs.readJson(new File(context.getFilesDir(),"subtitle-jobs/"+job+"/result.json"));if(saved!=null)put(result,"cues",saved.optJSONArray("cues"));}}
        return result;
    }

    private static void addIssue(JSONArray issues,String name,String reason){for(int i=0;i<issues.length();i++)if(issues.optJSONObject(i).optString("name").equals(name)&&issues.optJSONObject(i).optString("reason").equals(reason))return;issues.put(obj("name",name,"reason",reason));}
    private JSONArray expandCueTracks(JSONArray originals,List<Entry> files,JSONArray issues) throws Exception {
        Map<String,List<JSONObject>> replacements=new HashMap<>();
        for(Entry entry:files){if(!MediaFormats.extension(entry.name).equals("cue"))continue;
            CueSheet sheet;try(InputStream in=context.getContentResolver().openInputStream(entry.uri)){if(in==null)continue;sheet=CueSheet.parse(TextCodec.decode(readBytes(in,2*1024*1024)));}catch(Exception ignored){continue;}
            for(CueSheet.Track cue:sheet.tracks){String relative=CueSheet.resolve(entry.relative,cue.file);if(relative==null){addIssue(issues,entry.relative,"CUE 引用了作品目录之外的文件");continue;}
                JSONObject source=null;for(int i=0;i<originals.length();i++)if(originals.getJSONObject(i).optString("relative").equalsIgnoreCase(relative)){source=originals.getJSONObject(i);break;}
                if(source==null){boolean found=false;for(Entry file:files)if(file.relative.equalsIgnoreCase(relative))found=true;if(!found)addIssue(issues,entry.relative,"CUE 找不到音频："+cue.file);continue;}String key=source.optString("id");List<JSONObject> list=replacements.computeIfAbsent(key,k->new ArrayList<>());
                // Alternate cue sheets must not duplicate the same album in the queue.
                if(!list.isEmpty()&&!list.get(0).optString("cueSource").equals(entry.relative))continue;
                long end=cue.endMs>=0?cue.endMs:source.optLong("duration");if(end<=cue.startMs)continue;
                JSONObject track=new JSONObject(source.toString());put(track,"id",id(key+"#"+entry.relative+"#"+cue.startMs));put(track,"title",cue.title);put(track,"name",String.format(Locale.ROOT,"%02d · %s",cue.number,cue.title));put(track,"clipStart",cue.startMs);put(track,"clipEnd",end);put(track,"duration",end-cue.startMs);put(track,"cueSource",entry.relative);put(track,"cueNumber",cue.number);put(track,"sourceName",source.optString("name"));
                JSONArray selectedSubs=new JSONArray();JSONArray sourceSubs=source.getJSONArray("subtitles");for(int n=0;n<sourceSubs.length();n++){JSONObject sub=new JSONObject(sourceSubs.getJSONObject(n).toString());java.util.regex.Matcher number=java.util.regex.Pattern.compile("(?i)\\.cue(\\d+)\\.mimi\\.").matcher(sub.optString("name"));if(number.find()){if(Integer.parseInt(number.group(1))!=cue.number)continue;put(sub,"relative",true);}selectedSubs.put(sub);}put(track,"subtitles",selectedSubs);
                list.add(track);
            }
        }
        JSONArray result=new JSONArray();for(int i=0;i<originals.length();i++){JSONObject t=originals.getJSONObject(i);List<JSONObject> replacement=replacements.get(t.optString("id"));if(replacement==null)result.put(t);else for(JSONObject part:replacement)result.put(part);}return result;
    }

    public synchronized void attachGenerated(String pid,String tid,JSONObject subtitle) throws Exception {
        attachGeneratedBatch(pid,tid,new JSONArray().put(subtitle),subtitle.optString("id"));
    }
    public synchronized void attachGeneratedBatch(String pid,String tid,JSONArray generated,String selected) throws Exception {
        JSONObject track=track(pid,tid);if(track==null)throw new IOException("曲目已从资料库移除");JSONArray subs=track.optJSONArray("subtitles");if(subs==null){subs=new JSONArray();put(track,"subtitles",subs);}
        for(int j=0;j<generated.length();j++){JSONObject subtitle=generated.getJSONObject(j);boolean exists=false;for(int i=0;i<subs.length();i++)if(subs.getJSONObject(i).optString("id").equals(subtitle.optString("id"))){subs.put(i,subtitle);exists=true;break;}if(!exists)subs.put(subtitle);}
        put(track,"selectedSub",selected);save();contentRevision++;
    }
    public synchronized void useConverted(String pid,String tid,File file,JSONObject info) throws Exception {
        JSONObject track=track(pid,tid);if(track==null)throw new IOException("曲目已移除");if(!track.has("originalUri")){put(track,"originalUri",track.optString("uri"));put(track,"originalAudioInfo",track.optJSONObject("audioInfo"));put(track,"sourceFormat",track.optString("format"));}
        put(track,"uri",Uri.fromFile(file).toString());put(track,"audioInfo",info);put(track,"format","FLAC");put(track,"mediaError","");put(track,"converted",true);save();contentRevision++;
    }
    static String audioFingerprint(JSONObject track){return id(track.optString("originalUri",track.optString("uri"))+"#"+track.optLong("size")+"#"+track.optLong("modified"));}
    static String sourceFingerprint(JSONObject track){return id(audioFingerprint(track)+"#"+track.optLong("clipStart")+"#"+track.optLong("clipEnd"));}
    private static boolean unchangedSource(JSONObject prior,JSONObject current){if(!prior.has("size")||!prior.has("modified"))return prior.optString("id").equals(current.optString("id"))&&prior.optString("originalUri",prior.optString("uri")).equals(current.optString("originalUri",current.optString("uri")));return sourceFingerprint(prior).equals(sourceFingerprint(current));}
    private static JSONObject findTrack(JSONObject project,String id){JSONArray tracks=project.optJSONArray("tracks");for(int i=0;tracks!=null&&i<tracks.length();i++)if(tracks.optJSONObject(i).optString("id").equals(id))return tracks.optJSONObject(i);return null;}
    private void mergeExisting(JSONObject incoming,JSONObject old) throws Exception {
        for(String key:new String[]{"favorite","resume","lastPlayed"})if(old.has(key))put(incoming,key,old.opt(key));
        JSONArray tracks=incoming.getJSONArray("tracks");
        for(int i=0;i<tracks.length();i++){
            JSONObject current=tracks.getJSONObject(i),prior=findTrack(old,current.optString("id"));if(prior==null)continue;
            put(current,"delay",prior.optDouble("delay",0));put(current,"selectedSub",prior.optString("selectedSub",""));
            if(prior.optBoolean("converted")&&unchangedSource(prior,current)&&new File(Uri.parse(prior.optString("uri")).getPath()).isFile())for(String field:new String[]{"uri","audioInfo","format","originalUri","originalAudioInfo","sourceFormat","converted"})put(current,field,prior.opt(field));
            JSONArray subs=current.getJSONArray("subtitles"),previous=prior.optJSONArray("subtitles");
            if(unchangedSource(prior,current))for(int j=0;previous!=null&&j<previous.length();j++){JSONObject sub=previous.getJSONObject(j);if(!sub.optBoolean("generated"))continue;boolean exists=false;for(int k=0;k<subs.length();k++)if(subs.getJSONObject(k).optString("id").equals(sub.optString("id")))exists=true;if(!exists)subs.put(sub);}
            String selected=current.optString("selectedSub");boolean valid=selected.isEmpty()||selected.equals("off");for(int j=0;j<subs.length();j++)if(subs.getJSONObject(j).optString("id").equals(selected))valid=true;if(!valid)put(current,"selectedSub","");
        }
        JSONObject resume=incoming.optJSONObject("resume");if(resume!=null&&findTrack(incoming,resume.optString("trackId"))==null){
            JSONObject previous=findTrack(old,resume.optString("trackId")),replacement=null;long position=0;
            if(previous!=null){String source=previous.optString("originalUri",previous.optString("uri"));long absolute=previous.optLong("clipStart")+resume.optLong("position");for(int i=0;i<tracks.length();i++){JSONObject candidate=tracks.getJSONObject(i);long start=candidate.optLong("clipStart"),end=candidate.optLong("clipEnd",candidate.optLong("duration"));if(candidate.optString("originalUri",candidate.optString("uri")).equals(source)&&absolute>=start&&absolute<end){replacement=candidate;position=absolute-start;break;}}}
            if(replacement==null&&tracks.length()>0)replacement=tracks.getJSONObject(0);
            if(replacement!=null){JSONObject mapped=obj("projectId",incoming.optString("id"),"trackId",replacement.optString("id"),"position",position);put(incoming,"resume",mapped);}else incoming.remove("resume");
        }
    }
    public synchronized void restoreOriginal(String pid,String tid) throws Exception {
        JSONObject track=track(pid,tid);if(track==null||!track.has("originalUri"))throw new IOException("当前使用的是原始音频");put(track,"uri",track.optString("originalUri"));put(track,"format",track.optString("sourceFormat"));put(track,"audioInfo",track.optJSONObject("originalAudioInfo"));put(track,"converted",false);save();contentRevision++;
    }
}
