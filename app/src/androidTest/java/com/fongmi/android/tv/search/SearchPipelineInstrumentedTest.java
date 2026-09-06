package com.fongmi.android.tv.search;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Observer;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.model.SearchSnapshot;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class SearchPipelineInstrumentedTest {
    private static Site site(MockWebServer server, String key, int type) {
        return App.gson().fromJson("{\"key\":\"" + key + "\",\"name\":\"" + key
                + "\",\"type\":" + type + ",\"api\":\"" + server.url("/api") + "\",\"searchable\":1}", Site.class);
    }
    private static MockResponse result(String id, String pic) {
        return new MockResponse().setBody("{\"pagecount\":2,\"list\":[{\"vod_id\":\"" + id
                + "\",\"vod_name\":\"庆余年\",\"vod_pic\":\"" + pic + "\"}]}");
    }
    private static void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }

    @Test public void basicTitlesArriveBeforeSlowPosterEnrichment() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return request.getRequestUrl().queryParameter("ac") == null ? result("first", "")
                            : result("first", "https://example.invalid/poster.jpg").setBodyDelay(3, TimeUnit.SECONDS);
                }
            });
            server.start();
            SiteViewModel model = new SiteViewModel();
            CountDownLatch first = new CountDownLatch(1), enriched = new CountDownLatch(1);
            Observer<SearchSnapshot> observer = snapshot -> {
                if (snapshot.results().isEmpty()) return;
                if (snapshot.results().get(0).getVod().getPic().isEmpty()) first.countDown();
                else enriched.countDown();
            };
            main(() -> { model.getAggregateSearch().observeForever(observer); model.searchContent(List.of(site(server, "poster", 1)), "庆余年", false); });
            try {
                assertTrue("Basic title must arrive before the 3 second poster request", first.await(2, TimeUnit.SECONDS));
                assertTrue(enriched.await(6, TimeUnit.SECONDS));
            } finally { main(() -> { model.getAggregateSearch().removeObserver(observer); model.stopSearch(); }); }
        }
    }

    @Test public void selectedSourcePaginationPreservesCardsAndStopsRepeatedPages() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(result("page1", "https://example.invalid/1.jpg"));
            server.enqueue(result("page2", "https://example.invalid/2.jpg"));
            server.start();
            Site selected = site(server, "pages", 4);
            SiteViewModel model = new SiteViewModel();
            AtomicReference<SearchSnapshot> latest = new AtomicReference<>();
            Observer<SearchSnapshot> observer = latest::set;
            main(() -> { model.getAggregateSearch().observeForever(observer); model.searchContent(List.of(selected), "庆余年", false); });
            try {
                await(() -> latest.get() != null && latest.get().results().size() == 1);
                main(() -> assertTrue(model.requestMore(site -> true, true)));
                await(() -> latest.get().results().size() == 2);
                assertEquals("page1", latest.get().results().get(0).getVod().getId());
                assertEquals("page2", latest.get().results().get(1).getVod().getId());
                assertFalse(model.sourceState(selected).hasMore());
                main(() -> assertFalse(model.requestMore(site -> true, true)));
                assertEquals(2, server.getRequestCount());
            } finally { main(() -> { model.getAggregateSearch().removeObserver(observer); model.stopSearch(); }); }
        }
    }

    @Test public void privateProcessReturnsResultsAndCancellationDoesNotKillUi() throws Exception {
        int uiPid = android.os.Process.myPid();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(result("recovered", ""));
            server.start();
            Site source = site(server, "isolated", 4);
            Future<Result> hanging = executor.submit(() -> IsolatedSpiderSearch.search(source, "庆余年", false, "1"));
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS));
            hanging.cancel(true);
            Future<Result> recovered = executor.submit(() -> IsolatedSpiderSearch.search(source, "庆余年", false, "1"));
            assertEquals("recovered", recovered.get(12, TimeUnit.SECONDS).getVod().getId());
            assertEquals(uiPid, android.os.Process.myPid());
        } finally { executor.shutdownNow(); }
    }

    @Test public void watchdogReclaimsNonResponsiveProcess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                IsolatedSpiderSearch.search(site(server, "timeout", 4), "庆余年", false, "1");
                fail("Expected a timeout");
            } catch (TimeoutException expected) {
                long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                assertTrue("Process watchdog should bound the wait", elapsed < 28000);
                System.out.println("SEARCH_PROCESS_TIMEOUT_MS=" + elapsed);
            }
        }
    }

    @Test public void externalJarIgnoringInterruptIsReclaimedAndNextSourceWorks() throws Exception {
        java.io.File jar = new java.io.File(App.get().getCacheDir(), "instrumented-search-fixture.jar");
        jar.delete();
        try (java.io.InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("search-fixture.zip");
             java.io.OutputStream output = new java.io.FileOutputStream(jar)) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        }
        assertTrue(jar.setReadOnly());
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "instrumented-native"); json.addProperty("name", "测试插件");
        json.addProperty("api", "csp_SearchFixture"); json.addProperty("type", 3);
        json.addProperty("jar", "file://" + jar.getAbsolutePath());
        Site source = App.gson().fromJson(json, Site.class);
        int pid = android.os.Process.myPid();
        long start = android.os.SystemClock.elapsedRealtime();
        try {
            IsolatedSpiderSearch.search(source, "hang", false, "1");
            fail("Ignoring interrupts must still time out");
        } catch (TimeoutException expected) {
            assertTrue(android.os.SystemClock.elapsedRealtime() - start < 28000);
        }
        Result next = IsolatedSpiderSearch.search(source, "庆余年", false, "1");
        assertEquals("fixture", next.getVod().getId());
        assertEquals(pid, android.os.Process.myPid());
        jar.delete();
    }

    private static void await(java.util.function.BooleanSupplier ready) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(ready.getAsBoolean());
    }
}
