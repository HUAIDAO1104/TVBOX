package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
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
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.repository.RepositorySiteKey;
import com.fongmi.android.tv.repository.RepositorySiteRegistry;
import com.fongmi.android.tv.repository.RepositorySiteResolver;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.adapter.ArrayAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.adapter.PartAdapter;
import com.fongmi.android.tv.ui.adapter.QualityAdapter;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.fongmi.android.tv.ui.detail.DetailTitlePolicy;
import com.fongmi.android.tv.ui.detail.EpisodeDisplayName;
import com.fongmi.android.tv.ui.dialog.ChapterDialog;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuSettingDialog;
import com.fongmi.android.tv.ui.dialog.EditionDialog;
import com.fongmi.android.tv.ui.dialog.ParseDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.PlaybackOverlayPolicy;
import com.fongmi.android.tv.playback.vod.DetailFocusPolicy;
import com.fongmi.android.tv.playback.vod.DetailSourceFallbackPolicy;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PartUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Future;

public class VideoActivity extends PlaybackActivity implements VodPlaybackHost, CustomKeyDownVod.Listener, TrackDialog.Listener, ParseDialog.Listener, ArrayAdapter.OnClickListener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, Clock.Callback {

    private static final String EXTRA_START_MODE = "start_mode";
    private static final String MODE_DETAIL = "DETAIL";
    private static final String MODE_PLAY_NOW = "PLAY_NOW";
    private static final int REQUEST_CLOUD_LOGIN = 2031;
    private static final String EXTRA_REPOSITORY_CANDIDATES = "repository_candidates";
    private static final String STATE_DETAIL_SCROLL = "detail_scroll";
    private static final String STATE_DETAIL_FOCUS = "detail_focus";
    private static final String STATE_DETAIL_LIST = "detail_list";
    private static final String STATE_DETAIL_POSITION = "detail_position";
    private static final String STATE_FLAG_POSITION = "flag_position";
    private static final String STATE_EPISODE_POSITION = "episode_position";

    private enum PlaybackErrorType { SOURCE, PARSE, NETWORK, CREDENTIAL, CREDENTIAL_EXPIRED, FORMAT, PLAYER, SUBTITLE, UNKNOWN }

