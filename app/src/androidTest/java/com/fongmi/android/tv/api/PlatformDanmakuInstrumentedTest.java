package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.player.danmaku.FilteringBiliParser;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;

@RunWith(AndroidJUnit4.class)
public class PlatformDanmakuInstrumentedTest {
    private void live() { Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive"))); }
    private <T> T waitFor(java.util.function.Consumer<IqiyiDanmaku.Callback<T>> start) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<T> value = new AtomicReference<>(); AtomicReference<IOException> error = new AtomicReference<>();
        start.accept(new IqiyiDanmaku.Callback<>() {
            public void onSuccess(T v) { value.set(v); latch.countDown(); }
            public void onFailure(IOException e) { android.util.Log.e("Danmaku575QA", "Native request failed", e); error.set(e); latch.countDown(); }
        });
        assertTrue("callback deadline", latch.await(55, TimeUnit.SECONDS)); assertNotNull(String.valueOf(error.get()), value.get()); return value.get();
    }
    @Test public void liveMangoMainDatesHalvesAndExtrasDownloadDistinctDocuments() throws Exception {
        live(); List<Danmaku> rows = waitFor(cb -> MangoDanmaku.search("披荆斩棘2026", cb));
        for (String[] spec : List.of(new String[]{"第1期上", "24566777"}, new String[]{"2026年8月16日下", "24567586"},
                new String[]{"会员加更第1期", "24579459"}, new String[]{"第2期下", "24578974"},
                new String[]{"2026-10-16第10期上", "24719651"})) {
            var best = DanmakuMatch.best("披荆斩棘2026", "2026", "综艺", spec[0], rows, Danmaku::getMatchName);
            assertNotNull(spec[0], best); assertTrue(best.getName(), best.getUrl().endsWith(spec[1])); checkDocument(best.getUrl(), spec[1], 100);
        }
        assertNull(DanmakuMatch.best("披荆斩棘2026", "2026", "综艺", "第1期", rows, Danmaku::getMatchName));
    }
    @Test public void liveOtherMangoVarietyDatesAndMovieCatalogue() throws Exception {
        live();
        for (String[] spec : List.of(new String[]{"你好星期六", "2026", "综艺", "20261010"},
                new String[]{"乘风2026", "2026", "综艺", "第1期上"},
                new String[]{"歌手2026", "2026", "综艺", "第1期"},
                new String[]{"抓娃娃", "2024", "电影", "正片"})) {
            List<Danmaku> rows = waitFor(cb -> MangoDanmaku.search(spec[0], cb));
            var best = DanmakuMatch.best(spec[0], spec[1], spec[2], spec[3], rows, Danmaku::getMatchName);
            assertNotNull(spec[0] + " rows=" + rows.stream().limit(4).map(Danmaku::getName).toList(), best);
            checkDocument(best.getUrl(), spec[0], 20);
        }
    }
    @Test public void liveCommonPlatformLinksProducePlayableComments() throws Exception {
        live();
        for (String[] spec : List.of(new String[]{"youku-film", "https://v.youku.com/v_show/id_XNTkxNDY2Nzg2MA==.html"},
                new String[]{"bili-episode", "https://www.bilibili.com/bangumi/play/ep691614"},
                new String[]{"bili-part", "https://www.bilibili.com/video/BV17x411w7KC?p=3"},
                new String[]{"mango-link", "https://www.mgtv.com/b/896231/24579459.html"},
                new String[]{"qq-link", "https://v.qq.com/x/cover/mzc00200tjkzeps/y4101qnn3jo.html"},
                new String[]{"iqiyi-link", "https://www.iqiyi.com/v_pp7j21xlp4.html"},
                new String[]{"gamer-link", "https://ani.gamer.com.tw/animeVideo.php?sn=41645"})) checkDocument(spec[1], spec[0], 20);
    }
    private void checkDocument(String source, String key, int minimum) throws Exception {
        android.util.Log.i("Danmaku575QA", "loading " + key + " " + source);
        long start = android.os.SystemClock.elapsedRealtime(); byte[] bytes = waitFor(cb -> NativeDanmaku.document(source, p -> {}, cb));
        var comments = FilteringBiliParser.INSTANCE.parse(new ByteArrayInputStream(bytes)); assertTrue(key + " count=" + comments.size(), comments.size() > minimum);
        long end = comments.stream().mapToLong(c -> c.timeMs).max().orElse(0); assertTrue(key + " last=" + end, end > 60000);
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "575-" + key + ".xml");
        java.nio.file.Files.write(file.toPath(), bytes);
        android.util.Log.i("Danmaku575QA", key + " source=" + source + " comments=" + comments.size() + " lastMs=" + end + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start));
    }
    private OkHttpClient client(MockWebServer server) { return new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url().encodedPath() + "?" + chain.request().url().encodedQuery())).build())).build(); }
    @Test public void mangoFailedSegmentDoesNotCommitPartialDocument() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) {
                    if (r.getPath().contains("/video/info")) return new MockResponse().setBody("{\"code\":200,\"data\":{\"info\":{\"videoId\":\"24579459\",\"clipId\":\"896231\",\"time\":\"1:30\"}}}");
                    if (r.getPath().endsWith("time=0")) return new MockResponse().setBody("{\"status\":0,\"data\":{\"items\":[{\"time\":1000,\"content\":\"有效弹幕\"}]}}");
                    return new MockResponse().setResponseCode(503);
                }
            }); server.start(); CountDownLatch done = new CountDownLatch(1); AtomicReference<byte[]> success = new AtomicReference<>();
            MangoDanmaku.document("mango-danmaku://video/896231/24579459", p -> {}, new IqiyiDanmaku.Callback<>() {
                public void onSuccess(byte[] v) { success.set(v); done.countDown(); }
                public void onFailure(IOException e) { done.countDown(); }
            }, client(server)); assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(success.get());
        }
    }
    @Test public void mangoCancellationHasNoLateCallback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("{}")); server.start(); AtomicInteger count = new AtomicInteger();
            var job = MangoDanmaku.document("mango-danmaku://video/896231/24579459", p -> {}, new IqiyiDanmaku.Callback<byte[]>() {
                public void onSuccess(byte[] v) { count.incrementAndGet(); }
                public void onFailure(IOException e) { count.incrementAndGet(); }
            }, client(server)); assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); job.cancel(); Thread.sleep(2200); assertEquals(0, count.get());
        }
    }
    @Test public void automaticBareYearFindsEpisodeTen() throws Exception {
        live();
        var done = new java.util.concurrent.CountDownLatch(1);
        var found = new java.util.concurrent.atomic.AtomicReference<Danmaku>();
        var failure = new java.util.concurrent.atomic.AtomicReference<DanmakuApi.SearchFailure>();
        long start = android.os.SystemClock.elapsedRealtime();
        DanmakuApi.searchDetailed("披荆斩棘2026", "", "综艺", "第十期上", new DanmakuApi.SearchCallback() {
            public void onFound(Danmaku item, List<Danmaku> rows) { found.set(item); done.countDown(); }
            public void onFailure(DanmakuApi.SearchFailure error) { failure.set(error); done.countDown(); }
        });
        try {
            assertTrue(done.await(95, java.util.concurrent.TimeUnit.SECONDS));
            android.util.Log.i("Danmaku578QA", "automatic elapsed=" + (android.os.SystemClock.elapsedRealtime()-start) + " failure=" + failure.get());
            assertNotNull("failure=" + failure.get(), found.get());
            assertTrue(found.get().getUrl(), found.get().getUrl().endsWith("/24719651"));
        } finally { DanmakuApi.cancel(); }
    }
}
