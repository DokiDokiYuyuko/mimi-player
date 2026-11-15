package app.mimi.player;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public final class SidecarExporter {
    public static JSONObject save(Context context,String jobId,String pid,String tid,String format) throws Exception {
        LibraryStore store = LibraryStore.get(context);
        synchronized (store.projectChanges()) {
            if (store.isHidden(pid)) throw new IOException("请使用「选择保存位置」导出");
            return savePublic(context, jobId, pid, tid, format);
        }
    }

    private static JSONObject savePublic(Context context,String jobId,String pid,String tid,String format) throws Exception {
        JSONObject track=LibraryStore.get(context).track(pid,tid);if(track==null||track.optString("parentUri").isEmpty())throw new IOException("请刷新作品，或选择其他保存位置");Uri parent=Uri.parse(track.optString("parentUri"));SubtitleJobs jobs=SubtitleJobs.get(context);File file=jobs.output(jobId,format);String name=jobs.outputName(jobId,format);Uri created=null;
        if("file".equals(parent.getScheme())){
            File directory=new File(parent.getPath()),root=new File(context.getFilesDir(),"imports");if(!directory.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("请选择保存位置");String base=name;int number=2;File destination;
            while(true){destination=new File(directory,name);try{java.nio.file.Files.copy(file.toPath(),destination.toPath());break;}catch(java.nio.file.FileAlreadyExistsException e){int dot=base.lastIndexOf('.');name=base.substring(0,dot)+" ("+(number++)+")"+base.substring(dot);}}
            return LibraryStore.obj("name",name,"uri",Uri.fromFile(destination).toString());
        }
        try{
            Set<String> existing=new HashSet<>();Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
            try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(cursor==null)throw new IOException("无法读取作品目录");while(cursor.moveToNext())existing.add(cursor.getString(0).toLowerCase(Locale.ROOT));}
            String base=name;int number=2;while(existing.contains(name.toLowerCase(Locale.ROOT))){int dot=base.lastIndexOf('.');name=base.substring(0,dot)+" ("+(number++)+")"+base.substring(dot);}
            created=DocumentsContract.createDocument(context.getContentResolver(),parent,format.equals("lrc")?"application/octet-stream":"text/vtt",name);if(created==null)throw new IOException("目录不允许写入");
            try(InputStream input=new FileInputStream(file);OutputStream output=context.getContentResolver().openOutputStream(created,"wt")){if(output==null)throw new IOException();byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}
            return LibraryStore.obj("name",name,"uri",created.toString());
        }catch(Exception e){if(created!=null)try{DocumentsContract.deleteDocument(context.getContentResolver(),created);}catch(Exception ignored){}throw new IOException("无法写入作品目录，请使用「选择保存位置」导出");}
    }
    private SidecarExporter(){}
}
