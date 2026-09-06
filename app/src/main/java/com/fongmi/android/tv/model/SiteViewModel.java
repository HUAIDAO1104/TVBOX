package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.ui.search.SearchSourceHealthStore;
import com.fongmi.android.tv.ui.search.SearchFailurePolicy;
import com.fongmi.android.tv.ui.search.SearchRelevance;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Trans;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> player;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<SearchSnapshot> aggregateSearch;
    private final MutableLiveData<Result> action;
    private final MutableLiveData<String> error;
    private final MutableLiveData<SearchProgress> searchProgress;

    private final ViewModelTaskRunner<TaskType> tasks;
    private final Map<TaskType, Integer> deliveries = new java.util.EnumMap<>(TaskType.class);
    private final ViewModelSearchRunner spiderSearches;
    private final ViewModelSearchRunner networkSearches;
    private final AtomicInteger searchSession;
    private final List<Result> aggregateResults;
    private SearchProgress currentSearchProgress;
    private String activeKeyword = "";
    private String activeSelection = "";
    private final Map<String, SourceState> sourceStates = new LinkedHashMap<>();
    private final MutableLiveData<Map<String, SourceState>> sourceProgress = new MutableLiveData<>(Map.of());
    private final Map<String, Site> searchSites = new LinkedHashMap<>();
    private final Map<String, java.util.Set<String>> seenIds = new HashMap<>();
    private int spiderEpoch, networkEpoch;

    private String cacheKey = "";
    private static final Map<String, CachedSearch> CACHE = new LinkedHashMap<>(4, 0.75f, true);
    private record CachedSearch(long time, List<Result> results, Map<String, SourceState> states,
                                Map<String, Site> sites, Map<String, java.util.Set<String>> ids, SearchProgress progress) { }

    private String cacheKey(String keyword, java.util.Set<String> families) {
        StringBuilder identity = new StringBuilder(VodConfig.getUrl()).append('|').append(keyword).append('|')
                .append(com.fongmi.android.tv.cloud.CloudCredentialBridge.generation());
        VodConfig.get().getSites().stream().filter(site -> com.fongmi.android.tv.ui.search.SearchSourcePreference.isEnabled(site, families))
                .sorted(java.util.Comparator.comparing(Site::getKey)).forEach(site -> identity.append('|')
                        .append(App.gson().toJson(site)));
        return com.github.catvod.utils.Util.md5(identity.toString());
    }

    public synchronized boolean restoreCachedSearch(String keyword, java.util.Set<String> families) {
        if (currentSearchProgress.session() > 0) return false;
        cacheKey = cacheKey(keyword, families);
        CachedSearch cached;
        synchronized (CACHE) { cached = CACHE.get(cacheKey); }
        if (cached == null || System.currentTimeMillis() - cached.time > 120000) return false;
        int session = searchSession.incrementAndGet();
        activeKeyword = keyword;
        activeSelection = com.fongmi.android.tv.ui.search.SearchSourcePreference.serialize(families);
        aggregateResults.addAll(cached.results);
        sourceStates.putAll(cached.states);
        searchSites.putAll(cached.sites);
        cached.ids.forEach((key, ids) -> seenIds.put(key, new java.util.HashSet<>(ids)));
        SearchProgress p = cached.progress;
        setSearchProgress(new SearchProgress(session, p.total(), p.completed(), p.successful(), p.empty(), p.failed(),
                p.timedOut(), p.resultCount(), false, p.cancelled()));
        aggregateSearch.setValue(new SearchSnapshot(session, aggregateResults));
        publishSources();
        spiderEpoch = spiderSearches.start(List.of(), site -> null, (site, result) -> {}, (site, error) -> {});
        networkEpoch = networkSearches.start(List.of(), site -> null, (site, result) -> {}, (site, error) -> {});
        return true;
    }

    private synchronized void cacheCurrentSearch() {
        if (cacheKey.isEmpty() || aggregateResults.isEmpty() || aggregateResults.stream().mapToInt(result -> result.getList().size()).sum() > 4000) return;
        Map<String, java.util.Set<String>> ids = new HashMap<>();
        seenIds.forEach((key, value) -> ids.put(key, java.util.Set.copyOf(value)));
        synchronized (CACHE) {
            CACHE.put(cacheKey, new CachedSearch(System.currentTimeMillis(), List.copyOf(aggregateResults), Map.copyOf(sourceStates),
                    Map.copyOf(searchSites), Map.copyOf(ids), currentSearchProgress));
            while (CACHE.size() > 3) CACHE.remove(CACHE.keySet().iterator().next());
        }
    }

    public enum SourcePhase { QUEUED, RUNNING, SUCCESS, EMPTY, FAILED, TIMED_OUT, CANCELLED }
    public record SourceState(SourcePhase phase, int page, boolean hasMore) {
        public boolean busy() { return phase == SourcePhase.QUEUED || phase == SourcePhase.RUNNING; }
        public boolean failed() { return phase == SourcePhase.FAILED || phase == SourcePhase.TIMED_OUT || phase == SourcePhase.CANCELLED; }
    }
    public LiveData<Map<String, SourceState>> getSourceProgress() { return sourceProgress; }
    public synchronized SourceState sourceState(Site site) { return sourceStates.get(site.getKey()); }
    public synchronized boolean hasSearch(String keyword, String selection) {
        return currentSearchProgress.session() > 0 && activeKeyword.equals(keyword) && activeSelection.equals(selection) && cacheKey.equals(cacheKey(keyword, com.fongmi.android.tv.ui.search.SearchSourcePreference.parse(selection)));
    }
    public synchronized void rememberSelection(String selection) {
        activeSelection = selection;
        cacheKey = cacheKey(activeKeyword, com.fongmi.android.tv.ui.search.SearchSourcePreference.parse(selection));
    }

    private void publishSources() { sourceProgress.postValue(Map.copyOf(sourceStates)); }


    public SiteViewModel() {
        result = new MutableLiveData<>();
        player = new MutableLiveData<>();
        search = new MutableLiveData<>();
        aggregateSearch = new MutableLiveData<>(new SearchSnapshot(0, List.of()));
        action = new MutableLiveData<>();
        error = new MutableLiveData<>();
        searchProgress = new MutableLiveData<>(SearchProgress.idle());
        tasks = new ViewModelTaskRunner<>(TaskType.class);
        spiderSearches = new ViewModelSearchRunner(Constant.TIMEOUT_SEARCH, 2);
        networkSearches = new ViewModelSearchRunner(Constant.TIMEOUT_SEARCH, 3);
        searchSession = new AtomicInteger();
        aggregateResults = new ArrayList<>();
        currentSearchProgress = SearchProgress.idle();
    }

    public int currentSearchSession() { return searchSession.get(); }

    public LiveData<Result> getResult() {
        return result;
    }

    public LiveData<Result> getPlayer() {
        return player;
    }

    public LiveData<Result> getSearch() {
        return search;
    }

    public LiveData<SearchSnapshot> getAggregateSearch() {
        return aggregateSearch;
    }

    public LiveData<Result> getAction() {
        return action;
    }

    public LiveData<String> getError() {
        return error;
    }

    public LiveData<SearchProgress> getSearchProgress() {
        return searchProgress;
    }

    public SiteViewModel init() {
        search.setValue(null);
        resetAggregateSearch(0);
        result.setValue(null);
        player.setValue(null);
        action.setValue(null);
        error.setValue(null);
        setSearchProgress(SearchProgress.idle());
        return this;
    }

    public void homeContent() {
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(VodConfig.get().getHome()));
    }

    public void categoryContent(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
        execute(TaskType.RESULT, result, () -> SiteApi.categoryContent(key, tid, page, filter, extend));
    }

    public void action(String key, String act) {
        execute(TaskType.ACTION, action, () -> SiteApi.action(key, act));
    }

    public void detailContent(String key, String id) {
        execute(TaskType.RESULT, result, () -> SiteApi.detailContent(key, id));
    }

    public void playerContent(String key, String flag, String id) {
        execute(TaskType.PLAYER, player, () -> SiteApi.playerContent(key, flag, id));
    }

    public void searchContent(Site site, String keyword, boolean quick, String page) {
        execute(TaskType.RESULT, result, SearchTask.create(site, keyword, quick, page));
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        cancelCatalogDiscovery();
        activeKeyword = keyword;
        cacheKey = "";
        sourceStates.clear();
        searchSites.clear();
        seenIds.clear();
        int session = searchSession.incrementAndGet();
        List<Site> safeSites = sites == null ? List.of() : sites;
        for (Site site : safeSites) {
            searchSites.put(site.getKey(), site);
            sourceStates.put(site.getKey(), new SourceState(SourcePhase.QUEUED, 1, true));
        }
        publishSources();
        List<Site> nativeSites = safeSites.stream().filter(SiteViewModel::usesNativeSpider).toList();
        List<Site> httpSites = safeSites.stream().filter(site -> !usesNativeSpider(site)).toList();
        search.setValue(null);
        resetAggregateSearch(session);
        setSearchProgress(SearchProgress.started(session, safeSites.size()));
        // Native sources run in two separate processes with globally serial slots and a hard
        // watchdog. Plain HTTP sources use an independent, cancellable three-request pool.
        spiderEpoch = spiderSearches.start(nativeSites, site -> trackedSearchTask(site, keyword, quick),
                (site, result) -> onSearchResult(session, site, result, keyword),
                (site, throwable) -> onSearchFailure(session, site, throwable));
        networkEpoch = networkSearches.start(httpSites, site -> trackedSearchTask(site, keyword, quick),
                (site, result) -> onSearchResult(session, site, result, keyword),
                (site, throwable) -> onSearchFailure(session, site, throwable));
    }

    static boolean usesNativeSpider(Site site) {
        return site != null && site.getType() == 3;
    }

    /**
     * Searches every searchable site in the currently selected warehouse/config only.  Repository
     * catalog discovery is intentionally excluded: expanding every enabled backup repository can
     * turn one user search into hundreds of Spider tasks and exhaust TV-class devices.
     */
    public int searchCurrentContent(String keyword, boolean quick) {
        return searchCurrentContent(keyword, quick, site -> true);
    }

    /** Searches only the checked sources, prioritizing the current and recently healthy sites. */
    public int searchCurrentContent(String keyword, boolean quick, Predicate<Site> sourceFilter) {
        List<Site> configured = VodConfig.get().getSites();
        Predicate<Site> safeFilter = sourceFilter == null ? site -> true : sourceFilter;
        Map<String, Site> current = uniqueSearchableSites(configured, safeFilter);
        List<Site> selected = new ArrayList<>(current.values());
        Site home = VodConfig.get().getHome();
        selected.sort((first, second) -> compareSearchPriority(first, second, home));
        SpiderDebug.log("search", "scope=checked-current-config,configured=%s,selectedUnique=%s",
                configured.size(), selected.size());
        searchContent(selected, keyword, quick);
        return searchSession.get();
    }

    /** Legacy name retained for callers; aggregate search is scoped to the active warehouse. */
    public void searchAllContent(String keyword, boolean quick) {
        searchCurrentContent(keyword, quick);
    }

    /** Legacy filtered overload; filtering never expands into backup warehouse configurations. */
    public void searchAllContent(String keyword, boolean quick, Predicate<Site> sourceFilter) {
        searchCurrentContent(keyword, quick, sourceFilter);
    }

    static int compareSearchPriority(Site first, Site second, Site home) {
        SearchSourceHealthStore.Snapshot firstHealth = SearchSourceHealthStore.get().snapshot(first.getKey());
        SearchSourceHealthStore.Snapshot secondHealth = SearchSourceHealthStore.get().snapshot(second.getKey());
        int compared = Integer.compare(availabilityRank(firstHealth), availabilityRank(secondHealth));
        if (compared != 0) return compared;
        compared = Integer.compare(firstHealth.recentFailureCount(), secondHealth.recentFailureCount());
        if (compared != 0) return compared;
        compared = Long.compare(responseRank(firstHealth), responseRank(secondHealth));
        if (compared != 0) return compared;
        compared = Long.compare(secondHealth.lastSuccessAtMillis(), firstHealth.lastSuccessAtMillis());
        if (compared != 0) return compared;
        compared = Boolean.compare(!sameSite(first, home), !sameSite(second, home));
        if (compared != 0) return compared;
        return String.valueOf(first.getName()).compareToIgnoreCase(String.valueOf(second.getName()));
    }

    private static boolean sameSite(Site first, Site second) {
        if (first == null || second == null) return false;
        return first == second || String.valueOf(first.getKey()).equals(String.valueOf(second.getKey()));
    }

    private static int availabilityRank(SearchSourceHealthStore.Snapshot health) {
        return switch (health.availability()) {
            case AVAILABLE -> 0;
            case UNKNOWN -> 1;
            case UNAVAILABLE -> 2;
        };
    }

    private static long responseRank(SearchSourceHealthStore.Snapshot health) {
        return health.responseTimeMillis() <= 0 ? Long.MAX_VALUE : health.responseTimeMillis();
    }

    static Map<String, Site> uniqueSearchableSites(List<Site> sites, Predicate<Site> filter) {
        Map<String, Site> unique = new LinkedHashMap<>();
        if (sites == null) return unique;
        for (Site site : sites) {
            if (site == null || !site.isSearchable() || !filter.test(site)) continue;
            unique.putIfAbsent(site.getKey(), site);
        }
        return unique;
    }

    private Callable<Result> trackedSearchTask(Site site, String keyword, boolean quick) {
        return trackedSearchTask(site, keyword, quick, 1);
    }

    private Callable<Result> trackedSearchTask(Site site, String keyword, boolean quick, int page) {
        int session = searchSession.get();
        SearchTask task = SearchTask.create(site, keyword, quick, String.valueOf(page));
        return () -> {
            synchronized (this) {
                if (searchSession.get() != session) throw new java.util.concurrent.CancellationException();
                sourceStates.put(site.getKey(), new SourceState(SourcePhase.RUNNING, page, true));
                publishSources();
            }
            SearchSourceHealthStore.get().begin(site.getKey());
            return task.call();
        };
    }

    private synchronized void onSearchResult(int session, Site site, Result result, String keyword) {
        if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
        SourceState prior = sourceStates.get(site.getKey());
        int page = prior == null ? 1 : prior.page();
        java.util.Set<String> seen = seenIds.computeIfAbsent(site.getKey(), ignored -> new java.util.HashSet<>());
        int before = seen.size();
        if (result != null) for (com.fongmi.android.tv.bean.Vod vod : result.getList()) seen.add(vod.getId());
        boolean hasMore = result != null && !result.getList().isEmpty() && seen.size() > before
                && (result.getPageCount() <= 0 || page < result.getPageCount());
        Result filtered = filterRelevant(result, keyword);
        boolean valid = filtered != null && !filtered.getList().isEmpty();
        sourceStates.put(site.getKey(), new SourceState(valid ? SourcePhase.SUCCESS
                : result != null && result.hasMsg() ? SourcePhase.FAILED : SourcePhase.EMPTY, page, hasMore));
        publishSources();
        if (valid) SearchSourceHealthStore.get().success(site.getKey());
        else if (result != null && result.hasMsg()) SearchSourceHealthStore.get().failure(site.getKey());
        else SearchSourceHealthStore.get().empty(site.getKey());
        if (filtered != null && !filtered.getList().isEmpty()) {
            aggregateResults.add(filtered);
            aggregateSearch.postValue(new SearchSnapshot(session, aggregateResults));
            enrichPosters(session, site, filtered);
        }
        // MutableLiveData.postValue coalesces pending values. Aggregate search can finish several
        // sources in the same main-loop frame, so dispatch each source as its own main-thread event
        // or valid results (and their source tabs) can silently disappear.
        App.post(() -> {
            synchronized (SiteViewModel.this) {
                if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
            }
            search.setValue(filtered);
        });
        recomputeSearchProgress();
    }

    private Result filterRelevant(Result result, String keyword) {
        if (result == null || result.getList().isEmpty()) return result;
        SearchRelevance relevance = new SearchRelevance();
        com.fongmi.android.tv.ui.search.SearchTitleNormalizer.NormalizedTitle query = com.fongmi.android.tv.ui.search.SearchTitleNormalizer.parse(keyword);
        result.setList(result.getList().stream()
                .filter(vod -> relevance.isRelevant(query, com.fongmi.android.tv.ui.search.SearchSource.builder()
                        .vodId(vod.getId()).title(vod.getName()).remarks(vod.getRemarks()).type(vod.getTypeName()).year(vod.getYear()).build()))
                .toList());
        return result;
    }

    private synchronized void resetAggregateSearch(int session) {
        aggregateResults.clear();
        aggregateSearch.setValue(new SearchSnapshot(session, List.of()));
    }

    private synchronized void onSearchFailure(int session, Site site, Throwable throwable) {
        if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
        SearchSourceHealthStore.get().failure(site.getKey());
        SourceState prior = sourceStates.get(site.getKey());
        sourceStates.put(site.getKey(), new SourceState(throwable instanceof TimeoutException ? SourcePhase.TIMED_OUT : SourcePhase.FAILED,
                prior == null ? 1 : prior.page(), true));
        publishSources();
        recomputeSearchProgress();
    }

    private void recomputeSearchProgress() {
        int completed = 0, successful = 0, empty = 0, failed = 0, timedOut = 0;
        boolean running = false;
        for (SourceState state : sourceStates.values()) {
            if (state.busy()) running = true;
            else if (state.phase() != SourcePhase.CANCELLED) completed++;
            switch (state.phase()) {
                case SUCCESS -> successful++;
                case EMPTY -> empty++;
                case FAILED -> failed++;
                case TIMED_OUT -> timedOut++;
                default -> { }
            }
        }
        setSearchProgress(new SearchProgress(searchSession.get(), sourceStates.size(), completed, successful,
                empty, failed, timedOut, aggregateResults.stream().mapToInt(value -> value.getList().size()).sum(), running, false));
    }

    private synchronized void setSearchProgress(SearchProgress progress) {
        currentSearchProgress = progress;
        searchProgress.postValue(progress);
        if (!progress.running()) cacheCurrentSearch();
    }

    private void enrichPosters(int session, Site site, Result original) {
        if (site.getType() > 2 || original.getList().stream().noneMatch(vod -> vod.getPic().isEmpty())) return;
        Task.execute(() -> {
            try {
                Result copy = new Result();
                copy.setList(new ArrayList<>(original.getList()));
                Result enriched = SiteApi.fetchPic(site, copy);
                Map<String, com.fongmi.android.tv.bean.Vod> byId = new HashMap<>();
                for (com.fongmi.android.tv.bean.Vod vod : enriched.getList()) byId.put(vod.getId(), vod);
                Result merged = new Result();
                merged.setList(original.getList().stream().map(vod -> {
                    com.fongmi.android.tv.bean.Vod replacement = byId.get(vod.getId());
                    if (replacement == null) return vod;
                    replacement.setSite(site);
                    return replacement;
                }).toList());
                synchronized (this) {
                    if (session != searchSession.get() || currentSearchProgress.cancelled()) return;
                    int position = aggregateResults.indexOf(original);
                    if (position < 0) return;
                    aggregateResults.set(position, merged);
                    aggregateSearch.postValue(new SearchSnapshot(session, aggregateResults));
                }
            } catch (Exception ignored) { /* Base titles and IDs remain usable. */ }
        });
    }

    /** Incremental retry/paging: never clears cards from successful sources. */
    public synchronized boolean requestMore(Predicate<Site> filter, boolean nextPage) {
        List<Site> selected = searchSites.values().stream().filter(filter).filter(site -> {
            SourceState state = sourceStates.get(site.getKey());
            return state != null && !state.busy() && (!nextPage || state.hasMore());
        }).toList();
        if (selected.isEmpty()) return false;
        int session = searchSession.get();
        if (currentSearchProgress.cancelled()) {
            spiderEpoch = spiderSearches.start(List.of(), site -> null, (site, value) -> {}, (site, error) -> {});
            networkEpoch = networkSearches.start(List.of(), site -> null, (site, value) -> {}, (site, error) -> {});
        }
        Map<Site, Integer> pages = new LinkedHashMap<>();
        for (Site site : selected) {
            SourceState state = sourceStates.get(site.getKey());
            int page = nextPage && !state.failed() ? state.page() + 1 : state.failed() ? state.page() : 1;
            pages.put(site, page);
            sourceStates.put(site.getKey(), new SourceState(SourcePhase.QUEUED, page, true));
            if (page == 1 && !nextPage) seenIds.remove(site.getKey());
        }
        recomputeSearchProgress();
        for (Site site : selected) {
            int page = pages.get(site);
            ViewModelSearchRunner runner = usesNativeSpider(site) ? spiderSearches : networkSearches;
            runner.add(List.of(site), usesNativeSpider(site) ? spiderEpoch : networkEpoch,
                    value -> trackedSearchTask(value, activeKeyword, false, page),
                    (value, result) -> onSearchResult(session, value, result, activeKeyword),
                    (value, error) -> onSearchFailure(session, value, error));
        }
        publishSources();
        return true;
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        error.setValue(null);
        int generation = deliveries.getOrDefault(type, 0) + 1;
        deliveries.put(type, generation);
        tasks.execute(type, Constant.TIMEOUT_VOD, Task.interactiveExecutor(), callable,
                value -> App.post(() -> { if (deliveries.getOrDefault(type, 0) == generation) liveData.setValue(value); }),
                error -> App.post(() -> {
                    if (deliveries.getOrDefault(type, 0) != generation) return;
                    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    this.error.setValue(message);
                    liveData.setValue(Result.error(message));
                }));
    }

    public void cancelPendingPlayback() {
        for (TaskType type : List.of(TaskType.RESULT, TaskType.PLAYER)) {
            deliveries.put(type, deliveries.getOrDefault(type, 0) + 1);
            tasks.cancel(type);
        }
    }

    public void prioritizeSearch(Predicate<Site> preferred) {
        spiderSearches.prioritize(preferred);
        networkSearches.prioritize(preferred);
    }

    public void stopSearch() {
        cancelCatalogDiscovery();
        spiderSearches.stop();
        networkSearches.stop();
        synchronized (this) {
            sourceStates.replaceAll((key, state) -> state.busy()
                    ? new SourceState(SourcePhase.CANCELLED, state.page(), true) : state);
            publishSources();
            setSearchProgress(currentSearchProgress.cancel());
        }
    }

    private void cancelCatalogDiscovery() {
        // Repository discovery used to happen here. Search is now deliberately scoped to the
        // active warehouse, so there is no catalog work to cancel.
    }

    @Override
    protected void onCleared() {
        stopSearch();
        spiderSearches.close();
        networkSearches.close();
        cancelPendingPlayback();
        tasks.cancelAll();
    }

    private record SearchTask(Site site, String keyword, boolean quick, String page) implements Callable<Result> {

        private static final String FIRST_PAGE = "1";

        SearchTask {
            keyword = Trans.t2s(keyword);
        }

        private static SearchTask create(Site site, String keyword, boolean quick) {
            return create(site, keyword, quick, FIRST_PAGE);
        }

        private static SearchTask create(Site site, String keyword, boolean quick, String page) {
            return new SearchTask(site, keyword, quick, page);
        }

        @Override
        public Result call() throws Exception {
            if (quick && !site.isQuickSearch()) return Result.empty();
            try {
                return usesNativeSpider(site)
                        ? com.fongmi.android.tv.search.IsolatedSpiderSearch.search(site, keyword, quick, page)
                        : SiteApi.searchContent(site, keyword, quick, page);
            } catch (Exception first) {
                if (!SearchFailurePolicy.isEmptyPayload(first)) throw first;
                try {
                    return usesNativeSpider(site)
                        ? com.fongmi.android.tv.search.IsolatedSpiderSearch.search(site, keyword, quick, page)
                        : SiteApi.searchContent(site, keyword, quick, page);
                } catch (Exception second) {
                    if (SearchFailurePolicy.isEmptyPayload(second)) return Result.empty();
                    throw second;
                }
            }
        }
    }

    private enum TaskType {RESULT, PLAYER, ACTION}
}
