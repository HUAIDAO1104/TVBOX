package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.player.danmaku.DanmakuHttp;
import com.fongmi.android.tv.player.danmaku.DanmakuLoadPolicy;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import com.fongmi.android.tv.player.danmaku.FilteringBiliParser;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public abstract class PlaybackActivity extends BaseActivity implements MediaController.Listener, Player.Listener, ServiceConnection {

    private final List<Runnable> foreverObserverRemovers = new ArrayList<>();
    private ListenableFuture<MediaController> mControllerFuture;
    private MediaController mController;
    private PlaybackService mService;
    private boolean audioOnly;
    private boolean scrubbing;
    private boolean redirect;
    private boolean bound;
    private boolean stop;
    private boolean lock;
    private boolean danmakuSourceApplied;
    private Uri appliedDanmakuUri;
    private Uri resolvedDanmakuUri;
    private Uri retryDanmakuUri;
    private DanmakuDocumentCache.Ticket danmakuLoadTicket;
    private int danmakuLoadGeneration;
    private int danmakuRetryCount;
    private TextView danmakuStatusView;
    private final Runnable hideDanmakuStatus = () -> {
        if (danmakuStatusView != null) danmakuStatusView.setVisibility(View.GONE);
    };

    private final Runnable retryDanmaku = () -> {
        if (isFinishing() || isDestroyed() || retryDanmakuUri == null
                || !Objects.equals(appliedDanmakuUri, retryDanmakuUri)) return;
        Uri source = retryDanmakuUri;
        retryDanmakuUri = null;
        DanmakuDocumentCache.invalidate(source);
        startDanmakuResolution(source);
    };

    protected MediaController controller() {
        return mController;
    }

    protected PlaybackService service() {
        return mService;
    }

    protected PlayerManager player() {
        return mService.player();
    }

    protected boolean isRedirect() {
        return redirect;
    }

    protected void setRedirect(boolean redirect) {
        this.redirect = redirect;
        if (mService != null) mService.setNavigationCallback(redirect ? null : getNavigationCallback(), getPlaybackKey());
    }

    protected void updateNavigationKey() {
        if (mService != null) mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
    }

    protected boolean isAudioOnly() {
        return audioOnly;
    }

    protected void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    protected boolean isStop() {
        return stop;
    }

    protected void setStop(boolean stop) {
        this.stop = stop;
    }

    protected boolean isLock() {
        return lock;
    }

    protected void setLock(boolean lock) {
        this.lock = lock;
    }

    protected abstract PlaybackService.NavigationCallback getNavigationCallback();

    protected abstract PlayerSeekView getSeekView();

    protected abstract PlayerView getPlayerView();

    protected abstract String getPlaybackKey();

    protected boolean isOwner() {
        String key = getPlaybackKey();
        return key == null || (mService != null && key.equals(player().getKey()));
    }

    protected <T> void observeForever(LiveData<T> liveData, Observer<T> observer) {
        liveData.observeForever(observer);
        foreverObserverRemovers.add(() -> liveData.removeObserver(observer));
    }

    public boolean isDebugViewVisible() {
        return getPlayerView().isDebugViewVisible();
    }

    public void toggleDebugView() {
        getPlayerView().toggleDebugView();
    }

    public void hideDebugView() {
        getPlayerView().hideDebugView();
    }

    public void chooseOtherPlayer(CharSequence title) {
        PlayerManager player = player();
        PlayerHelper.choose(this, player.getUrl(), player.getHeaders(), player.isVod(), player.getPosition(), title);
        setRedirect(true);
    }

    protected void setSeekNextFocusDown(int id) {
        View timeBar = getSeekView().findViewById(androidx.media3.ui.R.id.exo_progress);
        if (timeBar != null) timeBar.setNextFocusDownId(id);
    }

    protected void setActionFocusBoundary(View view) {
        if (view == null) return;
        if (view.isFocusable() && view.getId() != View.NO_ID) view.setNextFocusDownId(view.getId());
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) setActionFocusBoundary(group.getChildAt(i));
    }

    protected boolean isIdle() {
        return isPlaybackState(Player.STATE_IDLE);
    }

    protected boolean isEnded() {
        return isPlaybackState(Player.STATE_ENDED);
    }

    protected boolean isBuffering() {
        return isPlaybackState(Player.STATE_BUFFERING);
    }

    protected boolean isPaused() {
        return mController != null && !isBuffering() && !isIdle();
    }

    private boolean isPlaybackState(int state) {
        return mController != null && mController.getPlaybackState() == state;
    }

    protected void onServiceConnected() {
    }

    protected void onPrepare() {
    }

    protected void onTracksChanged() {
    }

    protected void onDecodeChanged() {
    }

    protected void onMediaOptionsChanged() {
    }

    protected void onError(String msg) {
    }

    protected void onPlayingChanged(boolean isPlaying) {
    }

    protected void onStateChanged(int state) {
    }

    protected void onSizeChanged(VideoSize size) {
    }

    protected void onReclaim() {
    }

    protected long startPositionMs() {
        return C.TIME_UNSET;
    }

    protected void seekTo(long deltaMs) {
        mController.seekTo(resolveSeekPositionMs(deltaMs));
        mController.play();
    }

    private long resolveSeekPositionMs(long deltaMs) {
        PlayerManager player = player();
        long targetMs = Math.max(0, player.getPosition() + deltaMs);
        long durationMs = player.getDuration();
        return durationMs > 0 ? Math.min(targetMs, durationMs) : targetMs;
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, MediaMetadata metadata) {
        startPlayer(key, result, useParse, timeout, startPositionMs(), metadata);
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, long startPositionMs, MediaMetadata metadata) {
        if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            onError(ResUtil.getString(R.string.error_play_drm));
        } else if (result.hasMsg()) {
            onError(result.getMsg());
        } else if (result.getRealUrl().isEmpty()) {
            onError(ResUtil.getString(R.string.error_play_url));
        } else if (result.needParse() || useParse) {
            attachSurface();
            player().parse(key, result, useParse, metadata, startPositionMs);
        } else {
            attachSurface();
            player().start(PlaySpec.from(result, key, metadata), timeout, startPositionMs);
        }
    }

    private void bindPlaybackService() {
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        buildControllerAsync();
        bound = true;
    }

    private void buildControllerAsync() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        mControllerFuture = new MediaController.Builder(this, token).setListener(this).buildAsync();
        mControllerFuture.addListener(this::onControllerConnected, ContextCompat.getMainExecutor(this));
    }

    private void onControllerConnected() {
        try {
            mController = mControllerFuture.get();
            getSeekView().setPlayer(mController);
            mController.addListener(this);
            updateKeyIncrement();
        } catch (Exception ignored) {
        }
    }

    private void addSeekListener() {
        getSeekView().getTimeBar().addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(@NonNull TimeBar timeBar, long position) {
                PlaybackActivity.this.setScrubbing(true);
            }

            @Override
            public void onScrubMove(@NonNull TimeBar timeBar, long position) {
                PlaybackActivity.this.setScrubbing(true);
            }

            @Override
            public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
                PlaybackActivity.this.onScrubStop(canceled);
            }
        });
    }

    protected boolean isScrubbing() {
        return scrubbing;
    }

    protected void onScrubStop(boolean canceled) {
        if (!canceled && mController != null && mController.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) mController.play();
        setScrubbing(false);
    }

    private void setScrubbing(boolean scrubbing) {
        if (this.scrubbing == scrubbing) return;
        this.scrubbing = scrubbing;
        onScrubbingChanged(scrubbing);
    }

    protected void onScrubbingChanged(boolean scrubbing) {
    }

    private void updateKeyIncrement() {
        long durationMs = mController == null ? C.TIME_UNSET : mController.getDuration();
        long incrementMs = getKeyTimeIncrementMs(durationMs);
        TimeBar timeBar = getSeekView().getTimeBar();
        timeBar.setKeyTimeIncrement(incrementMs);
    }

    private long getKeyTimeIncrementMs(long durationMs) {
        if (durationMs > TimeUnit.HOURS.toMillis(3)) {
            return TimeUnit.MINUTES.toMillis(5);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(30)) {
            return TimeUnit.MINUTES.toMillis(1);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(15)) {
            return TimeUnit.SECONDS.toMillis(30);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(10)) {
            return TimeUnit.SECONDS.toMillis(15);
        } else {
            return TimeUnit.SECONDS.toMillis(10);
        }
    }

    private PendingIntent buildSessionIntent() {
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        Bundle extras = getIntent().getExtras();
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private boolean shouldReclaim() {
        return mService != null && !isOwner();
    }

    private void closePiP() {
        if (!isInPictureInPictureMode()) return;
        detach();
        finish();
    }

    private void attachSurface() {
        if (mService != null && getPlayerView().getPlayer() == null) getPlayerView().setPlayer(player().getPlayer());
        applyDanmaku();
    }

    private void detachSurface() {
        getPlayerView().setPlayer(null);
    }

    private void setRender() {
        getPlayerView().setRender(PlayerSetting.getRender());
        detachSurface();
        attachSurface();
    }

    private void configurePlayerView() {
        PlayerView playerView = getPlayerView();
        playerView.setRender(PlayerSetting.getRender());
        // Repository advertising may arrive as an actual fixed-bottom XML comment rather than a
        // media-source label. Register the filtered parser before attaching any danmaku source.
        playerView.getDanmakuController().registerParser(FilteringBiliParser.INSTANCE);
        playerView.setDanmakuOkHttpClient(DanmakuHttp.client());
        playerView.getDanmakuController().setListener(new DanmakuController.Listener() {
            @Override
            public void onLoadCompleted(@NonNull Uri uri, int itemCount) {
                if (!Objects.equals(uri, resolvedDanmakuUri)) return;
                if (itemCount <= 0) {
                    scheduleDanmakuRetry(null, true);
                    return;
                }
                danmakuRetryCount = 0;
                retryDanmakuUri = null;
                player().notifyDanmakuStatus(DanmakuStatus.ready(itemCount));
                // Loading and selecting a source are not the same as mounting its items in the
                // current time window. An explicit refresh here makes the first comments visible
                // after replay, surface recreation and automatic episode transitions.
                playerView.setDanmakuSource(uri);
                // Manual season selections already contain neighbouring episode URLs. Warm the
                // next document only after the current one is visible so playback traffic wins.
                if (mService != null && isOwner()) VodPlaybackMedia.prefetchNextDanmaku(player());
            }

            @Override
            public void onLoadError(@NonNull Uri uri, @NonNull java.io.IOException error) {
                if (!Objects.equals(uri, resolvedDanmakuUri)) return;
                boolean localDocument = "file".equalsIgnoreCase(uri.getScheme());
                scheduleDanmakuRetry(error, localDocument);
            }
        });
        playerView.setDanmakuEnabled(DanmakuSetting.isShow());
        playerView.setDanmakuConfig(DanmakuSetting.getConfig());
        playerView.getSubtitleView().setStyle(getCaptionStyle());
        playerView.getSubtitleView().setApplyEmbeddedStyles(true);
        playerView.getSubtitleView().setApplyEmbeddedFontSizes(false);
        if (PlayerSetting.getSubtitlePosition() != 0) playerView.getSubtitleView().setBottomPosition(PlayerSetting.getSubtitlePosition());
        if (PlayerSetting.getSubtitleTextSize() != 0) playerView.getSubtitleView().setFractionalTextSize(PlayerSetting.getSubtitleTextSize());
    }

    private CaptionStyleCompat getCaptionStyle() {
        CaptioningManager manager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        if (PlayerSetting.isCaption() && manager != null) return CaptionStyleCompat.createFromCaptionStyle(manager.getUserStyle());
        return new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
    }

    private void applyDanmaku() {
        if (mService == null || !isOwner()) return;
        applyDanmakuSource(player().getSelectedDanmakuUri());
    }

    protected final void applyDanmakuSource(Uri uri) {
        if (uri == null) {
            App.removeCallbacks(retryDanmaku);
            retryDanmakuUri = null;
            cancelDanmakuResolution();
            danmakuLoadGeneration++;
            danmakuRetryCount = 0;
            appliedDanmakuUri = null;
            resolvedDanmakuUri = null;
            danmakuSourceApplied = true;
            getPlayerView().setDanmakuSource(null);
            return;
        }
        boolean sameSource = danmakuSourceApplied && Objects.equals(appliedDanmakuUri, uri);
        if (sameSource && resolvedDanmakuUri != null) {
            // DanmakuController refreshes its current window for the same URI without another
            // parse/download. This is the required replay/resume path that the old duplicate
            // guard accidentally suppressed.
            getPlayerView().setDanmakuSource(resolvedDanmakuUri);
            return;
        }
        if (sameSource && danmakuLoadTicket != null) return;
        if (sameSource && retryDanmakuUri != null) return;
        App.removeCallbacks(retryDanmaku);
        retryDanmakuUri = null;
        cancelDanmakuResolution();
        danmakuRetryCount = 0;
        appliedDanmakuUri = uri;
        resolvedDanmakuUri = null;
        danmakuSourceApplied = true;
        getPlayerView().setDanmakuSource(null);
        startDanmakuResolution(uri);
    }

    private void startDanmakuResolution(Uri source) {
        cancelDanmakuResolution();
        int generation = ++danmakuLoadGeneration;
        resolvedDanmakuUri = null;
        // Always detach first. When a failed/empty document is downloaded again to the same local
        // path, DanmakuController otherwise sees an equal URI and only refreshes its old empty
        // item window instead of reparsing the repaired file.
        getPlayerView().setDanmakuSource(null);
        if (mService != null) player().notifyDanmakuStatus(DanmakuStatus.downloading(0));
        danmakuLoadTicket = DanmakuDocumentCache.load(source, new DanmakuDocumentCache.Listener() {
            @Override
            public void onProgress(Uri original, int percent) {
                if (generation == danmakuLoadGeneration && Objects.equals(appliedDanmakuUri, original)
                        && mService != null) player().notifyDanmakuStatus(DanmakuStatus.downloading(percent));
            }

            @Override
            public void onReady(Uri original, Uri local) {
                if (generation != danmakuLoadGeneration || isFinishing() || isDestroyed()
                        || !Objects.equals(appliedDanmakuUri, original)) return;
                danmakuLoadTicket = null;
                resolvedDanmakuUri = local;
                getPlayerView().setDanmakuSource(local);
            }

            @Override
            public void onFailure(Uri original, java.io.IOException error) {
                if (generation != danmakuLoadGeneration || isFinishing() || isDestroyed()
                        || !Objects.equals(appliedDanmakuUri, original)) return;
                danmakuLoadTicket = null;
                scheduleDanmakuRetry(error, false);
            }
        });
    }

    private void scheduleDanmakuRetry(java.io.IOException error, boolean corruptDocument) {
        boolean retry = appliedDanmakuUri != null && danmakuRetryCount < 1
                && (corruptDocument || DanmakuLoadPolicy.shouldRetry(error, danmakuRetryCount));
        if (retry) {
            danmakuRetryCount++;
            retryDanmakuUri = appliedDanmakuUri;
            App.post(retryDanmaku, 350);
            return;
        }
        if (mService != null) player().notifyDanmakuStatus(DanmakuStatus.failed(corruptDocument
                ? DanmakuStatus.Failure.EMPTY_DOCUMENT : DanmakuStatus.Failure.DOWNLOAD));
    }

    private void cancelDanmakuResolution() {
        if (danmakuLoadTicket != null) danmakuLoadTicket.cancel();
        danmakuLoadTicket = null;
    }

    private void releasePlaybackService() {
        if (mService != null) releaseService(isOwner());
        detach();
    }

    private void releaseService(boolean owner) {
        mService.removePlayerCallback(mPlayerCallback);
        if (owner) mService.setNavigationCallback(null, null);
        if (mService.hasMediaClient() || mService.hasPlayerCallback()) {
            if (owner) mService.suspend();
            mService.resetSessionActivity();
        } else if (owner) {
            mService.shutdown();
        }
    }

    private void detach() {
        releaseController();
        releaseBinding();
    }

    private void releaseController() {
        if (mControllerFuture != null) MediaController.releaseFuture(mControllerFuture);
        if (mController != null) mController.removeListener(this);
        if (mController != null) getSeekView().setPlayer(null);
        mControllerFuture = null;
        mController = null;
    }

    private void releaseBinding() {
        if (!bound) return;
        bound = false;
        if (mService != null) mService.removePlayerCallback(mPlayerCallback);
        unbindService(this);
        mService = null;
    }

    private void clearForeverObservers() {
        foreverObserverRemovers.forEach(Runnable::run);
        foreverObserverRemovers.clear();
    }

    private final PlaybackService.PlayerCallback mPlayerCallback = new PlaybackService.PlayerCallback() {

        @Override
        public void onPrepare() {
            if (isOwner()) PlaybackActivity.this.onPrepare();
        }

        @Override
        public void onTracksChanged() {
            if (isOwner()) PlaybackActivity.this.onTracksChanged();
        }

        @Override
        public void onDecodeChanged() {
            if (isOwner()) PlaybackActivity.this.onDecodeChanged();
        }

        @Override
        public void onMediaOptionsChanged() {
            if (isOwner()) PlaybackActivity.this.onMediaOptionsChanged();
        }

        @Override
        public void onError(String msg) {
            if (isOwner()) PlaybackActivity.this.onError(msg);
        }

        @Override
        public void onPlayerRebuild(Player player) {
            if (isOwner()) setRender();
        }

        @Override
        public void onDanmakuSourceChanged(Uri uri) {
            // A null URI is a global detach signal for the shared playback service. Never reject
            // it just because navigation ownership changed a few instructions earlier; otherwise
            // the renderer can keep the previous programme's comments until another match wins.
            if (uri == null || isOwner()) applyDanmakuSource(uri);
        }

        @Override
        public void onDanmakuConfigChanged(DanmakuConfig config) {
            if (isOwner()) getPlayerView().setDanmakuConfig(config);
        }

        @Override
        public void onDanmakuEnabledChanged(boolean enabled) {
            if (isOwner()) getPlayerView().setDanmakuEnabled(enabled);
        }

        @Override
        public void onDanmakuSent(String text) {
            if (isOwner()) getPlayerView().sendDanmaku(text);
        }

        @Override
        public void onDanmakuStatusChanged(DanmakuStatus status) {
            if (isOwner()) showDanmakuStatus(status);
        }
    };

    private void showDanmakuStatus(DanmakuStatus status) {
        App.removeCallbacks(hideDanmakuStatus);
        if (status == null || status.stage() == DanmakuStatus.Stage.HIDDEN) {
            if (danmakuStatusView != null) danmakuStatusView.setVisibility(View.GONE);
            return;
        }
        TextView view = ensureDanmakuStatusView();
        view.setText(danmakuStatusText(status));
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(160).start();
        if (status.stage() == DanmakuStatus.Stage.READY) App.post(hideDanmakuStatus, 2400);
        else if (status.stage() == DanmakuStatus.Stage.FAILED) App.post(hideDanmakuStatus, 8000);
    }

    private TextView ensureDanmakuStatusView() {
        if (danmakuStatusView != null) return danmakuStatusView;
        FrameLayout root = findViewById(android.R.id.content);
        TextView view = new TextView(this);
        int horizontal = ResUtil.dp2px(18);
        int vertical = ResUtil.dp2px(9);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setTextColor(Color.WHITE);
        view.setTextSize(com.fongmi.android.tv.utils.Util.isLeanback() ? 18 : 14);
        view.setFocusable(false);
        view.setClickable(false);
        view.setElevation(ResUtil.dp2px(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(190, 14, 17, 24));
        background.setCornerRadius(ResUtil.dp2px(18));
        background.setStroke(ResUtil.dp2px(1), Color.argb(90, 255, 255, 255));
        view.setBackground(background);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        params.topMargin = ResUtil.dp2px(com.fongmi.android.tv.utils.Util.isLeanback() ? 28 : 16);
        root.addView(view, params);
        danmakuStatusView = view;
        return view;
    }

    private String danmakuStatusText(DanmakuStatus status) {
        return switch (status.stage()) {
            case MATCHING -> getString(R.string.danmaku_status_matching);
            case RESTORING -> getString(R.string.danmaku_status_restoring);
            case DOWNLOADING -> status.progress() > 0
                    ? getString(R.string.danmaku_status_downloading_percent, status.progress())
                    : getString(R.string.danmaku_status_downloading);
            case READY -> getString(R.string.danmaku_status_ready, status.itemCount());
            case FAILED -> getString(R.string.danmaku_status_failed, switch (status.failure()) {
                case DISABLED -> getString(R.string.danmaku_failure_disabled);
                case NO_MATCH -> getString(R.string.danmaku_failure_no_match);
                case NETWORK -> getString(R.string.danmaku_failure_network);
                case INVALID_RESPONSE -> getString(R.string.danmaku_failure_invalid);
                case EMPTY_DOCUMENT -> getString(R.string.danmaku_failure_empty);
                default -> getString(R.string.danmaku_failure_download);
            });
            default -> "";
        };
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        configurePlayerView();
        bindPlaybackService();
        addSeekListener();
    }

    @Override
    public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) updateKeyIncrement();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!isOwner()) return;
        if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else if (!isBuffering()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        onPlayingChanged(isPlaying);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (isOwner()) onStateChanged(state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize size) {
        if (isOwner()) onSizeChanged(size);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mService = ((PlaybackService.LocalBinder) binder).getService();
        mService.replaceBinding(this::closePiP);
        mService.setSessionActivity(buildSessionIntent());
        mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
        mService.addPlayerCallback(mPlayerCallback);
        onServiceConnected();
        applyDanmaku();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mService = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRedirect(false);
        if (shouldReclaim()) {
            detachSurface();
            onReclaim();
        } else {
            attachSurface();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isRedirect() && mController != null) mController.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isOwner() && PlayerSetting.isBackgroundOff() && mController != null) mController.pause();
    }

    @Override
    protected void onDestroy() {
        App.removeCallbacks(retryDanmaku);
        App.removeCallbacks(hideDanmakuStatus);
        cancelDanmakuResolution();
        danmakuLoadGeneration++;
        clearForeverObservers();
        super.onDestroy();
        releasePlaybackService();
    }
}
