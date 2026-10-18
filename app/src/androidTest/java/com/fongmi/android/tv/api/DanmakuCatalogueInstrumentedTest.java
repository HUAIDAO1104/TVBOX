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
    @Test public void exactAndAliasAttemptsShareDeadlineAndCancellation() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[]").setHeadersDelay(11, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setBody("[]").setHeadersDelay(11, TimeUnit.SECONDS));
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
                assertTrue(String.valueOf(error.get()), error.get() instanceof java.net.SocketTimeoutException);
                assertTrue(android.os.SystemClock.elapsedRealtime() - start < 21000);
                assertEquals(2, server.getRequestCount());
                android.os.SystemClock.sleep(2500);
                assertEquals(1, failures.get()); assertEquals(0, successes.get());
            } finally { job.cancel(); }
        }
    }
}
