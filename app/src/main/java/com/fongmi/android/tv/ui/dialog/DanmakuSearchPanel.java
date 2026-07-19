package com.fongmi.android.tv.ui.dialog;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.databinding.ViewDanmakuSearchEmbeddedBinding;
import com.fongmi.android.tv.player.PlayerManager;
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
    private final List<Call> calls;
    private final AtomicInteger requestId;
    private int pending;

    DanmakuSearchPanel(ViewDanmakuSearchEmbeddedBinding binding, PlayerManager player) {
        this.binding = binding;
        this.player = player;
        this.adapter = new DanmakuAdapter(this);
        this.results = new LinkedHashMap<>();
        this.calls = new ArrayList<>();
        this.requestId = new AtomicInteger();
    }

    void bind() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 12));
        if (player != null && player.getMetadata() != null && player.getMetadata().title != null) {
            String title = player.getMetadata().title.toString();
            binding.keyword.setText(title);
            binding.keyword.setSelection(title.length());
        }
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
        int id = requestId.incrementAndGet();
        for (Call call : calls) call.cancel();
        calls.clear();
        results.clear();
        adapter.clear();
        binding.recycler.setVisibility(GONE);
        binding.empty.setVisibility(GONE);
        binding.progress.setVisibility(VISIBLE);
        Util.hideKeyboard(binding.keyword);
        String episode = player.getMetadata().artist == null ? "" : player.getMetadata().artist.toString().trim();
        calls.addAll(DanmakuApi.newCalls(keyword, episode));
        pending = calls.size();
        binding.providerStatus.setText(ResUtil.getString(R.string.danmaku_search_running, pending));
        for (Call call : calls) call.enqueue(callback(id));
    }

    private Callback callback(int id) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                List<Danmaku> items = new ArrayList<>();
                try {
                    if (response.body() != null) items = Danmaku.arrayFrom(response.body().string());
                } catch (Exception ignored) {
                }
                List<Danmaku> value = items;
                App.post(() -> merge(id, value));
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                App.post(() -> merge(id, Collections.emptyList()));
            }
        };
    }

    private void merge(int id, List<Danmaku> items) {
        if (id != requestId.get()) return;
        for (Danmaku item : items) {
            if (item != null && !item.isEmpty()) results.putIfAbsent(item.getUrl(), item);
        }
        pending = Math.max(0, pending - 1);
        adapter.setItems(new ArrayList<>(results.values()));
        binding.recycler.setVisibility(results.isEmpty() ? GONE : VISIBLE);
        binding.progress.setVisibility(pending == 0 ? GONE : VISIBLE);
        binding.empty.setVisibility(pending == 0 && results.isEmpty() ? VISIBLE : GONE);
        binding.empty.setText(R.string.error_empty);
        binding.providerStatus.setText(pending == 0
                ? ResUtil.getString(R.string.danmaku_search_result, results.size())
                : ResUtil.getString(R.string.danmaku_search_running, pending));
        if (!results.isEmpty() && Util.isLeanback() && !binding.recycler.hasFocus()) binding.recycler.requestFocus();
    }

    @Override
    public void onItemClick(Danmaku item) {
        if (player == null) return;
        player.setDanmaku(item.isSelected() ? Danmaku.empty() : item);
        adapter.notifyDataSetChanged();
    }

    void destroy() {
        requestId.incrementAndGet();
        for (Call call : calls) call.cancel();
        calls.clear();
    }
}
