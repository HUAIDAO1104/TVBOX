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
    private final ViewModelSearchRunner searches;
    private final AtomicInteger searchSession;
    private final List<Result> aggregateResults;
    private SearchProgress currentSearchProgress;

    public SiteViewModel() {
        result = new MutableLiveData<>();
        player = new MutableLiveData<>();
        search = new MutableLiveData<>();
        aggregateSearch = new MutableLiveData<>(new SearchSnapshot(0, List.of()));
        action = new MutableLiveData<>();
        error = new MutableLiveData<>();
        searchProgress = new MutableLiveData<>(SearchProgress.idle());
        tasks = new ViewModelTaskRunner<>(TaskType.class);
        searches = new ViewModelSearchRunner();
        searchSession = new AtomicInteger();
        aggregateResults = new ArrayList<>();
        currentSearchProgress = SearchProgress.idle();
    }

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
        int session = searchSession.incrementAndGet();
        search.setValue(null);
        resetAggregateSearch(session);
        setSearchProgress(SearchProgress.started(session, sites.size()));
        searches.start(sites, site -> trackedSearchTask(site, keyword, quick),
                (site, result) -> onSearchResult(session, site, result, keyword),
                (site, throwable) -> onSearchFailure(session, site, throwable));
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
        int compared = Boolean.compare(!sameSite(first, home), !sameSite(second, home));
        if (compared != 0) return compared;
        SearchSourceHealthStore.Snapshot firstHealth = SearchSourceHealthStore.get().snapshot(first.getKey());
        SearchSourceHealthStore.Snapshot secondHealth = SearchSourceHealthStore.get().snapshot(second.getKey());
        compared = Integer.compare(availabilityRank(firstHealth), availabilityRank(secondHealth));
        if (compared != 0) return compared;
        compared = Integer.compare(firstHealth.recentFailureCount(), secondHealth.recentFailureCount());
        if (compared != 0) return compared;
        compared = Long.compare(responseRank(firstHealth), responseRank(secondHealth));
        if (compared != 0) return compared;
        compared = Long.compare(secondHealth.lastSuccessAtMillis(), firstHealth.lastSuccessAtMillis());
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
            unique.putIfAbsent(searchBackendIdentity(site), site);
        }
        return unique;
    }

    private static String searchBackendIdentity(Site site) {
        // Scoped repository keys are routing identifiers, not backend identities. Configs often
        // declare the same source more than once with a different key/name. Invoking both wastes
        // memory and can enter the same third-party native Spider concurrently after a timeout.
        return site.getType() + "\u0000" + site.getApi() + "\u0000" + site.getJar() + "\u0000"
                + site.getExt() + "\u0000" + site.getHeader().hashCode();
    }

    private Callable<Result> trackedSearchTask(Site site, String keyword, boolean quick) {
        SearchTask task = SearchTask.create(site, keyword, quick);
        return () -> {
            SearchSourceHealthStore.get().begin(site.getKey());
            return task.call();
        };
    }

    private synchronized void onSearchResult(int session, Site site, Result result, String keyword) {
        if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
        SearchSourceHealthStore.get().success(site.getKey());
        Result filtered = filterRelevant(result, keyword);
        if (filtered != null && !filtered.getList().isEmpty()) {
            aggregateResults.add(filtered);
            aggregateSearch.postValue(new SearchSnapshot(session, aggregateResults));
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
        setSearchProgress(currentSearchProgress.result(filtered));
    }

    private Result filterRelevant(Result result, String keyword) {
        if (result == null || result.getList().isEmpty()) return result;
        SearchRelevance relevance = new SearchRelevance();
        result.setList(result.getList().stream()
                .filter(vod -> relevance.isPotentiallyRelevant(keyword, vod.getName()))
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
        setSearchProgress(currentSearchProgress.failure(throwable instanceof TimeoutException));
    }

    private synchronized void setSearchProgress(SearchProgress progress) {
        currentSearchProgress = progress;
        searchProgress.postValue(progress);
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        error.setValue(null);
        tasks.execute(type, Constant.TIMEOUT_VOD, callable, liveData::postValue, error -> {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            this.error.postValue(message);
            liveData.postValue(Result.error(message));
            com.github.catvod.crawler.SpiderDebug.log(error);
        });
    }

    public void stopSearch() {
        cancelCatalogDiscovery();
        searches.stop();
        synchronized (this) {
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
        searches.close();
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
                return SiteApi.searchContent(site, keyword, quick, page);
            } catch (Exception first) {
                if (!SearchFailurePolicy.isEmptyPayload(first)) throw first;
                try {
                    return SiteApi.searchContent(site, keyword, quick, page);
                } catch (Exception second) {
                    if (SearchFailurePolicy.isEmptyPayload(second)) return Result.empty();
                    throw second;
                }
            }
        }
    }

    private enum TaskType {RESULT, PLAYER, ACTION}
}
