package app.mimi.player;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleConsumer;

final class CompatibleAudio {
    private static final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
    static boolean required(String codec){return codec.startsWith("dsd_");}
    static File prepare(Context context,Uri uri,String identity,CancellationSignal signal,DoubleConsumer progress) throws Exception {
        String key=LibraryStore.id(identity);Object lock=locks.computeIfAbsent(key,k->new Object());
        synchronized(lock){signal.throwIfCanceled();File dir=new File(context.getFilesDir(),"converted");dir.mkdirs();File output=new File(dir,key+".flac");if(output.isFile()){MediaTools.probe(context,Uri.fromFile(output));return output;}
            File temporary=new File(dir,key+"."+UUID.randomUUID()+".partial.flac");
            try{if(dir.getUsableSpace()<256L*1024*1024)throw new IOException("存储空间不足，无法生成兼容副本");MediaTools.convertLossless(context,uri,temporary,signal,progress);signal.throwIfCanceled();MediaTools.probe(context,Uri.fromFile(temporary));if(!temporary.renameTo(output))throw new IOException("无法保存兼容副本");return output;}
            finally{temporary.delete();}
        }
    }
    private CompatibleAudio(){}
}
