package app.mimi.player;

import android.content.*;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.collect.ImmutableList;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import java.io.*;
import java.util.*;

/** LibVLC decoding behind Media3's session/player contract. No video view or second audio session. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VlcAudioPlayer extends SimpleBasePlayer {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LibVLC lib;
    private MediaPlayer vlc;
    private ParcelFileDescriptor descriptor;
    private final List<MediaItem> queue=new ArrayList<>();
    private State stateCache;
    private boolean playlistDirty=true;
    private boolean privateQueue;
    private MediaMetadata privateMetadata;
    private static final PlaybackException PRIVATE_ERROR = new PlaybackException(
            "音频无法播放", null, PlaybackException.ERROR_CODE_DECODING_FAILED);

    MediaItem internalItem() { return queue.isEmpty() ? null : queue.get(index); }

    void setPrivateQueue(boolean value) {
        if (privateQueue == value) return;
        privateQueue = value;
        playlistDirty = true;
        publish();
    }

    private MediaItem sessionItem(MediaItem item, int queueIndex) {
        if (!privateQueue) return item;
        if (privateMetadata == null) {
            android.graphics.Bitmap icon = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.drawable.Drawable drawable = context.getDrawable(R.drawable.ic_launcher);
            drawable.setBounds(0, 0, icon.getWidth(), icon.getHeight());
            drawable.draw(new android.graphics.Canvas(icon));
            ByteArrayOutputStream artwork = new ByteArrayOutputStream();
            icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, artwork);
            icon.recycle();
            privateMetadata = new MediaMetadata.Builder().setTitle("正在播放").setArtist("MIMI").setAlbumTitle("MIMI")
                    .setArtworkData(artwork.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build();
        }
        return new MediaItem.Builder().setMediaId("mimi:" + queueIndex).setMediaMetadata(privateMetadata).build();
    }
    private static final AudioAttributes AUDIO_ATTRIBUTES=new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build();
    private final Map<String,Long> durations=new HashMap<>();
    private int index=0,status=Player.STATE_IDLE,repeat=Player.REPEAT_MODE_OFF,generation=0;
    private boolean wanted=false,loaded=false,started=false,released=false,suppressed=false,focusOwned=false,focusRequested=false,ending=false;
    private long positionMs=0,clipStartMs=0,clipEndMs=C.TIME_END_OF_SOURCE,seekOnStartMs=-1;
    private PlaybackParameters parameters=PlaybackParameters.DEFAULT;
    private PlaybackException failure;
    private float volume=1;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final PowerManager.WakeLock wakeLock;
    private long wakeRenewAt;
    private final boolean testMode;
    private final java.util.concurrent.ExecutorService preparation=java.util.concurrent.Executors.newSingleThreadExecutor();
    private CancellationSignal conversion;
    private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()))audioBecameNoisy();}};
    void audioBecameNoisy(){setPlayWhenReady(false);}
    boolean preparingAudio(){return conversion!=null;}
    private static final Player.Commands COMMANDS=new Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,Player.COMMAND_PREPARE,Player.COMMAND_STOP,Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_NEXT,Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,Player.COMMAND_SET_SPEED_AND_PITCH,
        Player.COMMAND_SET_REPEAT_MODE,Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,Player.COMMAND_SET_MEDIA_ITEM,Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_GET_AUDIO_ATTRIBUTES,Player.COMMAND_GET_VOLUME,Player.COMMAND_SET_VOLUME).build();

    public VlcAudioPlayer(Context context){this(context,false);}
    VlcAudioPlayer(Context context,boolean testMode){
        super(Looper.getMainLooper());this.context=context.getApplicationContext();this.testMode=testMode;
        ArrayList<String> options=new ArrayList<>(Arrays.asList("--no-video","--no-spu","--audio-time-stretch","--no-video-title-show"));
        if(testMode)options.add("--aout=dummy");
        lib=new LibVLC(this.context,options);
        audio=(AudioManager)this.context.getSystemService(Context.AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(this::onFocus,main).build();
        wakeLock=((PowerManager)this.context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MIMI:audio");wakeLock.setReferenceCounted(false);
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)this.context.registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED);else this.context.registerReceiver(noisy,filter);
        main.post(tick);
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(released)return;
        if(loaded&&started&&vlc!=null&&status==Player.STATE_READY){
            long raw=vlc.getTime();if(raw>=0)positionMs=Math.max(0,raw-clipStartMs);
            if(wanted&&!suppressed&&clipEndMs!=C.TIME_END_OF_SOURCE&&raw>=clipEndMs)finishCurrent();
        }
        main.postDelayed(this,wanted&&!suppressed?80:1000);
    }};
    @Override protected State getState(){
        State.Builder builder=stateCache==null?new State.Builder():stateCache.buildUpon();
        if(playlistDirty){
            ImmutableList.Builder<MediaItemData> items=ImmutableList.builder();
            int queueIndex = 0;
            for(MediaItem item:queue){
                long duration=duration(item);
                MediaItem visible = sessionItem(item, queueIndex++);
                items.add(new MediaItemData.Builder(visible.mediaId).setMediaItem(visible).setMediaMetadata(visible.mediaMetadata)
                    .setIsSeekable(true).setDurationUs(duration<0?C.TIME_UNSET:duration*1000).build());
            }
            // setPlaylist also rebuilds Media3's timeline indexes; retain them for progress-only updates.
            builder.setPlaylist(items.build());playlistDirty=false;
        }
        return stateCache=builder.setAvailableCommands(COMMANDS).setCurrentMediaItemIndex(queue.isEmpty()?C.INDEX_UNSET:index)
            .setPlayWhenReady(wanted,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).setPlaybackState(status)
            .setPlaybackSuppressionReason(suppressed?Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS:Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setIsLoading(status==Player.STATE_BUFFERING).setPlayerError(privateQueue && failure != null ? PRIVATE_ERROR : failure).setRepeatMode(repeat).setPlaybackParameters(parameters)
            .setContentPositionMs(()->positionMs).setContentBufferedPositionMs(()->Math.max(positionMs,currentDuration())).setTotalBufferedDurationMs(()->Math.max(0,currentDuration()-positionMs))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).setVolume(volume)
            .setAudioAttributes(AUDIO_ATTRIBUTES).build();
    }
    private long duration(MediaItem item){
        Long known=durations.get(item.mediaId);if(known!=null)return known;
        Bundle extras=item.mediaMetadata.extras;return extras==null?-1:extras.getLong("durationMs",-1);
    }
    private long currentDuration(){return queue.isEmpty()?0:Math.max(0,duration(queue.get(index)));}
    private void publish(){if(!released){updateWake();invalidateState();}}
    private void updateWake(){boolean active=wanted&&!suppressed&&(status==Player.STATE_READY||status==Player.STATE_BUFFERING);long now=SystemClock.elapsedRealtime();if(active&&(!wakeLock.isHeld()||now>=wakeRenewAt)){wakeLock.acquire(10*60*1000L);wakeRenewAt=now+5*60*1000L;}else if(!active&&wakeLock.isHeld()){wakeLock.release();wakeRenewAt=0;}}
    private boolean requestFocus(){if(testMode)return true;if(focusOwned)return true;if(focusRequested)return false;int result=audio.requestAudioFocus(focus);focusOwned=result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;focusRequested=focusOwned||result==AudioManager.AUDIOFOCUS_REQUEST_DELAYED;return focusOwned;}
    private void abandonFocus(){if(focusOwned||focusRequested)audio.abandonAudioFocusRequest(focus);focusOwned=false;focusRequested=false;suppressed=false;}
    private void retryFocus(int attempt){
        if(released||!wanted||focusOwned||focusRequested)return;
        main.postDelayed(()->{if(released||!wanted||focusOwned||focusRequested)return;if(requestFocus()){suppressed=false;if(vlc!=null){started=true;status=Player.STATE_BUFFERING;vlc.play();}publish();}else if(!focusRequested&&attempt<2)retryFocus(attempt+1);else if(!focusRequested)fail("无法开始播放，请打开应用后重试",null);},250L*(attempt+1));
    }
    private void onFocus(int change){
        if(released)return;
        if(change==AudioManager.AUDIOFOCUS_GAIN){if(!wanted){abandonFocus();publish();return;}focusOwned=true;focusRequested=true;suppressed=false;if(vlc!=null){started=true;status=Player.STATE_BUFFERING;vlc.play();}}
        else if(change==AudioManager.AUDIOFOCUS_LOSS){wanted=false;abandonFocus();if(vlc!=null)vlc.pause();}
        else {suppressed=true;if(vlc!=null)vlc.pause();}
        publish();
    }
    private void discardNative(){
        generation++;loaded=false;started=false;
        if(conversion!=null){conversion.cancel();conversion=null;}
        if(vlc!=null){vlc.setEventListener(null);vlc.stop();vlc.release();vlc=null;}
        if(descriptor!=null){try{descriptor.close();}catch(IOException ignored){}descriptor=null;}
    }
    private void loadCurrent(){
        discardNative();failure=null;ending=false;
        if(queue.isEmpty()){status=Player.STATE_IDLE;return;}
        MediaItem item=queue.get(index);clipStartMs=item.clippingConfiguration.startPositionMs;clipEndMs=item.clippingConfiguration.endPositionMs;
        final int token=generation;
        try{
            Uri uri=Objects.requireNonNull(item.localConfiguration).uri;
            if(item.mediaMetadata.extras!=null&&item.mediaMetadata.extras.getBoolean("convertBeforePlayback")){
                if(!wanted){status=Player.STATE_READY;return;}
                status=Player.STATE_BUFFERING;CancellationSignal signal=new CancellationSignal();conversion=signal;
                preparation.execute(()->{try{
                    File compatible=CompatibleAudio.prepare(context,uri,item.mediaMetadata.extras.getString("audioIdentity",item.mediaId),signal,null);
                    signal.throwIfCanceled();Bundle extra=new Bundle(item.mediaMetadata.extras);extra.putBoolean("convertBeforePlayback",false);
                    MediaItem updated=item.buildUpon().setUri(Uri.fromFile(compatible)).setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extra).build()).build();
                    String[] ids=item.mediaId.split(":",2);if(ids.length==2)LibraryStore.get(context).useConverted(ids[0],ids[1],compatible,MediaTools.probe(context,Uri.fromFile(compatible)));
                    main.post(()->{if(token!=generation||released)return;conversion=null;queue.set(index,updated);playlistDirty=true;loadCurrent();publish();});
                }catch(OperationCanceledException ignored){}catch(Exception e){main.post(()->{if(token==generation&&!released)fail("兼容转换未完成："+(e.getMessage()==null?"请重试":e.getMessage()),e);});}});
                return;
            }
            Media media;
            if("content".equals(uri.getScheme())){
                descriptor=context.getContentResolver().openFileDescriptor(uri,"r");if(descriptor==null)throw new FileNotFoundException();
                media=new Media(lib,descriptor.getFileDescriptor());
            }else media=new Media(lib,uri);
            media.addOption(":no-video");media.addOption(":no-spu");
            media.addOption(":start-time="+String.format(Locale.ROOT,"%.3f",(clipStartMs+positionMs)/1000.0));
            if(clipEndMs!=C.TIME_END_OF_SOURCE)media.addOption(":stop-time="+String.format(Locale.ROOT,"%.3f",clipEndMs/1000.0));
            vlc=new MediaPlayer(lib);vlc.setEventListener(event->{if(token==generation&&!released)nativeEvent(event);});
            vlc.setMedia(media);media.release();vlc.setRate(parameters.speed);vlc.setVolume(Math.round(volume*100));
            seekOnStartMs=clipStartMs+positionMs;loaded=true;
            if(wanted&&!suppressed){status=Player.STATE_BUFFERING;started=true;vlc.play();}else status=Player.STATE_READY;
        }catch(Exception e){fail(e instanceof SecurityException?"文件夹权限已失效，请重新授权":"无法打开音频文件，请刷新作品",e);}
    }
    private void nativeEvent(MediaPlayer.Event event){
        switch(event.type){
            case MediaPlayer.Event.Opening:status=Player.STATE_BUFFERING;break;
            case MediaPlayer.Event.Buffering:if(event.getBuffering()<100)status=Player.STATE_BUFFERING;else if(loaded)status=Player.STATE_READY;break;
            case MediaPlayer.Event.Playing:
                status=Player.STATE_READY;
                if(seekOnStartMs>=0){positionMs=Math.max(0,seekOnStartMs-clipStartMs);if(Math.abs(vlc.getTime()-seekOnStartMs)>100)vlc.setTime(seekOnStartMs,true);seekOnStartMs=-1;}
                if(!wanted||suppressed){vlc.pause();vlc.setVolume(Math.round(volume*100));}else vlc.setVolume(Math.round(volume*100));
                break;
            case MediaPlayer.Event.Paused:status=Player.STATE_READY;break;
            case MediaPlayer.Event.TimeChanged:if(seekOnStartMs<0)positionMs=Math.max(0,event.getTimeChanged()-clipStartMs);if(wanted&&clipEndMs!=C.TIME_END_OF_SOURCE&&event.getTimeChanged()>=clipEndMs)finishCurrent();break;
            case MediaPlayer.Event.LengthChanged:
                if(!queue.isEmpty()){long end=clipEndMs==C.TIME_END_OF_SOURCE?event.getLengthChanged():Math.min(clipEndMs,event.getLengthChanged());if(end>clipStartMs){long length=end-clipStartMs;String id=queue.get(index).mediaId;if(!Objects.equals(durations.get(id),length)){durations.put(id,length);playlistDirty=true;}}}break;
            case MediaPlayer.Event.EndReached:finishCurrent();return;
            case MediaPlayer.Event.EncounteredError:fail("无法解码这条音频，请查看曲目信息或尝试兼容转换",null);return;
            default:return;
        }
        publish();
    }
    private void fail(String message,Throwable cause){status=Player.STATE_IDLE;wanted=false;failure=new PlaybackException(message,cause,PlaybackException.ERROR_CODE_DECODING_FAILED);abandonFocus();publish();}
    private void finishCurrent(){
        if(ending||queue.isEmpty())return;ending=true;
        if(repeat==Player.REPEAT_MODE_ONE){positionMs=0;loadCurrent();}
        else if(index+1<queue.size()||repeat==Player.REPEAT_MODE_ALL){index=(index+1)%queue.size();positionMs=0;loadCurrent();}
        else {positionMs=currentDuration();status=Player.STATE_ENDED;wanted=false;discardNative();abandonFocus();}
        publish();
    }
    @Override protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items,int startIndex,long startPositionMs){
        discardNative();queue.clear();queue.addAll(items);durations.clear();playlistDirty=true;
        String[] ids = items.isEmpty() ? new String[0] : items.get(0).mediaId.split(":", 2);
        privateQueue = ids.length == 2 && LibraryStore.get(context).isHidden(ids[0]);index=items.isEmpty()?0:Math.max(0,Math.min(startIndex==C.INDEX_UNSET?0:startIndex,items.size()-1));
        positionMs=startPositionMs==C.TIME_UNSET?0:Math.max(0,startPositionMs);status=Player.STATE_IDLE;failure=null;return Futures.immediateVoidFuture();
    }
    @Override protected ListenableFuture<?> handlePrepare(){if(!queue.isEmpty())loadCurrent();return Futures.immediateVoidFuture();}
    @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean ready){
        wanted=ready;main.removeCallbacks(tick);main.post(tick);if(ready){suppressed=!requestFocus();if(suppressed&&!focusRequested)retryFocus(0);if(status==Player.STATE_ENDED){positionMs=0;loadCurrent();}else if(!loaded&&!queue.isEmpty())loadCurrent();else if(vlc!=null&&!suppressed){if(!started){status=Player.STATE_BUFFERING;started=true;}vlc.play();}}
        else {if(conversion!=null){discardNative();status=Player.STATE_READY;}if(vlc!=null)vlc.pause();abandonFocus();}updateWake();return Futures.immediateVoidFuture();
    }
    @Override protected ListenableFuture<?> handleSeek(int mediaItemIndex,long position,int seekCommand){
        if(queue.isEmpty())return Futures.immediateVoidFuture();int next=Math.max(0,Math.min(mediaItemIndex,queue.size()-1));long target=position==C.TIME_UNSET?0:Math.max(0,position);boolean changed=next!=index;index=next;positionMs=target;failure=null;
        if(conversion!=null&&!changed)return Futures.immediateVoidFuture();
        if(changed||!loaded||!started||status==Player.STATE_ENDED){if(status!=Player.STATE_IDLE||wanted)loadCurrent();}
        else {if(status==Player.STATE_BUFFERING)seekOnStartMs=clipStartMs+target;vlc.setTime(clipStartMs+target,true);}
        return Futures.immediateVoidFuture();
    }
    @Override protected ListenableFuture<?> handleSetRepeatMode(int value){repeat=value;return Futures.immediateVoidFuture();}
    @Override protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters value){parameters=value;if(vlc!=null)vlc.setRate(value.speed);return Futures.immediateVoidFuture();}
    @Override protected ListenableFuture<?> handleSetVolume(float value){volume=Math.max(0,Math.min(1,value));if(vlc!=null)vlc.setVolume(Math.round(volume*100));return Futures.immediateVoidFuture();}
    @Override protected ListenableFuture<?> handleStop(){wanted=false;discardNative();status=Player.STATE_IDLE;abandonFocus();updateWake();return Futures.immediateVoidFuture();}
    @Override protected ListenableFuture<?> handleRemoveMediaItems(int from,int to){
        playlistDirty=true;
        if(from==0&&to==queue.size()){handleStop();queue.clear();index=0;positionMs=0;}else{MediaItem current=queue.isEmpty()?null:queue.get(index);queue.subList(from,to).clear();index=Math.max(0,queue.indexOf(current));if(current!=null&&!queue.contains(current)){positionMs=0;if(!queue.isEmpty())loadCurrent();}}
        return Futures.immediateVoidFuture();
    }
    @Override protected ListenableFuture<?> handleRelease(){
        released=true;main.removeCallbacksAndMessages(null);discardNative();preparation.shutdown();abandonFocus();if(wakeLock.isHeld())wakeLock.release();try{context.unregisterReceiver(noisy);}catch(Exception ignored){}lib.release();return Futures.immediateVoidFuture();
    }
}
