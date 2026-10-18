package com.fongmi.android.tv.api;
import static org.junit.Assert.*;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

public class MangoDanmakuTest {
    private String fixture(String name) throws Exception { try (var in = getClass().getResourceAsStream("/danmaku/" + name + ".json")) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); } }
    @Test public void realMangoCatalogueSeparatesMainLiveAndBonusEpisodes() throws Exception {
        var works = MangoDanmaku.works(fixture("mgtv-search-signed"), "披荆斩棘2026"); assertEquals(1, works.size());
        var rows = MangoDanmaku.catalogue(fixture("mgtv-master"), works.get(0));
        for (String[] spec : List.of(new String[]{"第1期上", "24566777"}, new String[]{"第1期下", "24567586"},
                new String[]{"2026年8月16日下", "24567586"}, new String[]{"0816下", "24567586"},
                new String[]{"加更版第1期", "24579459"}, new String[]{"会员加更第2期", "24594607"},
                new String[]{"20260809超前企划第1期", "24547204"}, new String[]{"20260809超前企划第2期", "24547346"})) {
            var selected = DanmakuMatch.best("披荆斩棘2026", "2026", "综艺", spec[0], rows, Danmaku::getName);
            assertNotNull(spec[0], selected); assertTrue(selected.getName(), selected.getUrl().endsWith(spec[1]));
        }
        assertNull(DanmakuMatch.best("披荆斩棘2026", "2026", "综艺", "第1期", rows, Danmaku::getName));
        assertNull(DanmakuMatch.best("披荆斩棘2026", "2026", "综艺", "20260809超前企划", rows, Danmaku::getName));
    }
    @Test public void mangoMillisecondsAndLongDurationsArePreserved() throws Exception {
        assertEquals(11460, MangoDanmaku.duration("191:00"));
        assertEquals(3723, MangoDanmaku.duration("1:02:03"));
        assertThrows(IOException.class, () -> MangoDanmaku.duration("999999:59"));
        var rows = MangoDanmaku.comments(fixture("mgtv-comments")); assertEquals(4, rows.size());
        assertThrows(IOException.class, () -> MangoDanmaku.comments("{\"status\":202,\"data\":{\"items\":null}}"));
        assertThrows(IOException.class, () -> MangoDanmaku.comments("{\"status\":0,\"data\":{}}"));
    }
    @Test public void searchPreviewRejectsConflictingVideoIdentityAndPreservesEdition() throws Exception {
        String search = fixture("mgtv-search-signed");
        var work = MangoDanmaku.works(search, "披荆斩棘2026").get(0);
        assertEquals(5, work.preview().size());
        assertTrue(work.preview().get(0).getName().contains("2026-10-16 第10期上"));
        var wrongClip = MangoDanmaku.works(search.replace("/b/896231/", "/b/999999/"), "披荆斩棘2026").get(0);
        assertTrue(wrongClip.preview().isEmpty());
        var wrongId = MangoDanmaku.works(search.replace("/24719651.html", "/99999999.html"), "披荆斩棘2026").get(0);
        assertEquals(4, wrongId.preview().size());
    }
    @Test public void movieCardsUseTheirOwnMetadataAndNeverRelabelExtrasAsMain() throws Exception {
        var works = MangoDanmaku.works(fixture("mgtv-movie-search"), "抓娃娃");
        assertEquals(1, works.size()); assertEquals("2024", works.get(0).year()); assertEquals("电影", works.get(0).type());
        var rows = MangoDanmaku.catalogue(fixture("mgtv-movie-master"), works.get(0));
        var movie = DanmakuMatch.best("抓娃娃", "2024", "电影", "HD国语中字", rows, Danmaku::getName);
        assertNotNull(movie); assertTrue(movie.getUrl().endsWith("21770678"));
        var extras = MangoDanmaku.catalogue(fixture("mgtv-movie-master").replace("\"t3\": \"抓娃娃\"", "\"t3\": \"抓娃娃 预告片\""), works.get(0));
        assertNull(DanmakuMatch.best("抓娃娃", "2024", "电影", "正片", extras, Danmaku::getName));
    }
    @Test public void directLinksAreStrictlyScopedToKnownPlatformPages() {
        for (String url : List.of("https://www.mgtv.com/b/896231/24567586.html", "https://v.youku.com/v_show/id_XNTkxNDY2Nzg2MA==.html",
                "https://www.bilibili.com/bangumi/play/ep691614", "https://www.bilibili.com/video/BV17x411w7KC?p=3",
                "https://v.qq.com/x/cover/mzc002001bk7moh/r4102opm7x3.html", "https://www.iqiyi.com/v_123abc.html")) assertTrue(url, NativeDanmaku.supports(url));
        for (String url : List.of("https://mgtv.com.evil.test/b/896231/24567586.html", "https://v.youku.com@evil.test/v_show/id_XNTkxNDY2Nzg2MA==.html",
                "https://www.bilibili.com/video/../../secret", "https://localhost/b/123/45678.html")) assertFalse(url, NativeDanmaku.supports(url));
    }
    @Test public void automaticTargetDoesNotWaitForUnneededHistoricalMonths() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        String search = fixture("mgtv-search-signed"), page = fixture("mgtv-master");
        var client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            int count = requests.incrementAndGet();
            if (count > 2) throw new java.io.IOException("Historical page unavailable");
            String data = chain.request().url().host().startsWith("mobileso") ? search : page;
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(okhttp3.ResponseBody.create(data, okhttp3.MediaType.get("application/json"))).build();
        }).build();
        var done = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<List<Danmaku>>();
        var job = MangoDanmaku.search("披荆斩棘2026", "2026", "综艺", "第1期上", new IqiyiDanmaku.Callback<List<Danmaku>>() {
            public void onSuccess(List<Danmaku> rows) { result.set(rows); done.countDown(); }
            public void onFailure(java.io.IOException e) { done.countDown(); }
        }, client);
        try {
            assertTrue(done.await(4, java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(result.get());
            assertNotNull(DanmakuMatch.best("披荆斩棘2026", "2026", "综艺", "第1期上", result.get(), Danmaku::getMatchName));
            assertEquals(2, requests.get());
        } finally { job.cancel(); }
    }
}
