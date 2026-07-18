package com.fongmi.android.tv.model;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayDeque;
import java.util.List;
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
    private Request running;
    private boolean closed;

    ViewModelSearchRunner() {
        this(Constant.TIMEOUT_SEARCH);
    }

    ViewModelSearchRunner(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "site-search-serial");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    int start(List<Site> sites, Function<Site, Callable<Result>> taskFactory,
              BiConsumer<Site, Result> onResult, BiConsumer<Site, Throwable> onFailure) {
        int current = epoch.incrementAndGet();
        Request previous;
        Request launch;
        synchronized (lock) {
            if (closed) return current;
            queue.clear();
            previous = running;
            enqueueLocked(sites, taskFactory, current, onResult, onFailure);
            launch = takeNextLocked();
        }
        cancel(previous);
        launch(launch);
        return current;
    }

    void add(List<Site> sites, int expectedEpoch, Function<Site, Callable<Result>> taskFactory,
             BiConsumer<Site, Result> onResult, BiConsumer<Site, Throwable> onFailure) {
        Request launch;
        synchronized (lock) {
            if (closed || epoch.get() != expectedEpoch) return;
            enqueueLocked(sites, taskFactory, expectedEpoch, onResult, onFailure);
            launch = takeNextLocked();
        }
        launch(launch);
    }

    void stop() {
        epoch.incrementAndGet();
        Request previous;
        synchronized (lock) {
            queue.clear();
            previous = running;
        }
        cancel(previous);
    }

    void close() {
        Request previous;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            epoch.incrementAndGet();
            queue.clear();
            previous = running;
        }
        cancel(previous);
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
        if (closed || running != null) return null;
        while (!queue.isEmpty()) {
            Request request = queue.remove();
            if (request.epoch != epoch.get()) continue;
            running = request;
            request.future = new FutureTask<>(() -> {
                run(request);
                return null;
            });
            return request;
        }
        return null;
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
        Request launch;
        synchronized (lock) {
            if (running != request) return;
            running = null;
            launch = takeNextLocked();
        }
        launch(launch);
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
