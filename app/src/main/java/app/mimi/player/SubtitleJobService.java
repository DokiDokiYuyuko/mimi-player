package app.mimi.player;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;
import java.util.concurrent.*;

public final class SubtitleJobService extends Service {
    private static final String CHANNEL="subtitle-tasks";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private int pending,latestStartId;
    private volatile boolean stopping;
    private SubtitleJobs jobs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable updateNotification = () -> {
        if (!stopping && pending > 0) getSystemService(NotificationManager.class).notify(120, notification(jobs.notificationSnapshot()));
    };
    private final Runnable notificationObserver = () -> {
        main.removeCallbacks(updateNotification);
        main.post(updateNotification);
    };
    private PowerManager.WakeLock lock;
    public static void start(Context context){androidx.core.content.ContextCompat.startForegroundService(context,new Intent(context,SubtitleJobService.class));}
    @Override public void onCreate(){super.onCreate();jobs=SubtitleJobs.get(this);NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"字幕生成",NotificationManager.IMPORTANCE_LOW));
        lock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MIMI:subtitles");lock.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStartId=startId;
        if(intent!=null&&"pause".equals(intent.getAction())){jobs.pauseAll("");if(pending==0)stopSelf(startId);return START_NOT_STICKY;}
        Notification notification=notification(jobs.notificationSnapshot());if(Build.VERSION.SDK_INT>=29)startForeground(120,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(120,notification);
        jobs.observeNotification(notificationObserver);
        pending++;if(!lock.isHeld())lock.acquire(6*60*60*1000L);
        worker.execute(()->{try{jobs.runQueue(()->!stopping);}finally{main.post(()->{pending--;if(pending==0){jobs.unobserveNotification(notificationObserver);if(lock.isHeld())lock.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(latestStartId);}});}});
        return START_NOT_STICKY;
    }
    private Notification notification(JSONObject snapshot){
        String title = snapshot.optString("title", "字幕任务"), text = snapshot.optString("message", "准备中");
        int done = snapshot.optInt("completed"), total = snapshot.optInt("total");
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("openJobs",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,120,new Intent(this,SubtitleJobService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_subtitle_notification).setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setSilent(true).setOngoing(true).setProgress(total,done,total==0).addAction(android.R.drawable.ic_media_pause,"暂停",pause).build();
    }
    @Override public void onTimeout(int startId,int type){jobs.pauseAll("系统暂停了后台任务，打开应用后可继续");stopSelf();}
    @Override public void onDestroy(){stopping=true;main.removeCallbacks(updateNotification);if(pending>0)jobs.pauseAll("任务已暂停，点击继续");jobs.unobserveNotification(notificationObserver);worker.shutdown();if(lock!=null&&lock.isHeld())lock.release();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
