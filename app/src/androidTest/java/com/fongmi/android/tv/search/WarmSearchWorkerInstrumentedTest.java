package com.fongmi.android.tv.search;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Result;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
public class WarmSearchWorkerInstrumentedTest {
    private byte[] fixture() throws Exception {
        try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("search-fixture.zip")) {
            return input.readAllBytes();
        }
    }
    private Site source(MockWebServer server, String key) {
        var json = new com.google.gson.JsonObject();
        json.addProperty("key", key); json.addProperty("name", key); json.addProperty("type", 3);
        json.addProperty("api", "csp_SearchFixture"); json.addProperty("jar", server.url("/fixture.jar").toString());
        return App.gson().fromJson(json, Site.class);
    }
    @Test public void successfulSearchRetainsProcessAndPluginInstance() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(new Buffer().write(fixture())));
            server.start();
            Site source = source(server, "warm-instance");
            String first = IsolatedSpiderSearch.search(source, "first", false, "1").getVod().getRemarks();
            String second = IsolatedSpiderSearch.search(source, "second", false, "1").getVod().getRemarks();
            assertEquals(first.split(":")[0], second.split(":")[0]);
            assertEquals("1", first.split(":")[1]);
            assertEquals("2", second.split(":")[1]);
            assertEquals("No repeated plugin download", 1, server.getRequestCount());
        }
    }
    @Test public void pluginCanReadItsOwnLocalProxyWhileSearching() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(new Buffer().write(fixture())));
            server.start();
            assertEquals("worker-proxy-ready", IsolatedSpiderSearch.search(source(server, "local-proxy"), "proxy", false, "1").getVod().getRemarks());
        }
    }
    @Test public void nativeReadinessIsCheckedBeforeConstructingPlugin() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(new Buffer().write(fixture())));
            server.start();
            assertEquals("2", IsolatedSpiderSearch.search(source(server, "init-readiness"), "init", false, "1").getVod().getRemarks());
        }
    }
    @Test public void concurrentProcessesDownloadSharedJarOnlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(IsolatedSpiderSearch.parallelism());
        try (var server = new MockWebServer()) {
            byte[] bytes = fixture();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setBody(new Buffer().write(bytes)).setBodyDelay(500, TimeUnit.MILLISECONDS);
                }
            });
            server.start();
            List<Future<Result>> results = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < IsolatedSpiderSearch.parallelism(); i++) {
                Site source = source(server, "concurrent-" + i);
                results.add(executor.submit(() -> { start.await(); return IsolatedSpiderSearch.search(source, "庆余年", false, "1"); }));
            }
            start.countDown();
            Set<String> pids = new HashSet<>();
            for (Future<Result> result : results) {
                var vod = result.get(15, TimeUnit.SECONDS).getVod();
                assertEquals("fixture", vod.getId()); pids.add(vod.getRemarks().split(":")[0]);
            }
            assertEquals(IsolatedSpiderSearch.parallelism(), pids.size());
            assertEquals("One complete download is shared across processes", 1, server.getRequestCount());
        } finally { executor.shutdownNow(); }
    }
    @Test public void cachedArtifactMustMatchRequestedChecksum() throws Exception {
        try (var server = new MockWebServer()) {
            byte[] bytes = fixture();
            server.enqueue(new MockResponse().setBody(new Buffer().write(bytes)));
            server.enqueue(new MockResponse().setBody(new Buffer().write(bytes)));
            server.start();
            var cached = com.fongmi.android.tv.api.loader.JarArtifactCache.get(server.url("/fixture.jar").toString(), "");
            assertTrue(cached.isFile());
            try {
                com.fongmi.android.tv.api.loader.JarArtifactCache.get(server.url("/fixture.jar").toString(), "00000000000000000000000000000000");
                fail("A different expected checksum must not reuse the cached bytes");
            } catch (java.io.IOException expected) { assertTrue(cached.isFile()); }
        }
    }
}
