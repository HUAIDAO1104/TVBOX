package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import java.util.*;
import java.util.concurrent.*;

/** Incremental source rows. A slow provider cannot delay publishing an earlier response. */
public final class SearchRowsViewModel extends ViewModel {
    public record Rows(int session, Map<String, List<Hit>> sources) { }
    /** Source-local IDs are not globally unique. Keep selection and DiffUtil on the same identity. */
    public record Hit(Vod vod) implements com.fongmi.android.tv.impl.Diffable<Hit> {
        private static String id(Vod vod) { return vod.getId().isEmpty() ? "name:" + vod.getName() : "id:" + vod.getId(); }
        @Override public boolean isSameItem(Hit other) {
            return vod.getSiteKey().equals(other.vod.getSiteKey()) && id(vod).equals(id(other.vod));
        }
        @Override public boolean isSameContent(Hit other) {
            return vod.isSameContent(other.vod) && vod.getYear().equals(other.vod.getYear())
                    && vod.getArea().equals(other.vod.getArea()) && vod.getTypeName().equals(other.vod.getTypeName())
                    && vod.isFolder() == other.vod.isFolder();
        }
    }

    public static final class Index {
        private final Map<String, LinkedHashMap<String, Hit>> perSite = new LinkedHashMap<>();
        private final LinkedHashMap<List<String>, Hit> all = new LinkedHashMap<>();
        private final Map<String, List<Hit>> lists = new LinkedHashMap<>();
        private SearchSnapshot previous;

        public Rows update(SearchSnapshot next) {
            int start = 0;
            if (previous != null && previous.session() == next.session()
                    && previous.results().size() <= next.results().size()
                    && next.results().subList(0, previous.results().size()).equals(previous.results())) {
                start = previous.results().size();
            } else {
                perSite.clear(); all.clear(); lists.clear();
            }
            Set<String> changed = new HashSet<>();
            for (Result batch : next.results().subList(start, next.results().size())) for (Vod vod : batch.getList()) {
                if (vod == null) continue;
                String site = vod.getSiteKey(), id = Hit.id(vod);
                Hit hit = new Hit(vod);
                perSite.computeIfAbsent(site, ignored -> new LinkedHashMap<>()).put(id, hit);
                all.put(List.of(site, id), hit);
                changed.add(site);
            }
            for (String site : changed) lists.put(site, List.copyOf(perSite.get(site).values()));
            if (!changed.isEmpty() || !lists.containsKey("")) lists.put("", List.copyOf(all.values()));
            previous = next;
            return new Rows(next.session(), Map.copyOf(lists));
        }
    }

    private final Index index = new Index();
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
            Rows result = index.update(next);
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
