package com.fongmi.android.tv.ui.activity;

import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.HomeFeaturedViewModel;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.security.PromotionFilter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.ConfigQuickDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.fragment.TypeFragment;
import com.fongmi.android.tv.ui.home.HomeFeaturedController;
import com.fongmi.android.tv.ui.home.HomeFeaturedPolicy;
import com.fongmi.android.tv.ui.home.HomeHistoryAdapter;
import com.fongmi.android.tv.ui.home.HomeNavItem;
import com.fongmi.android.tv.ui.home.HomeNavigationAdapter;
import com.fongmi.android.tv.ui.home.HomeNavigationController;
import com.fongmi.android.tv.ui.home.HomePageController;
import com.fongmi.android.tv.ui.home.HomePosterAdapter;
import com.fongmi.android.tv.ui.home.HomeState;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class HomeActivity extends BaseActivity implements HomeNavigationAdapter.Listener, HomePosterAdapter.Listener, HomeHistoryAdapter.Listener, HomeFeaturedController.Listener, ConfigListener, SiteListener, TypeFragment.Host {

    private static final String STATE_CATEGORY = "home_category";
    private static final String STATE_SCROLL = "home_scroll";
    private static final String STATE_FEATURED = "home_featured";
    private static final String STATE_HISTORY = "home_history";
    private static final String STATE_RECOMMEND = "home_recommend";
    private static final String STATE_RECOMMEND_KEY = "home_recommend_key";
    private static final String STATE_CATEGORY_FOCUS = "home_category_focus";
    private static final String CATEGORY_TAG = "home-category-";

    private final HomeState homeState = new HomeState();
    private ActivityHomeBinding binding;
    private HomeNavigationAdapter navigationAdapter;
    private HomeHistoryAdapter historyAdapter;
    private HomePosterAdapter posterAdapter;
    private HomeFeaturedController featuredController;
    private HomePageController pageController;
    private SiteViewModel homeViewModel;
    private HomeFeaturedViewModel detailViewModel;
    private List<Class> categories = new ArrayList<>();
    private List<Vod> allRecommendations = new ArrayList<>();
    private Result homeResult = Result.empty();
    private String visibleCategoryTag;
    private boolean configReady;
    private boolean initialIntentHandled;
    private boolean resetHomeAfterConfig;
    private boolean homeAvailable;
    private boolean homeSurfaceAvailable;
    private Config rollbackConfig;
    private boolean homeUiRestored;
    private boolean cachedHistoryShown;
    private List<History> cachedHistories = new ArrayList<>();
    private long brandSplashStartedAt;
    private boolean brandSplashDismissed;
    private boolean brandSplashMotionStarted;
    private boolean brandSplashDismissRequested;
    private boolean systemSplashExited;
    private final Runnable deferredStartup = () -> {
        if (isFinishing() || isDestroyed()) return;
        PermissionUtil.requestNotify(this);
        DLNARendererService.start(this);
        Updater.create().start(this);
    };

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        // The system starting window is deliberately blank and color-matched. Begin the real
        // double-frame motion only after it has been removed, otherwise the animation runs hidden
        // behind Android's splash and appears as a second static screen on slower TV firmware.
        splashScreen.setOnExitAnimationListener(provider -> {
            provider.remove();
            systemSplashExited = true;
            startBrandSplashWhenAttached();
        });
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        restoreState(savedInstanceState);
        pageController = new HomePageController(binding, homeState);
        featuredController = new HomeFeaturedController(binding, this);
        setRecyclerViews();
        setViewModels();
        setInitialNavigation();
        // Start the in-app veil only after the hidden home hierarchy has been wired. The veil
        // owns focus while it is visible so remote events cannot accidentally open Search or
        // Settings behind the animation.
        prepareBrandSplash();
        // The platform splash is a colour-matched blank frame. The branded motion starts only
        // after it exits, so the launcher never shows a static logo before the real animation.
        startBrandSplashWhenAttached();
        // Some vendor Android 9 builds occasionally omit the compat exit callback. Keep a
        // bounded fallback without blocking cached-home/config work behind the launch veil.
        binding.brandSplash.postDelayed(() -> {
            if (brandSplashMotionStarted || brandSplashDismissed) return;
            systemSplashExited = true;
            startBrandSplashWhenAttached();
        }, 800L);
        pageController.showLoading(false);
        initConfig();
        // Prefer revealing an already useful cache/home surface. On a genuinely cold first run,
        // fall back to the explicit skeleton after a bounded interval instead of looking frozen.
        binding.brandSplash.postDelayed(this::dismissBrandSplashToLoading, 3500L);
        // Notification/service/update initialization must not compete with the first TV frame.
        binding.getRoot().postDelayed(deferredStartup, 1400L);
    }

    @Override
    protected void initEvent() {
        binding.search.setOnClickListener(view -> SearchActivity.start(this));
        binding.config.setOnClickListener(view -> openConfig());
        binding.config.setOnLongClickListener(view -> {
            ConfigDialog.create().vod().show(this);
            return true;
        });
        binding.settings.setOnClickListener(view -> SettingActivity.start(this));
        binding.recommendMore.setOnClickListener(view -> HomeFeaturedActivity.start(this, allRecommendations));
        binding.historyMore.setOnClickListener(view -> HistoryActivity.start(this));
        binding.emptyConfig.setOnClickListener(view -> openConfig());
        binding.errorConfig.setOnClickListener(view -> openConfig());
        binding.errorSettings.setOnClickListener(view -> SettingActivity.start(this));
        binding.retry.setOnClickListener(view -> retry());
        bindUtilityFocus(binding.search);
        bindUtilityFocus(binding.config);
        bindUtilityFocus(binding.settings);
        bindUtilityFocus(binding.recommendMore);
        bindUtilityFocus(binding.historyMore);
        getSupportFragmentManager().addOnBackStackChangedListener(this::syncVisibleCategoryTag);
    }

    private void restoreState(Bundle state) {
        if (state == null) return;
        homeState.setSelectedCategoryId(state.getString(STATE_CATEGORY, HomeState.HOME_ID));
        homeState.setHomeScrollY(state.getInt(STATE_SCROLL));
        homeState.setFeaturedIndex(state.getInt(STATE_FEATURED));
        homeState.setHistoryPosition(state.getInt(STATE_HISTORY));
        homeState.setRecommendPosition(state.getInt(STATE_RECOMMEND));
        homeState.setRecommendKey(state.getString(STATE_RECOMMEND_KEY, ""));
        homeState.restoreCategoryFocus((HashMap<String, Integer>) state.getSerializable(STATE_CATEGORY_FOCUS));
    }

    private void setRecyclerViews() {
        binding.nav.setHorizontalSpacing(ResUtil.dp2px(8));
        binding.nav.setAdapter(navigationAdapter = new HomeNavigationAdapter(this));
        binding.historyRecycler.setHorizontalSpacing(ResUtil.dp2px(8));
        binding.historyRecycler.setAdapter(historyAdapter = new HomeHistoryAdapter(this));
        binding.recommendRecycler.setHorizontalSpacing(ResUtil.dp2px(12));
        binding.recommendRecycler.setAdapter(posterAdapter = new HomePosterAdapter(this));
        binding.recommendRecycler.post(this::updateRecommendationLayout);
    }

    private void setViewModels() {
        ViewModelProvider provider = new ViewModelProvider(this);
        homeViewModel = provider.get("home-content", SiteViewModel.class);
        detailViewModel = provider.get("home-featured-detail", HomeFeaturedViewModel.class);
        homeViewModel.getResult().observe(this, result -> {
            if (result != null) onHomeResult(result);
        });
        homeViewModel.getError().observe(this, message -> {
            if (message != null && !message.isEmpty()) showHomeContentError();
        });
        // Remote home actions are intentionally not surfaced as Toasts. Repository messages
        // have historically been used for announcements and marketing overlays.
        detailViewModel.getResult().observe(this, result -> {
            if (result != null && !result.hasMsg()) featuredController.onDetail(result);
        });
    }

    private void setInitialNavigation() {
        navigationAdapter.submit(HomeNavigationController.build(getString(R.string.home_page), getString(R.string.home_more), List.of(), getResources().getInteger(R.integer.home_max_primary_categories)));
        navigationAdapter.select(HomeState.HOME_ID);
        binding.nav.setSelectedPosition(0);
        binding.search.requestFocus();
    }

    private void bindUtilityFocus(View view) {
        view.setOnFocusChangeListener((target, focused) -> {
            target.setTranslationZ(focused ? 10f : 0f);
            float scale = focused ? 1.015f : 1f;
            target.animate().scaleX(scale).scaleY(scale).setDuration(getResources().getInteger(R.integer.tv_focus_animation_duration)).start();
        });
    }

    private void initConfig() {
        configReady = false;
        Task.execute(() -> {
            try {
                List<History> histories = History.get();
                App.post(() -> onCachedHistoryLoaded(histories));
            } catch (Throwable error) {
                App.post(() -> onCachedHistoryLoaded(new ArrayList<>()));
            }
        });
        Task.execute(() -> {
            try {
                VodConfig.get().init();
                LiveConfig.get().init();
                WallConfig.get().init();
                App.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    VodConfig.get().load(configCallback(false));
                    LiveConfig.get().load();
                    WallConfig.get().load();
                });
            } catch (Throwable error) {
                App.post(this::showStartupError);
            }
        });
    }

    private void showStartupError() {
        if (isFinishing() || isDestroyed()) return;
        configReady = false;
        if (cachedHistoryShown) pageController.showHome();
        else showError();
        dismissBrandSplashWhenReady();
    }

    private void onCachedHistoryLoaded(List<History> histories) {
        if (isFinishing() || isDestroyed()) return;
        cachedHistories = histories == null ? new ArrayList<>() : new ArrayList<>(histories);
        if (!homeResult.getList().isEmpty()) {
            updateHistory(cachedHistories);
            updateHeroFocusTarget(!cachedHistories.isEmpty(), !allRecommendations.isEmpty());
            return;
        }
        showCachedHistoryWhileLoading(cachedHistories);
    }

    private void showCachedHistoryWhileLoading(List<History> histories) {
        if (histories == null || histories.isEmpty()) return;
        cachedHistories = new ArrayList<>(histories);
        cachedHistoryShown = true;
        homeSurfaceAvailable = true;
        homeAvailable = true;
        updateHistory(histories);
        binding.recommendSection.setVisibility(View.GONE);
        binding.recommendMore.setVisibility(View.GONE);
        featuredController.setInitial(historyVods(histories), homeState.getFeaturedIndex());
        updateHeroFocusTarget(true, false);
        pageController.showHome();
        binding.historyRecycler.setSelectedPosition(Math.min(homeState.getHistoryPosition(), histories.size() - 1));
        dismissBrandSplashWhenReady();
    }

    private Callback configCallback(boolean switching) {
        return new Callback() {
            @Override
            public void start() {
                resetHomeAfterConfig = switching;
                if (switching || !cachedHistoryShown) pageController.showLoading(switching);
            }

            @Override
            public void success() {
                configReady = true;
                handleInitialIntent();
            }

            @Override
            public void error(String msg) {
                configReady = false;
                if (cachedHistoryShown) {
                    pageController.showHome();
                    dismissBrandSplashWhenReady();
                    return;
                }
                showError();
                dismissBrandSplashWhenReady();
            }
        };
    }

    private void handleInitialIntent() {
        if (initialIntentHandled) return;
        initialIntentHandled = true;
        checkAction(getIntent());
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
            else SearchActivity.startVoice(this);
        } else if (Intent.ACTION_VOICE_COMMAND.equals(intent.getAction())) {
            SearchActivity.startVoice(this);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void onConfigLoaded() {
        boolean preserveCachedShell = cachedHistoryShown && !resetHomeAfterConfig;
        cachedHistoryShown = false;
        clearCategoryFragments();
        homeResult = Result.empty();
        allRecommendations = new ArrayList<>();
        homeSurfaceAvailable = false;
        if (!preserveCachedShell) {
            featuredController.clear();
            posterAdapter.submit(new ArrayList<>());
            binding.recommendSection.setVisibility(View.GONE);
            binding.recommendMore.setVisibility(View.GONE);
        }
        if (resetHomeAfterConfig) {
            homeState.setSelectedCategoryId(HomeState.HOME_ID);
            homeState.setHomeScrollY(0);
            homeState.setFeaturedIndex(0);
            homeState.setRecommendPosition(0);
            homeState.setRecommendKey("");
            homeUiRestored = false;
            navigationAdapter.select(HomeState.HOME_ID);
        }
        resetHomeAfterConfig = false;
        loadHistory();
        loadHome();
    }

    private void loadHome() {
        // Keep the cache-first shell visible while the remote home feed refreshes. Replacing an
        // already useful history/hero surface with a blocking spinner causes both flicker and a
        // focus reset on TV remotes.
        if (!homeAvailable) pageController.showLoading(false);
        homeViewModel.homeContent();
    }

    private void onHomeResult(Result result) {
        if (result.hasMsg()) {
            showHomeContentError();
            return;
        }
        homeResult = result;
        Cache.clear().put(result);
        categories = HomeNavigationController.sanitize(result.getTypes());
        navigationAdapter.submit(HomeNavigationController.build(getString(R.string.home_page), getString(R.string.home_more), categories, getResources().getInteger(R.integer.home_max_primary_categories)));
        List<Vod> recommendations = validVods(result.getList());
        allRecommendations = new ArrayList<>(recommendations);
        List<Vod> featured = featuredItems(recommendations);
        List<Vod> shelf = shelfItems(recommendations);
        submitRecommendationShelf(shelf);
        binding.recommendSection.setVisibility(shelf.isEmpty() ? View.GONE : View.VISIBLE);
        binding.recommendMore.setVisibility(recommendations.isEmpty() ? View.GONE : View.VISIBLE);
        List<History> histories = new ArrayList<>(cachedHistories);
        updateHistory(histories);
        updateHeroFocusTarget(!histories.isEmpty(), !shelf.isEmpty());
        if (featured.isEmpty()) featured = historyVods(histories);
        featuredController.setInitial(featured, homeState.getFeaturedIndex());
        homeSurfaceAvailable = !featured.isEmpty() || !histories.isEmpty();
        homeAvailable = homeSurfaceAvailable || !categories.isEmpty();
        if (!homeAvailable) pageController.showEmpty();
        else pageController.showHome();
        restorePageAfterLoad();
        dismissBrandSplashWhenReady();
    }

    private void prepareBrandSplash() {
        brandSplashDismissed = false;
        brandSplashMotionStarted = false;
        brandSplashDismissRequested = false;
        binding.brandSplash.setVisibility(View.VISIBLE);
        binding.brandSplash.setFocusable(true);
        binding.brandSplash.setFocusableInTouchMode(true);
        binding.brandSplash.setOnKeyListener((view, keyCode, event) -> true);
        binding.brandSplash.requestFocus();
        binding.brandSplash.setAlpha(1f);
        binding.brandSplashBackdrop.animate().cancel();
        binding.brandSplashBackdrop.setAlpha(0f);
        binding.brandSplashBackdrop.setScaleX(1.055f);
        binding.brandSplashBackdrop.setScaleY(1.055f);
        binding.brandSplashHalo.animate().cancel();
        binding.brandSplashHalo.setAlpha(0f);
        binding.brandSplashHalo.setScaleX(0.58f);
        binding.brandSplashHalo.setScaleY(0.58f);
        binding.brandSplashSweep.animate().cancel();
        binding.brandSplashSweep.setAlpha(0f);
        binding.brandSplashSweep.setTranslationX(-ResUtil.dp2px(380));
        binding.brandSplashFrameLeft.animate().cancel();
        binding.brandSplashFrameLeft.setAlpha(0f);
        binding.brandSplashFrameLeft.setScaleX(0.84f);
        binding.brandSplashFrameLeft.setScaleY(0.84f);
        binding.brandSplashFrameLeft.setRotation(-14f);
        binding.brandSplashFrameLeft.setTranslationX(-ResUtil.dp2px(42));
        binding.brandSplashFrameRight.animate().cancel();
        binding.brandSplashFrameRight.setAlpha(0f);
        binding.brandSplashFrameRight.setScaleX(0.84f);
        binding.brandSplashFrameRight.setScaleY(0.84f);
        binding.brandSplashFrameRight.setRotation(14f);
        binding.brandSplashFrameRight.setTranslationX(ResUtil.dp2px(42));
        binding.brandSplashPlay.animate().cancel();
        binding.brandSplashPlay.setAlpha(0f);
        binding.brandSplashPlay.setScaleX(0.58f);
        binding.brandSplashPlay.setScaleY(0.58f);
        binding.brandSplashPlay.setRotation(-6f);
        binding.brandSplashTitle.animate().cancel();
        binding.brandSplashTitle.setAlpha(0f);
        binding.brandSplashTitle.setTranslationY(ResUtil.dp2px(14));
        binding.brandSplashTagline.animate().cancel();
        binding.brandSplashTagline.setAlpha(0f);
        binding.brandSplashTagline.setTranslationY(ResUtil.dp2px(8));
        binding.brandSplashPulse.animate().cancel();
        binding.brandSplashPulse.setAlpha(0f);
        binding.brandSplashPulse.setPivotX(0f);
        binding.brandSplashPulse.setScaleX(0.02f);
        binding.brandSplashPulse.setTranslationX(-ResUtil.dp2px(42));
    }

    private void startBrandSplashWhenAttached() {
        if (!systemSplashExited || binding == null) return;
        binding.brandSplash.post(this::startBrandSplashMotion);
    }

    private void startBrandSplashMotion() {
        if (brandSplashDismissed || brandSplashMotionStarted || isFinishing() || isDestroyed()) return;
        brandSplashMotionStarted = true;
        brandSplashStartedAt = SystemClock.uptimeMillis();
        DecelerateInterpolator cinematic = new DecelerateInterpolator(1.7f);
        OvershootInterpolator settle = new OvershootInterpolator(0.72f);
        binding.brandSplashBackdrop.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(cinematic).setDuration(900L).start();
        binding.brandSplashHalo.animate().alpha(0.62f).scaleX(1f).scaleY(1f).setInterpolator(cinematic).setStartDelay(90L).setDuration(520L).withEndAction(() -> {
            if (brandSplashDismissed) return;
            binding.brandSplashHalo.animate().alpha(0.2f).scaleX(1.24f).scaleY(1.24f).setDuration(700L).start();
        }).start();
        binding.brandSplashFrameLeft.animate().alpha(0.38f).translationX(-ResUtil.dp2px(13)).rotation(-7f).scaleX(0.94f).scaleY(0.94f).setInterpolator(cinematic).setStartDelay(90L).setDuration(520L).start();
        binding.brandSplashFrameRight.animate().alpha(0.38f).translationX(ResUtil.dp2px(13)).rotation(7f).scaleX(0.94f).scaleY(0.94f).setInterpolator(cinematic).setStartDelay(130L).setDuration(520L).start();
        binding.brandSplashPlay.animate().alpha(1f).rotation(0f).scaleX(1f).scaleY(1f).setInterpolator(settle).setStartDelay(230L).setDuration(530L).start();
        binding.brandSplashSweep.animate().alpha(0.9f).translationX(ResUtil.dp2px(380)).setInterpolator(cinematic).setStartDelay(260L).setDuration(720L).withEndAction(() -> binding.brandSplashSweep.animate().alpha(0f).setDuration(180L).start()).start();
        binding.brandSplashTitle.animate().alpha(1f).translationY(0f).setInterpolator(cinematic).setStartDelay(520L).setDuration(360L).start();
        binding.brandSplashTagline.animate().alpha(1f).translationY(0f).setInterpolator(cinematic).setStartDelay(650L).setDuration(340L).start();
        binding.brandSplashPulse.animate().alpha(1f).translationX(0f).scaleX(1f).setInterpolator(cinematic).setStartDelay(720L).setDuration(480L).start();
        if (brandSplashDismissRequested) dismissBrandSplashWhenReady();
    }

    private void dismissBrandSplashWhenReady() {
        brandSplashDismissRequested = true;
        if (!brandSplashMotionStarted) return;
        long elapsed = SystemClock.uptimeMillis() - brandSplashStartedAt;
        binding.brandSplash.postDelayed(this::dismissBrandSplash, Math.max(0L, 1400L - elapsed));
    }

    private void dismissBrandSplashToLoading() {
        if (brandSplashDismissed || isFinishing() || isDestroyed()) return;
        if (!homeAvailable && !cachedHistoryShown) pageController.showLoading(false);
        brandSplashDismissRequested = true;
        if (brandSplashMotionStarted) dismissBrandSplash();
    }

    private void dismissBrandSplash() {
        if (brandSplashDismissed || isFinishing() || isDestroyed()) return;
        brandSplashDismissed = true;
        binding.brandSplash.animate().alpha(0f).setDuration(300L).withEndAction(() -> {
            binding.brandSplash.setVisibility(View.GONE);
            binding.brandSplash.setClickable(false);
            binding.brandSplash.setFocusable(false);
            binding.brandSplash.setOnKeyListener(null);
            if (isFinishing() || isDestroyed() || !hasWindowFocus()) return;
            View current = getCurrentFocus();
            if (current == null || current == binding.brandSplash) binding.search.requestFocus();
        }).start();
    }

    private List<Vod> validVods(List<Vod> source) {
        List<Vod> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (source == null) return result;
        for (Vod item : source) {
            if (item == null || item.getName().isEmpty() || item.getId().isEmpty()) continue;
            if (PromotionFilter.isPromotionalEntry(item.getName(), item.getRemarks(), item.getContent())) continue;
            if (item.getSiteKey().isEmpty()) item.setSite(getHome());
            String key = HomeFeaturedPolicy.stableKey(item.getSiteKey(), item.getId());
            if (seen.add(key)) result.add(item);
        }
        return result;
    }

    private List<Vod> featuredItems(List<Vod> recommendations) {
        return HomeFeaturedPolicy.preview(recommendations);
    }

    private List<Vod> shelfItems(List<Vod> recommendations) {
        return HomeFeaturedPolicy.preview(recommendations);
    }

    private void submitRecommendationShelf(List<Vod> shelf) {
        String focusedKey = homeState.getRecommendKey();
        int currentPosition = binding.recommendRecycler.getSelectedPosition();
        if (focusedKey.isEmpty() && currentPosition >= 0) focusedKey = posterAdapter.stableKeyAt(currentPosition);
        posterAdapter.submit(shelf);
        int target = HomeFeaturedPolicy.resolvePosition(posterAdapter.stableKeys(), focusedKey, homeState.getRecommendPosition());
        if (target == HomeFeaturedPolicy.NO_POSITION) {
            homeState.setRecommendPosition(0);
            homeState.setRecommendKey("");
            return;
        }
        homeState.setRecommendPosition(target);
        homeState.setRecommendKey(posterAdapter.stableKeyAt(target));
        binding.recommendRecycler.post(() -> {
            if (target < posterAdapter.getItemCount() && binding.recommendRecycler.getSelectedPosition() != target) {
                binding.recommendRecycler.setSelectedPosition(target);
            }
        });
        binding.recommendRecycler.post(this::updateRecommendationLayout);
    }

    private void updateRecommendationLayout() {
        int available = binding.recommendRecycler.getWidth() - binding.recommendRecycler.getPaddingLeft() - binding.recommendRecycler.getPaddingRight();
        if (available <= 0) return;
        int count = HomeFeaturedPolicy.PREVIEW_LIMIT;
        int minimumSpacing = getResources().getDimensionPixelSize(R.dimen.home_poster_min_spacing);
        int preferredWidth = getResources().getDimensionPixelSize(R.dimen.home_poster_card_width);
        int width = Math.min(preferredWidth, Math.max(1, (available - minimumSpacing * (count - 1)) / count));
        int height = Math.round(width * 4f / 3f);
        int spacing = count > 1 ? Math.max(minimumSpacing, (available - width * count) / (count - 1)) : 0;
        posterAdapter.setCardSize(width, height);
        binding.recommendRecycler.setHorizontalSpacing(spacing);
        int rowHeight = height + binding.recommendRecycler.getPaddingTop() + binding.recommendRecycler.getPaddingBottom();
        ViewGroup.LayoutParams params = binding.recommendRecycler.getLayoutParams();
        if (params.height != rowHeight) {
            params.height = rowHeight;
            binding.recommendRecycler.setLayoutParams(params);
        }
    }

    private List<Vod> historyVods(List<History> histories) {
        List<Vod> items = new ArrayList<>();
        for (History history : histories) {
            Vod item = new Vod();
            item.setId(history.getVodId());
            item.setName(history.getVodName());
            item.setPic(history.getVodPic());
            item.setSite(VodConfig.get().getSite(history.getSiteKey()));
            items.add(item);
        }
        return items;
    }

    private void restorePageAfterLoad() {
        Class selected = findCategory(homeState.getSelectedCategoryId());
        if (!homeState.isHome() && selected != null) showCategory(selected, false);
        else if (!homeSurfaceAvailable && !categories.isEmpty()) {
            Class fallback = categories.get(0);
            homeState.setSelectedCategoryId(fallback.getTypeId());
            showCategory(fallback, false);
        } else {
            homeState.setSelectedCategoryId(HomeState.HOME_ID);
            navigationAdapter.select(HomeState.HOME_ID);
        }
        if (!homeUiRestored) {
            binding.homeScroll.post(() -> binding.homeScroll.scrollTo(0, homeState.getHomeScrollY()));
            binding.historyRecycler.setSelectedPosition(homeState.getHistoryPosition());
            homeUiRestored = true;
        }
    }

    private void loadHistory() {
        Task.execute(() -> {
            List<History> histories = History.get();
            App.post(() -> applyHistory(histories));
        });
    }

    private void applyHistory(List<History> histories) {
        if (isFinishing() || isDestroyed()) return;
        cachedHistories = histories == null ? new ArrayList<>() : new ArrayList<>(histories);
        histories = cachedHistories;
        updateHistory(histories);
        List<Vod> recommendations = validVods(homeResult.getList());
        List<Vod> shelf = shelfItems(recommendations);
        updateHeroFocusTarget(!histories.isEmpty(), !shelf.isEmpty());
        if (recommendations.isEmpty()) featuredController.setInitial(historyVods(histories), homeState.getFeaturedIndex());
        homeSurfaceAvailable = !recommendations.isEmpty() || !histories.isEmpty();
        homeAvailable = homeSurfaceAvailable || !categories.isEmpty();
        if (!homeState.isHome()) return;
        if (homeState.getPage() == HomeState.Page.EMPTY && homeAvailable) pageController.showHome();
        else if (homeState.getPage() == HomeState.Page.HOME && !homeAvailable) pageController.showEmpty();
    }

    private void updateHistory(List<History> items) {
        List<History> safe = items == null ? List.of() : items;
        List<History> recent = new ArrayList<>(safe.subList(0, Math.min(3, safe.size())));
        historyAdapter.submit(recent);
        binding.historySection.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        if (recent.isEmpty()) historyAdapter.setDeleteMode(false);
    }

    private void updateHeroFocusTarget(boolean hasHistory, boolean hasRecommendations) {
        int target = hasHistory ? R.id.historyRecycler : hasRecommendations ? R.id.recommendRecycler : View.NO_ID;
        binding.play.setNextFocusDownId(target);
        binding.detail.setNextFocusDownId(target);
    }

    private void showHome(boolean focusNavigation) {
        saveCurrentCategoryPosition();
        clearFolderBackStack();
        hideVisibleCategory();
        if (!homeSurfaceAvailable && !categories.isEmpty()) {
            showCategory(categories.get(0), focusNavigation);
            return;
        }
        homeState.setSelectedCategoryId(HomeState.HOME_ID);
        navigationAdapter.select(HomeState.HOME_ID);
        if (homeAvailable) pageController.showHome();
        else pageController.showEmpty();
        binding.homeScroll.post(() -> binding.homeScroll.scrollTo(0, homeState.getHomeScrollY()));
        if (focusNavigation) focusNavigation(HomeState.HOME_ID);
    }

    private void showCategory(Class type, boolean focusNavigation) {
        if (type == null) return;
        saveCurrentCategoryPosition();
        clearFolderBackStack();
        homeState.setSelectedCategoryId(type.getTypeId());
        selectNavigationFor(type.getTypeId());
        FragmentManager manager = getSupportFragmentManager();
        if (manager.isStateSaved()) return;
        manager.executePendingTransactions();
        FragmentTransaction transaction = manager.beginTransaction();
        transaction.setReorderingAllowed(true);
        for (Fragment fragment : manager.getFragments()) if (isCategoryFragment(fragment)) transaction.hide(fragment);
        String tag = categoryTag(type.getTypeId());
        Fragment fragment = manager.findFragmentByTag(tag);
        if (fragment == null) {
            fragment = TypeFragment.newEmbeddedInstance(getHome().getKey(), type.getTypeId(), type.getStyle(), initialExtends(type), type.isFolder());
            transaction.add(R.id.categoryContainer, fragment, tag);
        } else {
            transaction.show(fragment);
        }
        // Focus-driven tab changes can arrive faster than asynchronous fragment commits. Apply
        // this transaction synchronously so A -> B -> A never creates duplicate/overlapping tabs.
        transaction.commitNow();
        visibleCategoryTag = tag;
        pageController.showCategory();
        if (fragment instanceof TypeFragment typeFragment) {
            typeFragment.restorePosition(homeState.getCategoryFocus(type.getTypeId()));
            if (!focusNavigation) binding.categoryContainer.post(typeFragment::requestContentFocus);
        }
        if (focusNavigation) focusNavigation(type.getTypeId());
    }

    private HashMap<String, String> initialExtends(Class type) {
        HashMap<String, String> extend = new HashMap<>();
        for (Filter filter : Cache.get(type)) if (filter.getInit() != null) extend.put(filter.getKey(), filter.getInit());
        return extend;
    }

    private void selectNavigationFor(String categoryId) {
        String navId = navigationAdapter.indexOf(categoryId) >= 0 ? categoryId : HomeNavItem.MORE_ID;
        navigationAdapter.select(navId);
        int position = navigationAdapter.indexOf(navId);
        if (position >= 0) binding.nav.setSelectedPosition(position);
    }

    private void focusNavigation(String categoryId) {
        String navId = navigationAdapter.indexOf(categoryId) >= 0 ? categoryId : HomeNavItem.MORE_ID;
        int position = navigationAdapter.indexOf(navId);
        if (position < 0) position = 0;
        binding.nav.setSelectedPosition(position);
        binding.nav.requestFocus();
    }

    private void hideVisibleCategory() {
        FragmentManager manager = getSupportFragmentManager();
        if (!manager.isStateSaved()) {
            manager.executePendingTransactions();
            FragmentTransaction transaction = manager.beginTransaction().setReorderingAllowed(true);
            boolean changed = false;
            for (Fragment fragment : manager.getFragments()) {
                if (!isCategoryFragment(fragment) || !fragment.isAdded() || fragment.isHidden()) continue;
                transaction.hide(fragment);
                changed = true;
            }
            if (changed) transaction.commitNow();
        }
        visibleCategoryTag = null;
    }

    private void saveCurrentCategoryPosition() {
        Fragment fragment = getVisibleCategoryFragment();
        if (fragment instanceof TypeFragment typeFragment && !homeState.isHome()) {
            homeState.putCategoryFocus(homeState.getSelectedCategoryId(), typeFragment.getSelectedPosition());
        }
    }

    private Fragment getVisibleCategoryFragment() {
        if (visibleCategoryTag != null) {
            Fragment tagged = getSupportFragmentManager().findFragmentByTag(visibleCategoryTag);
            if (tagged != null && !tagged.isHidden()) return tagged;
        }
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (isCategoryFragment(fragment) && fragment.isAdded() && !fragment.isHidden()) return fragment;
        }
        return null;
    }

    private boolean isCategoryFragment(Fragment fragment) {
        return fragment instanceof TypeFragment && fragment.getTag() != null && fragment.getTag().startsWith(CATEGORY_TAG);
    }

    private String categoryTag(String typeId) {
        return CATEGORY_TAG + Integer.toHexString(typeId.hashCode());
    }

    private void syncVisibleCategoryTag() {
        Fragment fragment = getVisibleCategoryFragment();
        visibleCategoryTag = fragment == null ? null : fragment.getTag();
    }

    private void clearFolderBackStack() {
        FragmentManager manager = getSupportFragmentManager();
        if (manager.getBackStackEntryCount() > 0) manager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    private void clearCategoryFragments() {
        FragmentManager manager = getSupportFragmentManager();
        clearFolderBackStack();
        FragmentTransaction transaction = manager.beginTransaction();
        boolean changed = false;
        for (Fragment fragment : manager.getFragments()) {
            if (!isCategoryFragment(fragment)) continue;
            transaction.remove(fragment);
            changed = true;
        }
        if (changed) transaction.commitNowAllowingStateLoss();
        visibleCategoryTag = null;
    }

    private Class findCategory(String id) {
        for (Class item : categories) if (item.getTypeId().equals(id)) return item;
        return null;
    }

    private void retry() {
        if (homeState.isHome()) {
            loadHome();
            return;
        }
        Fragment fragment = getVisibleCategoryFragment();
        if (fragment instanceof TypeFragment typeFragment) {
            pageController.showCategory();
            typeFragment.onRefresh();
        } else {
            Class type = findCategory(homeState.getSelectedCategoryId());
            if (type != null) showCategory(type, false);
            else loadHome();
        }
    }

    private void showError() {
        pageController.showError(getString(R.string.home_error_message));
    }

    private void showHomeContentError() {
        if (homeState.isHome() && homeAvailable) pageController.showHome();
        else showError();
        dismissBrandSplashWhenReady();
    }

    private void openConfig() {
        ConfigQuickDialog.create().show(this);
    }

    private void switchConfig(Config config) {
        Config previous = VodConfig.get().getConfig();
        rollbackConfig = previous == null || previous.isEmpty() || previous.getUrl().equals(config.getUrl()) ? null : previous;
        configReady = false;
        VodConfig.load(config, switchCallback());
    }

    private Callback switchCallback() {
        return new Callback() {
            @Override
            public void start() {
                pageController.showLoading(true);
            }

            @Override
            public void success() {
                rollbackConfig = null;
                resetHomeAfterConfig = true;
                configReady = true;
            }

            @Override
            public void error(String msg) {
                Config fallback = rollbackConfig;
                rollbackConfig = null;
                if (fallback == null) {
                    configReady = false;
                    showError();
                    return;
                }
                VodConfig.load(fallback, rollbackCallback());
            }
        };
    }

    private Callback rollbackCallback() {
        return new Callback() {
            @Override
            public void success() {
                resetHomeAfterConfig = false;
                configReady = true;
            }

            @Override
            public void error(String msg) {
                configReady = false;
                showError();
            }
        };
    }

    private void openVod(Vod item) {
        openVod(item, false);
    }

    private void openVod(Vod item, boolean playNow) {
        if (item == null) return;
        String key = item.getSiteKey().isEmpty() ? getHome().getKey() : item.getSiteKey();
        if (item.isAction()) homeViewModel.action(key, item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else if (playNow) VideoActivity.playNow(this, key, item.getId(), item.getName(), item.getPic());
        else VideoActivity.detail(this, key, item.getId(), item.getName(), item.getPic());
    }

    @Override
    public void onNavClick(HomeNavItem item) {
        if (item.isHome()) {
            showHome(false);
        } else if (item.isMore()) {
            showMore(item.overflow());
        } else {
            showCategory(item.type(), false);
        }
    }

    @Override
    public void onNavFocus(HomeNavItem item) {
        if (item == null || item.isMore()) return;
        if (item.isHome()) {
            if (!homeState.isHome()) showHome(true);
        } else if (!item.id().equals(homeState.getSelectedCategoryId())) {
            showCategory(item.type(), true);
        }
    }

    private void showMore(List<Class> overflow) {
        String[] names = overflow.stream().map(item -> SearchDisplayName.removeEmoji(item.getTypeName())).toArray(String[]::new);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.home_categories)
                .setItems(names, (dialog, which) -> showCategory(overflow.get(which), true))
                .show();
    }

    @Override
    public void onPosterClick(Vod item) {
        openVod(item);
    }

    @Override
    public boolean onPosterLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onPosterFocused(Vod item, int position) {
        homeState.setRecommendPosition(position);
        homeState.setRecommendKey(posterAdapter.stableKeyAt(position));
        featuredController.focus(item, position);
    }

    @Override
    public void onFeaturedOpen(Vod item) {
        openVod(item, true);
    }

    @Override
    public void onFeaturedDetails(Vod item) {
        openVod(item, false);
    }

    @Override
    public void onFeaturedDetailRequest(Vod item) {
        detailViewModel.resolve(item);
    }

    @Override
    public void onHistoryClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onHistoryDelete(History item) {
        item.delete();
        loadHistory();
    }

    @Override
    public boolean onHistoryLongClick() {
        if (historyAdapter.isDeleteMode()) {
            History.delete(VodConfig.getCid());
            loadHistory();
        } else {
            historyAdapter.setDeleteMode(true);
        }
        return true;
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) PermissionUtil.requestFile(this, allGranted -> switchConfig(config));
        else switchConfig(config);
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void openCategoryFolder(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        Fragment current = getVisibleCategoryFragment();
        if (current == null) return;
        TypeFragment next = TypeFragment.newEmbeddedInstance(key, typeId, style, new HashMap<>(extend), folder);
        String tag = CATEGORY_TAG + "folder-" + Integer.toHexString((key + typeId + System.nanoTime()).hashCode());
        getSupportFragmentManager().beginTransaction()
                .hide(current)
                .add(R.id.categoryContainer, next, tag)
                .addToBackStack(tag)
                .commit();
        visibleCategoryTag = tag;
    }

    @Override
    public void onCategoryError(String message) {
        showError();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                if (configReady) {
                    configReady = false;
                    onConfigLoaded();
                }
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
            default:
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                homeState.setSelectedCategoryId(HomeState.HOME_ID);
                loadHome();
                break;
            case HISTORY:
                loadHistory();
                break;
            case SIZE:
                loadHistory();
                loadHome();
                break;
            default:
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
            default:
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history().save(VodConfig.getCid()));
        } else {
            VodConfig.load(event.config(), castCallback(event));
        }
    }

    private Callback castCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private boolean isTopFocus(View current) {
        return current != null && (current == binding.search || current == binding.config || current == binding.settings || binding.nav.findContainingItemView(current) != null);
    }

    private boolean focusContentFromTop() {
        if (homeState.getPage() == HomeState.Page.CATEGORY) {
            Fragment fragment = getVisibleCategoryFragment();
            return fragment instanceof TypeFragment typeFragment && typeFragment.requestContentFocus();
        }
        if (binding.heroStage.getVisibility() == View.VISIBLE) return binding.play.requestFocus();
        if (binding.historySection.getVisibility() == View.VISIBLE) return binding.historyRecycler.requestFocus();
        if (binding.recommendSection.getVisibility() == View.VISIBLE) return binding.recommendRecycler.requestFocus();
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_SEARCH
                || event.getKeyCode() == KeyEvent.KEYCODE_VOICE_ASSIST
                || event.getKeyCode() == KeyEvent.KEYCODE_ASSIST)) {
            SearchActivity.startVoice(this);
            return true;
        }
        if (KeyUtil.isMenuKey(event)) SiteDialog.create().show(this);
        if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)
                && binding.historyRecycler.findContainingItemView(getCurrentFocus()) != null
                && binding.historyRecycler.getSelectedPosition() == historyAdapter.getItemCount() - 1) {
            return binding.historyMore.requestFocus();
        }
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && isTopFocus(getCurrentFocus()) && focusContentFromTop()) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        homeState.setHomeScrollY(binding.homeScroll.getScrollY());
        homeState.setFeaturedIndex(featuredController.getCurrentIndex());
        homeState.setHistoryPosition(Math.max(0, binding.historyRecycler.getSelectedPosition()));
        homeState.setRecommendPosition(Math.max(0, binding.recommendRecycler.getSelectedPosition()));
        homeState.setRecommendKey(posterAdapter.stableKeyAt(homeState.getRecommendPosition()));
        saveCurrentCategoryPosition();
        outState.putString(STATE_CATEGORY, homeState.getSelectedCategoryId());
        outState.putInt(STATE_SCROLL, homeState.getHomeScrollY());
        outState.putInt(STATE_FEATURED, homeState.getFeaturedIndex());
        outState.putInt(STATE_HISTORY, homeState.getHistoryPosition());
        outState.putInt(STATE_RECOMMEND, homeState.getRecommendPosition());
        outState.putString(STATE_RECOMMEND_KEY, homeState.getRecommendKey());
        outState.putSerializable(STATE_CATEGORY_FOCUS, homeState.copyCategoryFocus());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (featuredController != null) featuredController.startAuto();
    }

    @Override
    protected void onPause() {
        if (featuredController != null) featuredController.stopAuto();
        super.onPause();
    }

    @Override
    protected void onBackInvoked() {
        if (historyAdapter.isDeleteMode()) {
            historyAdapter.setDeleteMode(false);
        } else if (homeState.getPage() == HomeState.Page.CATEGORY) {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) getSupportFragmentManager().popBackStack();
            else showHome(true);
        } else if (binding.homeScroll.getScrollY() > 0) {
            binding.homeScroll.smoothScrollTo(0, 0);
        } else {
            if (PlaybackService.isRunning()) moveTaskToBack(true);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        binding.getRoot().removeCallbacks(deferredStartup);
        featuredController.destroy();
        DLNARendererService.stop(this);
        LiveConfig.get().clear();
        VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
