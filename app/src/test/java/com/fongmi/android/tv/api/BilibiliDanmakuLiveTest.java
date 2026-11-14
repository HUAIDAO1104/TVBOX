package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import org.junit.*;

/** Explicit opt-in live transport checks; no proxy, AI key, aggregate or fixture responses. */
public class BilibiliDanmakuLiveTest {
    @Test public void realAnimationCataloguesAndCompleteCommentsOverDirectNetwork() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("danmakuLive595"));
        var routes = NativeDanmakuRequest.CLIENT.proxySelector().select(URI.create("https://api.bilibili.com/"));
        assertTrue(routes.toString(), routes.stream().allMatch(p -> p.type() == Proxy.Type.DIRECT));
        List<String> failures = new ArrayList<>();
        for (String[] sample : List.of(new String[]{"凡人修仙传", "2020", "8"}, new String[]{"凡人修仙传", "2020", "9"},
                new String[]{"凡人修仙传", "2020", "10"}, new String[]{"凡人修仙传", "2020", "11"}, new String[]{"凡人修仙传", "2020", "12"},
                new String[]{"凡人修仙传", "2020", "21"}, new String[]{"凡人修仙传", "2020", "22"}, new String[]{"凡人修仙传", "2020", "77"},
                new String[]{"凡人修仙传", "2020", "100"}, new String[]{"凡人修仙传2020版", "2020", "10"},
                new String[]{"牧神记", "2024", "10"}, new String[]{"灵笼 第一季", "2019", "10"})) {
            try {
            long start = System.nanoTime();
            var ready = new CountDownLatch(1); var selected = new AtomicReference<Danmaku>(); var error = new AtomicReference<IOException>();
            var search = BilibiliDanmaku.search(sample[0], sample[0], sample[1], "国产动漫", "第" + sample[2] + "集", new IqiyiDanmaku.Callback<>() {
                public void onSuccess(List<Danmaku> rows) {
                    selected.set(DanmakuMatch.best(sample[0], sample[1], "国产动漫", "第" + sample[2] + "集", rows, Danmaku::getMatchName)); ready.countDown();
                }
                public void onFailure(IOException e) { error.set(e); ready.countDown(); }
            });
            try { assertTrue(ready.await(20, TimeUnit.SECONDS)); assertNotNull(Arrays.toString(sample) + ": " + error.get(), selected.get()); }
            finally { search.cancel(); }
            var loaded = new CountDownLatch(1); var document = new AtomicReference<byte[]>();
            var download = BilibiliDanmaku.document(selected.get().getUrl(), p -> {}, new IqiyiDanmaku.Callback<>() {
                public void onSuccess(byte[] bytes) { document.set(bytes); loaded.countDown(); }
                public void onFailure(IOException e) { error.set(e); loaded.countDown(); }
            });
            try {
                assertTrue(loaded.await(80, TimeUnit.SECONDS)); assertNotNull(Arrays.toString(sample) + ": " + error.get(), document.get());
                String xml = new String(document.get(), StandardCharsets.UTF_8);
                int count = xml.split("<d ").length - 1, atTenMinutes = 0;
                Matcher matcher = Pattern.compile("<d p=\"([0-9.]+),").matcher(xml);
                while (matcher.find()) { double t = Double.parseDouble(matcher.group(1)); if (t >= 590 && t <= 620) atTenMinutes++; }
                assertTrue("Real comments required: " + count, count > 100);
                assertTrue("A usable timeline around ten minutes is required", atTenMinutes > 0);
                System.out.println("DIRECT " + Arrays.toString(sample) + " source=" + selected.get().getUrl() + " comments=" + count
                        + " at590to620s=" + atTenMinutes + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            } finally { download.cancel(); }
            } catch (AssertionError failure) { failures.add(failure.getMessage()); System.out.println("UNAVAILABLE " + failure.getMessage()); }
            Thread.sleep(1000); // Pace live catalogue/comment checks like independent episode changes.
        }
        assertTrue(failures.toString(), failures.isEmpty());
    }
}
