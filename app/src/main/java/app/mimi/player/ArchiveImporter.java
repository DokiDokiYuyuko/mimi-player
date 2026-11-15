package app.mimi.player;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import me.zhanghai.android.libarchive.*;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Extracts media into an owned, atomic import directory; never writes to source archive paths. */
public final class ArchiveImporter {
    public static final class PasswordRequired extends IOException {PasswordRequired(){super("此作品包需要密码");}}
    public static String displayName(Context context,Uri uri){
        try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}
        return uri.getLastPathSegment()==null?"作品包":uri.getLastPathSegment();
    }
    public static File extract(Context context,Uri uri,String password,CancellationSignal cancel,Consumer<JSONObject> progress) throws Exception {
        File root=new File(context.getFilesDir(),"imports");root.mkdirs();String name=displayName(context,uri);long size=0,modified=0;
        try(Cursor c=context.getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int sizeColumn=c.getColumnIndex(OpenableColumns.SIZE),timeColumn=c.getColumnIndex("last_modified");if(sizeColumn>=0&&!c.isNull(sizeColumn))size=c.getLong(sizeColumn);if(timeColumn>=0&&!c.isNull(timeColumn))modified=c.getLong(timeColumn);}}
        String identity=LibraryStore.id(uri+"#"+size+"#"+modified);
        File existing=new File(root,identity);if(new File(existing,".mimi-import.json").isFile())return existing;
        File staging=new File(root,".pending-"+UUID.randomUUID());if(!staging.mkdirs())throw new IOException("无法创建作品目录");boolean complete=false;long archive=0;
        long limit=Math.min(16L*1024*1024*1024,Math.max(0,root.getUsableSpace()-256L*1024*1024));if(limit<16*1024*1024)throw new IOException("存储空间不足，请清理后重试");
        int saved=0,skipped=0,count=0;long written=0;
        try(ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(uri,"r")){
            if(descriptor==null)throw new IOException("无法读取作品包");archive=Archive.readNew();Archive.setCharset(archive,"UTF-8".getBytes(StandardCharsets.US_ASCII));Archive.readSupportFilterAll(archive);Archive.readSupportFormatAll(archive);
            if(!password.isEmpty())Archive.readAddPassphrase(archive,password.getBytes(StandardCharsets.UTF_8));
            Archive.readOpenFd(archive,descriptor.getFd(),65536);ByteBuffer buffer=ByteBuffer.allocateDirect(65536);byte[] bytes=new byte[65536];Set<String> paths=new HashSet<>();
            while(true){cancel.throwIfCanceled();long entry=Archive.readNextHeader(archive);if(entry==0)break;if(++count>20000)throw new IOException("作品包超过 20000 个文件，请分批导入");
                String path=ArchiveEntry.pathnameUtf8(entry);if(path==null){byte[] raw=ArchiveEntry.pathname(entry);path=raw==null?"":TextCodec.decode(raw);}
                String normalized=normalize(path);int type=ArchiveEntry.filetype(entry);
                if(type==ArchiveEntry.AE_IFDIR){Archive.readDataSkip(archive);continue;}
                if(type!=ArchiveEntry.AE_IFREG||ArchiveEntry.symlink(entry)!=null||ArchiveEntry.hardlink(entry)!=null){skipped++;Archive.readDataSkip(archive);continue;}
                if(!(MediaFormats.audio(normalized)||MediaFormats.subtitle(normalized)||MediaFormats.cue(normalized)||MediaFormats.image(normalized))){skipped++;Archive.readDataSkip(archive);continue;}
                if(!paths.add(normalized.toLowerCase(Locale.ROOT)))throw new IOException("作品包内存在重复文件路径，请先解压整理");
                if(ArchiveEntry.isEncrypted(entry)&&password.isEmpty())throw new PasswordRequired();
                if(ArchiveEntry.size(entry)>limit-written)throw new IOException("作品解压后超过可用空间");
                File destination=new File(staging,normalized);if(!destination.getCanonicalPath().startsWith(staging.getCanonicalPath()+File.separator))throw new IOException("作品包路径无效");destination.getParentFile().mkdirs();
                if(progress!=null)progress.accept(LibraryStore.obj("name",normalized,"files",saved,"bytes",written));
                try(FileOutputStream out=new FileOutputStream(destination)){
                    while(true){cancel.throwIfCanceled();buffer.clear();Archive.readData(archive,buffer);int n=buffer.position();if(n==0)break;written+=n;if(written>limit)throw new IOException("作品解压后超过可用空间");buffer.flip();buffer.get(bytes,0,n);out.write(bytes,0,n);}
                }
                if(ArchiveEntry.mtime(entry)>0)destination.setLastModified(ArchiveEntry.mtime(entry)*1000);
                saved++;
            }
            if(saved==0)throw new IOException("作品包中没有可导入的音频或字幕");
            SubtitleJobs.writeJson(new File(staging,".mimi-import.json"),LibraryStore.obj("source",uri.toString(),"name",name,"files",saved,"skipped",skipped,"bytes",written,"created",System.currentTimeMillis()));
            if(existing.exists())throw new IOException("作品目录已存在，请刷新资料库");if(!staging.renameTo(existing))throw new IOException("无法保存解压后的作品");complete=true;return existing;
        }catch(ArchiveException e){String message=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.ROOT);if(message.contains("passphrase")||message.contains("password")){if(password.isEmpty())throw new PasswordRequired();throw new IOException("密码不正确，或此加密方式需要先在文件管理器中解压",e);}if(message.contains("encrypt"))throw new IOException("此加密作品包需要先在文件管理器中解压",e);throw new IOException("作品包无法完整读取，请检查分卷是否齐全或先解压后导入",e);}
        finally{if(archive!=0)try{Archive.free(archive);}catch(Exception ignored){}if(!complete)deleteOwned(staging,root);}
    }
    static String normalize(String path) throws IOException {
        String value=path.replace('\\','/');if(value.isEmpty()||value.startsWith("/")||value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")||value.indexOf('\0')>=0)throw new IOException("作品包包含无效路径");
        List<String> result=new ArrayList<>();for(String part:value.split("/")){if(part.isEmpty()||part.equals("."))continue;if(part.equals(".."))throw new IOException("作品包包含越界路径");result.add(part);}if(result.isEmpty())throw new IOException("作品包包含空路径");return String.join("/",result);
    }
    private static void deleteOwned(File target,File root){try{if(!target.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))return;if(java.nio.file.Files.isSymbolicLink(target.toPath())){target.delete();return;}File[] files=target.listFiles();if(files!=null)for(File child:files)deleteOwned(child,root);target.delete();}catch(Exception ignored){}}
    private ArchiveImporter(){}
}
