package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public class DanmakuRoutingInstrumentedTest {
    private String endpoint(MockWebServer server) { return server.url("/search?name={name}&episode={episode}").toString().replace("%7B", "{").replace("%7D", "}"); }
    private MockResponse rows(MockWebServer server, String label, String path) {
        Danmaku item = Danmaku.from(server.url("/api/v2/comment/" + path + "?format=xml").toString());
        item.setName("自动线路测试(2026)【综艺】from fixture - " + label);
        return new MockResponse().setBody(App.gson().toJson(List.of(item)));
    }
    private MockResponse document() { return new MockResponse().setBody("<?xml version=\"1.0\"?><i><d p=\"1,1,25,16777215,0,0,0,1\">真实测试评论</d></i>"); }
    private Danmaku run(MockWebServer direct, MockWebServer backup) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Danmaku> found = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> DanmakuApi.searchStaged("自动线路测试", "2026", "综艺", "第1期下", "",
                List.of(endpoint(direct)), List.of(endpoint(backup)), new DanmakuApi.SearchCallback() {
                    public void onFound(Danmaku item, List<Danmaku> catalogue) { found.set(item); done.countDown(); }
                    public void onFailure(DanmakuApi.SearchFailure failure) { done.countDown(); }
                }));
        try { assertTrue(done.await(15, TimeUnit.SECONDS)); assertNotNull(found.get()); return found.get(); }
        finally { DanmakuApi.cancel(); }
    }
    @Test public void directMatchCompletesBeforeAnyBackupRequest() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start(); direct.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(direct,"第1期下","direct").setBodyDelay(250,TimeUnit.MILLISECONDS) : document(); }
            });
            assertTrue(run(direct, backup).getUrl().contains("/direct?")); assertEquals(0, backup.getRequestCount());
        }
    }
    @Test public void wrongHalfIsRejectedBeforeAutomaticBackupMatches() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start(); direct.enqueue(rows(direct,"第1期上","wrong"));
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(backup,"第1期下","backup") : document(); } });
            assertTrue(run(direct, backup).getUrl().contains("/backup?")); assertEquals(1,direct.getRequestCount());
        }
    }
    @Test public void failedDirectDocumentFallsBackWithoutManualLink() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start(); direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(direct,"第1期下","missing") : new MockResponse().setResponseCode(403); } });
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(backup,"第1期下","recovered") : document(); } });
            assertTrue(run(direct,backup).getUrl().contains("/recovered?"));
        }
    }
    @Test public void baseQueryFallbackDoesNotWaitForAnUnrelatedSlowProvider() throws Exception {
        try (var direct = new MockWebServer(); var stalled = new MockWebServer()) {
            direct.start(); stalled.start();
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                if (!r.getPath().startsWith("/search")) return document();
                String title = r.getRequestUrl().queryParameter("name");
                return title.endsWith("2026") ? rows(direct, "第1期上", "wrong-half") : rows(direct, "第1期下", "base");
            }});
            stalled.enqueue(new MockResponse().setBody("[]").setBodyDelay(10, TimeUnit.SECONDS));
            CountDownLatch done = new CountDownLatch(1); AtomicReference<Danmaku> found = new AtomicReference<>();
            long start = android.os.SystemClock.elapsedRealtime();
            DanmakuApi.searchStaged("自动线路测试2026", "", "综艺", "第1期下", "",
                    List.of(endpoint(direct), endpoint(stalled)), List.of(), new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item, List<Danmaku> rows) { found.set(item); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure failure) { done.countDown(); }
            });
            try {
                assertTrue(done.await(4, TimeUnit.SECONDS)); assertNotNull(found.get());
                assertTrue(found.get().getUrl().contains("/base?"));
                assertTrue(android.os.SystemClock.elapsedRealtime() - start < 4000);
            } finally { DanmakuApi.cancel(); }
        }
    }
    @Test public void slowDirectCatalogueSurvivesAFailedBackup() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(direct, "第1期下", "late-direct").setBodyDelay(9, TimeUnit.SECONDS) : document();
            }});
            backup.enqueue(new MockResponse().setResponseCode(403));
            assertTrue(run(direct, backup).getUrl().contains("/late-direct?"));
            assertEquals(1, backup.getRequestCount());
        }
    }
    @Test public void stalledCatalogueAllowsBackupWithoutWaitingForItsTimeout() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            direct.enqueue(new MockResponse().setBody("[]").setBodyDelay(12, TimeUnit.SECONDS));
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(backup, "第1期下", "hedged") : document();
            }});
            long start = android.os.SystemClock.elapsedRealtime();
            assertTrue(run(direct, backup).getUrl().contains("/hedged?"));
            assertTrue(android.os.SystemClock.elapsedRealtime() - start < 11000);
        }
    }
    @Test public void downloadingDirectCandidateKeepsPriorityOverBackup() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(direct, "第1期下", "slow-document") : document().setBodyDelay(9, TimeUnit.SECONDS);
            }});
            assertTrue(run(direct, backup).getUrl().contains("/slow-document?"));
            assertEquals(0, backup.getRequestCount());
        }
    }
    @Test public void malformedResponseIsNotMisreportedAsNetworkTimeout() throws Exception {
        try (var direct = new MockWebServer()) {
            direct.start(); direct.enqueue(new MockResponse().setBody("not json"));
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<DanmakuApi.SearchFailure> failure = new AtomicReference<>();
            DanmakuApi.searchStaged("自动线路测试", "", "综艺", "第1期下", "", List.of(endpoint(direct)), List.of(), new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item, List<Danmaku> rows) { done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure error) { failure.set(error); done.countDown(); }
            });
            try { assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(DanmakuApi.SearchFailure.INVALID_RESPONSE, failure.get()); }
            finally { DanmakuApi.cancel(); }
        }
    }
}
