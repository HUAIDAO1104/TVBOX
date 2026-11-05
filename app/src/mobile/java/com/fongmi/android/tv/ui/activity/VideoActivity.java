package com.fongmi.android.tv.ui.activity;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.ChangeBounds;
import androidx.transition.TransitionManager;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.adapter.QualityAdapter;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.CustomKeyDown;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.CastDialog;
import com.fongmi.android.tv.ui.dialog.ChapterDialog;
import com.fongmi.android.tv.ui.dialog.ControlDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuDialog;
import com.fongmi.android.tv.ui.dialog.EditionDialog;
import com.fongmi.android.tv.ui.dialog.EpisodeListDialog;
import com.fongmi.android.tv.ui.dialog.InfoDialog;
import com.fongmi.android.tv.ui.dialog.ParseDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.playback.PlaybackOrientation;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PiP;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Timer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.List;
import java.util.Objects;

public class VideoActivity extends PlaybackActivity implements Clock.Callback, CustomKeyDown.Listener, TrackDialog.Listener, ControlDialog.Listener, ControlDialog.Host, com.fongmi.android.tv.ui.dialog.OfflineBatchDialog.Host, ParseDialog.Listener, VodPlaybackHost, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, EpisodeListDialog.Listener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, CastDialog.Listener, CastDialog.Host, InfoDialog.Listener {

    private ActivityVideoBinding mBinding;
    private ViewGroup.LayoutParams mFrameParams;
    private Observer<Result> mObserveDetail;
    private Observer<Result> mObservePlayer;
    private Observer<Result> mObserveSearch;
    private EpisodeAdapter mEpisodeAdapter;
    private QualityAdapter mQualityAdapter;
    private QuickAdapter mQuickAdapter;
    private FlagAdapter mFlagAdapter;
    private VodPlaybackController mVod;
    private VideoViewModel mViewModel;
    private ValueAnimator mAnimator;
    private CustomKeyDown mKeyDown;
    private History mHistory;
    private boolean fullscreen;
    private boolean useParse;
    private boolean rotate;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Clock mClock;
    private PiP mPiP;
    private boolean playbackReady;

    public static void push(FragmentActivity activity, String text) {
        Uri uri = UrlUtil.uri(text);
        if (FileChooser.isValid(activity, uri)) file(activity, FileChooser.getPathFromUri(uri));
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, String path) {
        if (TextUtils.isEmpty(path)) return;
        String name = new File(path).getName();
        start(activity, SiteApi.PUSH, "file://" + path, name);
    }

