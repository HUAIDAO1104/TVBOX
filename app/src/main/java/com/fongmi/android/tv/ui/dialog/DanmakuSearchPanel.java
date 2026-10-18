package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.DanmakuCatalogueSearch;
import com.fongmi.android.tv.api.IqiyiDanmaku;
import com.fongmi.android.tv.api.NativeDanmaku;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.databinding.ViewDanmakuSearchEmbeddedBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.playback.vod.DanmakuQuery;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuMatchContext;
import com.fongmi.android.tv.playback.vod.DanmakuManualMatchStore;
import com.fongmi.android.tv.playback.vod.DanmakuResultGrouper;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.ui.adapter.DanmakuAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/** Manual matching controller that replaces the settings content inside the same sheet. */
final class DanmakuSearchPanel implements DanmakuAdapter.OnClickListener {

    private final ViewDanmakuSearchEmbeddedBinding binding;
    private final DanmakuAdapter adapter;
    private final PlayerManager player;
    private final Map<String, Danmaku> results;
    private final Map<String, List<Danmaku>> sourceCatalogues;
    private final List<Call> calls;
    private final AtomicInteger requestId;
    private DanmakuMatchContext matchContext;
    private String searchTitle;
    private String searchYear;
    private String searchType;
    private String searchEpisode;
    private boolean automaticRestoreStarted;
    private final List<NativeDanmaku.Job> nativeSearches = new ArrayList<>();
    private List<DanmakuResultGrouper.Work> rankedWorks = List.of();
    private final Map<String, DanmakuResultGrouper.Work> works = new LinkedHashMap<>();
    private String openWork = "";
    private String focusedWorkUrl = "";
    private boolean applying;
    private boolean destroyed;
    private int pending;
    private int failed;
    private int denied;
    private int invalid;
    private volatile int renderVersion;
    private final java.util.concurrent.ExecutorService sorter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "danmaku-results"));

    DanmakuSearchPanel(ViewDanmakuSearchEmbeddedBinding binding, PlayerManager player) {
        this.binding = binding;
        this.player = player;
        this.adapter = new DanmakuAdapter(this, true);
        this.results = new LinkedHashMap<>();
        this.sourceCatalogues = new LinkedHashMap<>();
        this.calls = new ArrayList<>();
        this.requestId = new AtomicInteger();
        this.matchContext = VodPlaybackMedia.contextOf(player);
        this.searchTitle = "";
        this.searchYear = "";
        this.searchType = "";
        this.searchEpisode = "";
        this.adapter.setSelected(player == null ? null : player.getSelectedDanmaku());
    }

    void bind() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 12));
        if (player != null && player.getMetadata() != null && player.getMetadata().title != null) {
            String preferred = VodPlaybackMedia.preferredSearchQuery(player);
            String title = preferred.isEmpty()
                    ? DanmakuQuery.from(player.getMetadata().title.toString()).searchTitle()
                    : preferred;
            binding.keyword.setText(title);
            binding.keyword.setSelection(title.length());
        }
        restoreCachedResult();
        binding.submit.setOnClickListener(view -> search());
        binding.backToWorks.setOnClickListener(view -> onBack());
        binding.clearKeyword.setOnClickListener(view -> {
            binding.keyword.setText("");
            binding.keyword.requestFocus();
            if (!Util.isLeanback()) Util.showKeyboard(binding.keyword);
        });
        binding.keyword.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) search();
            return true;
        });
        binding.keyword.setOnKeyListener((view, keyCode, event) -> {
            if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && binding.recycler.getVisibility() == VISIBLE) {
                return binding.recycler.requestFocus();
            }
            return false;
        });
    }

    void show(boolean focus) {
        binding.getRoot().setVisibility(VISIBLE);
        if (!automaticRestoreStarted
                && !VodPlaybackMedia.preferredSearchQuery(player).isEmpty()) {
            automaticRestoreStarted = true;
            binding.getRoot().post(this::search);
        }
        if (!focus) return;
        binding.keyword.requestFocus();
        if (!Util.isLeanback()) Util.showKeyboard(binding.keyword);
    }

    void hide() {
        binding.getRoot().setVisibility(GONE);
        Util.hideKeyboard(binding.keyword);
    }

    private void search() {
        String keyword = binding.keyword.getText() == null ? "" : binding.keyword.getText().toString().trim();
        if (keyword.isEmpty() || player == null || player.getMetadata() == null) return;
        VodPlaybackMedia.cancelSelection(player);
        applying = false;
        matchContext = VodPlaybackMedia.contextOf(player);
        searchTitle = DanmakuQuery.from(keyword).searchTitle();
        boolean sameWork = DanmakuMatch.isSameWork(searchTitle, matchContext.getTitle());
        searchYear = sameWork ? matchContext.getYear() : "";
        searchType = sameWork ? matchContext.getType() : "";
        int id = requestId.incrementAndGet();
        for (Call call : calls) call.cancel();
        calls.clear();
        for (NativeDanmaku.Job job : nativeSearches) job.cancel();
        nativeSearches.clear();
        openWork = "";
        rankedWorks = List.of();
        works.clear();
        binding.backToWorks.setVisibility(GONE);
        results.clear();
        sourceCatalogues.clear();
        adapter.clear();
        binding.recycler.setVisibility(GONE);
        binding.empty.setVisibility(GONE);
        binding.progress.setVisibility(VISIBLE);
        Util.hideKeyboard(binding.keyword);
        String metadataEpisode = player.getMetadata().artist == null ? "" : player.getMetadata().artist.toString().trim();
        searchEpisode = matchContext.getEpisode().isEmpty() ? metadataEpisode : matchContext.getEpisode();
        DanmakuApi.cancel();
        DanmakuQuery query = DanmakuQuery.from(keyword, searchYear);
        List<String> sources = new ArrayList<>(NativeDanmaku.sources(searchTitle));
        if (!NativeDanmaku.supports(searchTitle)) sources.addAll(DanmakuSetting.getSearchApiUrls());
        pending = sources.size();
        failed = denied = invalid = 0;
        binding.providerStatus.setText(ResUtil.getString(R.string.danmaku_search_running, pending));
        for (String source : sources) {
            String sourceKey = NativeDanmaku.isSource(source) ? source : DanmakuManualMatchStore.sourceKey(source);
            nativeSearches.add(DanmakuCatalogueSearch.search(source, query, searchYear, searchType, searchEpisode,
                    false, new IqiyiDanmaku.Callback<List<Danmaku>>() {
                @Override public void onPartial(List<Danmaku> items) {
                    for (Danmaku item : items) item.setSourceKey(sourceKey);
                    App.post(() -> merge(id, items, sourceKey, items, 0, false));
                }
                @Override public void onSuccess(List<Danmaku> items) {
                    for (Danmaku item : items) item.setSourceKey(sourceKey);
                    App.post(() -> merge(id, items, sourceKey, items, 0, true));
                }
                @Override public void onFailure(IOException error) {
                    int failure = error instanceof DanmakuCatalogueSearch.HttpFailure http && (http.code == 401 || http.code == 403) ? 2
                            : error instanceof DanmakuCatalogueSearch.InvalidResponse ? 3 : 1;
                    App.post(() -> merge(id, List.of(), sourceKey, List.of(), failure, true));
                }
            }));
        }
    }

    private void merge(int id, List<Danmaku> items, String sourceKey,
                       List<Danmaku> catalogue, int failure, boolean complete) {
        if (destroyed || id != requestId.get()) return;
        if (failure != 0) failed++;
        if (failure == 2) denied++;
        if (failure == 3) invalid++;
        if (!sourceKey.isEmpty() && catalogue != null && !catalogue.isEmpty()) {
            sourceCatalogues.put(sourceKey, catalogue);
        }
        for (Danmaku item : items) {
            if (item == null || item.isEmpty()) continue;
            results.put(item.getUrl(), item);
        }
        if (complete) pending = Math.max(0, pending - 1);
        List<Danmaku> snapshot = List.copyOf(results.values());
        String title = searchTitle, year = searchYear, type = searchType, episode = searchEpisode;
        int version = ++renderVersion;
        sorter.execute(() -> {
            if (id != requestId.get() || version != renderVersion) return;
            List<Danmaku> ranked = DanmakuResultGrouper.browse(title, year, type, episode, snapshot);
            List<DanmakuResultGrouper.Work> grouped = DanmakuResultGrouper.works(ranked);
            App.post(() -> {
                if (id != requestId.get() || version != renderVersion) return;
                displayWorks(grouped);
            });
        });
    }

    private void displayWorks(List<DanmakuResultGrouper.Work> groups) {
        rankedWorks = groups;
        works.clear();
        List<Danmaku> displayed = new ArrayList<>();
        for (DanmakuResultGrouper.Work work : groups) {
            String url = "danmaku-work://" + android.net.Uri.encode(work.key());
            works.put(url, work);
            Danmaku row = new Danmaku();
            Danmaku first = work.episodes().get(0);
            row.setName(work.name() + " from " + DanmakuMatch.resultProvider(first.getName())
                    + " - 【" + DanmakuMatch.resultPlatform(first.getName()) + "】 "
                    + ResUtil.getString(R.string.danmaku_work_options, work.episodes().size()));
            row.setUrl(url);
            row.setSourceKey(first.getSourceKey());
            displayed.add(row);
        }
        if (!openWork.isEmpty() && works.containsKey(openWork)) displayed = works.get(openWork).episodes();
        List<Danmaku> ranked = displayed;
        binding.backToWorks.setVisibility(openWork.isEmpty() ? GONE : VISIBLE);
        String focusedUrl = focusedUrl();
        boolean hadResultFocus = binding.recycler.hasFocus();
        adapter.setItems(ranked);
        binding.recycler.setVisibility(ranked.isEmpty() ? GONE : VISIBLE);
        binding.progress.setVisibility(pending == 0 && !applying ? GONE : VISIBLE);
        binding.empty.setVisibility(pending == 0 && ranked.isEmpty() ? VISIBLE : GONE);
        int emptyMessage = denied > 0 ? R.string.danmaku_failure_denied
                : invalid > 0 ? R.string.danmaku_failure_invalid
                : failed > 0 ? R.string.danmaku_failure_network : R.string.danmaku_search_empty;
        binding.empty.setText(emptyMessage);
        binding.providerStatus.setText(pending > 0
                ? ranked.isEmpty() ? ResUtil.getString(R.string.danmaku_search_running, pending)
                : ResUtil.getString(R.string.danmaku_search_updating, ranked.size(), pending)
                : failed > 0 ? ResUtil.getString(R.string.danmaku_search_partial, ranked.size(), failed)
                : ResUtil.getString(openWork.isEmpty() ? R.string.danmaku_search_works : R.string.danmaku_search_result, ranked.size()));
        if (!openWork.isEmpty() && works.containsKey(openWork)) {
            binding.providerStatus.setText(works.get(openWork).name() + " · "
                    + ResUtil.getString(R.string.danmaku_work_options, ranked.size()));
        }
        if (applying) binding.providerStatus.setText(R.string.danmaku_selection_loading);
        if (ranked.isEmpty() || !Util.isLeanback()) return;
        if (hadResultFocus && adapter.indexOfUrl(focusedUrl) < 0) {
            // DiffUtil normally keeps the focused holder. Only recover when the focused source
            // actually disappeared; never steal focus merely because another provider arrived.
            restoreResultFocus(focusedUrl);
        }
    }

    private String focusedUrl() {
        View focused = binding.recycler.getFocusedChild();
        if (focused == null) return "";
        androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                binding.recycler.findContainingViewHolder(focused);
        Danmaku item = holder == null ? null : adapter.getItem(holder.getBindingAdapterPosition());
        return item == null ? "" : item.getUrl();
    }

    private void restoreResultFocus(String focusedUrl) {
        int position = adapter.indexOfUrl(focusedUrl);
        if (position < 0) position = 0;
        int target = position;
        if (binding.recycler.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager layout) {
            layout.scrollToPositionWithOffset(target, binding.recycler.getPaddingTop());
        } else binding.recycler.scrollToPosition(target);
        binding.recycler.post(() -> {
            androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                    binding.recycler.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
            else binding.recycler.requestFocus();
        });
    }

    @Override
    public void onItemClick(Danmaku item) {
        if (works.containsKey(item.getUrl())) {
            openWork = focusedWorkUrl = item.getUrl();
            displayWorks(rankedWorks);
            DanmakuResultGrouper.Work work = works.get(openWork);
            Danmaku current = DanmakuMatch.best(searchTitle, searchYear, searchType,
                    searchEpisode, work.episodes(), Danmaku::getName);
            if (Util.isLeanback()) restoreResultFocus(current == null ? "" : current.getUrl());
            else if (current != null && binding.recycler.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager layout)
                layout.scrollToPositionWithOffset(adapter.indexOfUrl(current.getUrl()), 0);
            return;
        }
        if (player == null) return;
        // A manual choice is always an explicit retry, including when automatic matching already
        // selected the same URL but the renderer failed to load it.
        String query = binding.keyword.getText() == null ? "" : binding.keyword.getText().toString();
        List<Danmaku> catalogue = sourceCatalogues.get(item.getSourceKey());
        applying = true;
        binding.progress.setVisibility(VISIBLE);
        binding.providerStatus.setText(R.string.danmaku_selection_loading);
        VodPlaybackMedia.applyManualMatch(player, query, item,
                catalogue == null ? Collections.emptyList() : catalogue,
                selected -> {
                    if (destroyed) return;
                    applying = false;
                    adapter.setSelected(selected);
                    binding.progress.setVisibility(pending == 0 ? GONE : VISIBLE);
                    binding.providerStatus.setText(R.string.danmaku_selection_loaded);
                }, () -> {
                    if (destroyed) return;
                    applying = false;
                    binding.progress.setVisibility(pending == 0 ? GONE : VISIBLE);
                    binding.providerStatus.setText(R.string.danmaku_selection_failed);
                });
    }

    @Override
    public void onItemFocus(Danmaku item, int position, int total) {
        if (openWork.isEmpty() || applying) return;
        DanmakuResultGrouper.Work work = works.get(openWork);
        if (work != null) binding.providerStatus.setText(work.name() + " · " + (position + 1) + "/" + total);
    }

    boolean onBack() {
        if (openWork.isEmpty()) return false;
        openWork = "";
        displayWorks(rankedWorks);
        if (Util.isLeanback()) restoreResultFocus(focusedWorkUrl);
        else binding.recycler.scrollToPosition(Math.max(0, adapter.indexOfUrl(focusedWorkUrl)));
        return true;
    }

    void destroy() {
        destroyed = true;
        for (NativeDanmaku.Job job : nativeSearches) job.cancel();
        nativeSearches.clear();
        requestId.incrementAndGet();
        sorter.shutdownNow();
        for (Call call : calls) call.cancel();
        calls.clear();
        sourceCatalogues.clear();
    }

    private void restoreCachedResult() {
        Danmaku cached = VodPlaybackMedia.preferredEpisode(player);
        if (cached == null || cached.isEmpty()) return;
        results.put(cached.getUrl(), cached);
        displayWorks(DanmakuResultGrouper.works(List.of(cached)));
        binding.recycler.setVisibility(VISIBLE);
        binding.progress.setVisibility(GONE);
        binding.empty.setVisibility(GONE);
        binding.providerStatus.setText(ResUtil.getString(R.string.danmaku_search_result, 1));
    }
}
