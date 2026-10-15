package com.fongmi.android.tv.api;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class IqiyiDanmakuInstrumentedTest {
    private static final String SOURCE = "iqiyi-danmaku://video/2586684050154100";
    private static final byte[] SEGMENT = android.util.Base64.decode("CyCACghtZXRhZGF0YTI1CgIxMhIvEhXmtYvor5UgJiA8aGVsbG8+IPCfmIAyBDEyLjVCBjEyYWIzNJoGB3Vua25vd24D", 0);

    @Test public void successfulCatalogueIsReusedWithoutAnotherNetworkRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"templates\":[{\"albumInfo\":{\"title\":\"缓存测试片\",\"siteId\":\"iqiyi\",\"channel\":\"电影,1\",\"playUrl\":\"qips://tvid=123456789;\"}}]}}"));
            server.start();
            String query = "缓存测试片" + System.nanoTime();
            var http = client(server);
            for (int i = 0; i < 2; i++) {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<java.util.List<com.fongmi.android.tv.bean.Danmaku>> rows = new AtomicReference<>();
                IqiyiDanmaku.search(query, new IqiyiDanmaku.Callback<>() {
                    @Override public void onSuccess(java.util.List<com.fongmi.android.tv.bean.Danmaku> value) { rows.set(value); done.countDown(); }
                    @Override public void onFailure(IOException error) { done.countDown(); }
                }, http);
                assertTrue(done.await(3, TimeUnit.SECONDS));
                assertNotNull(rows.get());
                assertEquals("iqiyi-danmaku://video/123456789", rows.get().get(0).getUrl());
                rows.get().get(0).setUrl("mutated"); // consumers cannot corrupt the cached catalogue
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void completeDocumentCombinesAllSegmentsAndMissingMinutesAreAllowed() throws Exception {
        verifyDocument(200, 2);
        verifyDocument(404, 1);
    }

    @Test public void legacyXmlIsDecodedOnAndroid() throws Exception {
        byte[] xml = android.util.Base64.decode("G3sAACwO7JhkBLGkcNX0mocGy2y/MDWRLR+3CjNrGJ+2OvB/tpCzp1H5ndku9NMbPl4llmCH1+c2kPhmBiVNzrl1jgQcKrDQE3EaZ3xMoeJDGjSb0Nnd3QA=", 0);
        assertEquals(1, IqiyiDanmakuCodec.decode(xml).size());
    }

    @Test public void failedSegmentNeverReturnsAPartialDocument() throws Exception {
        verifyDocument(503, 0);
    }

    private void verifyDocument(int secondStatus, int expected) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("baseinfo")) return new MockResponse().setBody("{\"code\":\"A00000\",\"data\":{\"durationSec\":120}}");
                    if (request.getPath().contains("_60_2_")) return new MockResponse().setResponseCode(secondStatus)
                            .setBody(secondStatus == 200 ? new Buffer().write(SEGMENT) : new Buffer());
                    return new MockResponse().setBody(new Buffer().write(SEGMENT));
                }
            });
            server.start();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<byte[]> bytes = new AtomicReference<>();
            AtomicReference<IOException> failure = new AtomicReference<>();
            IqiyiDanmaku.document(SOURCE, p -> { }, new IqiyiDanmaku.Callback<byte[]>() {
                @Override public void onSuccess(byte[] value) { bytes.set(value); done.countDown(); }
                @Override public void onFailure(IOException error) { failure.set(error); done.countDown(); }
            }, client(server));
            assertTrue(done.await(10, TimeUnit.SECONDS));
            if (expected == 0) { assertNull(bytes.get()); assertNotNull(failure.get()); }
            else {
                assertNull(failure.get());
                assertEquals(expected, com.fongmi.android.tv.player.danmaku.FilteringBiliParser.INSTANCE
                        .parse(new java.io.ByteArrayInputStream(bytes.get())).size());
            }
        }
    }

    @Test public void cancellationStopsNativeRequestsWithoutDeliveringStaleResults() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBodyDelay(4, TimeUnit.SECONDS)
                    .setBody("{\"code\":\"A00000\",\"data\":{\"durationSec\":120}}"));
            server.start();
            AtomicInteger callbacks = new AtomicInteger();
            var job = IqiyiDanmaku.document(SOURCE, p -> { }, new IqiyiDanmaku.Callback<byte[]>() {
                @Override public void onSuccess(byte[] value) { callbacks.incrementAndGet(); }
                @Override public void onFailure(IOException error) { callbacks.incrementAndGet(); }
            }, client(server));
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            job.cancel();
            android.os.SystemClock.sleep(4_300);
            assertEquals(0, callbacks.get());
            assertEquals(1, server.getRequestCount());
        }
    }

    private OkHttpClient client(MockWebServer server) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            var request = chain.request();
            return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
        }).build();
    }
}
