package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
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
    private boolean resultFocusInitialized;
    private boolean automaticRestoreStarted;
    private int pending;
    private int renderVersion;
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
        if (!automaticRestoreStarted && results.isEmpty()
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
        matchContext = VodPlaybackMedia.contextOf(player);
        searchTitle = DanmakuQuery.from(keyword).searchTitle();
        boolean sameWork = DanmakuMatch.isSameWork(searchTitle, matchContext.getTitle());
        searchYear = sameWork ? matchContext.getYear() : "";
        searchType = sameWork ? matchContext.getType() : "";
        int id = requestId.incrementAndGet();
        for (Call call : calls) call.cancel();
        calls.clear();
        results.clear();
        sourceCatalogues.clear();
        adapter.clear();
        resultFocusInitialized = false;
        binding.recycler.setVisibility(GONE);
        binding.empty.setVisibility(GONE);
        binding.progress.setVisibility(VISIBLE);
        Util.hideKeyboard(binding.keyword);
        String metadataEpisode = player.getMetadata().artist == null ? "" : player.getMetadata().artist.toString().trim();
        searchEpisode = matchContext.getEpisode().isEmpty() ? metadataEpisode : matchContext.getEpisode();
        List<String> apiUrls = DanmakuSetting.getSearchApiUrls();
        calls.addAll(DanmakuApi.newCalls(keyword, searchEpisode));
        pending = calls.size();
        binding.providerStatus.setText(ResUtil.getString(R.string.danmaku_search_running, pending));
        for (int index = 0; index < calls.size(); index++) {
            String sourceKey = index < apiUrls.size()
                    ? DanmakuManualMatchStore.sourceKey(apiUrls.get(index)) : "";
            calls.get(index).enqueue(callback(
                    id, searchTitle, searchYear, searchType, searchEpisode, sourceKey));
        }
    }

    private Callback callback(int id, String title, String year, String type, String episode,
                              String sourceKey) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                List<Danmaku> items = new ArrayList<>();
                try {
                    if (response.body() != null) items = Danmaku.arrayFrom(response.body().string());
                    for (Danmaku item : items) item.setSourceKey(sourceKey);
                } catch (Exception ignored) {
                }
                // A provider may return hundreds of entries covering every episode and season.
                // Filter and sort that payload on OkHttp's worker thread so TV navigation never
                // competes with response parsing on the main thread.
                List<Danmaku> value = DanmakuResultGrouper.prepare(
                        title, year, type, episode, items);
                List<Danmaku> catalogue = items;
                App.post(() -> merge(id, value, sourceKey, catalogue));
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                App.post(() -> merge(id, Collections.emptyList(), sourceKey,
                        Collections.emptyList()));
            }
        };
    }

    private void merge(int id, List<Danmaku> items, String sourceKey,
                       List<Danmaku> catalogue) {
        if (id != requestId.get()) return;
        if (!sourceKey.isEmpty() && catalogue != null && !catalogue.isEmpty()) {
            sourceCatalogues.put(sourceKey, catalogue);
        }
        String focusedUrl = focusedUrl();
        boolean hadResultFocus = binding.recycler.hasFocus();
        for (Danmaku item : items) {
            if (item == null || item.isEmpty() || results.containsKey(item.getUrl())) continue;
            results.put(item.getUrl(), item);
        }
        pending = Math.max(0, pending - 1);
        List<Danmaku> snapshot = List.copyOf(results.values());
        String title = searchTitle, year = searchYear, type = searchType, episode = searchEpisode;
        int version = ++renderVersion;
        sorter.execute(() -> {
            if (id != requestId.get()) return;
            List<Danmaku> ranked = DanmakuResultGrouper.prepare(title, year, type, episode, snapshot);
            App.post(() -> {
                if (id != requestId.get() || version != renderVersion) return;
                displayRanked(ranked);
            });
        });
    }

    private void displayRanked(List<Danmaku> ranked) {
        String focusedUrl = focusedUrl();
        boolean hadResultFocus = binding.recycler.hasFocus();
        adapter.setItems(ranked);
        binding.recycler.setVisibility(ranked.isEmpty() ? GONE : VISIBLE);
        binding.progress.setVisibility(pending == 0 ? GONE : VISIBLE);
        binding.empty.setVisibility(pending == 0 && ranked.isEmpty() ? VISIBLE : GONE);
        binding.empty.setText(R.string.error_empty);
        binding.providerStatus.setText(pending == 0
                ? ResUtil.getString(R.string.danmaku_search_result, ranked.size())
                : ResUtil.getString(R.string.danmaku_search_running, pending));
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
        binding.recycler.scrollToPosition(target);
        binding.recycler.post(() -> {
            androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                    binding.recycler.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
            else binding.recycler.requestFocus();
        });
    }

    @Override
    public void onItemClick(Danmaku item) {
        if (player == null) return;
        // A manual choice is always an explicit retry, including when automatic matching already
        // selected the same URL but the renderer failed to load it.
        String query = binding.keyword.getText() == null ? "" : binding.keyword.getText().toString();
        List<Danmaku> catalogue = sourceCatalogues.get(item.getSourceKey());
        VodPlaybackMedia.applyManualMatch(player, query, item,
                catalogue == null ? Collections.emptyList() : catalogue,
                adapter::setSelected, null);
    }

    @Override
    public void onItemFocus(Danmaku item, int position, int total) {
        binding.providerStatus.setText(ResUtil.getString(
                R.string.danmaku_result_focus, position + 1, total, item.getName()));
    }

    void destroy() {
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
        adapter.setItems(List.of(cached));
        binding.recycler.setVisibility(VISIBLE);
        binding.progress.setVisibility(GONE);
        binding.empty.setVisibility(GONE);
        binding.providerStatus.setText(ResUtil.getString(R.string.danmaku_search_result, 1));
    }
}
