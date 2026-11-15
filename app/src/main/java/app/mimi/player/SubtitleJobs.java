package app.mimi.player;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One durable queue; per-chunk ASR and per-batch translation checkpoints survive process death. */
public final class SubtitleJobs {
    public interface Observer {void changed(JSONObject snapshot);}
    private static SubtitleJobs instance;
    public static synchronized SubtitleJobs get(Context context){if(instance==null)instance=new SubtitleJobs(context.getApplicationContext());return instance;}
    private final Context context;
    private final AtomicFile file;
    private JSONArray jobs;
    private CancellationSignal cancellation;
    private String active="";
    private Observer observer;
    private Runnable notificationObserver;
    private long revision;
    public synchronized long revision(){return revision;}
    private SubtitleJobs(Context context){
        this.context=context;file=new AtomicFile(new File(context.getFilesDir(),"subtitle-jobs.json"));
        try{jobs=new JSONArray(new String(file.readFully(),StandardCharsets.UTF_8));}catch(Exception e){jobs=new JSONArray();}
        for(int i=0;i<jobs.length();i++){JSONObject job=jobs.optJSONObject(i);if(job!=null&&Arrays.asList("running","queued").contains(job.optString("status"))){put(job,"status","paused");put(job,"message","任务已暂停，点击继续");}}save();
    }
    private static void put(JSONObject object,String key,Object value){LibraryStore.put(object,key,value);}
    public synchronized void observe(Observer value){observer=value;}
    public synchronized void unobserve(Observer value){if(observer==value)observer=null;}
    synchronized void observeNotification(Runnable callback) { notificationObserver = callback; }
    synchronized void unobserveNotification(Runnable callback) {
        if (notificationObserver == callback) notificationObserver = null;
    }
    private synchronized void detail(JSONObject job,String key,Object value){put(job,key,value);}
    public synchronized JSONObject snapshot(){try{return LibraryStore.obj("jobs",new JSONArray(jobs.toString()),"active",active);}catch(Exception e){throw new IllegalStateException(e);}}
    synchronized JSONObject snapshot(boolean hidden) {
        LibraryStore store = LibraryStore.get(context);
        JSONArray visible = new JSONArray();
        String visibleActive = "";
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (store.isHidden(job.optString("projectId")) == hidden) {
                visible.put(job);
                if (active.equals(job.optString("id"))) visibleActive = active;
            }
        }
        try { return LibraryStore.obj("jobs", new JSONArray(visible.toString()), "active", visibleActive); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    synchronized JSONObject notificationSnapshot() {
        JSONObject running = find(active);
        if (running == null) return new JSONObject();
        if (LibraryStore.get(context).isHidden(running.optString("projectId"))) {
            return LibraryStore.obj("title", "字幕任务", "message", "处理中");
        }
        return LibraryStore.obj("title", running.optString("title"), "message", running.optString("message", "处理中"),
                "completed", running.optInt("completed"), "total", running.optInt("total"));
    }

    synchronized String jobProject(String id) {
        JSONObject job = find(id);
        return job == null ? "" : job.optString("projectId");
    }

    synchronized void visibilityChanged() {
        revision++;
        if (notificationObserver != null) notificationObserver.run();
    }

    private synchronized JSONObject find(String id){for(int i=0;i<jobs.length();i++)if(jobs.optJSONObject(i).optString("id").equals(id))return jobs.optJSONObject(i);return null;}
    private synchronized void save(){FileOutputStream out=null;try{out=file.startWrite();out.write(jobs.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw new IllegalStateException("无法保存字幕任务",e);}}
    private synchronized void changed(){revision++;save();if(observer!=null)observer.changed(snapshot());if(notificationObserver!=null)notificationObserver.run();}
    private synchronized void progress(JSONObject job,String stage,int completed,int total,String message){put(job,"stage",stage);put(job,"completed",completed);put(job,"total",total);put(job,"message",message);put(job,"updated",System.currentTimeMillis());changed();}
    public synchronized JSONObject enqueue(String pid,String tid,String mode) throws Exception {
        LibraryStore store=LibraryStore.get(context);JSONObject track=store.track(pid,tid),project=store.project(pid);if(track==null)throw new IOException("曲目不存在");
        AiSettings settings=new AiSettings(context);settings.key();JSONObject config=settings.publicConfig();config.remove("hasKey");
        String identity=LibraryStore.id(pid+":"+tid+":"+LibraryStore.sourceFingerprint(track)+":"+mode+":"+config.optString("sttModel")+":"+config.optString("translationModel")+":"+config.optString("language")+":"+config.optString("glossary"));
        JSONObject existing=find(identity);
        if(existing!=null){if("done".equals(existing.optString("status")))return LibraryStore.obj("id",identity,"reused",true);if(!"running".equals(existing.optString("status"))){put(existing,"status","queued");put(existing,"message","");put(existing,"config",config);changed();}return LibraryStore.obj("id",identity,"reused",false);}
        if(jobs.length()>=200)throw new IOException("任务记录已满，请移除已完成的记录");
        String exportStem=SubtitleNames.stem(track.optString("sourceName",track.optString("name",track.optString("title"))),track.optInt("cueNumber"));
        JSONObject job=LibraryStore.obj("id",identity,"projectId",pid,"trackId",tid,"title",track.optString("title"),"exportStem",exportStem,"projectName",project.optString("name"),"status","queued","stage","waiting","completed",0,"total",0,"cost",0,"created",System.currentTimeMillis(),"mode",mode,"config",config);
        jobs.put(job);changed();return LibraryStore.obj("id",identity,"reused",false);
    }
    public synchronized int enqueueProject(String pid) throws Exception {
        JSONObject project=LibraryStore.get(context).project(pid);if(project==null)throw new IOException("作品不存在");JSONArray tracks=project.getJSONArray("tracks");int count=0;
        for(int i=0;i<tracks.length();i++){JSONObject t=tracks.getJSONObject(i);boolean hasChinese=false;JSONArray subs=t.optJSONArray("subtitles");for(int j=0;subs!=null&&j<subs.length();j++){JSONObject sub=subs.getJSONObject(j);if("zh".equals(sub.optString("language"))||sub.optString("name").matches("(?i).*(\\.zh[.-]|中文|中字|简中|繁中).*"))hasChinese=true;}if(!hasChinese){enqueue(pid,t.optString("id"),"auto");count++;}}
        return count;
    }
    public synchronized void control(String id,String action) throws Exception {
        JSONObject job=find(id);if(job==null)throw new IOException("任务不存在");String status=job.optString("status");
        if(action.equals("resume")){if(status.equals("running"))return;JSONObject cfg=new AiSettings(context).publicConfig();cfg.remove("hasKey");put(job,"config",cfg);put(job,"status","queued");put(job,"message","");}
        else if(action.equals("pause")||action.equals("cancel")){put(job,"status",action.equals("pause")?"paused":"cancelled");put(job,"message","");if(id.equals(active)&&cancellation!=null)cancellation.cancel();}
        else if(action.equals("remove")){if(id.equals(active))throw new IOException("请先暂停任务");for(int i=jobs.length()-1;i>=0;i--)if(jobs.getJSONObject(i).optString("id").equals(id))jobs.remove(i);}
        else throw new IOException("操作无效");changed();
    }
    public synchronized void pauseAll(String message) { pauseAll(message, null); }

    synchronized void pauseAll(String message, Boolean hiddenScope) {
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (inScope(job, hiddenScope) && Arrays.asList("running", "queued").contains(job.optString("status"))) {
                put(job, "status", "paused");
                put(job, "message", message);
                if (active.equals(job.optString("id")) && cancellation != null) cancellation.cancel();
            }
        }
        changed();
    }

    public synchronized int resumeAll() throws Exception { return resumeAll(false); }

    synchronized int resumeAll(boolean hiddenScope) throws Exception {
        JSONObject config = null;
        int count = 0;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i);
            if (inScope(job, hiddenScope) && "paused".equals(job.optString("status"))) {
                if (config == null) {
                    AiSettings settings = new AiSettings(context);
                    settings.key();
                    config = settings.publicConfig();
                    config.remove("hasKey");
                }
                put(job, "status", "queued");
                put(job, "config", new JSONObject(config.toString()));
                put(job, "message", "");
                count++;
            }
        }
        if (count > 0) changed();
        return count;
    }

    private boolean inScope(JSONObject job, Boolean scope) {
        return scope == null || LibraryStore.get(context).isHidden(job.optString("projectId")) == scope;
    }

    public synchronized void cancelProject(String pid){for(int i=0;i<jobs.length();i++){JSONObject job=jobs.optJSONObject(i);if(job.optString("projectId").equals(pid)&&Arrays.asList("running","queued").contains(job.optString("status"))){put(job,"status","cancelled");put(job,"message","作品已移除");if(job.optString("id").equals(active)&&cancellation!=null)cancellation.cancel();}}changed();}
    public void runQueue(){runQueue(()->true);}
    public void runQueue(java.util.function.BooleanSupplier keepRunning){
        while(true){JSONObject job;
            synchronized(this){while(!active.isEmpty()&&keepRunning.getAsBoolean())try{wait(200);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}if(!keepRunning.getAsBoolean())return;job=null;for(int i=0;i<jobs.length();i++)if("queued".equals(jobs.optJSONObject(i).optString("status"))){job=jobs.optJSONObject(i);break;}if(job==null)return;active=job.optString("id");cancellation=new CancellationSignal();put(job,"status","running");changed();}
            try{process(job,cancellation);synchronized(this){if(!cancellation.isCanceled()){put(job,"status","done");put(job,"stage","done");put(job,"message","");}}}
            catch(OperationCanceledException e){synchronized(this){if("running".equals(job.optString("status")))put(job,"status","paused");}}
            catch(Exception e){synchronized(this){put(job,"status",e instanceof OpenRouterClient.ApiException&&((OpenRouterClient.ApiException)e).review?"needs_review":"failed");put(job,"message",e.getMessage()==null?"任务未完成，可继续重试":e.getMessage());}}
            finally{synchronized(this){active="";cancellation=null;changed();notifyAll();}}
        }
    }
    private void budget(JSONObject job) throws IOException {JSONObject config=job.optJSONObject("config");if(job.optDouble("cost")>=config.optDouble("maxCost",3))throw new IOException("已达到本任务费用暂停线，可在设置中调整后继续");}
    private void process(JSONObject job,CancellationSignal cancel) throws Exception {
        LibraryStore store=LibraryStore.get(context);String pid=job.getString("projectId"),tid=job.getString("trackId");JSONObject track=store.track(pid,tid);if(track==null)throw new IOException("曲目已从资料库移除");
        JSONObject config=job.getJSONObject("config");String stt=config.getString("sttModel"),translator=config.getString("translationModel"),language=config.optString("language","auto"),glossary=config.optString("glossary");
        File jobDir=jobDir(job);jobDir.mkdirs();
        OpenRouterClient client=new OpenRouterClient(new AiSettings(context).key(),cancel,fee->{synchronized(this){put(job,"cost",job.optDouble("cost")+fee);changed();}});
        List<SubtitleCodec.Cue> original=null;
        if(!"recognize".equals(job.optString("mode"))){JSONArray subs=track.optJSONArray("subtitles");JSONObject chosen=null;for(int i=0;subs!=null&&i<subs.length();i++){JSONObject sub=subs.getJSONObject(i);String sourceLanguage=sub.optString("language");if((sub.optBoolean("generated")&&!sourceLanguage.equals("original"))||sourceLanguage.equals("zh")||sourceLanguage.equals("bilingual"))continue;if(chosen==null||sub.optString("id").equals(track.optString("selectedSub")))chosen=sub;}
            if(chosen!=null){String content=store.readSubtitle(pid,tid,chosen.getString("id"));original=SubtitleCodec.parse(content,content.trim().startsWith("WEBVTT")?"vtt":chosen.optString("format"));if(original.isEmpty())original=null;else detail(job,"source","subtitle");}}
        if(original==null&&"translate".equals(job.optString("mode")))throw new IOException("现有字幕没有有效时间轴，请选择重新识别音频");
        if(original==null){
            detail(job,"source","audio");
            Uri uri=Uri.parse(track.getString("uri"));long duration=track.optLong("duration");if(duration<=0)duration=MediaTools.probe(context,uri).optLong("duration");if(duration<=0)throw new IOException("无法确定音频时长，请刷新作品");if(duration>24*3600000L)throw new IOException("单条音频超过 24 小时，请先分轨");
            int parts=(int)Math.ceil(duration/30000.0);long clipStart=track.optLong("clipStart");original=new ArrayList<>();
            String asrKey=LibraryStore.id("asr-v3#"+tid+"#"+LibraryStore.sourceFingerprint(track)+"#"+clipStart+"#"+duration+"#"+stt+"#"+language);File asrDir=new File(context.getFilesDir(),"subtitle-cache/"+asrKey);asrDir.mkdirs();
            for(int i=0;i<parts;i++){
                cancel.throwIfCanceled();long coreStart=i*30000L,coreEnd=Math.min(duration,(i+1)*30000L),start=Math.max(0,coreStart-1000),end=Math.min(duration,coreEnd+1000);File checkpoint=new File(asrDir,i+".json");JSONObject response=readJson(checkpoint);
                progress(job,"recognizing",i,parts,"识别 "+(i+1)+" / "+parts);
                if(response==null){budget(job);File wav=new File(context.getCacheDir(),"ai-chunks/"+job.getString("id")+".wav");
                    try{MediaTools.audioChunk(context,uri,clipStart+start,end-start,wav,cancel,null);
                        if(MediaTools.pcmRms(wav)<.00008)response=LibraryStore.obj("text","","words",new JSONArray(),"segments",new JSONArray(),"silent",true);
                        else response=client.transcribe(wav,stt,language);
                        cancel.throwIfCanceled();
                        if(!response.optBoolean("silent")){List<SubtitleCodec.Cue> validated=SubtitleCodec.fromWords(response,start/1000.0,coreStart/1000.0,coreEnd/1000.0);if(validated.isEmpty()&&!response.optString("text").trim().isEmpty())throw new IOException("识别结果没有有效时间轴，可重试或更换识别模型");}
                        writeJson(checkpoint,response);
                    }finally{if(wav.exists())wav.delete();}
                }else synchronized(this){put(job,"cachedChunks",job.optInt("cachedChunks")+1);}
                if(!response.optBoolean("silent")){List<SubtitleCodec.Cue> chunk=SubtitleCodec.fromWords(response,start/1000.0,coreStart/1000.0,coreEnd/1000.0);if(chunk.isEmpty()&&!response.optString("text").trim().isEmpty())throw new IOException("识别结果没有有效时间轴，请更换识别模型");original.addAll(chunk);}
                if(!response.optString("language").isEmpty())detail(job,"detectedLanguage",response.optString("language"));
            }
        }
        cancel.throwIfCanceled();if(original.isEmpty())throw new IOException("未识别到可用语音，可切换语言或识别模型后重试");
        normalize(original);writeJson(new File(jobDir,"original.json"),LibraryStore.obj("cues",SubtitleCodec.json(original)));List<SubtitleCodec.Cue> translated=new ArrayList<>();
        List<SubtitleCodec.Cue> draft=copyOriginal(original);writeJson(new File(jobDir,"draft.json"),LibraryStore.obj("cues",SubtitleCodec.json(draft)));
        int batches=(int)Math.ceil(original.size()/12.0);
        for(int begin=0;begin<original.size();begin+=12){
            cancel.throwIfCanceled();List<SubtitleCodec.Cue> batch=new ArrayList<>(original.subList(begin,Math.min(begin+12,original.size())));int n=begin/12;
            progress(job,"translating",n,batches,"翻译 "+(n+1)+" / "+batches);
            String previous=begin>0?original.get(begin-1).text:"";List<SubtitleCodec.Cue> output=translateGroup(job,client,batch,translator,glossary,previous,cancel);
            if(output.size()!=batch.size())throw new IOException("缓存译文不完整，请更换模型后继续");translated.addAll(output);for(int j=0;j<output.size();j++)draft.set(begin+j,output.get(j));writeJson(new File(jobDir,"draft.json"),LibraryStore.obj("cues",SubtitleCodec.json(draft)));
        }
        cancel.throwIfCanceled();finish(job,translated,false);
    }
    private List<SubtitleCodec.Cue> translateGroup(JSONObject job,OpenRouterClient client,List<SubtitleCodec.Cue> batch,String model,String glossary,String previous,CancellationSignal cancel) throws Exception {
        cancel.throwIfCanceled();String key=LibraryStore.id("translation-v3#"+model+"#"+glossary+"#"+previous+"#"+SubtitleCodec.json(batch));File checkpoint=new File(context.getFilesDir(),"subtitle-cache/translations/"+key+".json");JSONObject saved=readJson(checkpoint);
        if(saved!=null&&saved.has("cues")){List<SubtitleCodec.Cue> cached=SubtitleCodec.fromJson(saved.optJSONArray("cues"));if(cached.size()==batch.size())return cached;}
        boolean split=saved!=null&&saved.optBoolean("split");List<SubtitleCodec.Cue> output=null;
        if(!split){budget(job);try{output=client.translate(batch,model,glossary,previous);}catch(OpenRouterClient.ApiException e){
            if(e.refusal)throw e;
            if(e.review){split=true;writeJson(checkpoint,LibraryStore.obj("split",true));}
            else{String fallback=job.getJSONObject("config").optString("fallbackModel");if(!e.retryable||fallback.isEmpty()||fallback.equals(model))throw e;budget(job);output=client.translate(batch,fallback,glossary,previous);detail(job,"usedFallback",fallback);}
        }}
        if(split){
            if(batch.size()==1){budget(job);output=client.translatePlain(batch.get(0),model,glossary);}
            else{int middle=batch.size()/2;output=translateGroup(job,client,new ArrayList<>(batch.subList(0,middle)),model,glossary,previous,cancel);output.addAll(translateGroup(job,client,new ArrayList<>(batch.subList(middle,batch.size())),model,glossary,batch.get(middle-1).text,cancel));}
        }
        cancel.throwIfCanceled();if(output==null||output.size()!=batch.size())throw new IOException("部分译文未完成，可继续重试");writeJson(checkpoint,LibraryStore.obj("cues",SubtitleCodec.json(output)));return output;
    }
    private static List<SubtitleCodec.Cue> copyOriginal(List<SubtitleCodec.Cue> originals){List<SubtitleCodec.Cue> result=new ArrayList<>();for(SubtitleCodec.Cue c:originals){SubtitleCodec.Cue copy=new SubtitleCodec.Cue(c.start,c.end,"");copy.original=c.text;result.add(copy);}return result;}
    private static void normalize(List<SubtitleCodec.Cue> cues){cues.sort(Comparator.comparingDouble(c->c.start));for(int i=0;i+1<cues.size();i++)if(cues.get(i).end>cues.get(i+1).start)cues.get(i).end=cues.get(i+1).start;cues.removeIf(c->c.end<=c.start||c.text.trim().isEmpty());}
    private void finish(JSONObject job,List<SubtitleCodec.Cue> cues,boolean edited) throws Exception {
        progress(job,"saving",0,1,"保存字幕");JSONObject config=job.getJSONObject("config");String id=job.getString("id"),pid=job.getString("projectId"),tid=job.getString("trackId");File directory=new File(context.getFilesDir(),"subtitles");directory.mkdirs();
        File zh=new File(directory,id+".mimi.zh.vtt"),lrc=new File(directory,id+".mimi.zh.lrc"),bilingual=new File(directory,id+".mimi.bilingual.vtt"),source=new File(directory,id+".mimi.original.vtt");
        writeText(zh,SubtitleCodec.vtt(cues,false));writeText(lrc,SubtitleCodec.lrc(cues,false));writeText(bilingual,SubtitleCodec.vtt(cues,true));List<SubtitleCodec.Cue> raw=new ArrayList<>();for(SubtitleCodec.Cue c:cues)raw.add(new SubtitleCodec.Cue(c.start,c.end,c.original.isEmpty()?c.text:c.original));writeText(source,SubtitleCodec.vtt(raw,false));
        writeJson(new File(jobDir(job),"result.json"),LibraryStore.obj("cues",SubtitleCodec.json(cues),"edited",edited,"sttModel",config.optString("sttModel"),"translationModel",config.optString("translationModel"),"created",System.currentTimeMillis()));
        LibraryStore store=LibraryStore.get(context);
        JSONArray generated=new JSONArray();for(File result:Arrays.asList(source,bilingual,zh)){String lang=result.equals(source)?"original":result.equals(bilingual)?"bilingual":"zh";generated.put(LibraryStore.obj("id",LibraryStore.id(result.getName()),"name",lang.equals("zh")?"中文 · 自动生成":lang.equals("bilingual")?"中文 + 原文":"原文 · 自动识别","uri",Uri.fromFile(result).toString(),"format","vtt","generated",true,"relative",true,"language",lang,"jobId",id));}
        store.attachGeneratedBatch(pid,tid,generated,LibraryStore.id((config.optBoolean("bilingual")?bilingual:zh).getName()));
        if(!edited&&"audio".equals(job.optString("source")))store.subtitlePref(pid,tid,LibraryStore.id((config.optBoolean("bilingual")?bilingual:zh).getName()),0);
        synchronized(this){put(job,"outputVtt",zh.getPath());put(job,"outputLrc",lrc.getPath());put(job,"outputBilingual",bilingual.getPath());put(job,"outputOriginal",source.getPath());put(job,"cueCount",cues.size());put(job,"edited",edited);put(job,"completed",1);put(job,"total",1);}
        if(!edited&&!job.optBoolean("sidecarAttempted")&&!store.isHidden(pid)){JSONArray sidecars=new JSONArray();for(String format:Arrays.asList("vtt","lrc"))try{sidecars.put(SidecarExporter.save(context,id,pid,tid,format));}catch(IOException ignored){}detail(job,"sidecars",sidecars);detail(job,"sidecarAttempted",true);}
    }
    public synchronized JSONArray draft(String id) throws Exception {JSONObject job=find(id);if(job==null)throw new IOException("任务不存在");JSONObject saved=readJson(new File(jobDir(job),"result.json"));if(saved==null)saved=readJson(new File(jobDir(job),"draft.json"));if(saved==null)throw new IOException("识别尚未完成");return saved.getJSONArray("cues");}
    public void saveDraft(String id,JSONArray values) throws Exception {
        JSONObject job;JSONArray source;synchronized(this){job=find(id);if(job==null)throw new IOException("任务不存在");if(id.equals(active))throw new IOException("请先暂停任务");source=draft(id);}
        if(source.length()!=values.length())throw new IOException("字幕数量发生变化，请重新打开编辑");List<SubtitleCodec.Cue> cues=new ArrayList<>();
        for(int i=0;i<source.length();i++){JSONObject old=source.getJSONObject(i),value=values.getJSONObject(i);String text=value.optString("text").trim();if(text.isEmpty()||text.length()>2000)throw new IOException("第 "+(i+1)+" 条译文为空或过长");SubtitleCodec.Cue c=new SubtitleCodec.Cue(old.getDouble("start"),old.getDouble("end"),text);c.original=old.optString("original");cues.add(c);}
        finish(job,cues,true);synchronized(this){put(job,"status","done");put(job,"stage","done");put(job,"message","");changed();}
    }
    public synchronized File output(String id,String format) throws Exception {JSONObject job=find(id);if(job==null)throw new IOException("任务不存在");String field=format.equals("lrc")?"outputLrc":format.equals("bilingual")?"outputBilingual":format.equals("original")?"outputOriginal":"outputVtt";File result=new File(job.optString(field));if(!result.isFile()||!result.getCanonicalPath().startsWith(new File(context.getFilesDir(),"subtitles").getCanonicalPath()+File.separator))throw new IOException("字幕文件尚未生成");return result;}
    public synchronized String outputName(String id,String format) throws Exception {JSONObject job=find(id);if(job==null)throw new IOException("任务不存在");String name=job.optString("exportStem",SubtitleNames.stem(job.optString("title","字幕"),0));return name+".mimi."+(format.equals("original")?"original":format.equals("bilingual")?"bilingual":"zh")+(format.equals("lrc")?".lrc":".vtt");}
    private File jobDir(JSONObject job){return new File(context.getFilesDir(),"subtitle-jobs/"+job.optString("id"));}
    static JSONObject readJson(File file){try{return new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));}catch(Exception e){return null;}}
    static void writeJson(File file,JSONObject value) throws Exception {writeText(file,value.toString());}
    static void writeText(File file,String content) throws Exception {file.getParentFile().mkdirs();AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(content.getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}}
}
