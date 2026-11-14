package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class BilibiliDanmakuTest {
    @Test public void knownOfficialWorkReturnsImmediatelyForManualAndNextEpisodeSearch() throws Exception {
        String title = "缓存动画回归";
        try (var server = new MockWebServer()) {
            String catalogue = fixture().replace("凡人修仙传", title);
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody(request.getPath().contains("/pgc/view/web/season") ? catalogue
                        : "{\"code\":0,\"data\":{\"total\":1,\"list\":[{\"season_id\":28747,\"season_type\":4,\"title\":\""+title+"\"}]}}");
            }}); server.start();
            var client = new OkHttpClient.Builder().addInterceptor(c -> c.proceed(c.request().newBuilder().url(
                    server.url(c.request().url().encodedPath()+"?"+c.request().url().encodedQuery())).build())).build();
            var first = new CountDownLatch(1);
            var initial = BilibiliDanmaku.search(title,title,"2020","动漫","第10集",new IqiyiDanmaku.Callback<>() {
                public void onSuccess(List<Danmaku> rows) { first.countDown(); }
                public void onFailure(IOException error) { fail(error.toString()); }
            },client,true);
            try {
                assertTrue(first.await(3,TimeUnit.SECONDS)); int requests = server.getRequestCount();
                var next = new AtomicReference<List<Danmaku>>(); var ready = new CountDownLatch(1);
                var lookup = BilibiliDanmaku.search(title,title,"2020","动漫","第11集",new IqiyiDanmaku.Callback<>() {
                    public void onSuccess(List<Danmaku> rows) { next.set(rows); ready.countDown(); }
                    public void onFailure(IOException error) { fail(error.toString()); }
                },client,true);
                try { assertTrue(ready.await(1,TimeUnit.SECONDS)); assertNotNull(next.get()); assertNotNull(DanmakuMatch.best(title,"2020","动漫","第11集",next.get(),Danmaku::getMatchName)); }
                finally { lookup.cancel(); }
                var partial = new AtomicBoolean(); var delivered = new CountDownLatch(1);
                var manual = BilibiliDanmaku.search(title,title,"","","",new IqiyiDanmaku.Callback<>() {
                    public void onPartial(List<Danmaku> rows) { partial.set(!rows.isEmpty()); delivered.countDown(); }
                    public void onSuccess(List<Danmaku> rows) { }
                    public void onFailure(IOException error) { fail(error.toString()); }
                },client,true);
                try { assertTrue(delivered.await(1,TimeUnit.SECONDS)); assertTrue("Cached rows must be delivered before another worker scan",partial.get()); }
                finally { manual.cancel(); }
            } finally { initial.cancel(); }
        }
    }
    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/danmaku/bilibili-fanren-catalogue.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test public void authoritativeFullEpisodesNeverChooseSameNumberTrailerOrOldCut() throws Exception {
        var rows = BilibiliDanmaku.catalogue(fixture(), "28747");
        for (String[] sample : List.of(new String[]{"8", "733323"}, new String[]{"9", "733324"},
                new String[]{"10", "733325"}, new String[]{"11", "733326"}, new String[]{"12", "733327"},
                new String[]{"21", "733336"}, new String[]{"22", "428988"}, new String[]{"77", "785516"}, new String[]{"100", "785539"})) {
            var row = DanmakuMatch.best("凡人修仙传", "2020", "国产动漫", "第" + sample[0] + "集", rows, Danmaku::getMatchName);
            assertNotNull(sample[0], row); assertTrue(row.getUrl(), row.getUrl().contains("/" + sample[1] + "/"));
        }
        var old = DanmakuMatch.best("凡人修仙传2020版", "2020", "动漫", "第10集", rows, Danmaku::getMatchName);
        assertNotNull(old); assertTrue(old.getUrl(), old.getUrl().contains("/734372/"));
        assertNull(DanmakuMatch.best("凡人修仙传", "2025", "电视剧", "第10集", rows, Danmaku::getMatchName));
        assertFalse(rows.stream().anyMatch(x -> x.getUrl().contains("/788322/") || x.getUrl().contains("/342223/")));
    }
    @Test public void zeroPaddedEpisodeNumbersRemainUsable() throws Exception {
        var rows = BilibiliDanmaku.catalogue(fixture().replace("\"title\": \"10\"", "\"title\": \"010\""), "28747");
        var row = DanmakuMatch.best("凡人修仙传", "2020", "动漫", "第10集", rows, Danmaku::getMatchName);
        assertNotNull(row); assertTrue(row.getUrl().contains("/733325/"));
    }
    @Test public void mismatchedSeasonAndUnsafeDocumentIdentitiesAreRejected() throws Exception {
        assertThrows(IOException.class, () -> BilibiliDanmaku.catalogue(fixture(), "2"));
        assertThrows(IOException.class, () -> BilibiliDanmaku.catalogue("{\"code\":-352}", "28747"));
        assertTrue(BilibiliDanmaku.supports("bilibili-danmaku://video/733325/1077921686/1176"));
        for (String bad : List.of("bilibili-danmaku://video/1/0/1200", "bilibili-danmaku://video/1/2/99999", "bilibili-danmaku://video/1/2/0", "https://evil.invalid/1/2/10")) assertFalse(bad, BilibiliDanmaku.supports(bad));
    }
    @Test public void publicIndexDiscoversTitlesBeyondTheFirstPageWithoutSearchService() throws Exception {
        try (var server = new MockWebServer()) {
            String catalogue = fixture().replace("凡人修仙传", "分页动画");
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) {
                    if (r.getPath().contains("/pgc/view/web/season")) return new MockResponse().setBody(catalogue);
                    if (!r.getPath().contains("/pgc/season/index/result")) return new MockResponse().setResponseCode(503);
                    boolean second = "2".equals(r.getRequestUrl().queryParameter("page"));
                    return new MockResponse().setBody("{\"code\":0,\"data\":{\"total\":101,\"list\":"
                            + (second ? "[{\"season_id\":28747,\"season_type\":4,\"title\":\"分页动画\"}]" : "[]") + "}}");
                }
            }); server.start();
            var client = new OkHttpClient.Builder().addInterceptor(c -> c.proceed(c.request().newBuilder().url(
                    server.url(c.request().url().encodedPath() + "?" + c.request().url().encodedQuery())).build())).build();
            var done = new CountDownLatch(1); var rows = new AtomicReference<List<Danmaku>>();
            var error = new AtomicReference<IOException>();
            var job = BilibiliDanmaku.search("分页动画", "分页动画", "2020", "动漫", "第10集", new IqiyiDanmaku.Callback<>() {
                public void onSuccess(List<Danmaku> value) { rows.set(value); done.countDown(); }
                public void onFailure(IOException e) { error.set(e); done.countDown(); }
            }, client, false);
            try {
                assertTrue(done.await(4, TimeUnit.SECONDS)); assertNotNull(String.valueOf(error.get()), rows.get());
                assertNotNull(DanmakuMatch.best("分页动画", "2020", "动漫", "第10集", rows.get(), Danmaku::getMatchName));
                assertEquals(4, server.getRequestCount()); // two first pages, CN page two, catalogue
            } finally { job.cancel(); }
        }
    }
}
