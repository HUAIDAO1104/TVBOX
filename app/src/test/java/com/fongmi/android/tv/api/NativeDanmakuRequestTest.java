package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class NativeDanmakuRequestTest {
    @Test public void stalledDocumentsLeaveRoomForAHealthyFallback() throws Exception {
        try (var server = new MockWebServer()) {
            var blocked = new CountDownLatch(2);
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().startsWith("/stalled")) {
                        blocked.countDown();
                        return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                    }
                    return new MockResponse().setBody("healthy");
                }
            });
            server.start();
            var done = new CountDownLatch(1);
            var value = new AtomicReference<String>();
            var callback = new IqiyiDanmaku.Callback<String>() {
                public void onSuccess(String result) { value.set(result); done.countDown(); }
                public void onFailure(IOException error) { done.countDown(); }
            };
            var jobs = new java.util.ArrayList<NativeDanmakuRequest>();
            try {
                for (int i = 0; i < 2; i++) {
                    var job = new NativeDanmakuRequest(15000, "https://example.invalid", NativeDanmakuRequest.CLIENT);
                    jobs.add(job);
                    job.start(NativeDanmakuRequest.DOCUMENTS, () -> job.text(server.url("/stalled").toString()), callback);
                }
                assertTrue(blocked.await(2, TimeUnit.SECONDS));
                var fallback = new NativeDanmakuRequest(15000, "https://example.invalid", NativeDanmakuRequest.CLIENT);
                jobs.add(fallback);
                fallback.start(NativeDanmakuRequest.DOCUMENTS, () -> fallback.text(server.url("/healthy").toString()), callback);
                assertTrue("A backup must not queue behind stalled documents", done.await(2, TimeUnit.SECONDS));
                assertEquals("healthy", value.get());
            } finally { for (var job : jobs) job.cancel(); }
        }
    }

    private OkHttpClient client(MockWebServer server) {
        return new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .url(server.url(chain.request().url().encodedPath() + "?" + chain.request().url().encodedQuery())).build())).build();
    }
    @Test public void longVarietyOnHighLatencyLinkFinishesBeforeOldDeadline() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicInteger segments = new AtomicInteger();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().startsWith("/video/info")) return new MockResponse().setBody("{\"code\":200,\"data\":{\"info\":{\"videoId\":\"24719651\",\"clipId\":\"896231\",\"time\":\"226:14\"}}}");
                    segments.incrementAndGet();
                    long millis = Long.parseLong(request.getRequestUrl().queryParameter("time")) + 1000;
                    return new MockResponse().setHeadersDelay(900, TimeUnit.MILLISECONDS)
                            .setBody("{\"status\":0,\"data\":{\"items\":[{\"time\":" + millis + ",\"content\":\"分段弹幕\"}]}}");
                }
            }); server.start();
            CountDownLatch done = new CountDownLatch(1); AtomicReference<byte[]> result = new AtomicReference<>(); AtomicReference<IOException> error = new AtomicReference<>();
            long start = System.nanoTime();
            var job = MangoDanmaku.document("mango-danmaku://video/896231/24719651", p -> {}, new IqiyiDanmaku.Callback<>() {
                public void onSuccess(byte[] bytes) { result.set(bytes); done.countDown(); }
                public void onFailure(IOException e) { error.set(e); done.countDown(); }
            }, client(server));
            try {
                assertTrue("227 minute requests must finish within 40 seconds", done.await(40, TimeUnit.SECONDS));
                assertNotNull(String.valueOf(error.get()), result.get()); assertEquals(227, segments.get());
                String xml = new String(result.get(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(227, xml.split("<d ").length - 1);
                System.out.println("227 segments at 900ms latency: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms, complete timeline");
            } finally { job.cancel(); }
        }
    }

    @Test public void tencentLongMovieDoesNotTimeoutOnTelevisionLatency() throws Exception {
        try (var server = new MockWebServer()) {
            var index = new com.google.gson.JsonObject();
            for (int i = 0; i < 200; i++) {
                var segment = new com.google.gson.JsonObject();
                segment.addProperty("segment_start", i * 30000);
                segment.addProperty("segment_name", "fixture/v1/" + i * 30000 + "/" + (i + 1) * 30000);
                index.add(String.valueOf(i), segment);
            }
            var root = new com.google.gson.JsonObject(); root.add("segment_index", index);
            var segments = new AtomicInteger();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) {
                    if (r.getPath().contains("/base/")) return new MockResponse().setBody(root.toString());
                    int n = segments.incrementAndGet();
                    return new MockResponse().setHeadersDelay(900, TimeUnit.MILLISECONDS)
                            .setBody("{\"barrage_list\":[{\"id\":\"" + n + "\",\"time_offset\":" + n * 30000
                                    + ",\"content\":\"完整分片\"}]}");
                }
            });
            server.start(); var done = new CountDownLatch(1); var result = new AtomicReference<byte[]>();
            var error = new AtomicReference<IOException>(); long start = System.nanoTime();
            var job = TencentDanmaku.document("tencent-danmaku://video/y4101qnn3jo", p -> {}, new IqiyiDanmaku.Callback<>() {
                public void onSuccess(byte[] value) { result.set(value); done.countDown(); }
                public void onFailure(IOException value) { error.set(value); done.countDown(); }
            }, client(server));
            try {
                assertTrue("200 half-minute segments must finish before old 40 s limit", done.await(35, TimeUnit.SECONDS));
                assertNotNull(String.valueOf(error.get()), result.get());
                assertEquals(200, segments.get());
                assertEquals(200, new String(result.get(), java.nio.charset.StandardCharsets.UTF_8).split("<d ").length - 1);
                System.out.println("Tencent 200 segments at 900ms latency: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
            } finally { job.cancel(); }
        }
    }
    @Test public void failedSegmentDoesNotWaitBehindSlowEarlierWorker() throws Exception {
        var job = new NativeDanmakuRequest(5000, "https://example.invalid", NativeDanmakuRequest.CLIENT);
        CountDownLatch done = new CountDownLatch(1); AtomicBoolean success = new AtomicBoolean();
        job.start(NativeDanmakuRequest.DOCUMENTS, () -> job.segments(12, p -> {}, index -> {
            if (index == 1) throw new IOException("segment rejected");
            try { Thread.sleep(3000); } catch (InterruptedException e) { throw new IOException(e); }
            return java.util.List.of(new IqiyiDanmakuCodec.Comment(index, 0xffffff, "comment"));
        }), new IqiyiDanmaku.Callback<>() {
            public void onSuccess(byte[] value) { success.set(true); done.countDown(); }
            public void onFailure(IOException error) { done.countDown(); }
        });
        try { assertTrue("terminal errors cancel pending segments promptly", done.await(1, TimeUnit.SECONDS)); assertFalse(success.get()); }
        finally { job.cancel(); }
    }
}
