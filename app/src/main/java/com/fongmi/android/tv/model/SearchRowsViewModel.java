package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import java.util.*;
import java.util.concurrent.*;

/** Mobile result grouping is worker-owned; coalescing never drops a source from a snapshot. */
public final class SearchRowsViewModel extends ViewModel {
    public record Rows(int session, Map<String, List<Vod>> sources) { }
    private final MutableLiveData<Rows> rows = new MutableLiveData<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "search-rows"));
    private SearchSnapshot pending;
    private boolean processing, closed;
    private long revision, deliveredRevision;
    private int latestSession;
    public LiveData<Rows> rows() { return rows; }
    public synchronized void submit(SearchSnapshot value) {
        if (closed || value == null) return;
        pending = value; latestSession = value.session(); revision++;
        if (processing) return;
        processing = true; worker.execute(this::drain);
    }
    private void drain() {
        while (true) {
            SearchSnapshot next; long current;
            synchronized (this) {
                next = pending; pending = null; current = revision;
                if (next == null || closed) { processing = false; return; }
            }
            Map<String, LinkedHashMap<String, Vod>> unique = new LinkedHashMap<>();
            for (Result result : next.results()) for (Vod vod : result.getList())
                unique.computeIfAbsent(vod.getSiteKey(), key -> new LinkedHashMap<>())
                        .put(vod.getId().isEmpty() ? "name:" + vod.getName() : "id:" + vod.getId(), vod);
            Map<String, List<Vod>> groups = new LinkedHashMap<>(); List<Vod> all = new ArrayList<>();
            unique.forEach((key, values) -> { List<Vod> list = List.copyOf(values.values()); groups.put(key, list); all.addAll(list); });
            groups.put("", List.copyOf(all)); Rows result = new Rows(next.session(), Map.copyOf(groups));
            App.post(() -> {
                synchronized (this) {
                    if (closed || next.session() != latestSession || current <= deliveredRevision) return;
                    deliveredRevision = current;
                }
                rows.setValue(result);
            });
        }
    }
    @Override protected synchronized void onCleared() { closed = true; pending = null; revision++; worker.shutdownNow(); }
}
