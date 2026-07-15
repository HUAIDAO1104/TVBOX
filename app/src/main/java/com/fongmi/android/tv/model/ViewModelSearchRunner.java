package com.fongmi.android.tv.model;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class ViewModelSearchRunner {

    private final List<Future<?>> futures;
    private final AtomicInteger epoch;

    ViewModelSearchRunner() {
        futures = new CopyOnWriteArrayList<>();
        epoch = new AtomicInteger(0);
    }

    int start(List<Site> sites, Function<Site, Callable<Result>> taskFactory, BiConsumer<Site, Result> onResult, BiConsumer<Site, Throwable> onFailure) {
        int current = nextEpoch();
        cancelFutures();
        sites.forEach(site -> execute(site, taskFactory.apply(site), current, onResult, onFailure));
        return current;
    }

    void add(List<Site> sites, int expectedEpoch, Function<Site, Callable<Result>> taskFactory,
             BiConsumer<Site, Result> onResult, BiConsumer<Site, Throwable> onFailure) {
        if (epoch.get() != expectedEpoch) return;
        sites.forEach(site -> execute(site, taskFactory.apply(site), expectedEpoch, onResult, onFailure));
    }

    void stop() {
        nextEpoch();
        cancelFutures();
    }

    private int nextEpoch() {
        return epoch.incrementAndGet();
    }

    private void cancelFutures() {
        futures.forEach(future -> future.cancel(true));
        futures.clear();
    }

    private void execute(Site site, Callable<Result> callable, int current, BiConsumer<Site, Result> onResult, BiConsumer<Site, Throwable> onFailure) {
        FluentFuture<Result> future = FluentFuture.from(Task.largeExecutor().submit(callable)).withTimeout(Constant.TIMEOUT_SEARCH, TimeUnit.MILLISECONDS, Task.scheduler());
        futures.add(future);
        future.addCallback(Task.callback(
                result -> {
                    futures.remove(future);
                    if (epoch.get() == current) onResult.accept(site, result);
                },
                error -> {
                    futures.remove(future);
                    if (error instanceof CancellationException) return;
                    if (epoch.get() == current) onFailure.accept(site, error);
                }
        ), MoreExecutors.directExecutor());
    }
}