    private ActivityVideoBinding mBinding;
    private ViewGroup.LayoutParams mFrameParams;
    private Observer<Result> mObserveDetail;
    private Observer<Result> mObservePlayer;
    private Observer<Result> mObserveSearch;
    private EpisodeAdapter mEpisodeAdapter;
    private QualityAdapter mQualityAdapter;
    private ArrayAdapter mArrayAdapter;
    private QuickAdapter mQuickAdapter;
    private FlagAdapter mFlagAdapter;
    private PartAdapter mPartAdapter;
    private VodPlaybackController mVod;
    private CustomKeyDownVod mKeyDown;
    private VideoViewModel mViewModel;
    private History mHistory;
    private boolean fullscreen;
    private boolean useParse;
    private boolean mInitialDetailFocusApplied;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Runnable mR5;
    private Runnable mR6;
    private Clock mClock;
    private View mFocus1;
    private View mFocus2;
    private String mDetailPosterUrl = "";
    private String mCurrentSourceName = "";
    private PlaybackErrorType mPlaybackErrorType = PlaybackErrorType.UNKNOWN;
    private int mFallbackAttempt;
    private boolean mFallbackActive;
    private boolean mBuffering;
    private boolean mRestoreDetailPending;
    private int mDetailScrollY;
    private int mDetailFocusId = View.NO_ID;
    private int mDetailListId = View.NO_ID;
    private int mDetailListPosition = RecyclerView.NO_POSITION;
    private int mFlagPosition = RecyclerView.NO_POSITION;
    private int mEpisodePosition = RecyclerView.NO_POSITION;
    private int mConsumedDirectionalKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private ArrayList<Vod> mDetailCandidates = new ArrayList<>();
    private DetailSourceFallbackPolicy mDetailSourceFallback = new DetailSourceFallbackPolicy(0, 0);
    private Future<?> mRepositorySiteResolve;
    private String mRepositoryResolveKey = "";

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
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic(), null, false, true);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        // Search results are an information-discovery flow: open the detail
        // stage first and let the user explicitly start playback.
        start(activity, key, id, name, pic, null, true, false, MODE_DETAIL);
    }

    public static void collect(Activity activity, Site site, String id, String name, String pic) {
        if (site == null) {
            collect(activity, "", id, name, pic);
            return;
        }
        start(activity, site.getKey(), id, name, pic, null, true, false, MODE_DETAIL, site);
    }

    public static void collect(Activity activity, ArrayList<Vod> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        Vod selected = candidates.get(0);
        Site site = selected.getSite();
        if (site == null) {
            collect(activity, selected.getSiteKey(), selected.getId(), selected.getName(), selected.getPic());
            return;
        }
        start(activity, site.getKey(), selected.getId(), selected.getName(), selected.getPic(), null,
                true, false, MODE_DETAIL, site, candidates);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        playNow(activity, key, id, name, pic);
    }

    public static void detail(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, false, false, MODE_DETAIL);
    }

    public static void playNow(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, false, false, MODE_PLAY_NOW);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast) {
        start(activity, key, id, name, pic, mark, collect, cast, MODE_PLAY_NOW);
    }

    private static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast, String startMode) {
        start(activity, key, id, name, pic, mark, collect, cast, startMode, null);
    }

    private static void start(Activity activity, String key, String id, String name, String pic, String mark,
                              boolean collect, boolean cast, String startMode, Site scopedSite) {
        start(activity, key, id, name, pic, mark, collect, cast, startMode, scopedSite, null);
    }

    private static void start(Activity activity, String key, String id, String name, String pic, String mark,
                              boolean collect, boolean cast, String startMode, Site scopedSite,
                              ArrayList<Vod> candidates) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("cast", cast);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        intent.putExtra(EXTRA_START_MODE, startMode);
        if (scopedSite != null) intent.putExtra("repository_site", scopedSite);
        if (candidates != null && !candidates.isEmpty()) {
            intent.putParcelableArrayListExtra(EXTRA_REPOSITORY_CANDIDATES, candidates);
        }
        activity.startActivity(intent);
    }

    private boolean isCast() {
        return getIntent().getBooleanExtra("cast", false);
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
        return mEpisodeAdapter.getActivated();
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
        return !MODE_DETAIL.equals(getIntent().getStringExtra(EXTRA_START_MODE));
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
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        // PlaybackActivity binds asynchronously.  Slow Android 9 TV devices can reach
        // VideoActivity.initView() before the service exists, so reconcile player-backed UI only
        // after the binder is ready.
        updateDanmakuAction();
        updatePlaybackControlAction();
        checkId();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String oldId = getId();
        String oldKey = getKey();
        super.onNewIntent(intent);
        String id = Objects.toString(intent.getStringExtra("id"), "");
        String key = Objects.toString(intent.getStringExtra("key"), "");
        if (TextUtils.isEmpty(id) || id.equals(oldId) && key.equals(oldKey)) return;
        cancelRepositorySiteResolve();
        saveHistory(false);
        getIntent().putExtras(intent);
        configureRepositoryCandidates(getIntent());
        mVod.reset();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        configureRepositoryCandidates(getIntent());
        mFrameParams = mBinding.video.getLayoutParams();
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownVod.create(this);
        mObserveDetail = this::onDetailObserved;
        mObservePlayer = this::onPlayerObserved;
        mObserveSearch = this::onSearchObserved;
        mR1 = this::hideControl;
        mR2 = this::updateFocus;
        mR3 = this::setTraffic;
        mR4 = this::showEmpty;
        mR5 = () -> {
            if (mBuffering) showBufferingProgress();
        };
        mR6 = this::showLongBufferingActions;
        restoreDetailState(savedInstanceState);
        setRecyclerView();
        setVideoView();
        setViewModel();
        checkCast();
    }

    private void configureRepositoryCandidates(Intent intent) {
        Site scopedSite = intent.getParcelableExtra("repository_site");
        if (scopedSite != null) RepositorySiteRegistry.register(scopedSite);
        ArrayList<Vod> candidates = intent.getParcelableArrayListExtra(EXTRA_REPOSITORY_CANDIDATES);
        mDetailCandidates = candidates == null ? new ArrayList<>() : candidates;
        for (Vod candidate : mDetailCandidates) {
            if (candidate.getSite() != null) RepositorySiteRegistry.register(candidate.getSite());
        }
        mDetailSourceFallback = new DetailSourceFallbackPolicy(mDetailCandidates.size(), findDetailCandidate());
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.back.setOnClickListener(view -> onBackInvoked());
        mBinding.primary.setOnClickListener(view -> onVideo());
        mBinding.episodeAction.setOnClickListener(view -> focusEpisodes());
        mBinding.keep.setOnClickListener(view -> onKeep());
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.change.setOnClickListener(view -> onChange());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.widget.errorRetry.setOnClickListener(view -> retryPlayback());
        mBinding.widget.errorSource.setOnClickListener(view -> switchPlaybackSource());
        mBinding.widget.errorLogin.setOnClickListener(view -> CloudAccountActivity.startForResult(this, REQUEST_CLOUD_LOGIN));
        mBinding.widget.errorContinue.setOnClickListener(view -> continueWithoutSubtitle());
        mBinding.widget.errorBack.setOnClickListener(view -> returnToDetail());
        mBinding.progress.fallbackCancel.setOnClickListener(view -> cancelFallback(false));
        mBinding.progress.fallbackBack.setOnClickListener(view -> cancelFallback(true));
        mBinding.progress.bufferRetry.setOnClickListener(view -> retryPlayback());
        mBinding.progress.bufferSource.setOnClickListener(view -> switchPlaybackSource());
        mBinding.control.action.playPause.setOnClickListener(this::togglePlaybackFromControl);
        mBinding.control.action.rewind.setOnClickListener(view -> seekFromControl(view, false));
        mBinding.control.action.forward.setOnClickListener(view -> seekFromControl(view, true));
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.speed.setUpListener(this::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(this::onSpeedSub);
        mBinding.control.action.ending.setUpListener(this::onEndingAdd);
        mBinding.control.action.ending.setDownListener(this::onEndingSub);
        mBinding.control.action.opening.setUpListener(this::onOpeningAdd);
        mBinding.control.action.opening.setDownListener(this::onOpeningSub);
        mBinding.control.action.text.setUpListener(this::onSubtitleClick);
        mBinding.control.action.text.setDownListener(this::onSubtitleClick);
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.danmaku.setOnClickListener(this::onDanmakuToggle);
        mBinding.control.action.danmakuSetting.setOnClickListener(view -> onDanmakuSetting());
        mBinding.control.action.danmaku.setOnLongClickListener(view -> {
            onDanmakuSetting();
            return true;
        });
        mBinding.control.action.danmaku.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_MENU || event.getAction() != KeyEvent.ACTION_UP) return false;
            onDanmakuSource();
            return true;
        });
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.flag.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mFlagAdapter.getItemCount() > 0) onItemClick(mFlagAdapter.get(position));
                // Binding a source selects its row programmatically. Scrolling the outer detail
                // page for that non-focused selection clipped the back button on first render.
                // Only reveal the row when the user has actually moved focus into this list.
                if (child != null && child.itemView.hasFocus()) ensureFocusVisible(child.itemView);
            }
        });
        mBinding.episode.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View child) {
                child.setOnFocusChangeListener((view, hasFocus) -> {
                    if (!hasFocus) return;
                    if (mBinding.video != mFocus1) mFocus1 = view;
                    ensureFocusVisible(view);
                });
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View child) {
                child.setOnFocusChangeListener(null);
            }
        });
        mBinding.array.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mEpisodeAdapter.getItemCount() > 20 && position > 1) mBinding.episode.scrollToPosition((position - 2) * 20);
            }
        });
        mBinding.scroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener)
                (view, scrollX, scrollY, oldScrollX, oldScrollY) -> syncPosterWithDetailScroll(scrollY));
    }

    private void setRecyclerView() {
        mBinding.flag.setHorizontalSpacing(ResUtil.dp2px(6));
        mBinding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.episode.setLayoutManager(new GridLayoutManager(this, 2));
        mBinding.episode.setItemAnimator(null);
        mBinding.episode.setNestedScrollingEnabled(false);
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this));
        mBinding.quality.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quality.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.array.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.array.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.array.setAdapter(mArrayAdapter = new ArrayAdapter(this));
        mBinding.part.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.part.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.part.setAdapter(mPartAdapter = new PartAdapter(item -> mVod.search(item, false)));
        mBinding.quick.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quick.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
    }

    private void setVideoView() {
        setSeekNextFocusDown(R.id.next);
        PlayerEngineDialog.setText(mBinding.control.action.player);
        int danmakuVisibility = DanmakuSetting.isLoad() ? View.VISIBLE : View.GONE;
        mBinding.control.action.danmaku.setVisibility(danmakuVisibility);
        mBinding.control.action.danmakuSetting.setVisibility(danmakuVisibility);
        updateDanmakuAction();
        configurePlaybackControlFocus();
        configureProgressActionFocus();
        mBinding.control.controlTitle.setText(SearchDisplayName.removeEmoji(getName()));
        mBinding.progress.stage.setText(R.string.player_v2_stage_preparing);
        mBinding.progress.title.setText(SearchDisplayName.removeEmoji(getName()));
        setupDetailBackdrop();
        bindDetailArtwork(getName(), getPic());
        updatePlaybackControlAction();
    }

    private List<View> getVisiblePlaybackControls() {
        List<View> controls = new ArrayList<>();
        addVisiblePlaybackControl(controls, mBinding.control.action.next);
        addVisiblePlaybackControl(controls, mBinding.control.action.audio);
        addVisiblePlaybackControl(controls, mBinding.control.action.danmaku);
        addVisiblePlaybackControl(controls, mBinding.control.action.danmakuSetting);
        addVisiblePlaybackControl(controls, mBinding.control.action.prev);
        addVisiblePlaybackControl(controls, mBinding.control.action.speed);
        addVisiblePlaybackControl(controls, mBinding.control.action.scale);
        addVisiblePlaybackControl(controls, mBinding.control.action.text);
        addVisiblePlaybackControl(controls, mBinding.control.action.player);
        return controls;
    }

    private void addVisiblePlaybackControl(List<View> controls, View control) {
        if (control.getVisibility() == View.VISIBLE) controls.add(control);
    }

    private void configurePlaybackControlFocus() {
        List<View> controls = getVisiblePlaybackControls();
        for (int i = 0; i < controls.size(); i++) {
            View control = controls.get(i);
            control.setNextFocusLeftId(controls.get(Math.max(0, i - 1)).getId());
            control.setNextFocusRightId(controls.get(Math.min(controls.size() - 1, i + 1)).getId());
            // There is intentionally one actionable row.  Vertical keys may be consumed by
            // CustomUpDownView for value changes, but must never escape into the detail lists.
            control.setNextFocusUpId(control.getId());
            control.setNextFocusDownId(control.getId());
        }
    }

    private boolean isPlaybackControl(View view) {
        return view != null && view.getVisibility() == View.VISIBLE && getVisiblePlaybackControls().contains(view);
    }

    private View getPlaybackControlTarget(View preferred) {
        if (isPlaybackControl(preferred)) return preferred;
        if (isPlaybackControl(mFocus2)) return mFocus2;
        return mBinding.control.action.next;
    }

    private void configureProgressActionFocus() {
        configureHorizontalFocusPair(mBinding.progress.fallbackCancel, mBinding.progress.fallbackBack);
        configureHorizontalFocusPair(mBinding.progress.bufferRetry, mBinding.progress.bufferSource);
    }

    private void configureHorizontalFocusPair(View first, View second) {
        first.setNextFocusLeftId(first.getId());
        first.setNextFocusRightId(second.getId());
        first.setNextFocusUpId(first.getId());
        first.setNextFocusDownId(first.getId());
        second.setNextFocusLeftId(first.getId());
        second.setNextFocusRightId(second.getId());
        second.setNextFocusUpId(second.getId());
        second.setNextFocusDownId(second.getId());
    }

    private boolean progressActionsVisible() {
        return isVisible(mBinding.progress.fallbackActions) || isVisible(mBinding.progress.bufferActions);
    }

    private boolean focusInsideProgressActions() {
        View focus = getCurrentFocus();
        return isDescendantOf(focus, mBinding.progress.fallbackActions)
                || isDescendantOf(focus, mBinding.progress.bufferActions);
    }

    private View getProgressActionTarget() {
        if (isVisible(mBinding.progress.fallbackActions)) return mBinding.progress.fallbackCancel;
        return mBinding.progress.bufferRetry;
    }

    private void setupDetailBackdrop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        float backdropBlur = ResUtil.dp2px(46);
        float auraBlur = ResUtil.dp2px(34);
        mBinding.detailBackdrop.setRenderEffect(RenderEffect.createBlurEffect(backdropBlur, backdropBlur, Shader.TileMode.CLAMP));
        mBinding.detailPreviewBackdrop.setRenderEffect(RenderEffect.createBlurEffect(auraBlur, auraBlur, Shader.TileMode.CLAMP));
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
        int index = mDetailSourceFallback.nextIndex();
        while (index >= 0 && index < mDetailCandidates.size()) {
            Vod candidate = mDetailCandidates.get(index);
            Site site = candidate.getSite();
            if (site != null && !candidate.getId().isEmpty()) {
                RepositorySiteRegistry.register(site);
                getIntent().putExtra("key", site.getKey());
                getIntent().putExtra("id", candidate.getId());
                getIntent().putExtra("name", candidate.getName());
                getIntent().putExtra("pic", candidate.getPic());
                getIntent().putExtra("repository_site", site);
                mVod.reset();
                prepareSource(candidate);
                setDetailTitle(candidate.getName());
                mBinding.progress.title.setText(SearchDisplayName.removeEmoji(candidate.getName()));
                // This remains a detail request. The fullscreen playback fallback overlay would
                // otherwise survive renderDetail() and keep hiding the successfully loaded page.
                mBinding.progressLayout.showProgress();
                requestDetail(site.getKey(), candidate.getId());
                return true;
            }
            index = mDetailSourceFallback.nextIndex();
        }
        return false;
    }

    private int findDetailCandidate() {
        for (int index = 0; index < mDetailCandidates.size(); index++) {
            Vod candidate = mDetailCandidates.get(index);
            if (getKey().equals(candidate.getSiteKey()) && getId().equals(candidate.getId())) return index;
        }
        return 0;
    }

    @Override
    public void requestPlayer(VodPlayRequest request) {
        if (shouldAutoPlayOnDetail() && !isFullscreen()) enterFullscreen();
        String episodeTitle = EpisodeDisplayName.format(SearchDisplayName.removeEmoji(request.getTitle()));
        String playbackTitle = SearchDisplayName.removeEmoji(
                getString(R.string.detail_title, mBinding.name.getText(), episodeTitle));
        mBinding.widget.title.setText(playbackTitle);
        mBinding.control.controlTitle.setText(playbackTitle);
        mBinding.progress.title.setText(playbackTitle);
        mViewModel.playerContent(request.getKey(), request.getFlag(), request.getId());
        mBinding.widget.title.setSelected(true);
        showProgress(getString(R.string.player_v2_stage_resolving));
    }

    @Override
    public void requestSearch(List<Site> sites, String keyword) {
        mQuickAdapter.clear();
        mBinding.quick.setVisibility(View.GONE);
        mBinding.quickTitle.setVisibility(View.GONE);
        mViewModel.searchContent(sites, keyword, true);
    }

    @Override
    public void prepareSource(Vod item) {
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("pic", item.getPic());
        getIntent().putExtra("id", item.getId());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        player().reset();
        player().stop();
        mBinding.progress.stage.setText(R.string.player_v2_stage_switching);
    }

    @Override
    public void stopPlaybackForRefresh() {
        player().stop();
        player().clear();
        mClock.setCallback(null);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        player().replay(position);
    }

    @Override
    public void startPlayback(Result result, boolean useParse, long startPositionMs, History history, Episode episode) {
        mBinding.progress.stage.setText(R.string.player_v2_stage_preparing);
        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, VodPlaybackMedia.metadata(history, episode));
    }

    @Override
    public void loadDanmaku(Result result, History history, Episode episode) {
        VodPlaybackMedia.searchDanmaku(result, history, episode, player()::setDanmaku, player()::addDanmaku);
    }

    @Override
    public void renderDetail(Vod item, History history) {
        mHistory = history;
        mBinding.progressLayout.showContent();
        setDetailTitle(item.getName());
        if (!mRestoreDetailPending && !mInitialDetailFocusApplied) focusDetailDefault();
        App.removeCallbacks(mR4);
        mDetailPosterUrl = item.getPic();
        bindDetailArtwork(item.getName(), mDetailPosterUrl);
        checkKeepImg();
        setText(item);
        updatePrimaryAction();
        updateKeep();
        restoreDetailStateWhenReady(View.NO_ID);
    }

    @Override
    public void renderEmptyDetail() {
        showEmpty();
    }

    @Override
    public void renderFallbackName(String name) {
        setDetailTitle(name);
        mBinding.control.controlTitle.setText(mBinding.name.getText());
        bindDetailArtwork(name, getPic());
    }

    @Override
    public void renderFlags(List<Flag> items) {
        boolean visible = !items.isEmpty();
        mBinding.flag.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.sourceHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        mFlagAdapter.addAll(items);
        setR2Callback();
        restoreDetailStateWhenReady(R.id.flag);
    }

    @Override
    public void renderEpisodes(List<Episode> items) {
        setEpisodeAdapter(items);
        restoreDetailStateWhenReady(R.id.episode);
        if (!mInitialDetailFocusApplied && !mRestoreDetailPending && !items.isEmpty()) {
            mInitialDetailFocusApplied = true;
            mBinding.episode.post(() -> requestEpisodeFocus(mEpisodeAdapter.getPosition()));
        }
    }

    @Override
    public void renderFlagSelection(Flag item) {
        mBinding.flag.setSelectedPosition(mFlagAdapter.indexOf(item));
        notifyItemChanged(mBinding.flag, mFlagAdapter);
        mCurrentSourceName = cleanSourceName(item.getShow());
        if (TextUtils.isEmpty(mCurrentSourceName)) mCurrentSourceName = getString(R.string.detail_v2_source_fallback, mFlagAdapter.indexOf(item) + 1);
        mBinding.control.controlStatus.setText(getString(R.string.detail_v2_current_source, mCurrentSourceName));
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        mEpisodeAdapter.refreshSelection();
        mBinding.episode.scrollToPosition(mEpisodeAdapter.getPosition());
        updatePrimaryAction();
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) mBinding.episode.scrollToPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
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
        boolean visible = !mQuickAdapter.isEmpty();
        mBinding.quick.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.quickTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void renderHistory(History history) {
        mHistory = history;
        mBinding.control.action.opening.setText(history.getOpening() <= 0 ? getString(R.string.play_op) : Util.timeMs(history.getOpening()));
        mBinding.control.action.ending.setText(history.getEnding() <= 0 ? getString(R.string.play_ed) : Util.timeMs(history.getEnding()));
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, history.getSpeed());
        setScale(getScale());
        setPartAdapter();
        updatePrimaryAction();
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
        mBinding.content.setTag(desc);
        setSummary(desc);
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
        mBinding.part.setTag(keyword);
        showFallbackProgress(getString(R.string.player_v2_fallback_search), false);
    }

    @Override
    public void onSearchResult() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void onSearchEmpty() {
        App.removeCallbacks(mR4);
        mFallbackActive = false;
        showError(getString(R.string.player_v2_error_source));
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
    }

    @Override
    public void showSwitchLine(Flag flag) {
        mCurrentSourceName = flag.getShow();
        mBinding.control.controlStatus.setText(getString(R.string.detail_v2_current_source, mCurrentSourceName));
        showFallbackProgress(cleanSourceName(mCurrentSourceName), true);
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
    }

    @Override
    public void showSwitchSource(Vod item) {
        showFallbackProgress(cleanSourceName(item.getSiteName()), true);
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
    }

    @Override
    public void showEpisodeReady(Episode item) {
        Notify.show(getString(R.string.play_ready, item.getName()));
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
        if (isFromCollect()) {
            mFallbackActive = false;
            showError(getString(R.string.player_v2_error_source));
            return;
        }
        finish();
    }

    private void checkCast() {
        if (isCast() && !isFullscreen()) enterFullscreen();
        else mBinding.progressLayout.showProgress();
    }

    private void checkId() {
        String key = getKey();
        if (RepositorySiteKey.isScoped(key) && VodConfig.get().getSite(key).isEmpty()) {
            resolveRepositorySite(key);
            return;
        }
        mVod.checkId();
    }

    private void resolveRepositorySite(String expectedKey) {
        if (mRepositorySiteResolve != null && expectedKey.equals(mRepositoryResolveKey)) return;
        cancelRepositorySiteResolve();
        mRepositoryResolveKey = expectedKey;
        mBinding.progressLayout.showProgress();
        mRepositorySiteResolve = RepositorySiteResolver.resolve(expectedKey, new RepositorySiteResolver.Callback() {
            @Override
            public void onResolved(Site site) {
                if (!expectedKey.equals(mRepositoryResolveKey)) return;
                mRepositorySiteResolve = null;
                mRepositoryResolveKey = "";
                if (isFinishing() || isDestroyed() || !expectedKey.equals(getKey())) return;
                getIntent().putExtra("repository_site", site);
                mVod.checkId();
            }

            @Override
            public void onFailure(RepositorySiteResolver.Failure failure) {
                if (!expectedKey.equals(mRepositoryResolveKey)) return;
                mRepositorySiteResolve = null;
                mRepositoryResolveKey = "";
                if (isFinishing() || isDestroyed() || !expectedKey.equals(getKey())) return;
                int message = switch (failure) {
                    case TIMEOUT -> R.string.detail_repository_resolve_timeout;
                    case NOT_FOUND, INVALID_KEY -> R.string.detail_repository_resolve_missing;
                    case LOAD_FAILED, SITE_UNAVAILABLE -> R.string.detail_repository_resolve_failed;
                };
                mBinding.progressLayout.showEmpty(getString(message));
            }
        });
    }

    private void cancelRepositorySiteResolve() {
        if (mRepositorySiteResolve != null) mRepositorySiteResolve.cancel(true);
        mRepositorySiteResolve = null;
        mRepositoryResolveKey = "";
    }

    private void showEmpty() {
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        mBinding.content.setTag(item.getContent());
        List<String> meta = new ArrayList<>();
        addMeta(meta, item.getYear());
        addMeta(meta, item.getArea());
        addMeta(meta, item.getTypeName());
        addMeta(meta, item.getRemarks());
        setText(mBinding.remark, 0, TextUtils.join(getString(R.string.detail_v2_meta_separator), meta));
        setText(mBinding.site, R.string.detail_v2_source_value, cleanSourceName(getSite().getName()));
        mBinding.row1.setVisibility(mBinding.site.getVisibility());
        mBinding.year.setVisibility(View.GONE);
        mBinding.area.setVisibility(View.GONE);
        mBinding.type.setVisibility(View.GONE);
        setText(mBinding.director, R.string.detail_director, cleanPerson(item.getDirector()));
        setText(mBinding.actor, R.string.detail_actor, cleanPerson(item.getActor()));
        setSummary(item.getContent());
    }

    private void setDetailTitle(String title) {
        String value = SearchDisplayName.removeEmoji(Objects.toString(title, "").trim());
        mBinding.name.setText(value);
        mBinding.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, DetailTitlePolicy.textSizeSp(value));
        mBinding.name.setMaxLines(DetailTitlePolicy.maxLines(value));
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text)) {
            view.setText("");
            view.setVisibility(View.GONE);
            return;
        }
        view.setText(Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(View.VISIBLE);
        view.setLinkTextColor(MDColor.YELLOW_500);
        CustomMovement.bind(view);
    }

    private void addMeta(List<String> meta, String value) {
        if (!TextUtils.isEmpty(value)) meta.add(SearchDisplayName.removeEmoji(value.trim()));
    }

    private void setSummary(String summary) {
        String clean = SearchDisplayName.removeEmoji(cleanSummary(summary));
        boolean visible = !TextUtils.isEmpty(clean);
        mBinding.summary.setText(visible ? clean : "");
        mBinding.summary.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.content.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) mBinding.content.setTag(TextUtils.isEmpty(summary) ? clean : summary);
    }

    private String cleanSourceName(String source) {
        if (TextUtils.isEmpty(source)) return "";
        String clean = SearchDisplayName.clean(source.replaceAll("(?i)[|┃].*$", ""));
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("gzh") || lower.contains("公众号") || lower.contains("免费分享") || lower.contains("扫码")) return "";
        return clean;
    }

    private String cleanPerson(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("gzh") || lower.contains("公众号") || lower.contains("免费分享") || lower.contains("扫码")
                ? "" : SearchDisplayName.removeEmoji(value.trim());
    }

    private String cleanSummary(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String[] sentences = value.replace('\n', ' ').split("(?<=[。！？!?])");
        StringBuilder clean = new StringBuilder();
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (lower.contains("gzh") || lower.contains("公众号") || lower.contains("免费分享") || lower.contains("加群") || lower.contains("扫码") || lower.contains("秒播") || lower.contains("不卡")) continue;
            if (!sentence.trim().isEmpty()) clean.append(sentence.trim());
            if (clean.length() >= 180) break;
        }
        return clean.toString().trim();
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                VodActivity.start(getActivity(), getKey(), result);
                setRedirect(true);
            }
        };
    }

    @Override
    public void onItemClick(Flag item) {
        mVod.selectFlag(item);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        boolean visible = !items.isEmpty();
        mBinding.episode.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.episodeTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        // Episode choices already occupy the lower tier; duplicating this action in the compact
        // upper metadata pane pushes sources below the first TV viewport.
        mBinding.episodeAction.setVisibility(View.GONE);
        mEpisodeAdapter.addAll(items);
        updateEpisodeGridHeight(items.size());
        setArrayAdapter(items.size());
        updatePrimaryAction();
        setR2Callback();
    }

    private void updateEpisodeGridHeight(int itemCount) {
        ViewGroup.LayoutParams params = mBinding.episode.getLayoutParams();
        int rows = (itemCount + 1) / 2;
        int height = rows * ResUtil.dp2px(52);
        if (params.height == height) return;
        params.height = height;
        mBinding.episode.setLayoutParams(params);
    }

    @Override
    public void onItemClick(Episode item) {
        boolean selected = item.isSelected();
        if (!isFullscreen()) enterFullscreen();
        if (!selected) {
            mVod.selectEpisode(item);
        } else if (player().isEmpty() || !isOwner()) {
            showProgress(getString(R.string.player_v2_stage_preparing));
            mVod.refresh();
        } else {
            onPlay();
        }
    }

    private void setQualityVisible(boolean visible) {
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.qualityTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        setR2Callback();
    }

    @Override
    public void onItemClick(Result result) {
        mVod.selectQuality(result);
    }

    private void setArrayAdapter(int size) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.play_reverse));
        items.add(getString(mHistory.getRevPlayText()));
        mBinding.array.setVisibility(size > 1 ? View.VISIBLE : View.GONE);
        if (mHistory.isRevSort()) for (int i = size; i > 0; i -= 20) items.add(i + "-" + Math.max(i - 19, 1));
        else for (int i = 0; i < size; i += 20) items.add((i + 1) + "-" + Math.min(i + 20, size));
        mArrayAdapter.addAll(items);
    }

    private int findFocusDown(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick);
        for (int i = 0; i < orders.size(); i++) if (i > index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private int findFocusUp(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick);
        for (int i = orders.size() - 1; i >= 0; i--) if (i < index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private void updateFocus() {
        mPartAdapter.setNextFocusUp(findFocusUp(4));
        mFlagAdapter.setNextFocusDown(findFocusDown(0));
        int episodeFocusUp = findFocusUp(2);
        int episodeFocusDown = findFocusDown(2);
        mEpisodeAdapter.setFocusBounds(episodeFocusUp == 0 ? R.id.video : episodeFocusUp,
                episodeFocusDown);
        int firstDetailRow = findFocusDown(-1);
        if (firstDetailRow == 0) firstDetailRow = R.id.video;
        mBinding.primary.setNextFocusDownId(firstDetailRow);
        mBinding.episodeAction.setNextFocusDownId(isVisible(mBinding.episode) ? R.id.episode : firstDetailRow);
        mBinding.keep.setNextFocusDownId(firstDetailRow);
        mBinding.content.setNextFocusDownId(firstDetailRow);
        mBinding.change.setNextFocusDownId(firstDetailRow);
        notifyItemChanged(mBinding.part, mPartAdapter);
        notifyItemChanged(mBinding.flag, mFlagAdapter);
        if (mRestoreDetailPending) restoreDetailStateWhenReady(mDetailListId);
    }

    @Override
    public void onRevSort() {
        mVod.setRevSort(!mHistory.isRevSort());
        mVod.reverseEpisode(false);
    }

    @Override
    public void onRevPlay(TextView view) {
        mVod.setRevPlay(!mHistory.isRevPlay());
        view.setText(mHistory.getRevPlayText());
        Notify.show(mHistory.getRevPlayHint());
    }

    private void bindDetailArtwork(String name, String url) {
        mDetailPosterUrl = Objects.toString(url, "");
        ImgUtil.loadPoster(name, mDetailPosterUrl, mBinding.detailPoster);
        mBinding.detailPreviewGroup.setVisibility(View.GONE);
        mBinding.detailBackdrop.setVisibility(View.GONE);
        mBinding.detailVignette.setVisibility(View.GONE);
        syncPosterWithDetailScroll(mBinding.scroll.getScrollY());
    }

    private void updatePrimaryAction() {
        if (mHistory == null) {
            mBinding.primary.setText(R.string.detail_v2_play_now);
            return;
        }
        String episode = EpisodeDisplayName.compactActionLabel(mHistory.getVodRemarks());
        if (mHistory.getPosition() > 0) {
            mBinding.primary.setText(TextUtils.isEmpty(episode) ? getString(R.string.detail_v2_resume_plain) : getString(R.string.detail_v2_resume, episode));
        } else {
            mBinding.primary.setText(R.string.detail_v2_play_now);
        }
    }

    private void focusEpisodes() {
        if (!isVisible(mBinding.episode)) return;
        mBinding.scroll.post(() -> {
            requestEpisodeFocus(mEpisodeAdapter.getPosition());
        });
    }

    private void restoreDetailState(@Nullable Bundle state) {
        if (state == null) return;
        mDetailScrollY = state.getInt(STATE_DETAIL_SCROLL, 0);
        mDetailFocusId = state.getInt(STATE_DETAIL_FOCUS, View.NO_ID);
        mDetailListId = state.getInt(STATE_DETAIL_LIST, View.NO_ID);
        mDetailListPosition = state.getInt(STATE_DETAIL_POSITION, RecyclerView.NO_POSITION);
        mFlagPosition = state.getInt(STATE_FLAG_POSITION, RecyclerView.NO_POSITION);
        mEpisodePosition = state.getInt(STATE_EPISODE_POSITION, RecyclerView.NO_POSITION);
        mInitialDetailFocusApplied = true;
        mRestoreDetailPending = true;
    }

    private void captureDetailState(@Nullable View focus) {
        mDetailScrollY = mBinding.scroll.getScrollY();
        mFlagPosition = mBinding.flag.getSelectedPosition();
        mEpisodePosition = mEpisodeAdapter == null ? RecyclerView.NO_POSITION : mEpisodeAdapter.getPosition();
        RecyclerView list = findContainingRecycler(focus);
        if (list == null) {
            mDetailListId = View.NO_ID;
            mDetailListPosition = RecyclerView.NO_POSITION;
            mDetailFocusId = focus == null ? View.NO_ID : focus.getId();
        } else {
            RecyclerView.ViewHolder holder = list.findContainingViewHolder(focus);
            mDetailListId = list.getId();
            mDetailListPosition = holder == null ? RecyclerView.NO_POSITION : holder.getBindingAdapterPosition();
            mDetailFocusId = View.NO_ID;
        }
    }

    @Nullable
    private RecyclerView findContainingRecycler(@Nullable View view) {
        View current = view;
        while (current != null) {
            if (current instanceof RecyclerView) return (RecyclerView) current;
            if (!(current.getParent() instanceof View)) return null;
            current = (View) current.getParent();
        }
        return null;
    }

    private void restoreDetailStateWhenReady(int readyListId) {
        if (!mRestoreDetailPending) return;
        if (mDetailListId != readyListId) return;
        if (readyListId == R.id.flag) mDetailListPosition = DetailFocusPolicy.clampPosition(mDetailListPosition, mFlagAdapter.getItemCount());
        else if (readyListId == R.id.episode) mDetailListPosition = DetailFocusPolicy.clampPosition(mDetailListPosition, mEpisodeAdapter.getItemCount());
        else if (readyListId != View.NO_ID) {
            RecyclerView list = findViewById(readyListId);
            int count = list == null || list.getAdapter() == null ? 0 : list.getAdapter().getItemCount();
            mDetailListPosition = DetailFocusPolicy.clampPosition(mDetailListPosition, count);
        }
        if (readyListId != View.NO_ID && mDetailListPosition == RecyclerView.NO_POSITION) {
            mDetailListId = View.NO_ID;
            mDetailFocusId = R.id.video;
        }
        mRestoreDetailPending = false;
        restoreDetailSnapshot();
    }

    private void restoreDetailSnapshot() {
        mBinding.scroll.post(() -> {
            selectGridPosition(mBinding.flag, mFlagPosition);
            if (mEpisodePosition >= 0 && mEpisodePosition < mEpisodeAdapter.getItemCount()) mBinding.episode.scrollToPosition(mEpisodePosition);
            mBinding.scroll.scrollTo(0, Math.max(0, mDetailScrollY));
            View target = mDetailFocusId == View.NO_ID ? null : findViewById(mDetailFocusId);
            if (mDetailListId != View.NO_ID && mDetailListPosition != RecyclerView.NO_POSITION) {
                RecyclerView list = findViewById(mDetailListId);
                if (list instanceof BaseGridView) ((BaseGridView) list).setSelectedPosition(mDetailListPosition);
                if (list != null) {
                    if (list == mBinding.episode) {
                        requestEpisodeFocus(mDetailListPosition);
                        return;
                    }
                    list.requestFocus();
                    RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(mDetailListPosition);
                    if (holder != null) target = holder.itemView;
                }
            }
            if (target == null || target.getVisibility() != View.VISIBLE || !target.isFocusable()) target = mBinding.video;
            target.requestFocus();
            ensureFocusVisible(target);
        });
    }

    private void requestEpisodeFocus(int requestedPosition) {
        int position = DetailFocusPolicy.clampPosition(requestedPosition, mEpisodeAdapter.getItemCount());
        if (position == RecyclerView.NO_POSITION) {
            focusDetailDefault();
            return;
        }
        mBinding.episode.scrollToPosition(position);
        mBinding.episode.post(() -> {
            RecyclerView.ViewHolder holder = mBinding.episode.findViewHolderForAdapterPosition(position);
            View target = holder == null ? mBinding.episode : holder.itemView;
            target.requestFocus();
            ensureFocusVisible(target);
        });
    }

    private void selectGridPosition(BaseGridView grid, int position) {
        if (position >= 0 && grid.getAdapter() != null && position < grid.getAdapter().getItemCount()) grid.setSelectedPosition(position);
    }

    private void ensureFocusVisible(@Nullable View target) {
        if (target == null || !isDescendantOf(target, mBinding.scroll)) return;
        mBinding.scroll.post(() -> {
            int[] child = new int[2];
            int[] viewport = new int[2];
            target.getLocationOnScreen(child);
            mBinding.scroll.getLocationOnScreen(viewport);
            int safe = ResUtil.dp2px(24);
            int top = viewport[1] + safe;
            int bottom = viewport[1] + mBinding.scroll.getHeight() - safe;
            if (child[1] < top) mBinding.scroll.smoothScrollBy(0, child[1] - top);
            else if (child[1] + target.getHeight() > bottom) mBinding.scroll.smoothScrollBy(0, child[1] + target.getHeight() - bottom);
        });
    }

    private boolean isDescendantOf(@Nullable View child, @NonNull ViewGroup ancestor) {
        View current = child;
        while (current != null) {
            if (current == ancestor) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private void retryPlayback() {
        stopBufferingUi();
        mFallbackActive = false;
        mFallbackAttempt = 0;
        hideError();
        if (!isFullscreen()) enterFullscreen();
        showProgress(getString(R.string.player_v2_stage_preparing));
        mVod.refresh();
    }

    private void switchPlaybackSource() {
        stopBufferingUi();
        hideError();
        showFallbackProgress(getString(R.string.player_v2_fallback_search), false);
        onChange();
    }

    private void returnToDetail() {
        mVod.cancelFallback();
        mViewModel.stopSearch();
        mFallbackActive = false;
        hideError();
        hideProgress();
        if (isFullscreen()) exitFullscreen();
        else focusDetailDefault();
    }

    private void showFallbackProgress(String source, boolean increment) {
        if (!isFullscreen()) enterFullscreen();
        mFallbackActive = true;
        if (increment) mFallbackAttempt++;
        int attempt = Math.max(1, mFallbackAttempt + (increment ? 0 : 1));
        mBinding.progress.fallbackStatus.setText(getString(R.string.player_v2_fallback_status, attempt, source));
        mBinding.progress.fallbackStatus.setVisibility(View.VISIBLE);
        mBinding.progress.fallbackActions.setVisibility(View.VISIBLE);
        showProgress(getString(R.string.player_v2_stage_switching));
    }

    private void cancelFallback(boolean backToDetail) {
        mVod.cancelFallback();
        mViewModel.stopSearch();
        mFallbackActive = false;
        hideProgress();
        if (backToDetail) returnToDetail();
        else showError(getString(R.string.player_v2_error_source));
    }

    private void continueWithoutSubtitle() {
        hideError();
        hideProgress();
        if (isFullscreen()) mBinding.video.requestFocus();
        if (service() != null && controller() != null && !player().isEmpty()) onPlay();
    }

    private void enterFullscreen() {
        mFocus1 = getCurrentFocus();
        captureDetailState(mFocus1);
        setFullscreen(true);
        mBinding.video.setTranslationY(0f);
        mBinding.video.setAlpha(1f);
        mBinding.video.setClickable(true);
        mBinding.video.setFocusable(true);
        mBinding.detailPoster.setVisibility(View.GONE);
        mBinding.detailPreviewGroup.setVisibility(View.GONE);
        mBinding.detailBackdrop.setVisibility(View.GONE);
        mBinding.detailVignette.setVisibility(View.GONE);
        // Fullscreen belongs to the stream only.  Never expose detail art while the surface is
        // preparing, switching players, changing tracks, or between video frames.
        mBinding.video.setBackgroundColor(Color.BLACK);
        mBinding.player.setBackgroundColor(Color.BLACK);
        mBinding.player.setShutterBackgroundColor(Color.BLACK);
        mBinding.player.setDefaultArtwork(null);
        mBinding.video.setClipToOutline(false);
        mBinding.video.bringToFront();
        mBinding.video.requestFocus();
        mBinding.video.setForeground(null);
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        mBinding.flag.setSelectedPosition(mFlagAdapter.getPosition());
        mKeyDown.setFull(true);
        mFocus2 = null;
    }

    private void exitFullscreen() {
        setFullscreen(false);
        mBinding.video.setForeground(ResUtil.getDrawable(R.drawable.selector_video));
        mBinding.video.setLayoutParams(mFrameParams);
        mBinding.video.setClipToOutline(true);
        mBinding.detailPoster.setVisibility(View.VISIBLE);
        mBinding.detailPreviewGroup.setVisibility(View.GONE);
        mBinding.detailBackdrop.setVisibility(View.GONE);
        mBinding.detailVignette.setVisibility(View.GONE);
        mBinding.video.setBackgroundColor(Color.BLACK);
        mBinding.player.setBackgroundColor(Color.BLACK);
        mBinding.player.setShutterBackgroundColor(Color.BLACK);
        mBinding.player.setDefaultArtwork(null);
        mKeyDown.setFull(false);
        mBinding.video.bringToFront();
        syncPosterWithDetailScroll(mBinding.scroll.getScrollY());
        restoreDetailSnapshot();
        mFocus2 = null;
        hideInfo();
    }

    private void syncPosterWithDetailScroll(int scrollY) {
        if (isFullscreen()) return;
        // The outer NestedScrollView remains the single focus/restore authority. Counter-moving
        // the left column keeps the preview and metadata visually anchored while episode focus can
        // scroll the much longer right column without duplicating scroll state.
        mBinding.detailLeftColumn.setTranslationY(Math.max(0, scrollY));
        mBinding.video.setTranslationY(0f);
        mBinding.video.setAlpha(1f);
        mBinding.video.setClickable(true);
        mBinding.video.setFocusable(true);
    }

    private void onContent() {
        if (mBinding.content.getTag() == null) return;
        ContentDialog.create().content(mBinding.content.getTag().toString()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
    }

    private void onVideo() {
        if (isFullscreen()) return;
        if (service() == null) {
            // The detail request is started from onServiceConnected().  Keep the activity alive
            // and show its existing lightweight loading state if a user confirms unusually fast.
            mBinding.progressLayout.showProgress();
            return;
        }
        if (mEpisodeAdapter.getItemCount() == 0) {
            Notify.show(R.string.error_detail);
            if (isVisible(mBinding.flag)) mBinding.flag.requestFocus();
            return;
        }
        boolean start = player().isEmpty() || !isOwner();
        enterFullscreen();
        if (start) {
            showProgress(getString(R.string.player_v2_stage_preparing));
            mVod.refresh();
        }
    }

    private void onChange() {
        if (isFromCollect() && !mDetailCandidates.isEmpty()) {
            if (!tryNextDetailSource()) showError(getString(R.string.player_v2_error_source));
            return;
        }
        mVod.manualSwitchSource();
    }

    private void focusDetailDefault() {
        if (!isFullscreen()) mBinding.video.requestFocus();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        mVod.prevEpisode(true);
    }

    private void onNext(boolean notify) {
        Episode item = mEpisodeAdapter.getNext();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
    }

    private void onPrev(boolean notify) {
        Episode item = mEpisodeAdapter.getPrev();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        mVod.setSpeed(PlaybackAction.addSpeed(player(), mBinding.control.action.speed));
    }

    private void onSpeedAdd() {
        mVod.setSpeed(PlaybackAction.addSpeed(player(), mBinding.control.action.speed, 0.25f));
    }

    private void onSpeedSub() {
        mVod.setSpeed(PlaybackAction.subSpeed(player(), mBinding.control.action.speed, 0.25f));
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

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
    }

    private void onOpeningAdd() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) + 1000));
    }

    private void onOpeningSub() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) - 1000));
    }

    private boolean onOpeningReset() {
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
    }

    private void onEndingAdd() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) + 1000));
    }

    private void onEndingSub() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) - 1000));
    }

    private boolean onEndingReset() {
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
    }

    private void onChoose() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player(), mBinding.widget.title.getText());
        hideControl();
    }

    private void onDecode() {
        mClock.setCallback(null);
        PlaybackAction.toggleDecode(player());
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
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

    private void onDanmakuToggle(View view) {
        if (service() == null) return;
        player().setDanmakuEnabled(!player().isDanmakuEnabled());
        updateDanmakuAction();
        showControl(view);
    }

    private void onDanmakuSource() {
        if (service() == null) return;
        DanmakuDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onDanmakuSetting() {
        if (service() == null) return;
        DanmakuSettingDialog.create().player(player()).show(this);
        hideControl();
    }

    private void updateDanmakuAction() {
        if (mBinding == null) return;
        // initView() runs immediately after bindService(), while onServiceConnected() is
        // asynchronous.  Use the persisted preference until the real PlayerManager is ready.
        boolean enabled = service() == null ? DanmakuSetting.isShow() : player().isDanmakuEnabled();
        mBinding.control.action.danmaku.setText(enabled ? R.string.danmaku_on : R.string.danmaku_off);
        mBinding.control.action.danmaku.setSelected(enabled);
        mBinding.control.action.danmaku.setContentDescription(getString(
                enabled ? R.string.danmaku_on_description : R.string.danmaku_off_description));
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl(getFocus2());
    }

    private void togglePlaybackFromControl(View focus) {
        togglePlaybackRequest();
        showControl(focus);
    }

    private void seekFromControl(View focus, boolean forward) {
        if (forward) onSeekForward();
        else onSeekBack();
        showControl(focus);
    }

    private void updatePlaybackControlAction() {
        boolean playing = isPlaybackRequested();
        mBinding.control.action.playPause.setText(playing ? R.string.pause : R.string.play);
        mBinding.control.action.playPause.setCompoundDrawablesWithIntrinsicBounds(
                playing ? R.drawable.player_v2_ic_pause : R.drawable.player_v2_ic_play, 0, 0, 0);
        mBinding.control.action.playPause.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    private boolean isPlaybackRequested() {
        return controller() != null && controller().getPlayWhenReady();
    }

    private void togglePlaybackRequest() {
        if (service() == null || controller() == null) return;
        if (isPlaybackRequested()) onPaused();
        else if (player().isEmpty()) mVod.refresh();
        else onPlay();
        updatePlaybackControlAction();
    }

    private void showProgress() {
        showProgress(getString(R.string.player_v2_stage_preparing));
    }

    private void showProgress(String stage) {
        hideControl();
        mBinding.progress.stage.setText(stage);
        mBinding.progress.title.setText(mBinding.widget.title.getText());
        mBinding.progress.title.setVisibility(View.VISIBLE);
        mBinding.progress.bufferActions.setVisibility(View.GONE);
        if (!mFallbackActive) {
            mBinding.progress.fallbackStatus.setVisibility(View.GONE);
            mBinding.progress.fallbackActions.setVisibility(View.GONE);
        }
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR3, 0);
        hideCenter();
        hideError();
        if (isFullscreen() && !hasInteractiveOverlay()) mBinding.video.requestFocus();
    }

    private void showBufferingProgress() {
        showProgress(getString(R.string.player_v2_stage_buffering));
        App.removeCallbacks(mR3);
        mBinding.progress.title.setVisibility(View.GONE);
        mBinding.progress.traffic.setVisibility(View.GONE);
    }

    private void hideProgress() {
        boolean restoreVideoFocus = isFullscreen() && isDescendantOf(getCurrentFocus(), mBinding.progress.getRoot());
        stopBufferingUi();
        mBinding.progress.getRoot().setVisibility(View.GONE);
        mBinding.progress.traffic.setVisibility(View.GONE);
        App.removeCallbacks(mR3);
        Traffic.reset();
        if (restoreVideoFocus && !isVisible(mBinding.widget.error)) mBinding.video.requestFocus();
    }

    private void startBufferingUi() {
        mBuffering = true;
        App.removeCallbacks(mR5, mR6);
        if (isVisible(mBinding.progress.getRoot())) mBinding.progress.stage.setText(R.string.player_v2_stage_buffering);
        else App.post(mR5, PlaybackOverlayPolicy.BUFFERING_INDICATOR_DELAY_MS);
        App.post(mR6, PlaybackOverlayPolicy.BUFFERING_ACTION_DELAY_MS);
    }

    private void stopBufferingUi() {
        mBuffering = false;
        App.removeCallbacks(mR5, mR6);
    }

    private void showLongBufferingActions() {
        if (!mBuffering) return;
        showBufferingProgress();
        mBinding.progress.bufferActions.setVisibility(View.VISIBLE);
    }

    private void showError(String text) {
        mPlaybackErrorType = classifyPlaybackError(text);
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.text.setText(playbackErrorMessage(mPlaybackErrorType));
        boolean credential = mPlaybackErrorType == PlaybackErrorType.CREDENTIAL || mPlaybackErrorType == PlaybackErrorType.CREDENTIAL_EXPIRED;
        boolean subtitle = mPlaybackErrorType == PlaybackErrorType.SUBTITLE;
        mBinding.widget.errorLogin.setVisibility(credential ? View.VISIBLE : View.GONE);
        mBinding.widget.errorContinue.setVisibility(subtitle ? View.VISIBLE : View.GONE);
        mBinding.widget.errorRetry.setVisibility(subtitle ? View.GONE : View.VISIBLE);
        mBinding.widget.errorSource.setVisibility(credential || subtitle ? View.GONE : View.VISIBLE);
        hideControl();
        hideProgress();
        configureErrorFocusTrap();
        focusErrorAction();
        // Error can be raised while the detail stage still owns focus. Reassert after layout so
        // RecyclerView focus recovery cannot move it back to an episode in the same frame.
        mBinding.widget.error.post(() -> {
            if (isVisible(mBinding.widget.error)) focusErrorAction();
        });
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    private void configureErrorFocusTrap() {
        List<View> actions = new ArrayList<>();
        addVisibleErrorAction(actions, mBinding.widget.errorRetry);
        addVisibleErrorAction(actions, mBinding.widget.errorSource);
        addVisibleErrorAction(actions, mBinding.widget.errorLogin);
        addVisibleErrorAction(actions, mBinding.widget.errorContinue);
        addVisibleErrorAction(actions, mBinding.widget.errorBack);
        for (int i = 0; i < actions.size(); i++) {
            View action = actions.get(i);
            action.setNextFocusUpId(action.getId());
            action.setNextFocusDownId(action.getId());
            action.setNextFocusLeftId(actions.get(Math.max(0, i - 1)).getId());
            action.setNextFocusRightId(actions.get(Math.min(actions.size() - 1, i + 1)).getId());
        }
    }

    private void addVisibleErrorAction(List<View> actions, View action) {
        if (action.getVisibility() == View.VISIBLE) actions.add(action);
    }

    private void focusErrorAction() {
        View target;
        if (mBinding.widget.errorContinue.getVisibility() == View.VISIBLE) target = mBinding.widget.errorContinue;
        else if (mBinding.widget.errorLogin.getVisibility() == View.VISIBLE) target = mBinding.widget.errorLogin;
        else target = mBinding.widget.errorRetry;
        target.requestFocus();
    }

    private void showInfo() {
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(0));
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    private void showControl(View view) {
        updatePlaybackControlAction();
        configurePlaybackControlFocus();
        mBinding.control.controlTitle.setText(mBinding.widget.title.getText());
        if (!TextUtils.isEmpty(mCurrentSourceName)) mBinding.control.controlStatus.setText(getString(R.string.detail_v2_current_source, mCurrentSourceName));
        // Keep lightweight stream metadata visible above the bottom controller. The full center
        // HUD remains hidden so D-pad navigation never obscures the video.
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        getPlaybackControlTarget(view).requestFocus();
        setR1Callback();
    }

    private void hideControl() {
        View focused = getCurrentFocus();
        boolean restoreVideoFocus = isFullscreen() && isDescendantOf(focused, mBinding.control.getRoot());
        mBinding.control.getRoot().setVisibility(View.GONE);
        mBinding.widget.top.setVisibility(View.GONE);
        App.removeCallbacks(mR1);
        if (restoreVideoFocus && !hasInteractiveOverlay()) mBinding.video.requestFocus();
    }

    private boolean hasInteractiveOverlay() {
        return isVisible(mBinding.widget.error)
                || isVisible(mBinding.progress.fallbackActions)
                || isVisible(mBinding.progress.bufferActions);
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
        hideInfo();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR3, 1000);
    }

    private PlaybackErrorType classifyPlaybackError(String message) {
        String value = Objects.toString(message, "").toLowerCase(Locale.ROOT);
        if (value.contains("timeout") || value.contains("timed out") || value.contains("超时")) return PlaybackErrorType.NETWORK;
        if (value.contains("subtitle") || value.contains("caption") || value.contains("字幕")) return PlaybackErrorType.SUBTITLE;
        if (value.contains("expired") || value.contains("失效") || value.contains("过期")) return PlaybackErrorType.CREDENTIAL_EXPIRED;
        if (value.contains("cookie") || value.contains("token") || value.contains("credential") || value.contains("login") || value.contains("网盘") || value.contains("登录")) return PlaybackErrorType.CREDENTIAL;
        if (value.contains("player init") || value.contains("player start") || value.contains("播放器启动")) return PlaybackErrorType.PLAYER;
        if (value.contains("drm") || value.contains("codec") || value.contains("format") || value.contains("unsupported") || value.contains("格式")) return PlaybackErrorType.FORMAT;
        if (value.contains("parse") || value.contains("resolve") || value.contains("url") || value.contains("解析")) return PlaybackErrorType.PARSE;
        if (value.contains("source") || value.contains("flag") || value.contains("line") || value.contains("线路")) return PlaybackErrorType.SOURCE;
        return PlaybackErrorType.UNKNOWN;
    }

    private String playbackErrorMessage(PlaybackErrorType type) {
        return switch (type) {
            case SOURCE -> getString(R.string.player_v2_error_source);
            case PARSE -> getString(R.string.player_v2_error_parse);
            case NETWORK -> getString(R.string.player_v2_error_network);
            case CREDENTIAL -> getString(R.string.player_v2_error_credential);
            case CREDENTIAL_EXPIRED -> getString(R.string.player_v2_error_credential_expired);
            case FORMAT -> getString(R.string.player_v2_error_format);
            case PLAYER -> getString(R.string.player_v2_error_player);
            case SUBTITLE -> getString(R.string.player_v2_error_subtitle);
            case UNKNOWN -> getString(R.string.player_v2_error_unknown);
        };
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

    private void setR2Callback() {
        App.post(mR2, 500);
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        ImgUtil.load(this, mHistory.getVodPic(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.player.setDefaultArtwork(isFullscreen() ? null : resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.player.setDefaultArtwork(isFullscreen() ? null : errorDrawable);
            }
        });
    }

    private void setPartAdapter() {
        mPartAdapter.addAll(PartUtil.split(mHistory.getVodName()));
        mBinding.part.setVisibility(View.VISIBLE);
        setR2Callback();
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

    private void checkKeepImg() {
        mBinding.keep.setCompoundDrawablesWithIntrinsicBounds(Keep.find(getHistoryKey()) == null ? R.drawable.ic_detail_keep_off : R.drawable.ic_detail_keep_on, 0, 0, 0);
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
        if (name) setDetailTitle(item.getName());
        if (name) mBinding.widget.title.setText(SearchDisplayName.removeEmoji(item.getName()));
        mVod.mergeFlags(item.getFlags());
        if (pic) {
            mDetailPosterUrl = item.getPic();
            setArtwork(item.getPic());
            bindDetailArtwork(mBinding.name.getText().toString(), mDetailPosterUrl);
        }
        if (pic || name) setMetadata();
        if (pic || name) syncHistory();
        if (pic || name) updateKeep();
        if (id) updateNavigationKey();
        if (name) setPartAdapter();
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
                startBufferingUi();
                mClock.setCallback(null);
                break;
            case Player.STATE_READY:
                mFallbackActive = false;
                mFallbackAttempt = 0;
                hideProgress();
                player().reset();
                mClock.setCallback(this);
                break;
            case Player.STATE_ENDED:
                hideProgress();
                mVod.playbackEnded();
                mClock.setCallback(null);
                break;
        }
        updatePlaybackControlAction();
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            hideCenter();
        } else if (isPaused()) {
            if (isFullscreen()) showInfo();
            else hideInfo();
        }
        updatePrimaryAction();
        updatePlaybackControlAction();
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.player.getSubtitleView()).player(player()).show(this);
        App.post(this::hideControl, 100);
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner() || !player().isVod()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position < 0 || duration <= 0) return;
        mVod.onTimeChanged(time, position, duration);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) mVod.requestDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) mVod.refresh();
        else if (event.getType() == RefreshEvent.Type.VOD) updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    private void setTrackVisible() {
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
        configurePlaybackControlFocus();
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

    @Override
    public void onItemClick(Vod item) {
        mVod.selectSource(item);
    }

    @Override
    public void onParse(Parse item) {
        mVod.selectParse(item);
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
    }

    private boolean onSeekBack() {
        controller().seekBack();
        return true;
    }

    private boolean onSeekForward() {
        controller().seekForward();
        return true;
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    private View getFocus1() {
        return mFocus1 == null || mFocus1.getVisibility() != View.VISIBLE ? mBinding.video : mFocus1;
    }

    private View getFocus2() {
        return getPlaybackControlTarget(mFocus2);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean progressVisible = isVisible(mBinding.progress.getRoot());
        boolean errorVisible = isVisible(mBinding.widget.error);
        boolean controlVisible = isVisible(mBinding.control.getRoot());
        boolean directionalKey = isDirectionalKey(event.getKeyCode());
        boolean horizontalKey = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean playerEmpty = service() == null || player().isEmpty();
        boolean focusInsideError = isDescendantOf(getCurrentFocus(), mBinding.widget.error);
        if (PlaybackOverlayPolicy.shouldCaptureErrorFocus(errorVisible, focusInsideError)) {
            focusErrorAction();
            if (isErrorFocusKey(event.getKeyCode())) return true;
        }
        if (mConsumedDirectionalKeyCode != KeyEvent.KEYCODE_UNKNOWN
                && event.getKeyCode() == mConsumedDirectionalKeyCode) {
            if (KeyUtil.isActionUp(event)) mConsumedDirectionalKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (PlaybackOverlayPolicy.shouldEnterProgressActions(
                progressVisible, progressActionsVisible(), focusInsideProgressActions(), directionalKey)) {
            if (KeyUtil.isActionDown(event)) {
                mConsumedDirectionalKeyCode = event.getKeyCode();
                configureProgressActionFocus();
                getProgressActionTarget().requestFocus();
            }
            return true;
        }
        // Compact buffering is informational.  Do not let its D-pad events fall through into the
        // hidden detail source/episode rows behind the fullscreen player.
        if (progressVisible && directionalKey) return true;
        if (PlaybackOverlayPolicy.shouldSeekWithHiddenControls(
                isFullscreen(), controlVisible, progressVisible, errorVisible, horizontalKey)
                && mKeyDown.hasEvent(event) && service() != null) {
            return mKeyDown.onKeyDown(event);
        }
        if (PlaybackOverlayPolicy.shouldRevealControls(
                isFullscreen(), controlVisible, progressVisible, errorVisible,
                directionalKey && !horizontalKey)) {
            if (KeyUtil.isActionDown(event)) {
                mConsumedDirectionalKeyCode = event.getKeyCode();
                showControl(getFocus2());
            }
            return true;
        }
        if (isFullscreen() && !progressVisible && !errorVisible && KeyUtil.isMenuKey(event)) {
            if (controlVisible && getCurrentFocus() == mBinding.control.action.danmaku) onDanmakuSource();
            else onToggle();
            return true;
        }
        if (isFullscreen() && !errorVisible && KeyUtil.isMediaPlayPause(event)) {
            togglePlaybackRequest();
            if (!progressVisible) showControl(mBinding.control.action.next);
            return true;
        }
        if (PlaybackOverlayPolicy.canToggleDuringBuffering(
                isFullscreen(), progressVisible, mBuffering, playerEmpty, errorVisible)
                && KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            togglePlaybackRequest();
            mBinding.progress.stage.setText(isPlaybackRequested() ? R.string.player_v2_stage_buffering : R.string.pause);
            return true;
        }
        if (controlVisible) setR1Callback();
        if (controlVisible && isPlaybackControl(getCurrentFocus())) mFocus2 = getCurrentFocus();
        boolean routePlaybackKeys = PlaybackOverlayPolicy.routeToPlaybackGestures(
                isFullscreen(), controlVisible, progressVisible, errorVisible);
        if (routePlaybackKeys && mKeyDown.hasEvent(event) && service() != null) return mKeyDown.onKeyDown(event);
        if (!progressVisible && !errorVisible && KeyUtil.isMediaFastForward(event)) return onSeekForward();
        if (!progressVisible && !errorVisible && KeyUtil.isMediaRewind(event)) return onSeekBack();
        return super.dispatchKeyEvent(event);
    }

    private boolean isErrorFocusKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean isDirectionalKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        mKeyDown.reset();
        seekTo(time);
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
        mBinding.widget.speed.setVisibility(View.GONE);
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, mHistory.getSpeed());
    }

    @Override
    public void onKeyUp() {
        showControl(getFocus2());
    }

    @Override
    public void onKeyDown() {
        showControl(getFocus2());
    }

    @Override
    public void onKeyCenter() {
        if (player().isEmpty()) onRefresh();
        else togglePlaybackRequest();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        if (isFullscreen()) onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isFullscreen()) onKeyCenter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == REQUEST_CLOUD_LOGIN) {
            hideError();
            showProgress(getString(R.string.player_v2_stage_preparing));
            mVod.refresh();
        } else if (resultCode == RESULT_OK && requestCode == 1001) {
            PlayerHelper.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        captureDetailState(isFullscreen() ? mFocus1 : getCurrentFocus());
        outState.putInt(STATE_DETAIL_SCROLL, mDetailScrollY);
        outState.putInt(STATE_DETAIL_FOCUS, mDetailFocusId);
        outState.putInt(STATE_DETAIL_LIST, mDetailListId);
        outState.putInt(STATE_DETAIL_POSITION, mDetailListPosition);
        outState.putInt(STATE_FLAG_POSITION, mFlagPosition);
        outState.putInt(STATE_EPISODE_POSITION, mEpisodePosition);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.progress.getRoot())) {
            mVod.cancelFallback();
            mViewModel.stopSearch();
            mFallbackActive = false;
            hideProgress();
            if (isFullscreen()) exitFullscreen();
            else focusDetailDefault();
        } else if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.error)) {
            returnToDetail();
        } else if (isVisible(mBinding.widget.center)) {
            hideCenter();
        } else if (isFullscreen()) {
            exitFullscreen();
        } else {
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        cancelRepositorySiteResolve();
        if (mVod != null) mVod.cancelFallback();
        if (mViewModel != null) mViewModel.stopSearch();
        mClock.release();
        saveHistory(true);
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4, mR5, mR6);
        super.onDestroy();
    }
}
