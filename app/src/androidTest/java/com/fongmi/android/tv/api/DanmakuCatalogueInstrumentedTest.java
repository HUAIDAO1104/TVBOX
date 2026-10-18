package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuQuery;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DanmakuCatalogueInstrumentedTest {
    @Test public void cancelDoesNotWaitForCatalogueRanking() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[]")); server.start();
            var ranking = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var callbacks = new AtomicInteger();
            var job = DanmakuCatalogueSearch.search(server.url("/search").toString(), DanmakuQuery.from("取消回归片"), "", "", "", false,
                    rows -> {
                        ranking.countDown();
                        try { release.await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        return true;
                    }, new IqiyiDanmaku.Callback<List<Danmaku>>() {
                        public void onSuccess(List<Danmaku> rows) { callbacks.incrementAndGet(); }
                        public void onFailure(IOException error) { callbacks.incrementAndGet(); }
                    });
            try {
                assertTrue(ranking.await(2, TimeUnit.SECONDS));
                long started = android.os.SystemClock.elapsedRealtime();
                job.cancel();
                assertTrue("Changing the query must not wait for ranking", android.os.SystemClock.elapsedRealtime() - started < 250);
                release.countDown(); android.os.SystemClock.sleep(150);
                assertEquals(0, callbacks.get());
            } finally { release.countDown(); job.cancel(); }
        }
    }
    @Test public void exactAndAliasAttemptsShareDeadlineAndCancellation() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[]").setHeadersDelay(30, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setBody("[]").setHeadersDelay(30, TimeUnit.SECONDS));
            server.start();
            var done = new CountDownLatch(1);
            var failures = new AtomicInteger();
            var successes = new AtomicInteger();
            var error = new AtomicReference<IOException>();
            long start = android.os.SystemClock.elapsedRealtime();
            var job = DanmakuCatalogueSearch.search(server.url("/search?name={name}").toString().replace("%7B", "{").replace("%7D", "}"),
                    DanmakuQuery.from("截止时间测试2026"), "", "", "", false, new IqiyiDanmaku.Callback<List<Danmaku>>() {
                        public void onSuccess(List<Danmaku> rows) { successes.incrementAndGet(); done.countDown(); }
                        public void onFailure(IOException failure) { error.set(failure); failures.incrementAndGet(); done.countDown(); }
                    });
            try {
                assertTrue(done.await(21, TimeUnit.SECONDS));
                assertNotNull(error.get());
                assertTrue(android.os.SystemClock.elapsedRealtime() - start < 21000);
                assertEquals(2, server.getRequestCount());
                android.os.SystemClock.sleep(2500);
                assertEquals(1, failures.get()); assertEquals(0, successes.get());
            } finally { job.cancel(); }
        }
    }
    @Test public void slowExactSearchDoesNotBlockVerifiedYearAlias() throws Exception {
        exerciseAlias(false, false);
    }
    @Test public void exactSearchFailureDoesNotDiscardHealthyAlias() throws Exception {
        exerciseAlias(true, false);
    }
    @Test public void fastWrongYearAliasCannotReplaceExactEdition() throws Exception {
        exerciseAlias(false, true);
    }
    private void exerciseAlias(boolean exactFails, boolean wrongYear) throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    boolean exact = request.getRequestUrl().queryParameter("name").endsWith("2026");
                    if (exact && exactFails) return new MockResponse().setResponseCode(503);
                    String year = !exact && wrongYear ? "2025" : "2026";
                    String body = "[{\"name\":\"并行回退回归片(" + year + ")【综艺】from fixture - 【test】 第10期上\",\"url\":\"https://example.invalid/" + year + "\"}]";
                    return new MockResponse().setBody(body).setHeadersDelay(exact ? (wrongYear ? 1200 : 10000) : 0, TimeUnit.MILLISECONDS);
                }
            });
            server.start();
            var done = new CountDownLatch(1);
            var rows = new AtomicReference<List<Danmaku>>();
            var callbacks = new AtomicInteger();
            long start = android.os.SystemClock.elapsedRealtime();
            var job = DanmakuCatalogueSearch.search(server.url("/search?name={name}").toString().replace("%7B", "{").replace("%7D", "}"),
                    DanmakuQuery.from("并行回退回归片2026"), "2026", "综艺", "第10期上", false, new IqiyiDanmaku.Callback<List<Danmaku>>() {
                        public void onSuccess(List<Danmaku> value) { rows.set(value); callbacks.incrementAndGet(); done.countDown(); }
                        public void onFailure(IOException error) { callbacks.incrementAndGet(); done.countDown(); }
                    });
            try {
                assertTrue("Alias should not wait ten seconds for exact query", done.await(3, TimeUnit.SECONDS));
                assertNotNull(rows.get()); assertEquals(1, rows.get().size());
                assertTrue(rows.get().get(0).getUrl().endsWith("2026"));
                System.out.println("MANUAL_ALIAS exactFails=" + exactFails + " wrongYear=" + wrongYear + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start));
                assertTrue(android.os.SystemClock.elapsedRealtime() - start < 3000);
                android.os.SystemClock.sleep(500);
                assertEquals(1, callbacks.get());
                assertEquals(2, server.getRequestCount());
            } finally { job.cancel(); }
        }
    }
}
