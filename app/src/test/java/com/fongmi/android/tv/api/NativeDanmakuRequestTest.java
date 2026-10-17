package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class NativeDanmakuRequestTest {
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
