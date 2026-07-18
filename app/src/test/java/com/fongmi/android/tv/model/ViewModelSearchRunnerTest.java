package com.fongmi.android.tv.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ViewModelSearchRunnerTest {

    @Test
    public void queuesLargeSearchesWithoutExceedingNativeSafeConcurrency() throws Exception {
        ViewModelSearchRunner runner = new ViewModelSearchRunner();
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < 24; i++) sites.add(new Site());

        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(ViewModelSearchRunner.MAX_CONCURRENT_SEARCHES);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(sites.size());

        runner.start(sites, site -> () -> {
            int value = running.incrementAndGet();
            maximum.accumulateAndGet(value, Math::max);
            firstWave.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            running.decrementAndGet();
            return new Result();
        }, (site, result) -> {
            completed.incrementAndGet();
            finished.countDown();
        }, (site, error) -> finished.countDown());

        assertTrue(firstWave.await(5, TimeUnit.SECONDS));
        // No queued source may begin until the native-safe active slot has completed.
        Thread.sleep(100);
        assertEquals(ViewModelSearchRunner.MAX_CONCURRENT_SEARCHES, maximum.get());
        assertEquals(ViewModelSearchRunner.MAX_CONCURRENT_SEARCHES, running.get());

        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertEquals(sites.size(), completed.get());
        assertTrue(maximum.get() <= ViewModelSearchRunner.MAX_CONCURRENT_SEARCHES);
        runner.close();
    }

    @Test
    public void timeoutDoesNotReleasePhysicalSlotUntilCallableActuallyReturns() throws Exception {
        ViewModelSearchRunner runner = new ViewModelSearchRunner(80);
        List<Site> sites = List.of(new Site(), new Site());
        AtomicInteger started = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstTimedOut = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        runner.start(sites, site -> () -> {
            int index = started.incrementAndGet();
            if (index == 1) {
                firstStarted.countDown();
                while (releaseFirst.getCount() > 0) {
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException ignored) {
                        // Models third-party JNI code which does not honor cancellation.
                    }
                }
            } else {
                secondStarted.countDown();
            }
            return new Result();
        }, (site, result) -> { }, (site, error) -> {
            if (error instanceof java.util.concurrent.TimeoutException) firstTimedOut.countDown();
        });

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        assertTrue(firstTimedOut.await(2, TimeUnit.SECONDS));
        Thread.sleep(120);
        assertEquals(1, started.get());

        releaseFirst.countDown();
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        assertEquals(2, started.get());
        runner.close();
    }

    @Test
    public void explicitNetworkPoolRunsSafeRequestsInParallelWithinItsLimit() throws Exception {
        int limit = 3;
        ViewModelSearchRunner runner = new ViewModelSearchRunner(2_000, limit);
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < 9; i++) sites.add(new Site());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(sites.size());

        runner.start(sites, site -> () -> {
            int active = running.incrementAndGet();
            maximum.accumulateAndGet(active, Math::max);
            firstWave.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            running.decrementAndGet();
            return new Result();
        }, (site, result) -> finished.countDown(), (site, error) -> finished.countDown());

        assertTrue(firstWave.await(5, TimeUnit.SECONDS));
        assertEquals(limit, running.get());
        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertEquals(limit, maximum.get());
        runner.close();
    }
}