    public static void cast(Activity activity, History history) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic());
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        activity.startActivity(intent);
    }

    private String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    private String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    @Override
    public String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private Episode getEpisode() {
        return mEpisodeAdapter.isEmpty() ? new Episode() : mEpisodeAdapter.getActivated();
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : PlayerSetting.getScale();
    }

    private void setScale(int scale) {
        mVod.setScale(scale);
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    @Override
    public boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    @Override
    public boolean shouldAutoPlayOnDetail() {
        return true;
    }

    private boolean isAutoRotate() {
        return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    private boolean isLand() {
        return mBinding.getRoot().getTag().equals("land");
    }

    private boolean isPort() {
        return mBinding.getRoot().getTag().equals("port");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected PlayerView getPlayerView() {
        return mBinding.player;
    }

    @Override
    protected TextView playbackStatusView() { return mBinding.playbackStatus; }

    @Override
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        playbackReady = true;
        mBinding.detailFull.setEnabled(true);
        mBinding.detailDownload.setEnabled(true);
        checkLand();
        checkId();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String oldId = getId();
        String oldKey = getKey();
        super.onNewIntent(intent);
        String id = Objects.toString(intent.getStringExtra("id"), "");
        String key = Objects.toString(intent.getStringExtra("key"), "");
        // Site ids are only unique inside one repository/site.  Reusing the same id after a
        // source switch is still a different media identity and must invalidate the old player,
        // parse job and danmaku request before loading the new item.
        if (TextUtils.isEmpty(id) || id.equals(oldId) && key.equals(oldKey)) return;
        mBinding.swipeLayout.setRefreshing(true);
        saveHistory(false);
        VodPlaybackMedia.invalidate(player());
        player().stop();
        player().clear();
        getIntent().putExtras(intent);
        mVod.reset();
        setOrient();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> setStatusBar(insets));
        mKeyDown = CustomKeyDown.create(this, mBinding.player);
        mBinding.detailFull.setEnabled(false);
        mBinding.detailDownload.setEnabled(false);
        mFrameParams = mBinding.video.getLayoutParams();
        mFrameParams.height = Math.round(getResources().getDisplayMetrics().widthPixels * 9f / 16f);
        mBinding.video.setLayoutParams(mFrameParams);
        mBinding.progressLayout.showProgress();
        mBinding.swipeLayout.setEnabled(false);
        mObserveDetail = this::onDetailObserved;
        mObservePlayer = this::onPlayerObserved;
        mObserveSearch = this::onSearchObserved;
        mClock = Clock.create();
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        mR3 = this::setOrient;
        mR4 = this::showEmpty;
        mPiP = new PiP();
        checkDanmakuImg();
        setRecyclerView();
        setVideoView();
        setViewModel();
        showProgress();
        setAnimator();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        scrubPreview=new com.fongmi.android.tv.player.preview.ScrubPreview(mBinding.control.scrubPreview,mBinding.control.previewImage,mBinding.control.previewTime);
        getSeekView().getTimeBar().addListener(new androidx.media3.ui.TimeBar.OnScrubListener(){
            public void onScrubStart(androidx.media3.ui.TimeBar bar,long position){showScrubPreview(position);}
            public void onScrubMove(androidx.media3.ui.TimeBar bar,long position){showScrubPreview(position);}
            public void onScrubStop(androidx.media3.ui.TimeBar bar,long position,boolean canceled){scrubPreview.hide();mBinding.control.center.setVisibility(View.VISIBLE);}
        });
        getSeekView().setOnLongClickListener(v->{if(playbackReady)com.fongmi.android.tv.ui.dialog.PreciseSeekDialog.show(this,player().getPlayer());return true;});
        mBinding.detailCast.setOnClickListener(v -> onCast());
        mBinding.detailDownload.setOnClickListener(v -> onDownload());
        mBinding.control.download.setOnClickListener(v -> onDownload());
        mBinding.control.fullscreen.setOnClickListener(v -> { if (playbackReady) { if (isFullscreen()) exitFullscreen(); else enterFullscreen(); } });
        mBinding.contentToggle.setOnClickListener(v -> toggleSynopsis());
        mBinding.detailSettings.setOnClickListener(v -> { if (mHistory != null && playbackReady) onSetting(); });
        mBinding.detailFollow.setOnClickListener(v->followShow());
        mBinding.detailFollow.setOnLongClickListener(v->{WatchlistActivity.start(this);return true;});
        mBinding.detailKeep.setOnClickListener(v -> { if (mHistory != null) onKeep(); });
        mBinding.detailSource.setOnClickListener(v -> { if (mVod != null && mHistory != null) onChange(); });
        mBinding.detailFull.setOnClickListener(v -> { if (mVod != null && playbackReady) enterFullscreen(); });
        mBinding.more.setOnClickListener(view -> onMore());
        mBinding.actor.setOnClickListener(view -> onActor());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.reverse.setOnClickListener(view -> onReverse());
        mBinding.director.setOnClickListener(view -> onDirector());
        mBinding.name.setOnLongClickListener(view -> onChange());
        mBinding.content.setOnLongClickListener(view -> onCopy());
        mBinding.control.back.setOnClickListener(view -> onBack());
        mBinding.control.cast.setOnClickListener(view -> onCast());
        mBinding.control.info.setOnClickListener(view -> onInfo());
        mBinding.control.keep.setOnClickListener(view -> onKeep());
        mBinding.control.play.setOnClickListener(view -> checkPlay());
        mBinding.control.next.setOnClickListener(view -> checkNext());
        mBinding.control.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.setting.setOnClickListener(view -> onSetting());
        mBinding.control.title.setOnLongClickListener(view -> onChange());
        mBinding.control.right.lock.setOnClickListener(view -> onLock());
        mBinding.control.right.rotate.setOnClickListener(view -> onRotate());
        mBinding.control.danmaku.setOnClickListener(view -> { if (player().haveDanmaku()) onDanmakuShow(); else onDanmaku(); });
        mBinding.control.danmaku.setOnLongClickListener(view -> { onDanmaku(); return true; });
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.scale.setOnClickListener(view -> onSetting());
        mBinding.control.action.speed.setOnClickListener(view -> onSetting());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodes());
        mBinding.control.action.text.setOnLongClickListener(view -> onTextLong());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.control.action.getRoot().setOnTouchListener(this::onActionTouch);
        mBinding.swipeLayout.setOnRefreshListener(this::onSwipeRefresh);
    }

    private WindowInsetsCompat setStatusBar(WindowInsetsCompat insets) {
        androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        int top = isFullscreen() || isInPictureInPictureMode() ? 0 : bars.top;
        mBinding.getRoot().setPadding(0, 0, 0, isFullscreen() || isInPictureInPictureMode() ? 0 : bars.bottom);
        ViewGroup.LayoutParams lp = mBinding.statusBar.getLayoutParams();
        lp.height = top;
        mBinding.statusBar.setLayoutParams(lp);
        return WindowInsetsCompat.CONSUMED;
    }

    private void setRecyclerView() {
        mBinding.flag.setHasFixedSize(true);
        mBinding.flag.setItemAnimator(null);
        mBinding.flag.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
        mBinding.episode.setHasFixedSize(true);
        mBinding.episode.setItemAnimator(null);
        mBinding.episode.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this, ViewType.HORI));
        mBinding.quality.setHasFixedSize(true);
        mBinding.quality.setItemAnimator(null);
        mBinding.quality.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
    }

    private void setVideoView() {
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.danmaku.setVisibility(DanmakuSetting.isLoad() ? View.VISIBLE : View.GONE);
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mPiP.update(this, view));
    }

    private void setVideoView(boolean isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        } else {
            mBinding.video.setLayoutParams(mFrameParams);
        }
    }

    private void setAnimator() {
        mAnimator = new ValueAnimator();
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(animation -> {
            if (isLand() || isFullscreen() || isInPictureInPictureMode()) return;
            mFrameParams.height = (int) animation.getAnimatedValue();
            mBinding.video.setLayoutParams(mFrameParams);
        });
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(VideoViewModel.class);
        observeForever(mViewModel.getResult(), mObserveDetail);
        observeForever(mViewModel.getPlayer(), mObservePlayer);
        observeForever(mViewModel.getSearch(), mObserveSearch);
        mVod = mViewModel.createPlaybackController(this);
    }

    private void onDetailObserved(Result result) {
        if (service() == null) return;
        mVod.onDetailResult(result);
    }

    private void onPlayerObserved(Result result) {
        if (service() == null) return;
        mVod.onPlayerResult(result);
    }

    private void onSearchObserved(Result result) {
        if (service() == null) return;
        mVod.onSearchResult(result);
    }

    @Override
    public String getVodKey() {
        return getKey();
    }

    @Override
    public String getVodId() {
        return getId();
    }

    @Override
    public String getVodName() {
        String name = mBinding.name.getText().toString();
        return name.isEmpty() ? getName() : name;
    }

    @Override
    public String getVodPic() {
        return getPic();
    }

    @Override
    public String getVodMark() {
        return getMark();
    }

    @Override
    public boolean isSiteChangeable() {
        return getSite().isChangeable();
    }

    @Override
    public boolean isHostFinishing() {
        return isFinishing() || isDestroyed();
    }

    @Override
    public boolean isPlayerEmpty() {
        return player().isEmpty();
    }

    @Override
    public boolean isFullscreenForPlayback() {
        return isFullscreen();
    }

    @Override
    public long getPlayerPosition() {
        return player().getPosition();
    }

    @Override
    public void usePushId(String id) {
        getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", id);
    }

    @Override
    public void requestDetail(String key, String id) {
        mViewModel.detailContent(key, id);
    }

    @Override
    public boolean tryNextDetailSource() {
        // Ranked cross-repository candidates are only attached by the TV aggregate-search flow.
        // Mobile keeps its existing detail fallback/search behavior.
        return false;
    }

    @Override
    public void requestPlayer(VodPlayRequest request) {
        if(scrubPreview!=null)scrubPreview.hide();
        mBinding.control.center.setVisibility(View.VISIBLE);
        mBinding.control.title.setText(getString(R.string.detail_title, mBinding.name.getText(), request.getTitle()));
        mViewModel.playerContent(request.getKey(), request.getFlag(), request.getId());
        mBinding.control.title.setSelected(true);
        showProgress();
    }

    @Override
    public void requestSearch(List<Site> sites, String keyword) {
        mQuickAdapter.clear();
        mViewModel.searchContent(sites, keyword, true);
    }

    @Override
    public void prepareSource(Vod item) {
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("pic", item.getPic());
        getIntent().putExtra("id", item.getId());
        mBinding.swipeLayout.setRefreshing(true);
        mBinding.swipeLayout.setEnabled(false);
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        VodPlaybackMedia.invalidate(player());
        player().reset();
        player().stop();
        player().clear();
    }

    @Override
    public void stopPlaybackForRefresh() {
        VodPlaybackMedia.invalidate(player());
        player().stop();
        player().clear();
        mClock.setCallback(null);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        mBinding.swipeLayout.setEnabled(true);
        PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        player().replay(position);
    }

    @Override
    public void startPlayback(Result result, boolean useParse, long startPositionMs, History history, Episode episode) {
        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, VodPlaybackMedia.metadata(history, episode));
    }

    @Override
    public void loadDanmaku(Result result, History history, Episode episode, int stableEpisodeIndex, String year, String type) {
        VodPlaybackMedia.searchDanmaku(result, history, episode, stableEpisodeIndex, year, type, player());
    }

    @Override
    public void renderDetail(Vod item, History history) {
        mHistory = history;
        mBinding.swipeLayout.setRefreshing(false);
        mBinding.progressLayout.showContent();
        mBinding.name.setText(displayName(item.getName()));
        App.removeCallbacks(mR4);
        setArtwork(item.getPic());
        checkKeepImg();
        setText(item);
        if(!com.fongmi.android.tv.setting.Setting.isIncognito())watchId=com.fongmi.android.tv.watch.WatchlistStore.get(this).observe(com.fongmi.android.tv.api.config.VodConfig.getCid(),getKey(),getId(),getSite().getName(),item).id;
        else watchId=null;
        updateWatchButton();
        updateKeep();
    }

    private com.fongmi.android.tv.player.preview.ScrubPreview scrubPreview;
    private void showScrubPreview(long position){
        if(scrubPreview==null||!playbackReady||player().getCurrentMediaItem()==null)return;
        var item=player().getCurrentMediaItem();var config=item.localConfiguration;
        mBinding.control.center.setVisibility(View.INVISIBLE);
        scrubPreview.show(config==null||config.drmConfiguration!=null?null:config.uri,player().getHeaders(),config==null?null:config.mimeType,position,null,-1);
    }
    private String watchId;
    private void updateWatchButton(){
        var entry=watchId==null?null:com.fongmi.android.tv.watch.WatchlistStore.get(this).find(watchId);
        mBinding.detailFollow.setText(entry!=null&&entry.followed?"已追剧":"追剧");mBinding.detailFollow.setSelected(entry!=null&&entry.followed);
    }
    private void followShow(){
        if(watchId==null){android.widget.Toast.makeText(this,"无痕播放不保存追剧记录，请关闭无痕模式后重试",android.widget.Toast.LENGTH_LONG).show();return;}var store=com.fongmi.android.tv.watch.WatchlistStore.get(this);var entry=store.find(watchId);if(entry==null)return;
        store.follow(watchId,!entry.followed);updateWatchButton();android.widget.Toast.makeText(this,entry.followed?"已取消追剧":"已加入追剧，可在片库管理已看集数与更新提醒",android.widget.Toast.LENGTH_LONG).show();
    }
    @Override public boolean isWatched(Episode item){
        var entry=watchId==null?null:com.fongmi.android.tv.watch.WatchlistStore.get(this).find(watchId);return entry!=null&&entry.watched.contains(item.getName());
    }
    @Override public boolean onItemLongClick(Episode item){
        if(watchId==null)return false;boolean watched=isWatched(item);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(item.getName()).setItems(new String[]{watched?"标记为未看":"标记为已看"},(d,w)->{
            com.fongmi.android.tv.watch.WatchlistStore.get(this).watched(watchId,item.getName(),!watched);mEpisodeAdapter.notifyDataSetChanged();
        }).setNegativeButton("取消",null).show();return true;
    }
    private void markWatched(){
        if(watchId==null||com.fongmi.android.tv.setting.Setting.isIncognito()||mHistory==null)return;
        var store=com.fongmi.android.tv.watch.WatchlistStore.get(this);var entry=store.find(watchId);
        if(entry!=null&&!entry.watched.contains(mHistory.getVodRemarks())){store.watched(watchId,mHistory.getVodRemarks(),true);mEpisodeAdapter.notifyDataSetChanged();}
    }
    private String displayName(String value) {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return value;
        String supplied = getName();
        return supplied.isEmpty() || supplied.startsWith("http") ? "链接播放" : supplied;
    }

    @Override
    public void renderEmptyDetail() {
        showEmpty();
    }

    @Override
    public void renderFallbackName(String name) {
        mBinding.name.setText(name);
    }

    @Override
    public void renderFlags(List<Flag> items) {
        mBinding.flag.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mFlagAdapter.addAll(items);
    }

    @Override
    public void renderEpisodes(List<Episode> items) {
        setEpisodeAdapter(items);
    }

    @Override
    public void renderFlagSelection(Flag item) {
        if (item == null || item.isBlockedPlaybackSource()) return;
        if(watchId!=null&&watchId.equals(com.fongmi.android.tv.watch.WatchlistStore.id(com.fongmi.android.tv.api.config.VodConfig.getCid(),getKey(),getId())))com.fongmi.android.tv.watch.WatchlistStore.get(this).useFlag(watchId,item);
        notifyItemChanged(mBinding.flag, mFlagAdapter);
        scrollToPosition(mBinding.flag, mFlagAdapter.getPosition());
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        androidx.fragment.app.Fragment picker = getSupportFragmentManager().findFragmentByTag("episode_picker");
        if (picker instanceof EpisodeListDialog episodes) episodes.refreshCurrentEpisode();
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
        mBinding.swipeLayout.setRefreshing(false);
        mQualityAdapter.addAll(result);
        setQualityVisible(visible);
    }

    @Override
    public void renderQualityVisible(boolean visible) {
        setQualityVisible(visible);
    }

    @Override
    public void renderSources(List<Vod> items) {
        mQuickAdapter.addAll(items);
        mBinding.quick.setVisibility(mQuickAdapter.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void renderHistory(History history) {
        mHistory = history;
        mBinding.control.action.opening.setText(history.getOpening() <= 0 ? getString(R.string.play_op) : Util.timeMs(history.getOpening()));
        mBinding.control.action.ending.setText(history.getEnding() <= 0 ? getString(R.string.play_ed) : Util.timeMs(history.getEnding()));
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, history.getSpeed());
        setScale(getScale());
    }

    @Override
    public void renderUseParse(boolean useParse) {
        setUseParse(useParse);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void renderArtwork(String url) {
        setArtwork(url);
    }

    @Override
    public void renderDescription(String desc) {
        setText(mBinding.content, 0, desc);
    }

    @Override
    public void onDetailFallbackScheduled() {
        App.post(mR4, 10000);
    }

    @Override
    public void onDetailFallbackCancelled() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void onSearchStarted(String keyword) {
    }

    @Override
    public void onSearchResult() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void onSearchEmpty() {
        App.removeCallbacks(mR4);
        showError(getString(R.string.error_detail));
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
    }

    @Override
    public void showSwitchLine(Flag flag) {
        if (flag == null || flag.isBlockedPlaybackSource()) return;
        showInlinePlaybackMessage(getString(R.string.play_switch_flag, flag.getFlag()));
    }

    @Override
    public void showSwitchSource(Vod item) {
        showInlinePlaybackMessage(getString(R.string.play_switch_site, item.getSiteName()));
    }

    private void showInlinePlaybackMessage(String message) {
        mBinding.playbackStatus.setText(message);
        mBinding.playbackStatus.setVisibility(View.VISIBLE);
        // Status stays in the toolbar; it is hidden by the fullscreen/PiP layout itself.
    }

    @Override
    public void showEpisodeReady(Episode item) {
        showInlinePlaybackMessage(getString(R.string.play_ready, item.getName()));
    }

    @Override
    public void showNoNext(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_prev : R.string.error_play_next);
    }

    @Override
    public void showNoPrev(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_next : R.string.error_play_prev);
    }

    @Override
    public void finishVod() {
        finish();
    }

    private void checkId() {
        mVod.checkId();
    }

    private void checkLand() {
        if (isPort() && ResUtil.isLand(this)) enterFullscreen();
    }

    private void showEmpty() {
        showError(getString(R.string.error_detail));
        mBinding.swipeLayout.setEnabled(true);
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        setText(mBinding.site, 0, "来源 · " + displayName(getSite().getName()));
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.content, 0, item.getContent());

        setOther(mBinding.other, item);
        setContentVisible();
    }

    private void setText(TextView view, int resId, String text) {
        text = Objects.toString(text, "");
        String value = text.isEmpty() ? "" : (resId > 0 ? getString(resId, text) : text);
        view.setText(Sniffer.buildClickable(value, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (view == mBinding.content) setContentVisible();
        view.setLinkTextColor(Color.WHITE);
        CustomMovement.bind(view);
    }

    private void setContentVisible() {
        boolean hasDetails = !TextUtils.isEmpty(mBinding.content.getText()) || !TextUtils.isEmpty(mBinding.actor.getText()) || !TextUtils.isEmpty(mBinding.director.getText());
        mBinding.contentLayout.setVisibility(hasDetails ? View.VISIBLE : View.GONE);
        boolean expanded = mBinding.contentToggle.isSelected();
        mBinding.actor.setVisibility(expanded && !TextUtils.isEmpty(mBinding.actor.getText()) ? View.VISIBLE : View.GONE);
        mBinding.director.setVisibility(expanded && !TextUtils.isEmpty(mBinding.director.getText()) ? View.VISIBLE : View.GONE);
    }

    private void toggleSynopsis() {
        boolean expanded = !mBinding.contentToggle.isSelected();
        mBinding.contentToggle.setSelected(expanded);
        mBinding.contentToggle.setText(expanded ? "剧情简介 · 收起" : "剧情简介 · 展开");
        mBinding.content.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
        mBinding.actor.setMaxLines(expanded ? Integer.MAX_VALUE : 1);
        mBinding.director.setSingleLine(!expanded);
        setContentVisible();
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                FolderActivity.start(getActivity(), getKey(), result);
                ((TextView) view).setMaxLines(Integer.MAX_VALUE);
                setRedirect(true);
            }
        };
    }

    private void setOther(TextView view, Vod item) {
        StringBuilder sb = new StringBuilder();
        if (!item.getRemarks().isEmpty()) sb.append(item.getRemarks()).append(" · ");
        if (!item.getYear().isEmpty()) sb.append(item.getYear()).append(" · ");
        if (!item.getArea().isEmpty()) sb.append(item.getArea()).append(" · ");
        if (!item.getTypeName().isEmpty()) sb.append(item.getTypeName()).append(" · ");
        view.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        view.setText(sb.length() >= 3 ? sb.substring(0, sb.length() - 3) : "");
    }

    @Override
    public void onItemClick(Flag item) {
        mVod.selectFlag(item);
    }

    @Override
    public void onItemClick(Episode item) {
        if (item.isSelected()) return;
        mVod.selectEpisode(item);
    }

    @Override
    public void onItemClick(Result result) {
        mVod.selectQuality(result);
    }

    @Override
    public void onItemClick(Vod item) {
        mVod.selectSource(item);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        mBinding.control.action.episodes.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.next.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.prev.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.reverse.setVisibility(View.GONE);
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.more.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.more.setText("全部 " + items.size() + " 集");
        mEpisodeAdapter.addAll(items);
    }

    private void setQualityVisible(boolean visible) {
        mBinding.qualityText.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void onMore() {
        onEpisodes();
    }

    private void onActor() {
        mBinding.actor.setMaxLines(mBinding.actor.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onDirector() {
        mBinding.director.setMaxLines(mBinding.director.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onContent() {
        toggleSynopsis();
    }

    private void onReverse() {
        mVod.setRevSort(!mHistory.isRevSort());
        mVod.reverseEpisode(false);
    }

    private boolean onChange() {
        mVod.manualSwitchSource();
        return true;
    }

    private boolean onCopy() {
        Util.copy(mBinding.content.getText().toString());
        return true;
    }

    private void onBack() {
        if (isFullscreen()) exitFullscreen();
        else finish();
    }

    @Override public CastVideo castVideo() {
        return !playbackReady || player().isEmpty() ? null : new CastVideo(mBinding.name.getText().toString(), player().getUrl(), player().getPosition(), player().getHeaders(), player().getVideoWidth(), player().getVideoHeight());
    }
    @Override public History castHistory() { return mHistory; }

    private void onCast() {
        if (!playbackReady || mHistory == null || player().isEmpty()) {
            showInlinePlaybackMessage("视频地址准备好后即可投屏");
            return;
        }
        hideControl();
        CastDialog.create().history(mHistory).video(new CastVideo(mBinding.name.getText().toString(), player().getUrl(), player().getPosition(), player().getHeaders(), player().getVideoWidth(), player().getVideoHeight())).fm(true).show(this);
    }

    private void onInfo() {
        InfoDialog.create().title(mBinding.control.title.getText()).headers(player().getHeaders()).url(player().getUrl()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
    }

    private void checkPlay() {
        setR1Callback();
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        setR1Callback();
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        setR1Callback();
        mVod.prevEpisode(true);
    }

    @Override public ActivityVideoBinding playbackBinding() { return mBinding; }
    @Override public History playbackHistory() { return mHistory; }
    @Override public PlayerManager playbackPlayer() { return service() == null ? null : player(); }
    @Override public String playbackSiteKey() { return getKey(); }
    @Override public boolean playbackUsesParse() { return isUseParse(); }

    @Override public String offlineSite() { return getKey(); }
    @Override public String offlineVideo() { return getId(); }
    @Override public String offlinePoster() { return getPic(); }
    @Override public String offlineTitle() { return mBinding.name.getText().toString(); }
    @Override public PlayerManager offlinePlayer() { return player(); }
    @Override public Flag offlineFlag() { return mFlagAdapter == null || mFlagAdapter.isEmpty() ? null : mFlagAdapter.get(mFlagAdapter.getPosition()); }
    private void onDownload() {
        if (mHistory == null || !playbackReady) return;
        hideControl();
        if (offlineFlag() == null) com.fongmi.android.tv.offline.OfflineActions.download(this, player());
        else com.fongmi.android.tv.ui.dialog.OfflineBatchDialog.show(this);
    }

    private void onSetting() {
        ControlDialog.create().parent(mBinding).history(mHistory).siteKey(getKey()).parse(isUseParse()).player(player()).show(this);
    }

    private void onLock() {
        setLock(!isLock());
        setRequestedOrientation(getLockOrient());
        mKeyDown.setLock(isLock());
        checkLockImg();
        showControl();
    }

    private void onRotate() {
        setR1Callback();
        setRotate(!isRotate());
        setRequestedOrientation(PlaybackOrientation.getRotateOrientation(this));
    }

    private void onTrack(View view) {
        if (Integer.parseInt(view.getTag().toString()) == C.TRACK_TYPE_TEXT) com.fongmi.android.tv.ui.dialog.PlaybackAccessDialog.show(this);
        else TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl();
    }

    private void onEdition() {
        EditionDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onChapter() {
        ChapterDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onDanmaku() {
        com.fongmi.android.tv.ui.dialog.PlaybackAccessDialog.show(this);
        hideControl();
    }

    private void onDanmakuShow() {
        DanmakuSetting.putShow(!DanmakuSetting.isShow());
        checkDanmakuImg();
        syncDanmakuEnabled();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        if (mKeyDown.getScale() != 1.0f) mKeyDown.resetScale();
        else setScale(index == array.length - 1 ? 0 : ++index);
        setR1Callback();
    }

    private void onSpeed() {
        mVod.setSpeed(PlaybackAction.addSpeed(player(), mBinding.control.action.speed));
        setR1Callback();
    }

    private void onReset() {
        onRefresh();
    }

    private void onParse() {
        ParseDialog.create().siteKey(getKey()).show(this);
        hideControl();
    }

    private void onReplay() {
        mVod.replay();
    }

    private void onRefresh() {
        mVod.refresh();
    }

    private void onDecode() {
        mClock.setCallback(null);
        PlaybackAction.toggleDecode(player());
        setR1Callback();
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
        setR1Callback();
    }

    private boolean onEndingReset() {
        setR1Callback();
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
        setR1Callback();
    }

    private boolean onOpeningReset() {
        setR1Callback();
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
    }

    private void onEpisodes() {
        EpisodeListDialog.create().title(mBinding.name.getText().toString()).show(this);
    }

    @Override
    public List<Flag> getEpisodePickerFlags() {
        return mFlagAdapter.getItems();
    }

    @Override
    public boolean isEpisodePickerReversed() {
        return mHistory != null && mHistory.isRevSort();
    }

    @Override
    public void onEpisodePickerFlag(Flag item) {
        onItemClick(item);
    }

    @Override
    public void onEpisodePickerEpisode(Episode item) {
        onItemClick(item);
    }

    @Override
    public void onEpisodePickerReverse() {
        if (mHistory != null) onReverse();
    }

    @Override
    public void onEpisodePickerDismissed() {
        if (!isFinishing() && !isDestroyed() && isFullscreen()) showControl();
    }

    private void onChoose() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player(), mBinding.control.title.getText());
        hideControl();
    }

    private boolean onTextLong() {
        if (!player().haveTrack(C.TRACK_TYPE_TEXT)) return false;
        onSubtitleClick();
        return true;
    }

    private boolean onActionTouch(View v, MotionEvent e) {
        setR1Callback();
        return false;
    }

    private void onSwipeRefresh() {
        if (mBinding.progressLayout.isEmpty()) mVod.requestDetail();
        else mVod.refresh();
    }

    private void enterFullscreen() {
        if (isFullscreen()) return;
        setFullscreen(true);
        if (isLand() && !player().isPortrait()) setTransition();
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        setRequestedOrientation(PlaybackOrientation.getEnterFullscreenOrientation(player().isPortrait()));
        mBinding.control.title.setVisibility(View.VISIBLE);
        setRotate(player().isPortrait());
        mKeyDown.resetScale();
        App.post(mR3, 2000);
        hideControl();
    }

    private void exitFullscreen() {
        if (!isFullscreen()) return;
        setFullscreen(false);
        if (isLand() && !player().isPortrait()) setTransition();
        setRequestedOrientation(PlaybackOrientation.getExitFullscreenOrientation(isPort()));
        mBinding.episode.postDelayed(() -> mBinding.episode.scrollToPosition(mEpisodeAdapter.getPosition()), 100);
        mBinding.control.title.setVisibility(View.INVISIBLE);
        mBinding.video.setLayoutParams(mFrameParams);
        mKeyDown.resetScale();
        App.post(mR3, 2000);
        setRotate(false);
        hideControl();
    }

    private void setTransition() {
        ChangeBounds transition = new ChangeBounds();
        transition.setDuration(150);
        ViewGroup parent = (ViewGroup) mBinding.video.getParent();
        TransitionManager.beginDelayedTransition(parent, transition);
    }

    private int getLockOrient() {
        return PlaybackOrientation.getLockOrientation(this, isLock(), isRotate(), !isFullscreen() && isPort() && isAutoRotate());
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR2, 0);
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.error.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.error.setText("");
    }

    private void syncDanmakuEnabled() {
        player().setDanmakuEnabled(DanmakuSetting.isShow());
        // Leaving PiP must restore the view even when the persisted preference and the
        // player's logical setting did not change while the overlay was temporarily hidden.
        getPlayerView().setDanmakuEnabled(player().isDanmakuEnabled());
    }

    private void hideDanmaku() {
        // PiP hides a presentation layer; it must not save a user preference. The old call
        // persisted false, so every later search could load successfully while staying hidden.
        getPlayerView().setDanmakuEnabled(false);
    }

    private void showControl() {
        if (service() == null || isInPictureInPictureMode()) return;
        mBinding.control.danmaku.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.setting.setVisibility(mHistory == null || isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.right.rotate.setVisibility(isFullscreen() && !isLock() ? View.VISIBLE : View.GONE);
        mBinding.control.keep.setVisibility(View.GONE);
        mBinding.control.action.getRoot().setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.action.scale.setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.download.setVisibility(isLock() || mHistory == null ? View.GONE : View.VISIBLE);
        mBinding.control.fullscreen.setContentDescription(isFullscreen() ? "退出全屏" : "全屏播放");
        mBinding.control.right.lock.setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
        mBinding.control.info.setVisibility(player().isEmpty() || !isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.cast.setVisibility(!player().isEmpty() ? View.VISIBLE : View.GONE);
        mBinding.control.center.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
    }

    private void hideSheet() {
        getSupportFragmentManager().getFragments().stream().filter(fragment -> fragment instanceof BottomSheetDialogFragment).map(fragment -> (BottomSheetDialogFragment) fragment).forEach(BottomSheetDialogFragment::dismiss);
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private void setOrient() {
        if (isFullscreen()) {
            if (!isLock()) setRequestedOrientation(PlaybackOrientation.getEnterFullscreenOrientation(isRotate()));
            return;
        }
        if (isPort() && isAutoRotate()) setRequestedOrientation(PlaybackOrientation.getPortAutoRotateOrientation());
        if (isLand() && isAutoRotate()) setRequestedOrientation(PlaybackOrientation.getLandAutoRotateOrientation());
    }

    private void setR1Callback() {
        if (isScrubbing()) return;
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onScrubbingChanged(boolean scrubbing) {
        if (scrubbing) App.removeCallbacks(mR1);
        else if (isVisible(mBinding.control.getRoot())) setR1Callback();
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        ImgUtil.load(this, mHistory.getVodPic(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.player.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.player.setDefaultArtwork(errorDrawable);
            }
        });
    }

    private void saveHistory(boolean exit) {
        boolean owner = service() != null && isOwner();
        long position = owner ? player().getPosition() : C.TIME_UNSET;
        long duration = owner ? player().getDuration() : C.TIME_UNSET;
        if (mVod != null) mVod.saveHistory(exit, System.currentTimeMillis(), position, duration);
    }

    private void syncHistory() {
        if (mVod != null) mVod.syncHistory();
    }

    private void checkControl() {
        if (isVisible(mBinding.control.getRoot())) showControl();
    }

    private void checkKeepImg() {
        boolean kept = Keep.find(getHistoryKey()) != null;
        mBinding.control.keep.setImageResource(kept ? R.drawable.ic_control_keep_on : R.drawable.ic_control_keep_off);
        mBinding.detailKeep.setText(kept ? "已收藏" : "收藏");
        mBinding.detailKeep.setSelected(kept);
        mBinding.detailKeep.setIconTintResource(kept ? R.color.mobile_accent : R.color.mobile_muted);
    }

    private void checkLockImg() {
        mBinding.control.right.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }

    private void checkDanmakuImg() {
        mBinding.control.danmaku.setImageResource(DanmakuSetting.isShow() ? R.drawable.ic_control_danmaku_on : R.drawable.ic_control_danmaku_off);
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(mHistory.getVodPic());
        keep.setVodName(mHistory.getVodName());
        keep.setSiteName(getSite().getName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    private void updateKeep() {
        Keep keep = Keep.find(getHistoryKey());
        if (keep != null) {
            keep.setVodName(mHistory.getVodName());
            keep.setVodPic(mHistory.getVodPic());
            keep.save();
        }
    }

    private void updateVod(Vod item) {
        boolean id = !item.getId().isEmpty();
        boolean pic = !item.getPic().isEmpty();
        boolean name = !item.getName().isEmpty();
        if (id) getIntent().putExtra("id", item.getId());
        if (id) mHistory.replace(getHistoryKey());
        if (name) mHistory.setVodName(item.getName());
        if (name) mBinding.name.setText(item.getName());
        if (name) mBinding.control.title.setText(item.getName());
        mVod.mergeFlags(item.getFlags());
        if (pic) setArtwork(item.getPic());
        if (pic || name) setMetadata();
        if (pic || name) syncHistory();
        if (pic || name) updateKeep();
        if (id) updateNavigationKey();
        setText(item);
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }

        @Override
        public void onAudio() {
            moveTaskToBack(true);
            setAudioOnly(true);
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        setPlaybackMode();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onMediaOptionsChanged() {
        setMediaOptionVisible();
    }

    @Override
    protected void onError(String msg) {
        mVod.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        mVod.reclaim(player().getPosition());
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                mClock.setCallback(null);
                break;
            case Player.STATE_READY:
                hideProgress();
                checkControl();
                player().reset();
                mClock.setCallback(this);
                break;
            case Player.STATE_ENDED:
                markWatched();
                hideProgress();
                setR1Callback();
                mVod.playbackEnded();
                mClock.setCallback(null);
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            mPiP.update(this, true);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_pause);
        } else if (isPaused()) {
            mPiP.update(this, false);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_play);
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        changeHeight();
        checkOrientation();
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.player.getSubtitleView()).player(player()).show(this);
        hideControl();
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner() || !player().isVod()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position < 0 || duration <= 0) return;
        if(position>=duration*0.9)markWatched();
        mVod.onTimeChanged(time, position, duration);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (isRedirect()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) mVod.requestDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) mVod.refresh();
        else if (event.getType() == RefreshEvent.Type.VOD) updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) VodPlaybackMedia.applyExternalDanmaku(player(), Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    private void checkOrientation() {
        if (isFullscreen() && !isRotate() && player().isPortrait()) {
            setRequestedOrientation(PlaybackOrientation.getPortraitVideoSizeOrientation());
            setRotate(true);
        } else if (isFullscreen() && isRotate() && player().isLandscape()) {
            setRequestedOrientation(PlaybackOrientation.getLandscapeVideoSizeOrientation());
            setRotate(false);
        }
    }

    private void changeHeight() {
        if (isFullscreen() || isInPictureInPictureMode()) return;
        mFrameParams.height = Math.round(mBinding.getRoot().getWidth() * 9f / 16f);
        mBinding.video.setLayoutParams(mFrameParams);
    }

    private void setTrackVisible() {
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
    }

    private void setMediaOptionVisible() {
        PlaybackAction.setMediaOptions(player(), mBinding.control.action.edition, mBinding.control.action.chapter);
    }

    private MediaMetadata buildMetadata() {
        return VodPlaybackMedia.metadata(mHistory, getEpisode());
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        Util.toggleFullscreen(this, this.fullscreen = fullscreen);
        mBinding.detailActions.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        mBinding.swipeLayout.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        ViewCompat.requestApplyInsets(mBinding.getRoot());
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
        if (fullscreen && !rotate) setPadding(mBinding.control.getRoot());
        else noPadding(mBinding.control.getRoot());
    }

    private void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyItemRangeChanged(0, adapter.getItemCount()));
    }

    private void scrollToPosition(RecyclerView view, int position) {
        view.post(() -> view.scrollToPosition(position));
    }

    @Override
    public void onCasted() {
        player().stop();
    }

    @Override
    public void onScale(int tag) {
        mKeyDown.resetScale();
        setScale(tag);
    }

    @Override
    public void onParse(Parse item) {
        mVod.selectParse(item);
    }

    @Override
    public void onSpeedUp() {
        if (!player().isPlaying()) return;
        mBinding.widget.speed.setVisibility(View.VISIBLE);
        mBinding.widget.speed.startAnimation(ResUtil.getAnim(R.anim.forward));
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, PlayerSetting.getSpeed());
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.speed.clearAnimation();
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, mHistory.getSpeed());
    }

    @Override
    public void onBright(int progress) {
        mBinding.widget.bright.setVisibility(View.VISIBLE);
        mBinding.widget.brightProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_low);
        else if (progress < 70) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_medium);
        else mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_high);
    }

    @Override
    public void onVolume(int progress) {
        mBinding.widget.volume.setVisibility(View.VISIBLE);
        mBinding.widget.volumeProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_low);
        else if (progress < 70) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_medium);
        else mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_high);
    }

    @Override
    public void onFlingUp() {
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else mVod.nextEpisode(true);
    }

    @Override
    public void onFlingDown() {
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else mVod.prevEpisode(true);
    }

    @Override
    public void onSeeking(long time) {
        showScrubPreview(Math.max(0,Math.min(player().getDuration(),player().getPosition()+time)));
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        mBinding.widget.time.setText(player().getPositionTime(time));
        mBinding.widget.seek.setVisibility(View.VISIBLE);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if(scrubPreview!=null)scrubPreview.hide();
        mBinding.control.center.setVisibility(View.VISIBLE);
        seekTo(time);
    }

    @Override
    public void onSingleTap() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    @Override
    public void onDoubleTap() {
        if (isLock()) return;
        if (!isFullscreen()) {
            enterFullscreen();
        } else if (player().isPlaying()) {
            showControl();
            onPaused();
        } else {
            hideControl();
            onPlay();
        }
    }

    @Override
    public void onTouchEnd() {
        if(scrubPreview!=null)scrubPreview.hide();
        mBinding.control.center.setVisibility(View.VISIBLE);
        mBinding.widget.seek.setVisibility(View.GONE);
        mBinding.widget.speed.setVisibility(View.GONE);
        mBinding.widget.bright.setVisibility(View.GONE);
        mBinding.widget.volume.setVisibility(View.GONE);
    }

    @Override
    public void onShare(CharSequence title) {
        PlayerHelper.share(this, player().getUrl(), player().getHeaders(), title);
        setRedirect(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlayerHelper.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isRedirect()) return;
        if (isLock()) App.post(this::onLock, 500);
        if (service() != null && player().haveTrack(C.TRACK_TYPE_VIDEO)) mPiP.enter(this, player().getVideoWidth(), player().getVideoHeight(), getScale());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (!isFullscreen()) setVideoView(isInPictureInPictureMode);
        mBinding.detailActions.setVisibility(isInPictureInPictureMode || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.swipeLayout.setVisibility(isInPictureInPictureMode || isFullscreen() ? View.GONE : View.VISIBLE);
        ViewCompat.requestApplyInsets(mBinding.getRoot());
        if (isInPictureInPictureMode) {
            hideControl();
            hideDanmaku();
            hideSheet();
        } else {
            syncDanmakuEnabled();
            if (isStop()) finish();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isAutoRotate() && isPort() && newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && !isRotate() && !isLock()) exitFullscreen();
        if (isAutoRotate() && isPort() && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) enterFullscreen();
        if (isFullscreen()) Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isFullscreen() && hasFocus) Util.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
        setAudioOnly(false);
        setStop(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
        if (!isAudioOnly()) setStop(true);
    }

    @Override
    protected void onBackInvoked() {
        if (isFullscreen() && !isLock()) {
            exitFullscreen();
        } else if (!isLock()) {
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if(scrubPreview!=null)scrubPreview.close();
        if (mClock != null) mClock.release();
        saveHistory(true);
        Timer.get().reset();
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4);
        super.onDestroy();
    }
}
