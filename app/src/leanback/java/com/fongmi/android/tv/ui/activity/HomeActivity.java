package com.fongmi.android.tv.ui.activity;

import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

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
    private Config rollbackConfig;
    private boolean homeUiRestored;
    private boolean cachedHistoryShown;
    private List<History> cachedHistories = new ArrayList<>();
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
        SplashScreen.installSplashScreen(this);
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
        pageController.showLoading(false);
        initConfig();
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
        binding.emptyConfig.setOnClickListener(view -> openConfig());
        binding.errorConfig.setOnClickListener(view -> openConfig());
        binding.errorSettings.setOnClickListener(view -> SettingActivity.start(this));
        binding.retry.setOnClickListener(view -> retry());
        bindUtilityFocus(binding.search);
        bindUtilityFocus(binding.config);
        bindUtilityFocus(binding.settings);
        bindUtilityFocus(binding.recommendMore);
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
            List<History> histories = History.get();
            App.post(() -> onCachedHistoryLoaded(histories));
        });
        Task.execute(() -> {
            VodConfig.get().init();
            LiveConfig.get().init();
            WallConfig.get().init();
            App.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                VodConfig.get().load(configCallback(false));
                LiveConfig.get().load();
                WallConfig.get().load();
            });
        });
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
        homeAvailable = true;
        updateHistory(histories);
        binding.recommendSection.setVisibility(View.GONE);
        binding.recommendMore.setVisibility(View.GONE);
        featuredController.setInitial(historyVods(histories), homeState.getFeaturedIndex());
        updateHeroFocusTarget(true, false);
        pageController.showHome();
        binding.historyRecycler.setSelectedPosition(Math.min(homeState.getHistoryPosition(), histories.size() - 1));
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
                    return;
                }
                showError();
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
        homeAvailable = !featured.isEmpty() || !histories.isEmpty();
        if (!homeAvailable) pageController.showEmpty();
        else pageController.showHome();
        restorePageAfterLoad();
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
        else {
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
        homeAvailable = !recommendations.isEmpty() || !histories.isEmpty();
        if (!homeState.isHome()) return;
        if (homeState.getPage() == HomeState.Page.EMPTY && homeAvailable) pageController.showHome();
        else if (homeState.getPage() == HomeState.Page.HOME && !homeAvailable) pageController.showEmpty();
    }

    private void updateHistory(List<History> items) {
        historyAdapter.submit(items);
        binding.historySection.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (items.isEmpty()) historyAdapter.setDeleteMode(false);
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
        FragmentTransaction transaction = manager.beginTransaction();
        for (Fragment fragment : manager.getFragments()) if (isCategoryFragment(fragment)) transaction.hide(fragment);
        String tag = categoryTag(type.getTypeId());
        Fragment fragment = manager.findFragmentByTag(tag);
        if (fragment == null) {
            fragment = TypeFragment.newEmbeddedInstance(getHome().getKey(), type.getTypeId(), type.getStyle(), initialExtends(type), type.isFolder());
            transaction.add(R.id.categoryContainer, fragment, tag);
        } else {
            transaction.show(fragment);
        }
        transaction.commit();
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
        Fragment fragment = getVisibleCategoryFragment();
        if (fragment != null) getSupportFragmentManager().beginTransaction().hide(fragment).commit();
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

    private void showMore(List<Class> overflow) {
        String[] names = overflow.stream().map(Class::getTypeName).toArray(String[]::new);
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
