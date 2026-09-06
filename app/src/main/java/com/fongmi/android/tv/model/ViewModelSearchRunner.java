package com.fongmi.android.tv.model;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class ViewModelSearchRunner {

    /*
     * Spider implementations come from third-party jars and frequently share QuickJS, Python,
     * SSL and other native state. They cannot be assumed to be thread-safe. Parallel calls have
     * produced native heap corruption on both 32-bit projectors and the Android TV emulator (the
     * eventual crash may surface in ICU, Conscrypt or HWUI).
     *
     * This is a physical, rather than merely logical, single slot. A timeout is delivered to the
     * UI immediately, but the next source is not allowed to enter a Spider until the timed-out
     * callable has really returned. Some third-party JNI calls ignore Future.cancel/interruption;
     * releasing the slot when their timeout future completes would silently recreate concurrency.
     */
    static final int MAX_CONCURRENT_SEARCHES = 1;

    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final AtomicInteger epoch = new AtomicInteger();
    private final ExecutorService executor;
    private final Object lock = new Object();
    private final long timeoutMs;
    private final int maxConcurrentSearches;
    private final Set<Request> running = new LinkedHashSet<>();
    private boolean closed;

    ViewModelSearchRunner() {
        this(Constant.TIMEOUT_SEARCH);
    }

    ViewModelSearchRunner(long timeoutMs) {
        this(timeoutMs, MAX_CONCURRENT_SEARCHES);
    }

    ViewModelSearchRunner(long timeoutMs, int maxConcurrentSearches) {
        this.timeoutMs = timeoutMs;
        this.maxConcurrentSearches = Math.max(1, maxConcurrentSearches);
        this.executor = Executors.newFixedThreadPool(this.maxConcurrentSearches, runnable -> {
            Thread thread = new Thread(runnable, this.maxConcurrentSearches == 1
                    ? "site-search-serial" : "site-search-network");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    int start(List<Site> sites, Function<Site, Callable<Result>> taskFactory,
              BiConsumer<Site, Result> onResult, BiConsumer<Site, Throwable> onFailure) {
        int current = epoch.incrementAndGet();
        List<Request> previous;
        List<Request> launches;
        synchronized (lock) {
            if (closed) return current;
            queue.clear();
            previous = new ArrayList<>(running);
            enqueueLocked(sites, taskFactory, current, onResult, onFailure);
            launches = takeNextBatchLocked();
        }
        previous.forEach(this::cancel);
        launch(launches);
        return current;
    }

    void add(List<Site> sites, int expectedEpoch, Function<Site, Callable<Result>> taskFactory,
             BiConsumer<Site, Result> onResult, BiConsumer<Site, Throwable> onFailure) {
        List<Request> launches;
        synchronized (lock) {
            if (closed || epoch.get() != expectedEpoch) return;
            enqueueLocked(sites, taskFactory, expectedEpoch, onResult, onFailure);
            launches = takeNextBatchLocked();
        }
        launch(launches);
    }

    /** Moves requested sources ahead of other queued work without interrupting native calls. */
    void prioritize(Predicate<Site> preferred) {
        if (preferred == null) return;
        synchronized (lock) {
            if (closed || queue.isEmpty()) return;
            List<Request> first = new ArrayList<>();
            List<Request> remaining = new ArrayList<>();
            for (Request request : queue) {
                (preferred.test(request.site) ? first : remaining).add(request);
            }
            queue.clear();
            queue.addAll(first);
            queue.addAll(remaining);
        }
    }

    void stop() {
        epoch.incrementAndGet();
        List<Request> previous;
        synchronized (lock) {
            queue.clear();
            previous = new ArrayList<>(running);
        }
        previous.forEach(this::cancel);
    }

    void close() {
        List<Request> previous;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            epoch.incrementAndGet();
            queue.clear();
            previous = new ArrayList<>(running);
        }
        previous.forEach(this::cancel);
        executor.shutdownNow();
    }

    private void enqueueLocked(List<Site> sites, Function<Site, Callable<Result>> taskFactory,
                               int current, BiConsumer<Site, Result> onResult,
                               BiConsumer<Site, Throwable> onFailure) {
        if (sites == null) return;
        for (Site site : sites) {
            if (site == null) continue;
            queue.offer(new Request(site, taskFactory.apply(site), current, onResult, onFailure));
        }
    }

    /** Must be called with {@link #lock} held. */
    private Request takeNextLocked() {
        if (closed || running.size() >= maxConcurrentSearches) return null;
        while (!queue.isEmpty()) {
            Request request = queue.remove();
            if (request.epoch != epoch.get()) continue;
            running.add(request);
            request.future = new FutureTask<>(() -> {
                run(request);
                return null;
            });
            return request;
        }
        return null;
    }

    /** Must be called with {@link #lock} held. */
    private List<Request> takeNextBatchLocked() {
        List<Request> launches = new ArrayList<>();
        Request request;
        while ((request = takeNextLocked()) != null) launches.add(request);
        return launches;
    }

    private void launch(List<Request> requests) {
        if (requests == null) return;
        for (Request request : requests) launch(request);
    }

    private void launch(Request request) {
        if (request == null) return;
        try {
            executor.execute(request.future);
        } catch (Throwable error) {
            deliver(request, null, error);
            finishPhysical(request);
        }
    }

    private void run(Request request) {
        request.thread = Thread.currentThread();
        try {
            // A rapid stop/restart can invalidate a request before the executor has started it.
            // Do not enter third-party code for that stale request, but still run the physical
            // completion path so the new session can acquire the slot.
            if (request.epoch != epoch.get()) return;
            request.timeout = Task.scheduler().schedule(() -> timeout(request), timeoutMs, TimeUnit.MILLISECONDS);
            Result result = request.callable.call();
            deliver(request, result, null);
        } catch (Throwable error) {
            deliver(request, null, error);
        } finally {
            ScheduledFuture<?> timeout = request.timeout;
            if (timeout != null) timeout.cancel(false);
            request.thread = null;
            finishPhysical(request);
        }
    }

    private void timeout(Request request) {
        deliver(request, null, new TimeoutException("Search source timed out"));
        Thread thread = request.thread;
        if (thread != null) thread.interrupt();
    }

    private void deliver(Request request, Result result, Throwable error) {
        if (!request.delivered.compareAndSet(false, true)) return;
        if (request.epoch != epoch.get()) return;
        if (error == null) {
            request.onResult.accept(request.site, result);
        } else if (!(error instanceof CancellationException) && !(error instanceof InterruptedException)) {
            request.onFailure.accept(request.site, error);
        }
    }

    private void finishPhysical(Request request) {
        List<Request> launches;
        synchronized (lock) {
            if (!running.remove(request)) return;
            launches = takeNextBatchLocked();
        }
        launch(launches);
    }

    private void cancel(Request request) {
        if (request == null) return;
        ScheduledFuture<?> timeout = request.timeout;
        if (timeout != null) timeout.cancel(false);
        Thread thread = request.thread;
        if (thread != null) thread.interrupt();
    }

    private static final class Request {

        private final Site site;
        private final Callable<Result> callable;
        private final int epoch;
        private final BiConsumer<Site, Result> onResult;
        private final BiConsumer<Site, Throwable> onFailure;
        private final AtomicBoolean delivered = new AtomicBoolean();
        private volatile FutureTask<Void> future;
        private volatile ScheduledFuture<?> timeout;
        private volatile Thread thread;

        private Request(Site site, Callable<Result> callable, int epoch,
                        BiConsumer<Site, Result> onResult,
                        BiConsumer<Site, Throwable> onFailure) {
            this.site = site;
            this.callable = callable;
            this.epoch = epoch;
            this.onResult = onResult;
            this.onFailure = onFailure;
        }
    }
}
