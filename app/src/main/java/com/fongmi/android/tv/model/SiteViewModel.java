package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.repository.RepositorySiteParser;
import com.fongmi.android.tv.repository.RepositorySiteRegistry;
import com.fongmi.android.tv.ui.search.SearchSourceHealthStore;
import com.fongmi.android.tv.ui.search.SearchFailurePolicy;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Trans;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
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
    private final List<Future<?>> catalogFutures;
    private final Set<String> searchedSiteKeys;
    private final List<Result> aggregateResults;
    private SearchProgress currentSearchProgress;
    private int searchRunnerEpoch;

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
        catalogFutures = new CopyOnWriteArrayList<>();
        searchedSiteKeys = ConcurrentHashMap.newKeySet();
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
        searchedSiteKeys.clear();
        sites.forEach(site -> searchedSiteKeys.add(site.getKey()));
        searchRunnerEpoch = searches.start(sites, site -> trackedSearchTask(site, keyword, quick),
                (site, result) -> onSearchResult(session, site, result),
                (site, throwable) -> onSearchFailure(session, site, throwable));
    }

    /**
     * Searches the active configuration immediately, then discovers every enabled repository
     * configuration off the main thread and adds its sites to the same stable search session.
     */
    public void searchAllContent(String keyword, boolean quick) {
        searchAllContent(keyword, quick, site -> true);
    }

    public void searchAllContent(String keyword, boolean quick, Predicate<Site> sourceFilter) {
        cancelCatalogDiscovery();
        int session = searchSession.incrementAndGet();
        search.setValue(null);
        resetAggregateSearch(session);
        searchedSiteKeys.clear();

        Predicate<Site> safeFilter = sourceFilter == null ? site -> true : sourceFilter;
        List<Site> activeSites = new ArrayList<>(VodConfig.get().getSites().stream()
                .filter(Site::isSearchable)
                .filter(safeFilter)
                .toList());
        activeSites.forEach(site -> searchedSiteKeys.add(site.getKey()));
        // One pending slot represents the repository catalog itself. It prevents the active-site
        // searches from briefly publishing a terminal state while Room is still enumerating the
        // enabled repositories on a worker thread.
        setSearchProgress(SearchProgress.started(session, activeSites.size() + 1));
        searchRunnerEpoch = searches.start(activeSites, site -> trackedSearchTask(site, keyword, quick),
                (site, value) -> onSearchResult(session, site, value),
                (site, throwable) -> onSearchFailure(session, site, throwable));
        discoverRepositoryCatalog(session, keyword, quick, VodConfig.getUrl(), safeFilter);
    }

    private void discoverRepositoryCatalog(int session, String keyword, boolean quick, String activeUrl,
                                           Predicate<Site> sourceFilter) {
        FluentFuture<List<RepositoryEntry>> future = FluentFuture
                .from(com.fongmi.android.tv.utils.Task.largeExecutor().submit(() -> repositoryEntries(activeUrl)))
                .withTimeout(Math.min(Constant.TIMEOUT_SEARCH, TimeUnit.SECONDS.toMillis(15)),
                        TimeUnit.MILLISECONDS, com.fongmi.android.tv.utils.Task.scheduler());
        catalogFutures.add(future);
        future.addCallback(com.fongmi.android.tv.utils.Task.callback(
                entries -> onRepositoryEntries(session, keyword, quick, future, entries, sourceFilter),
                throwable -> onCatalogFailure(session, future, throwable)), MoreExecutors.directExecutor());
    }

    private List<RepositoryEntry> repositoryEntries(String activeUrl) {
        Map<String, RepositoryEntry> entries = new LinkedHashMap<>();
        for (Repository repository : RepositoryManager.get().getEnabled()) {
            for (RepositoryItem item : RepositoryManager.get().getItems(repository.getId())) {
                if (item.getType() != 0 || item.getUrl().isBlank()) continue;
                if (sameUrl(activeUrl, item.getUrl())) continue;
                entries.putIfAbsent(repository.getId() + "\n" + item.getUrl(), new RepositoryEntry(repository, item));
            }
        }
        return new ArrayList<>(entries.values());
    }

    private synchronized void onRepositoryEntries(int session, String keyword, boolean quick,
                                                  Future<?> future, List<RepositoryEntry> entries,
                                                  Predicate<Site> sourceFilter) {
        catalogFutures.remove(future);
        if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
        List<RepositoryEntry> safe = entries == null ? List.of() : entries;
        if (safe.isEmpty()) {
            setSearchProgress(currentSearchProgress.result(Result.empty()));
            return;
        }
        // Replace the single catalog placeholder with one independently timed placeholder per
        // repository configuration. Each configuration will subsequently expand to its sites.
        setSearchProgress(currentSearchProgress.expand(safe.size() - 1));
        for (RepositoryEntry entry : safe) discover(entry, session, keyword, quick, sourceFilter);
    }

    private boolean sameUrl(String first, String second) {
        if (first == null || second == null) return false;
        return first.trim().replaceAll("/+$", "").equals(second.trim().replaceAll("/+$", ""));
    }

    private void discover(RepositoryEntry entry, int session, String keyword, boolean quick,
                          Predicate<Site> sourceFilter) {
        String tag = "AggregateRepository-" + entry.repository().getId() + "-" + entry.item().getId();
        FluentFuture<List<Site>> future = FluentFuture
                .from(com.fongmi.android.tv.utils.Task.largeExecutor().submit(() -> {
                    String json = Decoder.getJson(UrlUtil.convert(entry.item().getUrl()), tag);
                    return RepositorySiteParser.parse(entry.repository(), entry.item(), json);
                }))
                .withTimeout(Math.min(Constant.TIMEOUT_SEARCH, TimeUnit.SECONDS.toMillis(15)),
                        TimeUnit.MILLISECONDS, com.fongmi.android.tv.utils.Task.scheduler());
        catalogFutures.add(future);
        future.addCallback(com.fongmi.android.tv.utils.Task.callback(
                sites -> onSitesDiscovered(session, keyword, quick, future, sites, sourceFilter),
                throwable -> onCatalogFailure(session, future, throwable)), MoreExecutors.directExecutor());
    }

    private synchronized void onSitesDiscovered(int session, String keyword, boolean quick,
                                                Future<?> future, List<Site> sites,
                                                Predicate<Site> sourceFilter) {
        catalogFutures.remove(future);
        if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
        List<Site> additions = sites == null ? List.of() : sites.stream()
                .filter(Site::isSearchable)
                .filter(sourceFilter)
                .filter(site -> searchedSiteKeys.add(site.getKey()))
                .toList();
        if (additions.isEmpty()) {
            setSearchProgress(currentSearchProgress.result(Result.empty()));
            return;
        }
        additions.forEach(RepositorySiteRegistry::register);
        setSearchProgress(currentSearchProgress.expand(additions.size() - 1));
        searches.add(additions, searchRunnerEpoch, site -> trackedSearchTask(site, keyword, quick),
                (site, result) -> onSearchResult(session, site, result),
                (site, throwable) -> onSearchFailure(session, site, throwable));
    }

    private synchronized void onCatalogFailure(int session, Future<?> future, Throwable throwable) {
        catalogFutures.remove(future);
        if (throwable instanceof CancellationException) return;
        if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
        setSearchProgress(currentSearchProgress.failure(isTimeout(throwable)));
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    private Callable<Result> trackedSearchTask(Site site, String keyword, boolean quick) {
        SearchTask task = SearchTask.create(site, keyword, quick);
        return () -> {
            SearchSourceHealthStore.get().begin(site.getKey());
            return task.call();
        };
    }

    private synchronized void onSearchResult(int session, Site site, Result result) {
        if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
        SearchSourceHealthStore.get().success(site.getKey());
        if (result != null && !result.getList().isEmpty()) {
            aggregateResults.add(result);
            aggregateSearch.postValue(new SearchSnapshot(session, aggregateResults));
        }
        // MutableLiveData.postValue coalesces pending values. Aggregate search can finish several
        // sources in the same main-loop frame, so dispatch each source as its own main-thread event
        // or valid results (and their source tabs) can silently disappear.
        App.post(() -> {
            synchronized (SiteViewModel.this) {
                if (currentSearchProgress.session() != session || currentSearchProgress.cancelled()) return;
            }
            search.setValue(result);
        });
        setSearchProgress(currentSearchProgress.result(result));
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
        catalogFutures.forEach(future -> future.cancel(true));
        catalogFutures.clear();
    }

    @Override
    protected void onCleared() {
        stopSearch();
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

    private record RepositoryEntry(Repository repository, RepositoryItem item) {
    }

    private enum TaskType {RESULT, PLAYER, ACTION}
}
