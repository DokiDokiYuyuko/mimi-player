package app.mimi.player;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.media3.common.*;
import androidx.media3.session.*;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

public final class MainActivity extends ComponentActivity {
    private WebView web;
    private FrameLayout frame;
    private LibraryStore store;
    private ListenableFuture<MediaController> connection;
    private MediaController controller;
    private android.media.SoundPool feedback;
    private int tapSound,openSound;
    private final java.util.Set<Integer> loadedSounds=new java.util.HashSet<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService authentication=Executors.newSingleThreadExecutor();
    private boolean ready=false,visible=false,importing=false;
    private String importMode="project";
    private String lastState="",lastCompletedJobs="";
    private long jobPolledAt=0,libraryRevision=0,jobsRevision=-1;
    private HiddenCredentials credentials;
    private HiddenAccess access;
    private String challenge = "", moveProject = "";
    private boolean pinBusy, obscured;
    private CancellationSignal biometric;
    private ExportRequest export;
    private Uri exportDestination;
    private HiddenAccess.Ticket archiveTicket;
    private HiddenAccess.Ticket importTaskTicket;
    private String archiveProject = "";
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                export = null;
                exportDestination = null;
                lockHidden(false);
            }
        }
    };
    private static final class ExportRequest {
        final File file;
        final String projectId;
        final boolean hidden;
        ExportRequest(File file, String projectId, boolean hidden) {
            this.file = file;
            this.projectId = projectId;
            this.hidden = hidden;
        }
    }
    private Uri pendingArchive;
    private CancellationSignal importCancellation;
    private final Runnable poll=new Runnable(){public void run(){
        if(visible&&ready){
            if (libraryRevision != store.contentRevision()) {
                libraryRevision = store.contentRevision();
                PlaybackService service = PlaybackService.instance;
                if (service != null) {
                    JSONObject current = service.state();
                    if (!current.optString("trackId").isEmpty() && store.track(current.optString("projectId"), current.optString("trackId")) == null) {
                        service.player.stop();
                        service.player.clearMediaItems();
                    } else service.syncPrivacy();
                }
                sendLibrary();
            }
            PlaybackService service = PlaybackService.instance;
            if (service != null) {
                JSONObject state = service.state(access.ticket().hidden);
                if (!state.toString().equals(lastState)) {
                    lastState = state.toString();
                    send("state", state);
                }
            }
            if (SystemClock.elapsedRealtime() - jobPolledAt >= 1000) {
                jobPolledAt = SystemClock.elapsedRealtime();
                SubtitleJobs jobs = SubtitleJobs.get(MainActivity.this);
                long revision = jobs.revision();
                if (revision != jobsRevision) {
                    jobsRevision = revision;
                    JSONObject snapshot = jobs.snapshot(access.ticket().hidden);
                    send("jobs", snapshot);
                    StringBuilder completed = new StringBuilder();
                    JSONArray list = snapshot.optJSONArray("jobs");
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject job = list.optJSONObject(i);
                        if ("done".equals(job.optString("status"))) completed.append(job.optString("id")).append(':').append(job.optLong("updated")).append(';');
                    }
                    if (!lastCompletedJobs.equals(completed.toString())) {
                        lastCompletedJobs = completed.toString();
                        sendLibrary();
                    }
                }
            }
        }main.postDelayed(this,visible?250:1000);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);store=LibraryStore.get(this);access=new HiddenAccess(store);credentials=HiddenCredentials.get(this);
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        feedback=new android.media.SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        feedback.setOnLoadCompleteListener((pool,id,status)->{if(status==0)loadedSounds.add(id);});
        tapSound=feedback.load(this,R.raw.sticker_tap,1);openSound=feedback.load(this,R.raw.sticker_open,1);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.rgb(238,237,243));
        web=new WebView(this);frame.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(frame);
        frame.setOnApplyWindowInsetsListener((v,insets)->{
            int l,t,r,b;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());l=bars.left;t=bars.top;r=bars.right;b=bars.bottom;}
            else {l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();r=insets.getSystemWindowInsetRight();b=insets.getSystemWindowInsetBottom();}
            v.setPadding(l,t,r,b);return Build.VERSION.SDK_INT>=30?WindowInsets.CONSUMED:insets.consumeSystemWindowInsets();
        });
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setTextZoom(Math.round(getResources().getConfiguration().fontScale*100));settings.setMediaPlaybackRequiresUserGesture(true);
        web.setBackgroundColor(Color.rgb(238,237,243));web.setSoundEffectsEnabled(false);web.addJavascriptInterface(new Bridge(),"Native");
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();if(!"appassets.androidplatform.net".equals(uri.getHost())||!"https".equals(uri.getScheme()))return blocked();
                String path=uri.getPath();if(path==null||path.contains(".."))return blocked();
                try{
                    InputStream input;String mime="text/plain";
                    if (path.startsWith("/covers/")) {
                        String name = path.substring(8);
                        HiddenAccess.Ticket ticket = access.ticket();
                        if (!name.matches("[a-f0-9]{24}\\.webp") || !access.coverAllowed(ticket,
                                uri.getQueryParameter("project"), name, uri.getQueryParameter("epoch"))) return blocked();
                        input = new FileInputStream(new File(getFilesDir(), "covers/" + name));
                        if (!access.valid(ticket)) {
                            input.close();
                            return blocked();
                        }
                        return new WebResourceResponse("image/webp", null, 200, "OK",
                                java.util.Collections.singletonMap("Cache-Control", "no-store"), input);
                    }
                    else{String asset=path.equals("/")?"index.html":path.substring(1);input=getAssets().open(asset);if(asset.endsWith(".html"))mime="text/html";else if(asset.endsWith(".js"))mime="application/javascript";else if(asset.endsWith(".css"))mime="text/css";else if(asset.endsWith(".woff2"))mime="font/woff2";else if(asset.endsWith(".ttf"))mime="font/ttf";}
                    return new WebResourceResponse(mime,"UTF-8",input);
                }catch(Exception e){return blocked();}
            }
            @Override public void onPageFinished(WebView view,String url){ready=true;themeBars(store.theme());sendLibrary();send("ai-config",new AiSettings(MainActivity.this).publicConfig());sendJobs();sendState();if (!access.ticket().hidden && getIntent().getBooleanExtra("openJobs",false))send("open-jobs",true);}
        });
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){web.evaluateJavascript("window.handleBack ? window.handleBack() : false",result->{if(!"true".equals(result)){if(access.ticket().hidden)lockHidden(true);else moveTaskToBack(true);}});}});
        web.loadUrl("https://appassets.androidplatform.net/index.html");
        connection=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).buildAsync();
        connection.addListener(()->{try{controller=connection.get();if(ready)sendState();}catch(Exception e){send("error","播放服务连接失败");}},command->main.post(command));
        main.post(poll);
    }
    static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    private void sendLibrary() { send("library", store.snapshot(access.ticket().hidden)); }
    private void sendJobs() { send("jobs", SubtitleJobs.get(this).snapshot(access.ticket().hidden)); }
    private void sendState() {
        PlaybackService service = PlaybackService.instance;
        if (service != null) send("state", service.state(access.ticket().hidden));
    }

    void send(String type, Object payload) {
        if (!ready || isFinishing() || isDestroyed()) return;
        JSONObject event = LibraryStore.obj("type", type, "value", payload, "epoch", access.ticket().epoch);
        web.evaluateJavascript("window.receive(" + event + ")", null);
    }

    void result(int id, Object value, String error) {
        if (id != 0) send("reply", LibraryStore.obj("id", id, "value", value, "error", error));
    }

    private void result(int id, HiddenAccess.Ticket ticket, String projectId, Object value, Exception error) {
        if ((ticket.hidden && !visible) || !access.allows(ticket, projectId)) return;
        result(id, value, error == null ? "" : ticket.hidden ? "操作失败，请重试" : error.getMessage());
    }

    private boolean biometricAvailable() { return Build.VERSION.SDK_INT >= 30 && HiddenBiometrics.available(this); }

    private JSONObject privacyOptions() {
        boolean available = biometricAvailable();
        return LibraryStore.obj("configured", credentials.configured(), "biometricAvailable", available,
                "biometricEnabled", available && credentials.biometricEnabled(), "purpose", challenge);
    }

    private void prepareChallenge(String purpose, String projectId) {
        challenge = purpose;
        moveProject = projectId;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }

    private boolean canAuthenticate(HiddenAccess.Ticket ticket) {
        return visible && access.valid(ticket) && getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(android.app.KeyguardManager.class).isDeviceLocked();
    }

    private void finishChallenge() {
        challenge = "";
        moveProject = "";
        if (!access.ticket().hidden && !obscured) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_AUTO);
        }
    }

    private void revealPublic(HiddenAccess.Ticket ticket) {
        if (!ready || isDestroyed()) return;
        web.evaluateJavascript("window.privacyCleared && window.privacyCleared(" + ticket.epoch + ")", result -> {
            if (!"true".equals(result) || !access.valid(ticket) || isDestroyed()) return;
            web.postVisualStateCallback(ticket.epoch, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    if (!access.valid(ticket) || isDestroyed()) return;
                    obscured = false;
                    if (challenge.isEmpty()) finishChallenge();
                    web.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void changeScope(boolean hidden, String destination) {
        HiddenAccess.Ticket ticket = access.changeScope(hidden);
        lastState = "";
        lastCompletedJobs = "";
        jobsRevision = -1;
        send("privacy", LibraryStore.obj("hidden", hidden, "destination", destination));
        sendLibrary();
        sendState();
        sendJobs();
        if (!hidden && obscured) revealPublic(ticket);
    }

    private void lockHidden(boolean pause) {
        if (!access.ticket().hidden && challenge.isEmpty()) return;
        if (pause && access.ticket().hidden) {
            PlaybackService service = PlaybackService.instance;
            if (service != null) service.exitPrivatePlayback();
        }
        if (biometric != null) {
            CancellationSignal cancellation = biometric;
            biometric = null;
            cancellation.cancel();
        }
        if (archiveTicket != null && archiveTicket.hidden) {
            pendingArchive = null;
            archiveTicket = null;
            archiveProject = "";
        }
        obscured = true;
        web.setVisibility(View.INVISIBLE);
        finishChallenge();
        changeScope(false, "library");
    }

    private void changeVisibility(String projectId, boolean hidden) throws Exception {
        PlaybackService service = PlaybackService.instance;
        if (service != null && projectId.equals(service.currentProjectId())) service.remember();
        store.setHidden(projectId, hidden);
        if (service != null) {
            service.syncPrivacy();
            if (hidden && store.isHidden(service.currentProjectId()) && !service.player.getPlayWhenReady()) {
                service.player.stop();
                service.player.clearMediaItems();
            }
        }
        SubtitleJobs.get(this).visibilityChanged();
    }

    private void authenticated(int requestId) {
        String purpose = challenge, projectId = moveProject;
        try {
            if ("move".equals(purpose)) {
                access.require(access.ticket(), projectId);
                changeVisibility(projectId, true);
                obscured = true;
                web.setVisibility(View.INVISIBLE);
                finishChallenge();
                result(requestId, true, "");
                changeScope(false, "library");
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                challenge = "";
                moveProject = "";
                result(requestId, true, "");
                changeScope(true, "hidden");
                if ("export".equals(purpose) && export != null && exportDestination != null) {
                    ExportRequest request = export;
                    Uri destination = exportDestination;
                    export = null;
                    exportDestination = null;
                    exportSubtitle(request, destination);
                }
            }
        } catch (Exception error) {
            result(requestId, null, "无法完成操作，请重试");
        }
    }

    private void checkPin(String action, JSONObject payload, int requestId) throws IOException {
        HiddenAccess.Ticket ticket = access.ticket();
        boolean change = "hidden-pin-change".equals(action);
        boolean setup = "hidden-pin-setup".equals(action);
        if (!canAuthenticate(ticket) || (change ? !ticket.hidden : challenge.isEmpty() || ticket.hidden)) {
            throw new IOException(HiddenAccess.UNAVAILABLE);
        }
        if (pinBusy || biometric != null) throw new IOException("正在验证，请稍候");
        String purpose = challenge, projectId = moveProject;
        pinBusy = true;
        authentication.execute(() -> {
            Exception failure = null;
            try {
                if (setup || change) credentials.setPin(payload.optString("pin"), payload.optString("confirmation"),
                        change ? payload.optString("previous") : null);
                else credentials.verify(payload.optString("pin"));
            } catch (Exception error) { failure = error; }
            Exception outcome = failure;
            main.post(() -> {
                pinBusy = false;
                if (!canAuthenticate(ticket) || !challenge.equals(purpose) || !moveProject.equals(projectId)) return;
                if (outcome != null) {
                    result(requestId, null, outcome instanceof IOException ? outcome.getMessage() : "密码验证失败，请重试");
                } else if (change) {
                    result(requestId, privacyOptions(), "");
                } else authenticated(requestId);
            });
        });
    }

    private void showBiometric(boolean enable, JSONObject payload, int requestId) throws Exception {
        HiddenAccess.Ticket ticket = access.ticket();
        if (!canAuthenticate(ticket) || !biometricAvailable() || pinBusy || biometric != null
                || (enable ? !ticket.hidden : ticket.hidden || challenge.isEmpty() || !credentials.biometricEnabled())) {
            throw new IOException(HiddenAccess.UNAVAILABLE);
        }
        if (enable) {
            pinBusy = true;
            authentication.execute(() -> {
                Exception failure = null;
                try { credentials.verify(payload.optString("pin")); }
                catch (Exception error) { failure = error; }
                Exception outcome = failure;
                main.post(() -> {
                    pinBusy = false;
                    if (!canAuthenticate(ticket)) return;
                    if (outcome == null) biometricPrompt(ticket, true, requestId);
                    else result(requestId, null, outcome instanceof IOException ? outcome.getMessage() : "密码验证失败，请重试");
                });
            });
        } else biometricPrompt(ticket, false, requestId);
    }

    private void biometricPrompt(HiddenAccess.Ticket ticket, boolean enabling, int requestId) {
        if (Build.VERSION.SDK_INT < 30) return;
        String purpose = challenge, projectId = moveProject;
        CancellationSignal signal = new CancellationSignal();
        biometric = signal;
        try {
            HiddenBiometrics.prompt(this, enabling, signal, success -> {
                if (biometric != signal) return;
                biometric = null;
                if (!canAuthenticate(ticket) || !challenge.equals(purpose) || !moveProject.equals(projectId)) return;
                if (!success) {
                    result(requestId, null, "指纹验证未完成，请重试或使用密码");
                    return;
                }
                try {
                    if (enabling) {
                        credentials.biometricEnabled(true);
                        result(requestId, privacyOptions(), "");
                    } else authenticated(requestId);
                } catch (Exception failure) {
                    result(requestId, null, "无法启用指纹快捷解锁");
                }
            });
        } catch (Exception failure) {
            biometric = null;
            if (!enabling) try { credentials.biometricEnabled(false); } catch (Exception ignored) {}
            result(requestId, null, "指纹快捷解锁不可用，请使用密码");
        }
    }

    private boolean performPrivacy(String action, JSONObject payload, int id) throws Exception {
        HiddenAccess.Ticket ticket = access.ticket();
        switch (action) {
            case "hidden-prepare":
                if (ticket.hidden || !canAuthenticate(ticket) || !challenge.isEmpty()) throw new IOException(HiddenAccess.UNAVAILABLE);
                String projectId = payload.optString("projectId");
                if (!projectId.isEmpty()) {
                    access.require(ticket, projectId);
                    if (store.project(projectId) == null) throw new IOException(HiddenAccess.UNAVAILABLE);
                }
                prepareChallenge(projectId.isEmpty() ? "open" : "move", projectId);
                result(id, privacyOptions(), "");
                return true;
            case "hidden-cancel":
                if (pinBusy || biometric != null || ticket.hidden) throw new IOException(HiddenAccess.UNAVAILABLE);
                if ("export".equals(challenge)) {
                    export = null;
                    exportDestination = null;
                }
                finishChallenge();
                result(id, true, "");
                return true;
            case "hidden-options":
                if (!ticket.hidden) throw new IOException(HiddenAccess.UNAVAILABLE);
                result(id, privacyOptions(), "");
                return true;
            case "hidden-pin-setup":
            case "hidden-pin-unlock":
            case "hidden-pin-change":
                checkPin(action, payload, id);
                return true;
            case "hidden-biometric-unlock":
            case "hidden-biometric-enable":
                showBiometric("hidden-biometric-enable".equals(action), payload, id);
                return true;
            case "hidden-biometric-disable":
                if (!ticket.hidden) throw new IOException(HiddenAccess.UNAVAILABLE);
                credentials.biometricEnabled(false);
                result(id, privacyOptions(), "");
                return true;
            case "hidden-restore":
                if (!ticket.hidden) throw new IOException(HiddenAccess.UNAVAILABLE);
                access.require(ticket, payload.optString("projectId"));
                changeVisibility(payload.optString("projectId"), false);
                sendLibrary();
                sendState();
                sendJobs();
                result(id, true, "");
                return true;
            case "hidden-lock":
                result(id, true, "");
                if (ticket.hidden) lockHidden(true);
                return true;
            default:
                return false;
        }
    }

    final class Bridge {
        @JavascriptInterface public void request(String raw){
            if(raw.length()>2*1024*1024)return;
            main.post(()->{try{JSONObject m=new JSONObject(raw);perform(m.optString("action"),m.optJSONObject("payload")==null?new JSONObject():m.optJSONObject("payload"),m.optInt("id"));}catch(Exception e){send("error","操作失败，请重试");}});
        }
    }
    private void perform(String action,JSONObject p,int requestId){
        try{
            if (performPrivacy(action, p, requestId)) return;
            HiddenAccess.Ticket ticket = access.ticket();
            PlaybackService service=PlaybackService.instance;
            access.requireCommand(this, ticket, action, p, service == null ? "" : service.currentProjectId());
            switch(action){
                case "snapshot":result(requestId,store.snapshot(ticket.hidden),"");return;
                case "ai-config-get":result(requestId,new AiSettings(this).publicConfig(),"");return;
                case "ai-jobs":result(requestId,SubtitleJobs.get(this).snapshot(ticket.hidden),"");return;
                case "ai-config-save":
                    io.execute(()->{try{AiSettings settings=new AiSettings(this);settings.update(p);main.post(()->{send("ai-config",settings.publicConfig());result(requestId,settings.publicConfig(),"");});}catch(Exception e){main.post(()->result(requestId,null,e.getMessage()));}});return;
                case "ai-check":
                    io.execute(()->{try{JSONObject check=new OpenRouterClient(new AiSettings(this).key(),new CancellationSignal(),null).validateKey();main.post(()->result(requestId,check,""));}catch(Exception e){main.post(()->result(requestId,null,e.getMessage()));}});return;
                case "ai-start":
                    if(!ticket.hidden&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},41);
                    String startProject = p.optString("projectId");
                    io.execute(() -> {
                        try {
                            access.require(ticket, startProject);
                            SubtitleJobs jobs = SubtitleJobs.get(this);
                            Object value = p.optBoolean("project") ? LibraryStore.obj("count", jobs.enqueueProject(startProject))
                                    : jobs.enqueue(startProject, p.optString("trackId"), p.optString("mode", "auto"));
                            main.post(() -> {
                                try {
                                    SubtitleJobService.start(this);
                                    result(requestId, ticket, startProject, value, null);
                                    sendJobs();
                                } catch (Exception failure) {
                                    result(requestId, ticket, startProject, null, new IOException("无法启动后台任务，请重新打开应用后继续"));
                                }
                            });
                        } catch (Exception failure) { main.post(() -> result(requestId, ticket, startProject, null, failure)); }
                    });
                    return;
                case "ai-control":
                    SubtitleJobs.get(this).control(p.optString("jobId"),p.optString("command"));if(p.optString("command").equals("resume"))SubtitleJobService.start(this);result(requestId,SubtitleJobs.get(this).snapshot(ticket.hidden),"");return;
                case "ai-control-all":
                    if ("resume".equals(p.optString("command"))) {
                        if (SubtitleJobs.get(this).resumeAll(ticket.hidden) > 0) SubtitleJobService.start(this);
                    } else if ("pause".equals(p.optString("command"))) SubtitleJobs.get(this).pauseAll("", ticket.hidden);
                    else throw new IOException("操作无效");
                    result(requestId, SubtitleJobs.get(this).snapshot(ticket.hidden), "");
                    return;
                case "ai-draft":
                case "ai-save-draft":
                case "ai-save-beside":
                    String jobId = p.optString("jobId"), jobProject = SubtitleJobs.get(this).jobProject(jobId);
                    io.execute(() -> {
                        try {
                            access.require(ticket, jobProject);
                            SubtitleJobs jobs = SubtitleJobs.get(this);
                            Object value;
                            if ("ai-draft".equals(action)) value = jobs.draft(jobId);
                            else if ("ai-save-draft".equals(action)) {
                                jobs.saveDraft(jobId, p.getJSONArray("cues"));
                                value = true;
                            } else {
                                if (!jobProject.equals(p.optString("projectId"))) throw new IOException(HiddenAccess.UNAVAILABLE);
                                value = SidecarExporter.save(this, jobId, jobProject, p.optString("trackId"), p.optString("format", "vtt"));
                            }
                            main.post(() -> {
                                result(requestId, ticket, jobProject, value, null);
                                if ("ai-save-draft".equals(action)) { sendLibrary(); sendJobs(); }
                            });
                        } catch (Exception failure) { main.post(() -> result(requestId, ticket, jobProject, null, failure)); }
                    });
                    return;
                case "ai-export":
                    chooseSubtitleDestination(p, ticket);
                    result(requestId, true, "");
                    return;
                case "import":
                    if(importing)throw new Exception("正在导入作品");importMode=p.optString("mode","project");
                    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);startActivityForResult(intent,40);result(requestId,true,"");return;
                case "import-files":
                    if(importing)throw new Exception("正在导入作品");Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(choose,43);result(requestId,true,"");return;
                case "import-password":
                    if(pendingArchive==null)throw new Exception("请重新选择作品包");access.require(archiveTicket == null ? ticket : archiveTicket, archiveProject.isEmpty() ? "new-import" : archiveProject);scanArchive(pendingArchive,p.optString("password"),ticket,archiveProject);result(requestId,true,"");return;
                case "import-cancel":
                    if (importCancellation != null && access.valid(importTaskTicket)) importCancellation.cancel();
                    result(requestId, true, "");
                    return;
                case "convert-audio":convertAudio(p.optString("projectId"),p.optString("trackId"),ticket);result(requestId,true,"");return;
                case "restore-audio":store.restoreOriginal(p.optString("projectId"),p.optString("trackId"));reloadCurrent(p.optString("projectId"),p.optString("trackId"));break;
                case "refresh":
                    JSONObject existing=store.project(p.optString("projectId"));if(existing==null)throw new Exception("作品不存在");if(!existing.optString("archiveUri").isEmpty())scanArchive(Uri.parse(existing.optString("archiveUri")),"",ticket,p.optString("projectId"));else scan(Uri.parse(existing.optString("root")),existing.optString("importMode","project"),ticket,p.optString("projectId"));result(requestId,true,"");return;
                case "favorite":store.favorite(p.optString("projectId"));break;
                case "remove":
                    SubtitleJobs.get(this).cancelProject(p.optString("projectId"));
                    if(service!=null&&service.state().optString("projectId").equals(p.optString("projectId"))){service.remember();service.player.stop();service.player.clearMediaItems();}
                    store.remove(p.optString("projectId"));break;
                case "feedback":
                    int effect="open".equals(p.optString("kind"))?openSound:tapSound;
                    boolean quiet=(controller!=null&&controller.getPlayWhenReady())||(PlaybackService.instance!=null&&PlaybackService.instance.player.getPlayWhenReady());
                    boolean played=!quiet&&store.clickSounds()&&loadedSounds.contains(effect)&&feedback.play(effect,.55f,.55f,1,0,1)>0;
                    result(requestId,played,"");return;
                case "click-sounds":store.preference("clickSounds",p.optBoolean("value",true));break;
                case "caption-size":int captionSize=p.optInt("value",20);if(captionSize!=20&&captionSize!=22&&captionSize!=24)throw new Exception("不支持的字幕大小");store.preference("captionSize",captionSize);break;
                case "theme":store.preference("theme",p.optString("value","light"));themeBars(p.optString("value"));break;
                case "subtitle-pref":store.subtitlePref(p.optString("projectId"),p.optString("trackId"),p.optString("selected"),p.optDouble("delay",0));break;
                case "subtitles":
                    String projectId = p.optString("projectId");
                    io.execute(() -> {
                        try {
                            access.require(ticket, projectId);
                            JSONObject content = store.subtitlePayload(projectId, p.optString("trackId"), p.optString("selected"));
                            main.post(() -> result(requestId, ticket, projectId, content, null));
                        } catch (Exception failure) {
                            main.post(() -> result(requestId, ticket, projectId, null, new IOException("无法读取字幕，请刷新或重新导入文件夹")));
                        }
                    });
                    return;
                default:
                    if(controller==null||service==null)throw new Exception("播放服务正在连接，请稍后重试");
                    switch(action){
                        case "play":
                            if(!ticket.hidden&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},41);
                            service.load(p.optString("projectId"),p.optString("trackId"),p.optLong("position"),true);break;
                        case "toggle":if(controller.isPlaying()||controller.getPlayWhenReady()){controller.pause();service.remember();}else {if(controller.getPlaybackState()==Player.STATE_ENDED)controller.seekTo(0);if(controller.getPlaybackState()==Player.STATE_IDLE)controller.prepare();controller.play();}break;
                        case "seek":controller.seekTo(Math.max(0,p.optLong("position")));service.remember();break;
                        case "previous":if(controller.hasPreviousMediaItem())controller.seekToPreviousMediaItem();else controller.seekTo(0);service.remember();break;
                        case "next":if(controller.hasNextMediaItem())controller.seekToNextMediaItem();service.remember();break;
                        case "speed":float speed=(float)Math.max(0.5,Math.min(2,p.optDouble("value",1)));controller.setPlaybackSpeed(speed);store.preference("speed",speed);break;
                        case "repeat":int mode=Math.max(0,Math.min(2,p.optInt("value")));controller.setRepeatMode(mode);store.preference("repeat",mode);break;
                        case "sleep":service.sleepUntil=p.optInt("minutes")>0?System.currentTimeMillis()+Math.min(180,p.optInt("minutes"))*60000L:0;store.preference("sleepUntil",service.sleepUntil);break;
                        case "ab":if(service.abA<0)service.abA=controller.getCurrentPosition();else if(service.abB<0){if(controller.getCurrentPosition()<service.abA+500)throw new Exception("终点需晚于起点");service.abB=controller.getCurrentPosition();}else{service.abA=-1;service.abB=-1;}break;
                        default:throw new Exception("不支持的操作");
                    }
            }
            result(requestId,true,"");sendLibrary();sendState();
        }catch(Exception e){result(requestId,null,e.getMessage()==null?"操作失败":e.getMessage());}
    }
    private void themeBars(String theme){boolean dark="dark".equals(theme);int color=dark?Color.rgb(18,16,22):Color.rgb(238,237,243);frame.setBackgroundColor(color);web.setBackgroundColor(color);getWindow().setStatusBarColor(color);if(Build.VERSION.SDK_INT>=30){getWindow().getInsetsController().setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}getWindow().setNavigationBarColor(dark?Color.rgb(18,16,22):Color.rgb(238,237,243));getWindow().getDecorView().setBackgroundColor(dark?Color.rgb(18,16,22):Color.rgb(238,237,243));}
    private void publishImport() {
        PlaybackService service = PlaybackService.instance;
        if (service != null) service.syncPrivacy();
        SubtitleJobs.get(this).visibilityChanged();
        sendLibrary();
        sendJobs();
    }

    private boolean scanVisible(HiddenAccess.Ticket ticket, String projectId) {
        return access.valid(ticket) && (projectId.isEmpty() || access.allows(ticket, projectId));
    }

    private void scan(Uri uri, String mode, HiddenAccess.Ticket ticket, String projectId) {
        if (importing) { send("error", "正在导入作品"); return; }
        importing = true;
        send("scan", true);
        io.execute(() -> {
            try {
                if (!scanVisible(ticket, projectId)) return;
                int count = "single".equals(mode) ? store.importSingle(uri) : store.importTree(uri, mode);
                main.post(() -> {
                    publishImport();
                    if (scanVisible(ticket, projectId)) {
                        if (ticket.hidden) send("refreshed", true);
                        else send("imported", count);
                    }
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (scanVisible(ticket, projectId)) send("error", ticket.hidden ? "无法刷新作品，请重试" :
                            failure instanceof SecurityException ? "文件夹访问已失效，请重新导入" : failure.getMessage());
                });
            } finally { main.post(() -> { importing = false; send("scan", false); }); }
        });
    }

    private void scanArchive(Uri uri, String password, HiddenAccess.Ticket ticket, String projectId) {
        if (importing) { send("error", "正在导入作品"); return; }
        pendingArchive = uri;
        archiveTicket = ticket;
        archiveProject = projectId;
        importing = true;
        CancellationSignal signal = new CancellationSignal();
        importCancellation = signal;
        importTaskTicket = ticket;
        send("scan", true);
        io.execute(() -> {
            try {
                if (!scanVisible(ticket, projectId)) return;
                File directory = ArchiveImporter.extract(this, uri, password, signal,
                        status -> main.post(() -> { if (scanVisible(ticket, projectId)) send("archive-progress", status); }));
                signal.throwIfCanceled();
                int count = store.importLocal(directory, ArchiveImporter.displayName(this, uri).replaceFirst("\\.[^.]+$", ""), "project");
                main.post(() -> {
                    pendingArchive = null;
                    publishImport();
                    if (scanVisible(ticket, projectId)) {
                        if (ticket.hidden) send("refreshed", true);
                        else send("imported", count);
                    }
                });
            } catch (ArchiveImporter.PasswordRequired failure) {
                main.post(() -> { if (scanVisible(ticket, projectId)) send("archive-password", ArchiveImporter.displayName(this, uri)); });
            } catch (OperationCanceledException failure) {
                main.post(() -> { if (scanVisible(ticket, projectId)) send("import-cancelled", true); });
            } catch (Exception failure) {
                main.post(() -> { if (scanVisible(ticket, projectId)) send("error", ticket.hidden || failure.getMessage() == null
                        ? "作品包导入失败" : failure.getMessage()); });
            } finally {
                main.post(() -> { importing = false; importCancellation = null; send("scan", false); });
            }
        });
    }

    private void convertAudio(String pid, String tid, HiddenAccess.Ticket ticket) throws Exception {
        if (importing) throw new IOException("正在处理文件，请稍后再试");
        JSONObject track = store.track(pid, tid);
        if (track == null) throw new IOException("曲目不存在");
        if (track.optBoolean("converted")) throw new IOException("当前已使用兼容副本");
        Uri input = Uri.parse(track.optString("uri"));
        importing = true;
        CancellationSignal signal = new CancellationSignal();
        importCancellation = signal;
        importTaskTicket = ticket;
        send("convert-progress", LibraryStore.obj("title", track.optString("title"), "position", 0, "duration", track.optLong("duration")));
        io.execute(() -> {
            try {
                long[] noticeAt = {0};
                File output = CompatibleAudio.prepare(this, input, LibraryStore.audioFingerprint(track), signal, time -> {
                    long now = System.currentTimeMillis();
                    if (now - noticeAt[0] > 300) {
                        noticeAt[0] = now;
                        main.post(() -> {
                            if (access.allows(ticket, pid)) send("convert-progress", LibraryStore.obj("title", track.optString("title"),
                                    "position", time, "duration", track.optLong("clipEnd", track.optLong("duration"))));
                        });
                    }
                });
                signal.throwIfCanceled();
                JSONObject info = MediaTools.probe(this, Uri.fromFile(output));
                store.useConverted(pid, tid, output, info);
                main.post(() -> {
                    if (access.allows(ticket, pid)) { reloadCurrent(pid, tid); send("converted", true); }
                    sendLibrary();
                });
            } catch (OperationCanceledException failure) {
                main.post(() -> { if (access.allows(ticket, pid)) send("import-cancelled", true); });
            } catch (Exception failure) {
                main.post(() -> { if (access.allows(ticket, pid)) send("error", ticket.hidden || failure.getMessage() == null
                        ? "音频转换失败" : failure.getMessage()); });
            } finally {
                main.post(() -> { importing = false; importCancellation = null; });
            }
        });
    }

    private void reloadCurrent(String pid,String tid){PlaybackService service=PlaybackService.instance;if(service==null)return;JSONObject state=service.state();if(state.optString("projectId").equals(pid)&&state.optString("trackId").equals(tid))try{service.load(pid,tid,state.optLong("position"),state.optBoolean("playing"));}catch(Exception e){send("error",e.getMessage());}}
    private void chooseSubtitleDestination(JSONObject payload, HiddenAccess.Ticket ticket) throws Exception {
        if (export != null) throw new IOException("正在导出字幕");
        SubtitleJobs jobs = SubtitleJobs.get(this);
        String id = payload.optString("jobId"), format = payload.optString("format", "vtt");
        export = new ExportRequest(jobs.output(id, format), jobs.jobProject(id), ticket.hidden);
        String filename = ticket.hidden ? "MIMI 字幕." + ("lrc".equals(format) ? "lrc" : "vtt") : jobs.outputName(id, format);
        Intent choose = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("lrc".equals(format) ? "application/octet-stream" : "text/vtt").putExtra(Intent.EXTRA_TITLE, filename);
        try { startActivityForResult(choose, 42); }
        catch (Exception failure) { export = null; throw failure; }
    }

    private void exportSubtitle(ExportRequest request, Uri destination) {
        HiddenAccess.Ticket ticket = access.ticket();
        io.execute(() -> {
            try {
                synchronized (store.projectChanges()) {
                    if (request.hidden ? !access.allows(ticket, request.projectId) : store.isHidden(request.projectId)) return;
                    try (InputStream source = new FileInputStream(request.file);
                         OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                        if (output == null) throw new IOException();
                        byte[] buffer = new byte[8192];
                        int bytes;
                        while ((bytes = source.read(buffer)) != -1) output.write(buffer, 0, bytes);
                    }
                }
                main.post(() -> { if (access.allows(ticket, request.projectId)) send("exported", true); });
            } catch (Exception failure) {
                main.post(() -> { if (access.allows(ticket, request.projectId)) send("error", "导出失败，请重新选择保存位置"); });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == 42) {
            if (export == null || result != RESULT_OK || intent == null || intent.getData() == null) {
                export = null;
                exportDestination = null;
                return;
            }
            if (export.hidden && !access.ticket().hidden) exportDestination = intent.getData();
            else {
                ExportRequest entry = export;
                export = null;
                exportSubtitle(entry, intent.getData());
            }
            return;
        }
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (request == 40) {
            try {
                if ((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } else getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                scan(uri, importMode, access.ticket(), "");
            } catch (Exception failure) { send("error", "无法获得文件夹访问权限，请重新选择"); }
        } else if (request == 43) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            if (MediaFormats.archive(ArchiveImporter.displayName(this, uri))) scanArchive(uri, "", access.ticket(), "");
            else scan(uri, "single", access.ticket(), "");
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ready && intent.getBooleanExtra("openJobs", false) && !access.ticket().hidden && challenge.isEmpty()) send("open-jobs", true);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        main.removeCallbacks(poll);
        main.post(poll);
        if (ready) {
            if (obscured) revealPublic(access.ticket());
            sendLibrary();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (exportDestination != null && export != null && !access.ticket().hidden && canAuthenticate(access.ticket())) {
            prepareChallenge("export", export.projectId);
            send("hidden-export-auth", privacyOptions());
        }
    }

    @Override protected void onStop() {
        visible = false;
        lockHidden(false);
        if (PlaybackService.instance != null) PlaybackService.instance.remember();
        super.onStop();
    }

    @Override protected void onDestroy() {
        lockHidden(false);
        unregisterReceiver(screenOff);
        main.removeCallbacksAndMessages(null);
        io.shutdown();
        authentication.shutdown();
        if (connection != null) MediaController.releaseFuture(connection);
        feedback.release();
        web.removeJavascriptInterface("Native");
        web.destroy();
        super.onDestroy();
    }
}
