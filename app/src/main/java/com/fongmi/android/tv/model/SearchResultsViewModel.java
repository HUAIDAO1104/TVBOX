package com.fongmi.android.tv.model;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.search.SearchResultIndex;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Coalesces incoming snapshots without doing title processing on the main thread. */
public final class SearchResultsViewModel extends ViewModel {
    private final MutableLiveData<SearchResultIndex.Snapshot> results = new MutableLiveData<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "search-index"));
    private SearchResultIndex index;
    private Request pending;
    private boolean processing;
    private int generation;

    public LiveData<SearchResultIndex.Snapshot> results() { return results; }

    public synchronized void submit(String keyword, Set<String> families, SearchSnapshot snapshot) {
        pending = new Request(keyword, Set.copyOf(families), snapshot, generation);
        if (processing) return;
        processing = true;
        worker.execute(this::drain);
    }

    public synchronized void reset() {
        generation++;
        pending = null;
        results.setValue(null);
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (this) {
                request = pending;
                pending = null;
                if (request == null) { processing = false; return; }
            }
            if (index == null || !index.accepts(request.keyword, request.families, request.snapshot))
                index = new SearchResultIndex(request.keyword, request.families);
            SearchResultIndex.Snapshot value = index.append(request.snapshot);
            App.post(() -> {
                synchronized (this) {
                    if (request.generation != generation) return;
                }
                results.setValue(value);
            });
        }
    }

    @Override protected synchronized void onCleared() {
        generation++;
        pending = null;
        worker.shutdownNow();
    }

    private record Request(String keyword, Set<String> families, SearchSnapshot snapshot, int generation) { }
}
