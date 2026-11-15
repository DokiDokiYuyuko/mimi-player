package app.mimi.player;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import com.arthenica.ffmpegkit.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.DoubleConsumer;

/** File-scoped FFmpeg operations. A fresh SAF registration is required for each invocation. */
public final class MediaTools {
    public static String input(Context context,Uri uri){
        if("content".equals(uri.getScheme()))return FFmpegKitConfig.getSafParameterForRead(context,uri);
        if("file".equals(uri.getScheme()))return uri.getPath();
        if(uri.getScheme()==null)return uri.toString();
        throw new IllegalArgumentException("请选择本地音频文件");
    }
    public static JSONObject probe(Context context,Uri uri) throws Exception {
        FFprobeSession session=FFprobeKit.executeWithArguments(new String[]{"-v","error","-select_streams","a:0","-show_entries",
            "stream=index,codec_name,codec_type,sample_rate,channels,bits_per_sample,bits_per_raw_sample,duration,bit_rate:format=format_name,duration,bit_rate:format_tags=title,album,artist",
            "-of","json",input(context,uri)});
        if(!ReturnCode.isSuccess(session.getReturnCode()))throw new IOException("无法读取音频信息，文件可能不完整或已损坏");
        JSONObject data=new JSONObject(session.getOutput());JSONArray streams=data.optJSONArray("streams");
        if(streams==null||streams.length()==0)throw new IOException("文件中没有音频轨道");
        JSONObject stream=streams.getJSONObject(0),format=data.optJSONObject("format");if(format==null)format=new JSONObject();
        double seconds=number(format.optString("duration"),number(stream.optString("duration"),0));
        JSONObject tags=format.optJSONObject("tags");if(tags==null)tags=new JSONObject();
        return new JSONObject().put("codec",stream.optString("codec_name")).put("container",format.optString("format_name"))
            .put("duration",Math.max(0,Math.round(seconds*1000))).put("sampleRate",stream.optInt("sample_rate"))
            .put("channels",stream.optInt("channels")).put("bits",Math.max(stream.optInt("bits_per_sample"),stream.optInt("bits_per_raw_sample")))
            .put("bitrate",format.optLong("bit_rate",stream.optLong("bit_rate"))).put("title",tags.optString("title"))
            .put("album",tags.optString("album")).put("artist",tags.optString("artist"));
    }
    public static void audioChunk(Context context,Uri uri,long absoluteStartMs,long lengthMs,File output,CancellationSignal cancel,DoubleConsumer progress) throws Exception {
        if(lengthMs<=0)throw new IllegalArgumentException("音频片段时长无效");
        output.getParentFile().mkdirs();
        run(new String[]{"-hide_banner","-v","error","-nostdin","-y","-ss",decimal(absoluteStartMs),"-i",input(context,uri),
            "-t",decimal(lengthMs),"-map","0:a:0","-vn","-sn","-dn","-ac","1","-ar","16000","-c:a","pcm_s16le",output.getPath()},cancel,progress,Math.max(120000,lengthMs*2));
        if(output.length()<44)throw new IOException("音频片段为空");
        long actual=probe(context,Uri.fromFile(output)).optLong("duration");
        if(actual+500<lengthMs)throw new IOException("音频片段不完整，文件可能仍在复制，请刷新作品后重试");
    }
    public static void convertLossless(Context context,Uri uri,File output,CancellationSignal cancel,DoubleConsumer progress) throws Exception {
        output.getParentFile().mkdirs();
        run(new String[]{"-hide_banner","-v","error","-nostdin","-y","-i",input(context,uri),"-map","0:a:0","-vn","-sn","-dn","-c:a","flac",output.getPath()},cancel,progress,1800000);
    }
    private static void run(String[] arguments,CancellationSignal cancel,DoubleConsumer progress,long timeout) throws Exception {
        cancel.throwIfCanceled();CountDownLatch done=new CountDownLatch(1);
        FFmpegSession session=FFmpegKit.executeWithArgumentsAsync(arguments,s->done.countDown(),log->{},stats->{if(progress!=null)progress.accept(stats.getTime());});
        long deadline=System.currentTimeMillis()+timeout;
        try{
            while(!done.await(150,TimeUnit.MILLISECONDS)){
                if(cancel.isCanceled()){session.cancel();throw new OperationCanceledException();}
                if(System.currentTimeMillis()>deadline){session.cancel();throw new IOException("音频处理超时，可重试继续");}
            }
            cancel.throwIfCanceled();
            if(!ReturnCode.isSuccess(session.getReturnCode()))throw new IOException("无法解码音频，请检查文件是否完整");
        }catch(InterruptedException e){session.cancel();Thread.currentThread().interrupt();throw new OperationCanceledException();}
    }
    public static double pcmRms(File wav) throws IOException {
        // Generated PCM16 WAV may contain metadata chunks; locate data rather than assuming byte 44.
        try(RandomAccessFile file=new RandomAccessFile(wav,"r")){
            if(file.length()<44)return 0;file.seek(12);long count=0;double sum=0;
            while(file.getFilePointer()+8<=file.length()){
                byte[] name=new byte[4];file.readFully(name);long length=Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
                if(Arrays.equals(name,new byte[]{'d','a','t','a'})){
                    long end=Math.min(file.length(),file.getFilePointer()+length);byte[] bytes=new byte[8192];
                    while(file.getFilePointer()<end){int n=file.read(bytes,0,(int)Math.min(bytes.length,end-file.getFilePointer()));if(n<0)break;for(int i=0;i+1<n;i+=2){short sample=(short)((bytes[i]&255)|(bytes[i+1]<<8));sum+=(double)sample*sample;count++;}}
                    return count==0?0:Math.sqrt(sum/count)/32768.0;
                }
                long next=file.getFilePointer()+length+(length&1);if(next>file.length())break;file.seek(next);
            }return 0;
        }
    }
    private static String decimal(long ms){return String.format(Locale.ROOT,"%.3f",ms/1000.0);}
    private static double number(String value,double fallback){try{double n=Double.parseDouble(value);return Double.isFinite(n)?n:fallback;}catch(Exception e){return fallback;}}
    private MediaTools(){}
}
