package app.mimi.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import org.json.*;
import java.util.*;

public final class PlaybackService extends MediaSessionService {
    static PlaybackService instance;
    private MediaSession session;
    Player player;
    private LibraryStore store;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long savedAt=0;
    long sleepUntil=0,abA=-1,abB=-1;
    String error="";
    private String previousMediaId = "";
    private final Runnable tick=new Runnable(){public void run(){
        if(player!=null){
            if(sleepUntil>0&&System.currentTimeMillis()>=sleepUntil){sleepUntil=0;store.preference("sleepUntil",0);player.pause();remember();}
            if(abA>=0&&abB>abA&&player.isPlaying()&&player.getCurrentPosition()>=abB)player.seekTo(abA);
            if(player.isPlaying()&&System.currentTimeMillis()-savedAt>4000)remember();
        }
        handler.postDelayed(this,200);
    }};
    @Override public void onCreate(){
        super.onCreate();instance=this;store=LibraryStore.get(this);sleepUntil=store.sleepUntil();if(sleepUntil<System.currentTimeMillis())sleepUntil=0;
        player=new VlcAudioPlayer(this);
        player.setRepeatMode(store.repeat());player.setPlaybackSpeed((float)Math.max(0.5,Math.min(2,store.speed())));
        PendingIntent activity=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        session=new MediaSession.Builder(this,player).setSessionActivity(activity).setCallback(new MediaSession.Callback() {
            @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
            @Override public MediaSession.ConnectionResult onConnect(MediaSession session, MediaSession.ControllerInfo controller) {
                Player.Commands commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build();
                return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(commands).build();
            }
        }).build();
        player.addListener(new Player.Listener(){
            @Override public void onIsPlayingChanged(boolean playing){remember();}
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                MediaItem current = internalItem();
                String mediaId = current == null ? "" : current.mediaId;
                if (!previousMediaId.equals(mediaId)) { abA = -1; abB = -1; error = ""; }
                previousMediaId = mediaId;
                remember();
            }
            @Override public void onPlayerError(PlaybackException e){error=e.getMessage()==null?"音频无法播放":e.getMessage();remember();}
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED)remember();}
        });
        JSONObject last=store.last();
        if(last!=null)try{load(last.optString("projectId"),last.optString("trackId"),last.optLong("position"),false);}catch(Exception ignored){}
        handler.post(tick);
    }
    void load(String pid,String tid,long position,boolean play) throws Exception {
        JSONObject p=store.project(pid);if(p==null)throw new Exception("作品不存在");JSONArray tracks=p.optJSONArray("tracks");List<MediaItem> queue=new ArrayList<>();int index=-1;
        for(int i=0;i<tracks.length();i++){
            JSONObject t=tracks.optJSONObject(i);if(t.optString("id").equals(tid))index=i;
            MediaMetadata.Builder meta=new MediaMetadata.Builder().setTitle(t.optString("title")).setAlbumTitle(p.optString("name")).setArtist(p.optString("artist",p.optString("name")));
            String cover=p.optString("cover");if(!cover.isEmpty()){String name=cover.substring(cover.lastIndexOf('/')+1);java.io.File f=new java.io.File(getFilesDir(),"covers/"+name);if(f.exists())try{meta.setArtworkData(java.nio.file.Files.readAllBytes(f.toPath()),MediaMetadata.PICTURE_TYPE_FRONT_COVER);}catch(Exception ignored){}}
            Bundle extra=new Bundle();extra.putLong("durationMs",t.optLong("duration",-1));JSONObject info=t.optJSONObject("audioInfo");extra.putBoolean("convertBeforePlayback",!t.optBoolean("converted")&&info!=null&&CompatibleAudio.required(info.optString("codec")));extra.putString("audioIdentity",LibraryStore.audioFingerprint(t));meta.setExtras(extra);
            MediaItem.Builder item=new MediaItem.Builder().setMediaId(pid+":"+t.optString("id")).setUri(Uri.parse(t.optString("uri"))).setMediaMetadata(meta.build());
            if(t.has("clipStart"))item.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(t.optLong("clipStart")).setEndPositionMs(t.optLong("clipEnd",C.TIME_END_OF_SOURCE)).build());
            queue.add(item.build());
        }
        if(index<0)throw new Exception("曲目不存在");remember();error="";abA=-1;abB=-1;
        player.setPlayWhenReady(false);player.setMediaItems(queue,index,Math.max(0,position));player.prepare();player.setPlayWhenReady(play);remember();
    }
    private MediaItem internalItem() {
        return player instanceof VlcAudioPlayer ? ((VlcAudioPlayer) player).internalItem() : null;
    }

    String currentProjectId() {
        MediaItem item = internalItem();
        return item == null ? "" : item.mediaId.split(":", 2)[0];
    }

    void exitPrivatePlayback() {
        if (!store.isHidden(currentProjectId())) return;
        remember();
        player.stop();
        player.clearMediaItems();
    }

    void syncPrivacy() {
        if (player instanceof VlcAudioPlayer) ((VlcAudioPlayer) player).setPrivateQueue(store.isHidden(currentProjectId()));
    }

    void remember(){
        MediaItem item = internalItem();
        if (item == null) return;
        String[] ids=item.mediaId.split(":",2);if(ids.length==2){store.remember(ids[0],ids[1],player.getCurrentPosition());savedAt=System.currentTimeMillis();}
    }
    JSONObject state(){
        String pid="",tid="";MediaItem item = internalItem(); if(item!=null){String[] ids=item.mediaId.split(":",2);if(ids.length==2){pid=ids[0];tid=ids[1];}}
        return LibraryStore.obj("projectId",pid,"trackId",tid,"position",player.getCurrentPosition(),"duration",Math.max(0,player.getDuration()),"playing",player.isPlaying(),"paused",!player.getPlayWhenReady(),"buffering",player.getPlaybackState()==Player.STATE_BUFFERING,"preparingAudio",player instanceof VlcAudioPlayer&&((VlcAudioPlayer)player).preparingAudio(),"ended",player.getPlaybackState()==Player.STATE_ENDED,"speed",player.getPlaybackParameters().speed,"repeat",player.getRepeatMode(),"sleepUntil",sleepUntil,"abA",abA,"abB",abB,"error",error);
    }
    JSONObject state(boolean hidden) {
        JSONObject state = state();
        if (!state.optString("projectId").isEmpty() && store.isHidden(state.optString("projectId")) != hidden) {
            return LibraryStore.obj("projectId", "", "trackId", "", "paused", true, "speed", state.optDouble("speed", 1),
                    "repeat", state.optInt("repeat"), "sleepUntil", state.optLong("sleepUntil"), "abA", -1, "abB", -1);
        }
        return state;
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onDestroy(){remember();handler.removeCallbacksAndMessages(null);if(session!=null)session.release();if(player!=null)player.release();instance=null;super.onDestroy();}
}
