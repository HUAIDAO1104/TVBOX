package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.player.danmaku.FilteringBiliParser;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public class TencentDanmakuInstrumentedTest {
    private static final String SOURCE = "tencent-danmaku://video/r4102opm7x3";
    private static final String INDEX = "{\"total\":0,\"segment_index\":{\"0\":{\"segment_start\":0,\"segment_name\":\"t/v1/0/30000\"},\"30\":{\"segment_start\":30000,\"segment_name\":\"t/v1/30000/60000\"}}}";

    private OkHttpClient client(MockWebServer server) {
        return new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .url(server.url(chain.request().url().encodedPath())).build())).build();
    }
    private String segment(int second) { return "{\"barrage_list\":[{\"id\":\"" + second + "\",\"time_offset\":" + second * 1000 + ",\"content\":\"弹幕测试\"}]}"; }

    @Test public void completeDocumentRequiresEverySegmentAndKeepsMilliseconds() throws Exception { verify(200, 2); }
    @Test public void failedSegmentCannotCommitPartialDocument() throws Exception { verify(503, 0); }
    @Test public void missingSegmentIsNotSilentlyTreatedAsEmpty() throws Exception { verify(404, 0); }
    private void verify(int status, int expected) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("/base/")) return new MockResponse().setBody(INDEX);
                    if (request.getPath().endsWith("/0/30000")) return new MockResponse().setBody(segment(1));
                    return new MockResponse().setResponseCode(status).setBody(segment(31));
                }
            }); server.start();
            CountDownLatch done = new CountDownLatch(1); AtomicReference<byte[]> result = new AtomicReference<>();
            AtomicReference<IOException> error = new AtomicReference<>();
            TencentDanmaku.document(SOURCE, p -> {}, new IqiyiDanmaku.Callback<>() {
                public void onSuccess(byte[] bytes) { result.set(bytes); done.countDown(); }
                public void onFailure(IOException e) { error.set(e); done.countDown(); }
            }, client(server));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            if (expected == 0) { assertNull(result.get()); assertNotNull(error.get()); }
            else {
                var comments = FilteringBiliParser.INSTANCE.parse(new ByteArrayInputStream(result.get()));
                assertEquals(expected, comments.size()); assertEquals(31000, comments.get(1).timeMs);
            }
        }
    }

    @Test public void cancelledDocumentDoesNotEmitLateSuccessOrFailure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody(INDEX)); server.start();
            AtomicInteger callbacks = new AtomicInteger();
            var job = TencentDanmaku.document(SOURCE, p -> {}, new IqiyiDanmaku.Callback<byte[]>() {
                public void onSuccess(byte[] bytes) { callbacks.incrementAndGet(); }
                public void onFailure(IOException e) { callbacks.incrementAndGet(); }
            }, client(server));
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); job.cancel();
            Thread.sleep(2200); assertEquals(0, callbacks.get()); assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void liveCurrentVarietyAndMovieProduceCompletePlayableDocuments() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        for (String[] spec : List.of(new String[]{"现在就出发第四季", "2026", "综艺", "第1期上", "r4102opm7x3"},
                new String[]{"现在就出发第四季", "2026", "综艺", "第1期下", "z41023tinp7"},
                new String[]{"哪吒2", "2025", "动画电影", "正片", "y4101qnn3jo"})) {
            long start = android.os.SystemClock.elapsedRealtime();
            CountDownLatch search = new CountDownLatch(1); AtomicReference<List<Danmaku>> rows = new AtomicReference<>();
            AtomicReference<IOException> error = new AtomicReference<>();
            TencentDanmaku.search(spec[0], new IqiyiDanmaku.Callback<>() {
                public void onSuccess(List<Danmaku> items) { rows.set(items); search.countDown(); }
                public void onFailure(IOException e) { error.set(e); search.countDown(); }
            });
            assertTrue(search.await(20, TimeUnit.SECONDS)); assertNotNull(String.valueOf(error.get()), rows.get());
            var best = DanmakuMatch.best(spec[0], spec[1], spec[2], spec[3], rows.get(), Danmaku::getMatchName);
            assertNotNull(spec[0] + spec[3], best); assertTrue(best.getUrl(), best.getUrl().endsWith(spec[4]));
            long searched = android.os.SystemClock.elapsedRealtime();
            CountDownLatch download = new CountDownLatch(1); AtomicReference<byte[]> bytes = new AtomicReference<>();
            TencentDanmaku.document(best.getUrl(), p -> {}, new IqiyiDanmaku.Callback<>() {
                public void onSuccess(byte[] data) { bytes.set(data); download.countDown(); }
                public void onFailure(IOException e) { error.set(e); download.countDown(); }
            });
            assertTrue(download.await(45, TimeUnit.SECONDS)); assertNotNull(String.valueOf(error.get()), bytes.get());
            var parsed = FilteringBiliParser.INSTANCE.parse(new ByteArrayInputStream(bytes.get()));
            assertTrue(parsed.size() > 1000);
            long latest = parsed.stream().mapToLong(d -> d.timeMs).max().orElse(0);
            assertTrue("Must retain comments near the end", latest > (spec[3].contains("上") ? 5000 : spec[3].contains("下") ? 6000 : 8000) * 1000L);
            File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), spec[4] + ".xml");
            java.nio.file.Files.write(file.toPath(), bytes.get());
            android.util.Log.i("Danmaku574QA", spec[0] + " " + spec[3] + " rows=" + rows.get().size() + " comments=" + parsed.size()
                    + " endMs=" + latest + " searchMs=" + (searched - start) + " documentMs=" + (android.os.SystemClock.elapsedRealtime() - searched));
        }
    }
}
