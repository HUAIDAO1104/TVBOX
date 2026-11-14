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
    @Test public void winningBackupCancelsPendingAlternateDocumentStarts() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start(); var late = new AtomicInteger();
            List<Danmaku> rows = new ArrayList<>();
            for (String id : List.of("bad", "slow", "must-not-start")) {
                Danmaku row = Danmaku.from(direct.url("/api/v2/comment/"+id+"?format=xml").toString());
                row.setName("自动线路测试(2026)【综艺】from fixture - 第1期下"); rows.add(row);
            }
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                if (r.getPath().startsWith("/search")) return new MockResponse().setBody(App.gson().toJson(rows));
                if (r.getPath().contains("/bad?")) return new MockResponse().setResponseCode(403);
                if (r.getPath().contains("/must-not-start")) late.incrementAndGet();
                return document().setBodyDelay(8,TimeUnit.SECONDS);
            }});
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(backup,"第1期下","backup") : document();
            }});
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>();
            DanmakuApi.searchStaged("自动线路测试","2026","综艺","第1期下","",List.of(endpoint(direct)),List.of(endpoint(backup)),new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item,List<Danmaku> catalogue) { found.set(item);done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure error) { done.countDown(); }
            });
            try {
                assertTrue(done.await(4,TimeUnit.SECONDS));assertNotNull(found.get());assertTrue(found.get().getUrl().contains("/backup?"));
                android.os.SystemClock.sleep(1500);
                assertEquals("A finished match must not launch a queued alternative",0,late.get());
            } finally { DanmakuApi.cancel(); }
        }
    }
    @Test public void slowFirstExactDocumentDoesNotBlockAHealthySameEpisode() throws Exception { sameEpisodeRace(false); }
    @Test public void failedFastAlternativeDoesNotDiscardTheHealthySlowExactDocument() throws Exception { sameEpisodeRace(true); }
    private void sameEpisodeRace(boolean fastFails) throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); var first = Danmaku.from(server.url("/api/v2/comment/slow?format=xml").toString());
            first.setName("自动线路测试(2026)【综艺】from fixture - 第1期下");
            var second = Danmaku.from(server.url("/api/v2/comment/fast?format=xml").toString()); second.setName(first.getName());
            server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                if (r.getPath().startsWith("/search")) return new MockResponse().setBody(App.gson().toJson(List.of(first, second)));
                return r.getPath().contains("/slow") ? document().setBodyDelay(fastFails ? 2 : 8, TimeUnit.SECONDS)
                        : fastFails ? new MockResponse().setResponseCode(403) : document();
            }});
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>(); long started = android.os.SystemClock.elapsedRealtime();
            DanmakuApi.searchStaged("自动线路测试", "2026", "综艺", "第1期下", "", List.of(endpoint(server)), List.of(), new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item, List<Danmaku> rows) { found.set(item); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure error) { done.countDown(); }
            });
            try {
                assertTrue("Healthy exact candidate must not wait for a stalled document", done.await(4, TimeUnit.SECONDS));
                assertNotNull(found.get()); assertEquals((fastFails ? first : second).getUrl(), found.get().getUrl());
                android.util.Log.i("Danmaku597QA", "same-episode alternate elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-started));
            } finally { DanmakuApi.cancel(); }
        }
    }
    @Test public void refreshedCatalogueRetriesTransientDocumentOnceWithoutManualSearch() throws Exception { transientRecovery(true); }
    @Test public void persistentDocumentFailureStopsAfterOneCatalogueRefresh() throws Exception { transientRecovery(false); }
    private void transientRecovery(boolean recovers) throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); var documents = new AtomicInteger(); var searches = new AtomicInteger();
            server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                if (r.getPath().startsWith("/search")) { searches.incrementAndGet(); return rows(server, "第1期下", "transient"); }
                return documents.incrementAndGet() == 1 || !recovers ? new MockResponse().setResponseCode(503) : document();
            }});
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>();
            long started = android.os.SystemClock.elapsedRealtime();
            DanmakuApi.searchStaged("自动线路测试", "2026", "综艺", "第1期下", "", List.of(endpoint(server)), List.of(), new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item, List<Danmaku> rows) { found.set(item); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure error) { done.countDown(); }
            });
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS));
                if (recovers) assertNotNull("A refreshed, now-working document must not require manual search", found.get());
                else assertNull(found.get());
                assertEquals(2, searches.get()); assertEquals(2, documents.get());
                android.util.Log.i("Danmaku597QA", "automatic transient recovery elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-started));
            } finally { DanmakuApi.cancel(); }
        }
    }
    @Test public void newCatalogueReplacesAnOlderPrefetchWithoutLateCacheOverwrite() throws Exception {
        try(var server=new MockWebServer()) {
            server.start(); var requests=new AtomicInteger(); var started=new CountDownLatch(1);
            server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                int n=requests.incrementAndGet(); started.countDown();
                return new MockResponse().setBody("<i><d p=\"1,1,25,16777215,0,0,0,1\">"+(n==1?"旧内容":"新内容")+"</d></i>").setBodyDelay(n==1?600:0,TimeUnit.MILLISECONDS);
            }});
            var source=android.net.Uri.parse(server.url("/comment.xml").toString());
            com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.load(source,new com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.Listener() {
                public void onReady(android.net.Uri s,android.net.Uri l) { fail("Superseded request delivered"); }
                public void onFailure(android.net.Uri s,java.io.IOException e) { }
            });
            assertTrue(started.await(3,TimeUnit.SECONDS)); android.os.SystemClock.sleep(20);
            var done=new CountDownLatch(1);var result=new AtomicReference<android.net.Uri>();
            com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.loadConfirmed(source,System.currentTimeMillis(),new com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.Listener() {
                public void onReady(android.net.Uri s,android.net.Uri l) { result.set(l);done.countDown(); }
                public void onFailure(android.net.Uri s,java.io.IOException e) { done.countDown(); }
            });
            assertTrue(done.await(3,TimeUnit.SECONDS));assertNotNull(result.get());
            android.os.SystemClock.sleep(800);
            try(var in=new java.io.FileInputStream(result.get().getPath())) {
                String body=new String(com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(in,10000),java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(body.contains("新内容"));assertFalse(body.contains("旧内容"));
            }
            assertEquals(2,requests.get());
        }
    }

    @Test public void recycledProviderIdCannotReplayAnUnrelatedCachedDocument() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); var stale = new AtomicBoolean(true); var documents = new AtomicInteger();
            server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                if (r.getPath().startsWith("/search")) return rows(server, "第1期下", "recycled");
                documents.incrementAndGet();
                return new MockResponse().setHeader("Cache-Control", "public, max-age=604800")
                        .setBody("<i><d p=\"1,1,25,16777215,0,0,0,1\">" + (stale.get() ? "噬金虫：旧的不相关内容" : "当前集的正确内容") + "</d></i>");
            }});
            var uri = android.net.Uri.parse(server.url("/api/v2/comment/recycled?format=xml").toString());
            var primed = new CountDownLatch(1); var oldFile = new AtomicReference<android.net.Uri>();
            com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.load(uri, new com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.Listener() {
                public void onReady(android.net.Uri s, android.net.Uri file) { oldFile.set(file); primed.countDown(); }
                public void onFailure(android.net.Uri s, java.io.IOException error) { primed.countDown(); }
            });
            assertTrue(primed.await(5, TimeUnit.SECONDS)); assertNotNull(oldFile.get());
            try (var in = new java.io.FileInputStream(oldFile.get().getPath())) {
                assertTrue(new String(com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(in, 10000),
                        java.nio.charset.StandardCharsets.UTF_8).contains("噬金虫：旧的不相关内容"));
            }
            stale.set(false);
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>();
            DanmakuApi.searchStaged("自动线路测试", "2026", "综艺", "第1期下", "", List.of(endpoint(server)), List.of(), new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item, List<Danmaku> rows) { found.set(item); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure failure) { done.countDown(); }
            });
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS)); assertNotNull(found.get());
                var loaded = new CountDownLatch(1); var xml = new AtomicReference<String>();
                com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.load(uri, new com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.Listener() {
                    public void onReady(android.net.Uri s, android.net.Uri file) {
                        try (var in = new java.io.FileInputStream(file.getPath())) {
                            xml.set(new String(com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(in, 10000), java.nio.charset.StandardCharsets.UTF_8));
                        } catch (Exception e) { throw new AssertionError(e); } finally { loaded.countDown(); }
                    }
                    public void onFailure(android.net.Uri s, java.io.IOException error) { loaded.countDown(); }
                });
                assertTrue(loaded.await(5, TimeUnit.SECONDS)); assertNotNull(xml.get());
                assertTrue(xml.get().contains("当前集的正确内容")); assertFalse(xml.get().contains("噬金虫"));
                assertEquals("Refresh must fetch once, mounting must reuse the verified document", 2, documents.get());
            } finally { DanmakuApi.cancel(); }
        }
    }
    @Test public void playbackResolutionHappensBeforeDownloadingTheDefaultCandidate() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var wrongRequests = new AtomicInteger();
            Danmaku wrong = Danmaku.from(server.url("/api/v2/comment/wrong?format=xml").toString());
            wrong.setName("自动线路测试(2026)【综艺】from fixture - 第1期下");
            Danmaku correct = Danmaku.from(server.url("/api/v2/comment/correct?format=xml").toString());
            correct.setName(wrong.getName());
            server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                if (r.getPath().startsWith("/search")) return new MockResponse().setBody(App.gson().toJson(List.of(wrong, correct)));
                if (r.getPath().contains("/wrong")) { wrongRequests.incrementAndGet(); return new MockResponse().setResponseCode(403); }
                return document();
            }});
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>();
            DanmakuApi.searchStaged("自动线路测试", "2026", "综艺", "第1期下", "", List.of(endpoint(server)), List.of(), new DanmakuApi.SearchCallback() {
                public void resolveCandidates(List<Danmaku> candidates, List<Danmaku> rows, java.util.function.Consumer<List<Danmaku>> ready) { ready.accept(List.of(correct)); }
                public void onFound(Danmaku item, List<Danmaku> rows) { found.set(item); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure failure) { done.countDown(); }
            });
            try { assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(correct, found.get()); assertEquals(0, wrongRequests.get()); }
            finally { DanmakuApi.cancel(); }
        }
    }
    @Test public void liveDramaAutomaticMatchesAcrossTitlesAndEpisodeLabels() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        List<String> errors = new ArrayList<>();
        for (String[] spec : List.of(new String[]{"喜剧之王", "2026", "第14集"},
                new String[]{"狂飙", "2023", "14"}, new String[]{"庆余年第二季", "2024", "第14集"},
                new String[]{"长相思第二季", "2024", "第5集"}, new String[]{"罚罪2", "2025", "第2集"})) {
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>();
            var failure = new AtomicReference<DanmakuApi.SearchFailure>(); long start = android.os.SystemClock.elapsedRealtime();
            DanmakuApi.searchDetailed(spec[0], spec[1], "电视剧", spec[2], new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item, List<Danmaku> rows) { found.set(item); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure error) { failure.set(error); done.countDown(); }
            });
            try {
                assertTrue("Drama search deadline", done.await(95, TimeUnit.SECONDS));
                android.util.Log.i("Danmaku585QA", "drama " + Arrays.toString(spec) + " failure=" + failure.get()
                        + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime()-start)
                        + " result=" + (found.get()==null ? "" : found.get().getMatchName()+" "+found.get().getUrl()));
                if (found.get()==null) errors.add(Arrays.toString(spec)+": "+failure.get());
                else assertFalse(com.fongmi.android.tv.playback.vod.DanmakuMatch.ranked(spec[0], spec[1], "电视剧", spec[2],
                        List.of(found.get()), Danmaku::getMatchName).isEmpty());
            } finally { DanmakuApi.cancel(); }
        }
        assertEquals(List.of(), errors);
    }
    @Test public void liveAutomaticBroadTitleKeepsSeasonDatesHalvesAndExtras() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        for (String[] spec : List.of(new String[]{"第1期上", "r4102opm7x3"}, new String[]{"第1期下", "z41023tinp7"},
                new String[]{"2026年10月10日上", "r4102opm7x3"}, new String[]{"第1期加更", "r4102w56q2o"})) {
            var done = new CountDownLatch(1); var item = new AtomicReference<Danmaku>();
            var failure = new AtomicReference<DanmakuApi.SearchFailure>(); long start = android.os.SystemClock.elapsedRealtime();
            DanmakuApi.searchDetailed("现在就出发第四季", "2026", "综艺", spec[0], new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku found, List<Danmaku> rows) { item.set(found); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure error) { failure.set(error); done.countDown(); }
            });
            try {
                assertTrue(done.await(95, TimeUnit.SECONDS)); assertNotNull(spec[0] + ": " + failure.get(), item.get());
                assertTrue(item.get().getUrl(), item.get().getUrl().endsWith(spec[1]));
                android.util.Log.i("Danmaku584QA", "automatic " + spec[0] + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start) + " source=" + item.get().getUrl());
            } finally { DanmakuApi.cancel(); }
        }
    }
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
    @Test public void lateDirectDocumentSurvivesAFailedBackup() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(direct, "第1期下", "slow-document") : document().setBodyDelay(9, TimeUnit.SECONDS);
            }});
            backup.enqueue(new MockResponse().setResponseCode(503));
            assertTrue(run(direct, backup).getUrl().contains("/slow-document?"));
            assertEquals(1, backup.getRequestCount());
        }
    }

    @Test public void stalledDocumentDoesNotSuppressHealthyBackup() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(direct, "第1期下", "stalled")
                        : document().setBodyDelay(30, TimeUnit.SECONDS);
            }});
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(backup, "第1期下", "healthy") : document();
            }});
            long start = android.os.SystemClock.elapsedRealtime();
            assertTrue(run(direct, backup).getUrl().contains("/healthy?"));
            assertTrue("A candidate without bytes is not a working service",
                    android.os.SystemClock.elapsedRealtime() - start < 9000);
        }
    }
    @Test public void failedDocumentStartsBackupBeforeUnrelatedCatalogueFinishes() throws Exception {
        try (var direct = new MockWebServer(); var stalled = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); stalled.start(); backup.start();
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(direct, "第1期下", "denied") : new MockResponse().setResponseCode(403);
            }});
            stalled.enqueue(new MockResponse().setBody("[]").setBodyDelay(12, TimeUnit.SECONDS));
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(backup, "第1期下", "prompt") : document();
            }});
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>();
            DanmakuApi.searchStaged("自动线路测试", "2026", "综艺", "第1期下", "",
                    List.of(endpoint(direct), endpoint(stalled)), List.of(endpoint(backup)), new DanmakuApi.SearchCallback() {
                public void onFound(Danmaku item, List<Danmaku> catalogue) { found.set(item); done.countDown(); }
                public void onFailure(DanmakuApi.SearchFailure failure) { done.countDown(); }
            });
            try { assertTrue("Broken document must unblock backups", done.await(5, TimeUnit.SECONDS)); assertNotNull(found.get()); }
            finally { DanmakuApi.cancel(); }
        }
    }

    @Test public void ordinaryXmlUrlsAreVerifiedBeforeDeclaringAMatch() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                if (!r.getPath().startsWith("/search")) return new MockResponse().setResponseCode(503);
                Danmaku row = Danmaku.from(direct.url("/custom/comments.xml").toString());
                row.setName("自动线路测试(2026)【综艺】from fixture - 第1期下");
                return new MockResponse().setBody(App.gson().toJson(List.of(row)));
            }});
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) {
                return r.getPath().startsWith("/search") ? rows(backup, "第1期下", "verified") : document();
            }});
            assertTrue(run(direct, backup).getUrl().contains("/verified?"));
            assertTrue("Custom XML must actually be fetched", direct.getRequestCount() >= 2);
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
