package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class BilibiliTransportTest {
    @Test public void segmentsArePacedAndServiceRefusalIsNotRetried() throws Exception {
        try (var server = new MockWebServer()) {
            var times = new CopyOnWriteArrayList<Long>();
            var refuse = new AtomicBoolean();
            var segmentOutage = new AtomicBoolean();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().startsWith("/123.xml")) {
                        if (!segmentOutage.get()) return new MockResponse().setResponseCode(404);
                        return new MockResponse().setBody("<i><chatid>123</chatid><d p='1,1,25,1,0,0,0,42'>snapshot</d></i>");
                    }
                    if (request.getPath().startsWith("/124.xml")) {
                        return new MockResponse().setBody("<i><chatid>124</chatid><d p='1,1,25,1,0,0,0,43'>next episode</d></i>");
                    }
                    if (segmentOutage.get()) return new MockResponse().setResponseCode(503);
                    times.add(System.nanoTime());
                    return refuse.get() ? new MockResponse().setBody("{\"code\":-352}")
                            : new MockResponse().setBody(new okio.Buffer().write(new byte[]{10,9,16,(byte)210,9,24,1,58,2,111,107}));
                }
            }); server.start();
            var client = new OkHttpClient.Builder().addInterceptor(c -> c.proceed(c.request().newBuilder()
                    .url(server.url(c.request().url().encodedPath() + "?" + c.request().url().encodedQuery())).build())).build();
            run(client, false);
            assertEquals(3, times.size());
            for (int i = 1; i < times.size(); i++) assertTrue("Segment starts must be spaced", times.get(i) - times.get(i - 1) > TimeUnit.MILLISECONDS.toNanos(350));
            segmentOutage.set(true);
            var job = new NativeDanmakuRequest(5000, "https://www.bilibili.com/", client);
            try {
                byte[] fallback = BilibiliDanmaku.comments(job, "123", 360, value -> {});
                assertTrue(new String(fallback, java.nio.charset.StandardCharsets.UTF_8).contains(">snapshot</d>"));
            } finally { job.cancel(); }
            segmentOutage.set(false);
            refuse.set(true);
            run(client, true);
            assertEquals("A provider refusal must stop pending segments and retries", 4, times.size());
            run(client, true);
            assertEquals("Immediate retry must respect the provider cooldown", 4, times.size());
            var next = new NativeDanmakuRequest(5000, "https://www.bilibili.com/", client);
            try {
                byte[] bytes = BilibiliDanmaku.comments(next, "124", 1080, value -> {});
                assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains(">next episode</d>"));
                assertEquals(4, times.size());
            } finally { next.cancel(); }
        }
    }

    @Test public void officialSnapshotIsDecodedBeforeXmlParsingWithinTheExpandedLimit() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var request = new NativeDanmakuRequest(5000, "", NativeDanmakuRequest.CLIENT);
            StringBuilder xml = new StringBuilder("<i><chatid>124</chatid>");
            for (int id = 1; id <= 3600; id++) {
                xml.append("<d p='1,1,25,1,0,0,0,").append(id).append("'>同一时间</d>");
            }
            byte[] plain = xml.append("</i>").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                for (boolean raw : new boolean[]{true, false}) {
                    var bytes = new java.io.ByteArrayOutputStream();
                    var deflater = new java.util.zip.Deflater(6, raw);
                    try (var compressed = new java.util.zip.DeflaterOutputStream(bytes, deflater)) {
                        compressed.write(plain);
                    } finally { deflater.end(); }
                    for (int limit : new int[]{plain.length, plain.length - 1}) {
                        server.enqueue(new MockResponse().setHeader("Content-Encoding", "deflate")
                                .setBody(new okio.Buffer().write(bytes.toByteArray())));
                        String url = server.url("/124.xml").toString();
                        if (limit == plain.length) {
                            assertEquals(3600, BilibiliCommentPool.parse(request.bytes(url, limit), "124").size());
                        } else {
                            assertThrows(IOException.class, () -> request.bytes(url, limit));
                        }
                    }
                }
            } finally { request.cancel(); }
        }
    }
    private static void run(OkHttpClient client, boolean rejected) throws Exception {
        var done = new CountDownLatch(1); var failure = new AtomicReference<IOException>();
        var fallback = new AtomicBoolean();
        var job = new NativeDanmakuRequest(5000, "https://www.bilibili.com/", client);
        job.start(NativeDanmakuRequest.DOCUMENTS, () -> BilibiliDanmaku.comments(job, "123", 1080, value -> {}), new IqiyiDanmaku.Callback<byte[]>() {
            public void onSuccess(byte[] value) { done.countDown(); }
            public void onFallbackSuccess(byte[] value) { fallback.set(true); onSuccess(value); }
            public void onFailure(IOException error) { failure.set(error); done.countDown(); }
        });
        try {
            assertTrue(done.await(6, TimeUnit.SECONDS));
            if (rejected) assertTrue(String.valueOf(failure.get()), failure.get() instanceof PlatformDanmaku.RejectedComments);
            else {
                assertNull(failure.get());
                assertTrue("A segment-only result needs short cache lifetime", fallback.get());
            }
        } finally { job.cancel(); }
    }
}
