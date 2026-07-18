package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SearchProgress;
import com.fongmi.android.tv.model.SearchSnapshot;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.playback.vod.DetailSourceFallbackPolicy;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.SearchSourceFamilyAdapter;
import com.fongmi.android.tv.ui.adapter.SearchSourcePanelAdapter;
import com.fongmi.android.tv.ui.adapter.SearchWorkAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.search.SearchAggregator;
import com.fongmi.android.tv.ui.search.SearchSource;
import com.fongmi.android.tv.ui.search.SearchSourcePreference;
import com.fongmi.android.tv.ui.search.SearchSourceHealthStore;
import com.fongmi.android.tv.ui.search.SearchWork;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.gson.reflect.TypeToken;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CollectActivity extends BaseActivity implements SearchWorkAdapter.Listener,
        SearchSourcePanelAdapter.Listener, SearchSourceFamilyAdapter.Listener {

    // Five columns keep 3:4 posters readable at TV distance while retaining two visible rows.
    private static final int COLUMN_COUNT = 5;
    private static final String STATE_WORK_ID = "aggregate_search_work_id";
    private static final String STATE_SOURCE_ID = "aggregate_search_source_id";
    private static final String STATE_LAYOUT = "aggregate_search_layout";
    private static final String STATE_SOURCE_FAMILY = "aggregate_search_source_family";
    private static final String SOURCE_FAMILY_ALL = "source-family-all";
    private static final Pattern EPISODE_COUNT = Pattern.compile("(?:全|更新至|至)?\\s*(\\d{1,4})\\s*(?:集|期)");

    private ActivityCollectBinding binding;
    private SiteViewModel viewModel;
    private SearchWorkAdapter workAdapter;
    private SearchSourcePanelAdapter sourceAdapter;
    private SearchSourceFamilyAdapter sourceFamilyAdapter;
    private SearchAggregator aggregator;
    private SearchSnapshot latestSnapshot;
    private SearchProgress progress = SearchProgress.idle();
    private Map<String, Vod> vodBySource = new LinkedHashMap<>();
    private final Map<String, Vod> allVodBySource = new LinkedHashMap<>();
    private final Map<String, List<Vod>> fallbackCandidatesBySource = new LinkedHashMap<>();
    private final Map<String, SearchSource> sourceCache = new LinkedHashMap<>();
    private final Map<String, Integer> sourceFamilyCounts = new LinkedHashMap<>();
    private final Map<String, SearchAggregator> aggregatorsByFamily = new LinkedHashMap<>();
    private final Map<String, Map<String, Vod>> vodByFamily = new LinkedHashMap<>();
    private final Map<String, String> borrowedPosterBySource = new LinkedHashMap<>();
    private final Set<String> enabledSearchFamilies = new LinkedHashSet<>();
    private SearchAggregator fallbackAggregator;
    private String selectedWorkId;
    private String selectedSourceId;
    private String panelWorkId;
    private String pendingRestoreWorkId;
    private String pendingRestoreSourceId;
    private Parcelable pendingLayoutState;
    private String activeSourceFamily = "";
    private SearchSnapshot pendingSnapshot;
    private boolean snapshotRenderScheduled;
    private final Runnable renderPendingSnapshot = this::renderPendingSnapshot;

    private enum SearchUiState {
        IDLE, SEARCHING, PARTIAL_SUCCESS, SUCCESS, EMPTY, CANCELLED, ERROR
    }

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        String keyword = getIntent().getStringExtra("keyword");
        return keyword == null ? "" : keyword.trim();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        pendingRestoreWorkId = savedInstanceState == null ? null : savedInstanceState.getString(STATE_WORK_ID);
        pendingRestoreSourceId = savedInstanceState == null ? null : savedInstanceState.getString(STATE_SOURCE_ID);
        pendingLayoutState = savedInstanceState == null ? null : savedInstanceState.getParcelable(STATE_LAYOUT);
        activeSourceFamily = savedInstanceState == null
                ? ""
                : savedInstanceState.getString(STATE_SOURCE_FAMILY, "");
        setupLists();
        setupViewModel();
        saveKeyword();
        resetAndSearch(false);
        binding.body.post(this::focusActiveSourceFamily);
    }

    @Override
    protected void initEvent() {
        binding.stop.setOnClickListener(view -> {
            if (progress.running()) viewModel.stopSearch();
            else resetAndSearch(true);
        });
        binding.retry.setOnClickListener(view -> resetAndSearch(true));
        binding.sourceClose.setOnClickListener(view -> closeSourcePanel());
        binding.sourceFilter.setOnClickListener(view -> showSourceFilter());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        viewModel.stopSearch();
        setIntent(intent);
        saveKeyword();
        pendingRestoreWorkId = null;
        pendingRestoreSourceId = null;
        pendingLayoutState = null;
        activeSourceFamily = "";
        resetAndSearch(false);
        binding.body.post(this::focusActiveSourceFamily);
    }

    private void setupLists() {
        ViewGroup.LayoutParams sourcePanelParams = binding.sourcePanel.getLayoutParams();
        sourcePanelParams.width = Math.min(ResUtil.dp2px(360), Math.round(ResUtil.getScreenWidth(this) * 0.30f));
        binding.sourcePanel.setLayoutParams(sourcePanelParams);
        binding.resultRecycler.setHasFixedSize(true);
        binding.resultRecycler.setPreserveFocusAfterLayout(true);
        binding.resultRecycler.setItemAnimator(null);
        binding.resultRecycler.setLayoutManager(new GridLayoutManager(this, COLUMN_COUNT));
        binding.resultRecycler.addItemDecoration(new SpaceItemDecoration(COLUMN_COUNT, 8));
        binding.resultRecycler.setItemViewCacheSize(COLUMN_COUNT * 2);
        binding.resultRecycler.setAdapter(workAdapter = new SearchWorkAdapter(this, COLUMN_COUNT));

        binding.sourceFamilyRecycler.setItemAnimator(null);
        binding.sourceFamilyRecycler.setPreserveFocusAfterLayout(true);
        binding.sourceFamilyRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.sourceFamilyRecycler.setAdapter(sourceFamilyAdapter = new SearchSourceFamilyAdapter(this));

        binding.sourceRecycler.setItemAnimator(null);
        binding.sourceRecycler.setPreserveFocusAfterLayout(true);
        binding.sourceRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.sourceRecycler.setAdapter(sourceAdapter = new SearchSourcePanelAdapter(this));
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        viewModel.getAggregateSearch().observe(this, this::applySearchSnapshot);
        viewModel.getSearchProgress().observe(this, value -> {
            if (value == null) return;
            if (progress.session() > 0 && value.session() > 0 && value.session() < progress.session()) return;
            boolean startedNow = !progress.running() && value.running();
            boolean finishedNow = progress.running() && !value.running();
            progress = value;
            if (!value.running()) {
                renderPendingSnapshot();
                if (finishedNow && latestSnapshot != null) finalizeFallbackCandidates();
            }
            renderState(resolveState(value));
            // Result snapshots update this lane in batches. Empty/failed sources only need one
            // final state refresh, not a full result scan for every progress tick.
            if (startedNow || !value.running()) updateSourceFamilyLane();
            restoreFocusIfPossible();
            if (finishedNow) focusSearchCompletionTarget();
        });
    }

    private void resetAndSearch(boolean focusStop) {
        viewModel.stopSearch();
        closeSourcePanel(false);
        fallbackAggregator = new SearchAggregator(getKeyword());
        latestSnapshot = null;
        pendingSnapshot = null;
        snapshotRenderScheduled = false;
        if (binding != null) binding.resultArea.removeCallbacks(renderPendingSnapshot);
        vodBySource = new LinkedHashMap<>();
        allVodBySource.clear();
        fallbackCandidatesBySource.clear();
        sourceCache.clear();
        sourceFamilyCounts.clear();
        aggregatorsByFamily.clear();
        vodByFamily.clear();
        borrowedPosterBySource.clear();
        workAdapter.updatePosterOverrides(Map.of());
        selectedWorkId = null;
        selectedSourceId = null;
        workAdapter.submit(List.of());
        progress = SearchProgress.idle();
        enabledSearchFamilies.clear();
        enabledSearchFamilies.addAll(resolveSelectedSourceFamilies());
        Setting.putSearchSources(searchSourceScope(), SearchSourcePreference.serialize(enabledSearchFamilies));
        ensureActiveSourceFamily();
        initializeFamilyCaches();
        binding.result.setText(getString(R.string.collect_result, getKeyword()));
        updateSourceFilterLabel();
        updateSourceFamilyLane();
        renderState(SearchUiState.IDLE);
        Set<String> searchAllowList = Set.copyOf(enabledSearchFamilies);
        viewModel.searchAllContent(getKeyword(), false,
                site -> SearchSourcePreference.isEnabled(site, searchAllowList));
        if (focusStop) binding.stop.post(binding.stop::requestFocus);
    }

    private void showSourceFilter() {
        List<String> choices = SearchSourcePreference.choices(VodConfig.get().getSites());
        String[] displayChoices = choices.stream().map(source -> {
            return SearchSourcePreference.isFourKDefault(source) ? source + " · 4K" : source;
        }).toArray(String[]::new);
        Set<String> pending = new LinkedHashSet<>(selectedSourceFamilies());
        boolean[] checked = new boolean[choices.size()];
        for (int index = 0; index < choices.size(); index++) checked[index] = pending.contains(choices.get(index));
        AlertDialog alert = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.search_v2_source_filter_title)
                .setMultiChoiceItems(displayChoices, checked, (dialog, which, enabled) -> {
                    String selected = choices.get(which);
                    if (enabled) pending.add(selected);
                    else pending.remove(selected);
                })
                .setNeutralButton(R.string.search_v2_source_filter_default, (dialog, which) -> {
                    applySourcePreference(SearchSourcePreference.resolveSelection(
                            "", VodConfig.get().getSites(), VodConfig.get().getHome()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.search_v2_source_filter_apply, (dialog, which) -> {
                    applySourcePreference(pending);
                })
                .create();
        alert.setOnShowListener(ignored -> compactSourceFilterRows(alert.getListView()));
        alert.show();
    }

    private void compactSourceFilterRows(ListView listView) {
        if (listView == null) return;
        listView.setDividerHeight(0);
        // Android TV's dialog theme expands choice rows to 48dp; six rows then
        // hide the non-default entry behind scrolling on 1080p. A compact
        // 38dp focus row keeps all five defaults plus "other" discoverable.
        int rowHeight = ResUtil.dp2px(38);
        listView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                child.setMinimumHeight(rowHeight);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null) {
                    params.height = rowHeight;
                    child.setLayoutParams(params);
                }
                if (child instanceof TextView) {
                    ((TextView) child).setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        listView.post(() -> {
            for (int index = 0; index < listView.getChildCount(); index++) {
                View child = listView.getChildAt(index);
                child.setMinimumHeight(rowHeight);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null) {
                    params.height = rowHeight;
                    child.setLayoutParams(params);
                }
                if (child instanceof TextView) {
                    ((TextView) child).setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                }
            }
        });
    }

    private String searchSourceScope() {
        String url = VodConfig.getUrl();
        if (url != null && !url.isBlank()) return url.trim();
        Site home = VodConfig.get().getHome();
        return home == null ? "active-default" : "active-default:" + home.getKey();
    }

    private Set<String> selectedSourceFamilies() {
        if (enabledSearchFamilies.isEmpty()) enabledSearchFamilies.addAll(resolveSelectedSourceFamilies());
        return new LinkedHashSet<>(enabledSearchFamilies);
    }

    private Set<String> resolveSelectedSourceFamilies() {
        return SearchSourcePreference.resolveSelection(Setting.getSearchSources(searchSourceScope()),
                VodConfig.get().getSites(), VodConfig.get().getHome());
    }

    /** The aggregate All row is always present and must not be duplicated as a source row. */
    private Set<String> visibleSourceFamilies() {
        return selectedSourceFamilies();
    }

    private void applySourcePreference(Set<String> selected) {
        Set<String> resolved = SearchSourcePreference.resolveSelection(
                SearchSourcePreference.serialize(selected), VodConfig.get().getSites(), VodConfig.get().getHome());
        enabledSearchFamilies.clear();
        enabledSearchFamilies.addAll(resolved);
        Setting.putSearchSources(searchSourceScope(), SearchSourcePreference.serialize(resolved));
        activeSourceFamily = SOURCE_FAMILY_ALL;
        resetAndSearch(false);
        binding.sourceFamilyRecycler.post(this::focusActiveSourceFamily);
    }

    private void updateSourceFilterLabel() {
        Set<String> selected = selectedSourceFamilies();
        binding.sourceFilter.setText(getString(R.string.search_v2_source_filter_count, selected.size()));
    }

    private void applySearchSnapshot(SearchSnapshot snapshot) {
        if (snapshot == null || aggregator == null) return;
        int latestSession = latestSnapshot == null ? 0 : latestSnapshot.session();
        if (latestSession > 0 && snapshot.session() < latestSession) return;
        if (progress.session() > 0 && snapshot.session() < progress.session()) return;
        pendingSnapshot = snapshot;
        if (snapshot.results().isEmpty() || !progress.running()) {
            renderPendingSnapshot();
        } else if (!snapshotRenderScheduled) {
            snapshotRenderScheduled = true;
            // Coalesce closely spaced provider callbacks into one frame. The data remains
            // incremental; this only avoids repeated layout/Diff work on the main thread.
            binding.resultArea.postDelayed(renderPendingSnapshot, 320);
        }
    }

    private void renderPendingSnapshot() {
        if (binding == null) return;
        binding.resultArea.removeCallbacks(renderPendingSnapshot);
        snapshotRenderScheduled = false;
        SearchSnapshot snapshot = pendingSnapshot;
        if (snapshot == null) return;
        pendingSnapshot = null;
        if (latestSnapshot != null && snapshot.session() < latestSnapshot.session()) return;
        SearchSnapshot previous = latestSnapshot;
        latestSnapshot = snapshot;
        if (isAppendOnly(previous, snapshot)) {
            int from = previous == null ? 0 : previous.results().size();
            appendResults(snapshot.results().subList(from, snapshot.results().size()));
            if (!progress.running()) finalizeFallbackCandidates();
            submitWorks();
            updateSourceFamilyLane();
            renderState(resolveState(progress));
        } else {
            rebuildForActiveSource();
        }
    }

    private boolean isAppendOnly(SearchSnapshot previous, SearchSnapshot next) {
        if (previous == null) return true;
        if (previous.session() != next.session() || previous.results().size() > next.results().size()) return false;
        for (int index = 0; index < previous.results().size(); index++) {
            // SearchSnapshot copies only the list container. Existing Result instances are durable,
            // so identity is a cheap and strict check that no earlier result was replaced.
            if (previous.results().get(index) != next.results().get(index)) return false;
        }
        return true;
    }

    private void appendResults(List<Result> results) {
        if (results == null || results.isEmpty()) return;
        Set<String> enabledFamilies = visibleSourceFamilies();
        ensureFamilyCaches(enabledFamilies);
        ensureSourceFamilyCountKeys(enabledFamilies);
        for (Result result : results) {
            for (Vod vod : result.getList()) {
                Site site = vod.getSite() == null ? new Site() : vod.getSite();
                incrementSourceFamilyCounts(site, enabledFamilies);
                SearchSource source = sourceCache.computeIfAbsent(rawSourceKey(vod), ignored -> sourceFrom(vod));
                allVodBySource.put(source.stableId(), vod);
                refreshBorrowedPosters(fallbackAggregator.add(source).work());
                if (progress.running()) fallbackCandidatesBySource.put(source.stableId(), List.of(vod));
                addToFamily(SOURCE_FAMILY_ALL, source, vod);
                for (String family : enabledFamilies) {
                    if (matchesSourceFamily(site, family)) addToFamily(family, source, vod);
                }
            }
        }
        workAdapter.updatePosterOverrides(borrowedPosterBySource);
    }

    private void rebuildForActiveSource() {
        SearchAggregator fallbackGroups = new SearchAggregator(getKeyword());
        Map<String, Vod> allSources = new LinkedHashMap<>();
        aggregatorsByFamily.clear();
        vodByFamily.clear();
        borrowedPosterBySource.clear();
        initializeFamilyCaches();
        boolean buildCrossSourceFallback = !progress.running();
        List<Result> results = latestSnapshot == null ? List.of() : latestSnapshot.results();
        for (Result result : results) {
            List<Vod> items = new ArrayList<>(result.getList());
            for (Vod vod : items) {
                Site site = vod.getSite() == null ? new Site() : vod.getSite();
                SearchSource source = sourceCache.computeIfAbsent(rawSourceKey(vod), ignored -> sourceFrom(vod));
                allSources.put(source.stableId(), vod);
                refreshBorrowedPosters(fallbackGroups.add(source).work());
                addToFamily(SOURCE_FAMILY_ALL, source, vod);
                for (String family : visibleSourceFamilies()) {
                    if (matchesSourceFamily(site, family)) addToFamily(family, source, vod);
                }
            }
        }
        fallbackAggregator = fallbackGroups;
        activateFamily(activeSourceFamily);
        allVodBySource.clear();
        allVodBySource.putAll(allSources);
        workAdapter.updatePosterOverrides(borrowedPosterBySource);
        rebuildSourceFamilyCounts();
        if (buildCrossSourceFallback) {
            rebuildFallbackCandidates(fallbackGroups, allSources);
        } else {
            // While results stream in, keep direct navigation cheap and deterministic. The final
            // snapshot computes normalized-title fallback groups once, instead of repeating an
            // O(n²) grouping pass for every returning source.
            fallbackCandidatesBySource.clear();
            allSources.forEach((stableId, vod) -> fallbackCandidatesBySource.put(stableId, List.of(vod)));
        }
        submitWorks();
        updateSourceFamilyLane();
        renderState(resolveState(progress));
    }

    private void initializeFamilyCaches() {
        ensureFamilyCaches(visibleSourceFamilies());
        activateFamily(activeSourceFamily);
    }

    private void ensureFamilyCaches(Set<String> families) {
        aggregatorsByFamily.computeIfAbsent(SOURCE_FAMILY_ALL, ignored -> new SearchAggregator(getKeyword()));
        vodByFamily.computeIfAbsent(SOURCE_FAMILY_ALL, ignored -> new LinkedHashMap<>());
        for (String family : families) {
            aggregatorsByFamily.computeIfAbsent(family, ignored -> new SearchAggregator(getKeyword()));
            vodByFamily.computeIfAbsent(family, ignored -> new LinkedHashMap<>());
        }
    }

    private void addToFamily(String family, SearchSource source, Vod vod) {
        SearchAggregator familyAggregator = aggregatorsByFamily.get(family);
        Map<String, Vod> familySources = vodByFamily.get(family);
        if (familyAggregator == null || familySources == null) return;
        SearchAggregator.Update update = familyAggregator.addUnaggregated(source);
        if (update.changed()) familySources.put(source.stableId(), vod);
    }

    private void activateFamily(String family) {
        String target = aggregatorsByFamily.containsKey(family) ? family : SOURCE_FAMILY_ALL;
        activeSourceFamily = target;
        aggregator = aggregatorsByFamily.computeIfAbsent(target, ignored -> new SearchAggregator(getKeyword()));
        vodBySource = vodByFamily.computeIfAbsent(target, ignored -> new LinkedHashMap<>());
    }

    /** Shares a real poster only inside a cautiously normalized cross-source work group. */
    private void refreshBorrowedPosters(SearchWork work) {
        if (work == null || work.sources().isEmpty()) return;
        String poster = "";
        for (SearchSource source : work.rankedSources()) {
            if (!source.requiresLogin() && source.posterUrl() != null && !source.posterUrl().isBlank()) {
                poster = source.posterUrl();
                break;
            }
        }
        if (poster.isBlank()) {
            for (SearchSource source : work.rankedSources()) {
                if (source.posterUrl() != null && !source.posterUrl().isBlank()) {
                    poster = source.posterUrl();
                    break;
                }
            }
        }
        if (poster.isBlank()) return;
        for (SearchSource source : work.sources()) borrowedPosterBySource.put(source.stableId(), poster);
    }

    private void finalizeFallbackCandidates() {
        if (fallbackAggregator == null || allVodBySource.isEmpty()) return;
        rebuildFallbackCandidates(fallbackAggregator, allVodBySource);
    }

    private void rebuildFallbackCandidates(SearchAggregator grouped, Map<String, Vod> allSources) {
        fallbackCandidatesBySource.clear();
        for (SearchWork work : grouped.works()) {
            List<Vod> candidates = new ArrayList<>();
            for (SearchSource source : work.rankedSources()) {
                Vod vod = allSources.get(source.stableId());
                if (vod != null && vod.getSite() != null) candidates.add(vod);
            }
            if (candidates.isEmpty()) continue;
            List<Vod> snapshot = List.copyOf(candidates);
            for (SearchSource source : work.sources()) fallbackCandidatesBySource.put(source.stableId(), snapshot);
        }
    }

    private boolean matchesSourceFamily(Site site, String family) {
        if (SOURCE_FAMILY_ALL.equals(family)) return true;
        if (SearchSourcePreference.ALL_OTHER_SOURCES.equals(family)) {
            for (String preferred : SearchSourcePreference.DEFAULT_SOURCES) {
                if (SearchSourcePreference.isEnabled(site, Set.of(preferred))) return false;
            }
            return true;
        }
        return SearchSourcePreference.isEnabled(site, Set.of(family));
    }

    private void updateSourceFamilyLane() {
        if (sourceFamilyAdapter == null) return;
        Set<String> enabled = visibleSourceFamilies();
        ensureSourceFamilyCountKeys(enabled);
        if (!SOURCE_FAMILY_ALL.equals(activeSourceFamily) && !enabled.contains(activeSourceFamily)) {
            activeSourceFamily = SOURCE_FAMILY_ALL;
        }
        List<SearchSourceFamilyAdapter.Item> items = new ArrayList<>();
        items.add(sourceFamilyItem(SOURCE_FAMILY_ALL, getString(R.string.search_v2_source_lane_all),
                sourceFamilyCounts.getOrDefault(SOURCE_FAMILY_ALL, 0)));
        for (String family : enabled) {
            String label;
            if (SearchSourcePreference.ALL_OTHER_SOURCES.equals(family)) {
                label = getString(R.string.search_v2_source_filter_other);
            } else {
                label = family + (SearchSourcePreference.isFourKDefault(family) ? " · 4K" : "");
            }
            items.add(sourceFamilyItem(family, label, sourceFamilyCounts.getOrDefault(family, 0)));
        }
        sourceFamilyAdapter.submit(items);
    }

    private void rebuildSourceFamilyCounts() {
        Set<String> enabled = visibleSourceFamilies();
        sourceFamilyCounts.clear();
        ensureSourceFamilyCountKeys(enabled);
        if (latestSnapshot == null) return;
        for (Result result : latestSnapshot.results()) {
            for (Vod vod : result.getList()) {
                incrementSourceFamilyCounts(vod.getSite() == null ? new Site() : vod.getSite(), enabled);
            }
        }
    }

    private void ensureSourceFamilyCountKeys(Set<String> enabled) {
        sourceFamilyCounts.putIfAbsent(SOURCE_FAMILY_ALL, 0);
        for (String family : enabled) sourceFamilyCounts.putIfAbsent(family, 0);
        sourceFamilyCounts.keySet().removeIf(key -> !SOURCE_FAMILY_ALL.equals(key) && !enabled.contains(key));
    }

    private void incrementSourceFamilyCounts(Site site, Set<String> enabled) {
        sourceFamilyCounts.computeIfPresent(SOURCE_FAMILY_ALL, (key, count) -> count + 1);
        for (String family : enabled) {
            if (matchesSourceFamily(site, family)) {
                sourceFamilyCounts.computeIfPresent(family, (key, count) -> count + 1);
            }
        }
    }

    private void ensureActiveSourceFamily() {
        Set<String> enabled = visibleSourceFamilies();
        if (activeSourceFamily.isBlank()
                || !SOURCE_FAMILY_ALL.equals(activeSourceFamily) && !enabled.contains(activeSourceFamily)) {
            activeSourceFamily = SOURCE_FAMILY_ALL;
        }
    }

    private SearchSourceFamilyAdapter.Item sourceFamilyItem(String id, String label, int count) {
        String status = progress.running() && count == 0
                ? getString(R.string.search_v2_source_lane_searching)
                : String.valueOf(count);
        return new SearchSourceFamilyAdapter.Item(id, label, status, id.equals(activeSourceFamily));
    }

    @Override
    public void onSelect(SearchSourceFamilyAdapter.Item item) {
        if (item == null || item.id().equals(activeSourceFamily)) return;
        View currentFocus = getCurrentFocus();
        boolean keepSourceFocus = currentFocus != null
                && binding.sourceFamilyRecycler.findContainingViewHolder(currentFocus) != null;
        closeSourcePanel(false);
        activeSourceFamily = item.id();
        selectedWorkId = null;
        selectedSourceId = null;
        binding.resultArea.animate().cancel();
        binding.resultArea.setAlpha(0.72f);
        activateFamily(activeSourceFamily);
        updateSourceFamilyLane();
        submitWorks();
        renderState(resolveState(progress));
        binding.resultRecycler.scrollToPosition(0);
        binding.resultArea.animate().alpha(1f).setDuration(120).start();
        if (!keepSourceFocus && workAdapter.getItemCount() > 0) focusWork(null, null);
    }

    private SearchSource sourceFrom(Vod vod) {
        Site site = vod.getSite() == null ? new Site() : vod.getSite();
        SearchSourceHealthStore.Snapshot health = SearchSourceHealthStore.get().snapshot(site.getKey());
        String scope = site.getRepositoryId() == 0
                ? "current:" + site.getConfigUrl()
                : String.valueOf(site.getRepositoryId());
        String config = site.getConfigName().isEmpty() ? getString(R.string.search_v2_current_repository) : site.getConfigName();
        return SearchSource.builder()
                .repositoryId(scope)
                .repositoryName(site.getRepositoryName())
                .configId(config)
                .siteKey(site.getKey())
                .siteName(site.getName())
                .vodId(vod.getId())
                .title(vod.getName())
                .posterUrl(vod.getPic())
                .year(vod.getYear())
                .area(vod.getArea())
                .type(vod.getTypeName())
                .actors(vod.getActor())
                .remarks(vod.getRemarks())
                .episodeCount(parseEpisodeCount(vod.getRemarks()))
                .availability(health.availability())
                .detailsAvailable(!vod.getId().isEmpty())
                .deviceCompatible(true)
                .requiresLogin(requiresLogin(site))
                .lastSuccessAtMillis(health.lastSuccessAtMillis())
                .responseTimeMillis(health.responseTimeMillis())
                .recentFailureCount(health.recentFailureCount())
                .repositoryPriority(site.getRepositoryPriority())
                .build();
    }

    private String rawSourceKey(Vod vod) {
        Site site = vod.getSite() == null ? new Site() : vod.getSite();
        return site.getRepositoryId() + "\u0000" + site.getConfigUrl() + "\u0000" + site.getKey()
                + "\u0000" + vod.getId() + "\u0000" + vod.getName();
    }

    private int parseEpisodeCount(String remarks) {
        if (remarks == null || remarks.isBlank()) return 0;
        Matcher matcher = EPISODE_COUNT.matcher(remarks);
        int result = 0;
        while (matcher.find()) result = Math.max(result, Integer.parseInt(matcher.group(1)));
        return result;
    }

    private boolean requiresLogin(Site site) {
        String value = (site.getName() + " " + site.getConfigName()).toLowerCase(Locale.ROOT);
        return value.contains("网盘") || value.contains("云盘") || value.contains("夸克")
                || value.contains("uc") || value.contains("阿里") || value.contains("迅雷");
    }

    private void submitWorks() {
        View currentFocus = getCurrentFocus();
        RecyclerView.ViewHolder focusedHolder = currentFocus == null
                ? null : binding.resultRecycler.findContainingViewHolder(currentFocus);
        boolean resultHadFocus = focusedHolder != null
                && focusedHolder.getBindingAdapterPosition() != RecyclerView.NO_POSITION;
        String focused = focusedWorkId();
        String focusedSource = focusedWorkSourceId();
        workAdapter.submit(aggregator.snapshot());
        binding.resultSummary.setText(getString(R.string.search_v2_result_summary,
                aggregator.workCount(), aggregator.sourceCount()));
        if (focused != null) selectedWorkId = focused;
        if (focusedSource != null) selectedSourceId = focusedSource;
        updateOpenPanel();
        restoreFocusIfPossible();
        if (resultHadFocus && workAdapter.positionOf(focused) < 0
                && workAdapter.positionContainingSource(focusedSource) >= 0) {
            focusWork(focused, focusedSource);
        }
    }

    private void updateOpenPanel() {
        if (!isSourcePanelOpen() || panelWorkId == null) return;
        SearchWork work = aggregator.findWork(panelWorkId);
        String focusedSource = focusedSourceId();
        if (focusedSource == null) focusedSource = selectedSourceId;
        if (work == null) work = findWorkBySource(focusedSource);
        if (work == null) {
            closeSourcePanel();
            return;
        }
        panelWorkId = work.stableId();
        selectedWorkId = work.stableId();
        binding.sourceTitle.setText(getString(R.string.search_v2_source_title, work.displayTitle()));
        boolean changed = sourceAdapter.submit(work.rankedSources(), work.recommendedSource());
        if (changed) restoreSourceFocus(focusedSource);
    }

    private SearchWork findWorkBySource(String sourceStableId) {
        if (sourceStableId == null) return null;
        for (SearchWork work : aggregator.snapshot()) {
            for (SearchSource source : work.sources()) {
                if (sourceStableId.equals(source.stableId())) return work;
            }
        }
        return null;
    }

    private void saveKeyword() {
        if (getKeyword().isEmpty()) return;
        List<String> items;
        try {
            items = Setting.getKeyword().isEmpty()
                    ? new ArrayList<>()
                    : App.gson().fromJson(Setting.getKeyword(), TypeToken.getParameterized(List.class, String.class).getType());
        } catch (Exception ignored) {
            items = new ArrayList<>();
        }
        if (items == null) items = new ArrayList<>();
        items.remove(getKeyword());
        items.add(0, getKeyword());
        if (items.size() > 9) items = new ArrayList<>(items.subList(0, 9));
        Setting.putKeyword(App.gson().toJson(items));
    }

    private SearchUiState resolveState(SearchProgress value) {
        if (value.cancelled()) return SearchUiState.CANCELLED;
        if (value.running()) return aggregator.workCount() > 0 ? SearchUiState.PARTIAL_SUCCESS : SearchUiState.SEARCHING;
        if (aggregator.workCount() > 0) {
            return value.failed() + value.timedOut() > 0 ? SearchUiState.PARTIAL_SUCCESS : SearchUiState.SUCCESS;
        }
        if (value.session() == 0) return SearchUiState.IDLE;
        if (value.failed() + value.timedOut() > 0 && value.completed() == value.failed() + value.timedOut()) {
            return SearchUiState.ERROR;
        }
        return SearchUiState.EMPTY;
    }

    private void renderState(SearchUiState state) {
        View previousFocus = getCurrentFocus();
        binding.stateBadge.setText(getStateLabel(state));
        binding.stateBadge.setTextColor(ContextCompat.getColor(this, getStateColor(state)));
        int workCount = aggregator == null ? 0 : aggregator.workCount();
        int sourceCount = aggregator == null ? 0 : aggregator.sourceCount();
        int unavailable = progress.failed() + progress.timedOut();
        if (progress.running()) {
            binding.resultSummary.setText(getString(R.string.search_v2_compact_searching,
                    workCount, sourceCount, progress.completed(), progress.total()));
        } else if (unavailable > 0 && workCount > 0) {
            binding.resultSummary.setText(getResources().getQuantityString(
                    R.plurals.search_v2_compact_complete_with_unavailable, unavailable,
                    workCount, sourceCount, progress.completed(), progress.total(), unavailable));
        } else {
            binding.resultSummary.setText(getString(R.string.search_v2_compact_complete,
                    workCount, sourceCount, progress.completed(), progress.total()));
        }
        binding.status.setText(getString(R.string.search_v2_source_progress, progress.total(),
                progress.completed(), progress.pending(), progress.timedOut(), progress.failed()));
        binding.progress.setMax(Math.max(1, progress.total()));
        binding.progress.setProgress(progress.completed());
        boolean running = progress.running();
        boolean hasResults = aggregator != null && aggregator.workCount() > 0;
        boolean canRestartAtTop = !running && hasResults
                && (state == SearchUiState.CANCELLED || state == SearchUiState.PARTIAL_SUCCESS);
        binding.stop.setText(running ? R.string.search_v2_stop : R.string.search_v2_retry);
        binding.stop.setVisibility(running || canRestartAtTop ? View.VISIBLE : View.GONE);
        binding.resultRecycler.setVisibility(hasResults ? View.VISIBLE : View.INVISIBLE);
        boolean showPanel = !hasResults && state != SearchUiState.IDLE;
        binding.statePanel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        boolean loading = state == SearchUiState.SEARCHING;
        binding.stateProgress.setVisibility(showPanel && loading ? View.VISIBLE : View.GONE);
        binding.retry.setVisibility(showPanel && !loading ? View.VISIBLE : View.GONE);
        if (showPanel) {
            if (loading) {
                binding.stateTitle.setText(R.string.search_v2_loading_title);
                binding.stateMessage.setText(R.string.search_v2_loading_message);
            } else if (state == SearchUiState.EMPTY) {
                binding.stateTitle.setText(R.string.search_v2_empty_title);
                binding.stateMessage.setText(progress.total() == 0 ? R.string.search_v2_no_source_message : R.string.search_v2_empty_message);
            } else if (state == SearchUiState.CANCELLED) {
                binding.stateTitle.setText(R.string.search_v2_cancelled_title);
                binding.stateMessage.setText(R.string.search_v2_cancelled_message);
            } else {
                binding.stateTitle.setText(isNetworkConnected() ? R.string.search_v2_error_title : R.string.search_v2_network_title);
                binding.stateMessage.setText(isNetworkConnected() ? R.string.search_v2_error_message : R.string.search_v2_network_message);
            }
        }
        if ((previousFocus == binding.stop && binding.stop.getVisibility() != View.VISIBLE)
                || (previousFocus != null && !hasResults
                && binding.resultRecycler.findContainingViewHolder(previousFocus) != null)) {
            binding.body.post(this::focusPrimaryContent);
        }
    }

    private int getStateLabel(SearchUiState state) {
        return switch (state) {
            case SEARCHING -> R.string.search_v2_state_searching;
            case PARTIAL_SUCCESS -> R.string.search_v2_state_partial;
            case SUCCESS -> R.string.search_v2_state_success;
            case EMPTY -> R.string.search_v2_state_empty;
            case CANCELLED -> R.string.search_v2_state_cancelled;
            case ERROR -> R.string.search_v2_state_error;
            default -> R.string.search_v2_state_idle;
        };
    }

    private int getStateColor(SearchUiState state) {
        return switch (state) {
            case SUCCESS -> R.color.tv_success;
            case PARTIAL_SUCCESS, CANCELLED -> R.color.tv_warning;
            case ERROR -> R.color.tv_error;
            default -> R.color.tv_text_secondary;
        };
    }

    private boolean isNetworkConnected() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || manager.getActiveNetwork() == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    public void onOpen(SearchWork work) {
        if (work == null || work.recommendedSource() == null) return;
        selectedWorkId = work.stableId();
        selectedSourceId = work.recommendedSource().stableId();
        openSource(work.recommendedSource());
    }

    @Override
    public void onShowSources(SearchWork work) {
        if (work == null || work.sources().isEmpty()) return;
        selectedWorkId = work.stableId();
        selectedSourceId = sourceAnchor(work);
        panelWorkId = work.stableId();
        binding.sourceTitle.setText(getString(R.string.search_v2_source_title, work.displayTitle()));
        sourceAdapter.submit(work.rankedSources(), work.recommendedSource());
        binding.sourceScrim.setVisibility(View.VISIBLE);
        binding.sourcePanel.setVisibility(View.VISIBLE);
        binding.sourcePanel.bringToFront();
        binding.sourceRecycler.post(() -> {
            RecyclerView.ViewHolder holder = binding.sourceRecycler.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else binding.sourceRecycler.requestFocus();
        });
    }

    @Override
    public void onOpen(SearchSource source) {
        if (source != null) selectedSourceId = source.stableId();
        openSource(source);
    }

    private void openSource(SearchSource source) {
        if (source == null) return;
        Vod selected = vodBySource.get(source.stableId());
        if (selected == null || selected.getSite() == null) return;
        List<Vod> ranked = fallbackCandidatesBySource.getOrDefault(source.stableId(), List.of(selected));
        ArrayList<Vod> candidates = DetailSourceFallbackPolicy.prioritize(selected, ranked,
                vod -> vod.getSiteKey() + '\u0000' + vod.getId());
        VideoActivity.collect(this, candidates);
    }

    @Override
    public void onClose() {
        closeSourcePanel();
    }

    private boolean isSourcePanelOpen() {
        return binding.sourcePanel.getVisibility() == View.VISIBLE;
    }

    private void closeSourcePanel() {
        closeSourcePanel(true);
    }

    private void closeSourcePanel(boolean restoreFocus) {
        if (!isSourcePanelOpen()) return;
        String focusedSource = focusedSourceId();
        if (focusedSource != null) selectedSourceId = focusedSource;
        binding.sourcePanel.setVisibility(View.GONE);
        binding.sourceScrim.setVisibility(View.GONE);
        panelWorkId = null;
        if (restoreFocus) focusWork(selectedWorkId, selectedSourceId);
    }

    private String focusedWorkId() {
        View focus = getCurrentFocus();
        if (focus == null) return selectedWorkId;
        RecyclerView.ViewHolder holder = binding.resultRecycler.findContainingViewHolder(focus);
        if (holder == null) return selectedWorkId;
        int position = holder.getBindingAdapterPosition();
        if (!isValidPosition(position, workAdapter.getItemCount())) return selectedWorkId;
        return workAdapter.get(position).stableId();
    }

    private String focusedWorkSourceId() {
        View focus = getCurrentFocus();
        RecyclerView.ViewHolder holder = focus == null ? null : binding.resultRecycler.findContainingViewHolder(focus);
        SearchWork work = null;
        if (holder != null && isValidPosition(holder.getBindingAdapterPosition(), workAdapter.getItemCount())) {
            work = workAdapter.get(holder.getBindingAdapterPosition());
        } else {
            int position = workAdapter.positionOf(selectedWorkId);
            if (position < 0) position = workAdapter.positionContainingSource(selectedSourceId);
            if (position >= 0) work = workAdapter.get(position);
        }
        return work == null ? selectedSourceId : sourceAnchor(work);
    }

    private String sourceAnchor(SearchWork work) {
        if (work == null || work.sources().isEmpty()) return null;
        if (selectedSourceId != null) {
            for (SearchSource source : work.sources()) {
                if (selectedSourceId.equals(source.stableId())) return selectedSourceId;
            }
        }
        return work.sources().get(0).stableId();
    }

    private String focusedSourceId() {
        View focus = getCurrentFocus();
        if (focus == null) return null;
        RecyclerView.ViewHolder holder = binding.sourceRecycler.findContainingViewHolder(focus);
        if (holder == null) return null;
        int position = holder.getBindingAdapterPosition();
        if (!isValidPosition(position, sourceAdapter.getItemCount())) return null;
        return sourceAdapter.get(position).stableId();
    }

    private static boolean isValidPosition(int position, int size) {
        return position != RecyclerView.NO_POSITION && position >= 0 && position < size;
    }

    private void restoreSourceFocus(String stableId) {
        if (stableId == null) return;
        for (int position = 0; position < sourceAdapter.getItemCount(); position++) {
            if (!stableId.equals(sourceAdapter.get(position).stableId())) continue;
            int target = position;
            binding.sourceRecycler.scrollToPosition(target);
            binding.sourceRecycler.post(() -> {
                RecyclerView.ViewHolder holder = binding.sourceRecycler.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
            });
            return;
        }
    }

    private void restoreFocusIfPossible() {
        if (pendingRestoreWorkId != null || pendingRestoreSourceId != null) {
            int position = workAdapter.positionOf(pendingRestoreWorkId);
            if (position < 0) position = workAdapter.positionContainingSource(pendingRestoreSourceId);
            if (position >= 0) {
                String id = workAdapter.get(position).stableId();
                String sourceId = pendingRestoreSourceId;
                pendingRestoreWorkId = null;
                pendingRestoreSourceId = null;
                pendingLayoutState = null;
                focusWork(id, sourceId);
                return;
            }
        }
        if (pendingLayoutState != null && workAdapter.getItemCount() > 0 && !progress.running()) {
            binding.resultRecycler.getLayoutManager().onRestoreInstanceState(pendingLayoutState);
            pendingLayoutState = null;
        }
    }

    private void focusWork(String stableId) {
        focusWork(stableId, selectedSourceId);
    }

    private void focusWork(String stableId, String sourceStableId) {
        int position = workAdapter.positionOf(stableId);
        if (position < 0) position = workAdapter.positionContainingSource(sourceStableId);
        if (position < 0 && workAdapter.getItemCount() > 0) position = 0;
        if (position < 0) {
            focusActiveSourceFamily();
            return;
        }
        SearchWork targetWork = workAdapter.get(position);
        selectedWorkId = targetWork.stableId();
        if (workAdapter.positionContainingSource(sourceStableId) == position) selectedSourceId = sourceStableId;
        else selectedSourceId = sourceAnchor(targetWork);
        int target = position;
        binding.resultRecycler.scrollToPosition(target);
        binding.resultRecycler.post(() -> {
            RecyclerView.ViewHolder holder = binding.resultRecycler.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void focusPrimaryContent() {
        if (workAdapter.getItemCount() > 0) {
            focusWork(selectedWorkId, selectedSourceId);
        } else if (binding.retry.getVisibility() == View.VISIBLE) {
            binding.retry.requestFocus();
        } else if (binding.stop.getVisibility() == View.VISIBLE) {
            binding.stop.requestFocus();
        } else {
            focusActiveSourceFamily();
        }
    }

    private void focusActiveSourceFamily() {
        int position = sourceFamilyAdapter.positionOf(activeSourceFamily);
        if (position < 0) position = 0;
        int target = position;
        binding.sourceFamilyRecycler.scrollToPosition(target);
        binding.sourceFamilyRecycler.post(() -> {
            RecyclerView.ViewHolder holder = binding.sourceFamilyRecycler.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
            else binding.sourceFamilyRecycler.requestFocus();
        });
    }

    private void focusSearchCompletionTarget() {
        if (isSourcePanelOpen()) return;
        View focus = getCurrentFocus();
        boolean headerHasFocus = focus == null || focus == binding.stop || focus == binding.sourceFilter;
        if (!headerHasFocus || selectedWorkId != null) return;
        if (workAdapter.getItemCount() > 0) {
            activeSourceFamily = SOURCE_FAMILY_ALL;
            updateSourceFamilyLane();
            binding.body.post(this::focusActiveSourceFamily);
        } else if (binding.retry.getVisibility() == View.VISIBLE) {
            binding.body.post(binding.retry::requestFocus);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK && isSourcePanelOpen()) {
                closeSourcePanel();
                return true;
            }
            if (isSourcePanelOpen() && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                closeSourcePanel();
                return true;
            }
            View focus = getCurrentFocus();
            if ((focus == binding.stop || focus == binding.sourceFilter) && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusActiveSourceFamily();
                return true;
            }
            RecyclerView.ViewHolder holder = focus == null ? null : binding.resultRecycler.findContainingViewHolder(focus);
            if (holder != null && holder.getBindingAdapterPosition() < COLUMN_COUNT && code == KeyEvent.KEYCODE_DPAD_UP) {
                binding.sourceFilter.requestFocus();
                return true;
            }
            if (holder != null && holder.getBindingAdapterPosition() % COLUMN_COUNT == 0
                    && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusActiveSourceFamily();
                return true;
            }
            RecyclerView.ViewHolder familyHolder = focus == null
                    ? null : binding.sourceFamilyRecycler.findContainingViewHolder(focus);
            if (familyHolder != null && familyHolder.getBindingAdapterPosition() == 0
                    && code == KeyEvent.KEYCODE_DPAD_UP) {
                binding.sourceFilter.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        String workId = focusedWorkId();
        if (workId != null) outState.putString(STATE_WORK_ID, workId);
        String sourceId = focusedWorkSourceId();
        if (sourceId != null) outState.putString(STATE_SOURCE_ID, sourceId);
        outState.putString(STATE_SOURCE_FAMILY, activeSourceFamily);
        RecyclerView.LayoutManager manager = binding.resultRecycler.getLayoutManager();
        if (manager != null) outState.putParcelable(STATE_LAYOUT, manager.onSaveInstanceState());
    }

    @Override
    protected void onBackInvoked() {
        if (isSourcePanelOpen()) {
            closeSourcePanel();
            return;
        }
        if (progress.running()) viewModel.stopSearch();
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            binding.resultArea.removeCallbacks(renderPendingSnapshot);
            binding.resultArea.animate().cancel();
        }
        if (isFinishing()) viewModel.stopSearch();
        super.onDestroy();
    }
}
