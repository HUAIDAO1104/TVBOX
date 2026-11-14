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
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
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
            refuse.set(true);
            run(client, true);
            assertEquals("A provider refusal must stop pending segments and retries", 4, times.size());
            run(client, true);
            assertEquals("Immediate retry must respect the provider cooldown", 4, times.size());
        }
    }
    private static void run(OkHttpClient client, boolean rejected) throws Exception {
        var done = new CountDownLatch(1); var failure = new AtomicReference<IOException>();
        var job = new NativeDanmakuRequest(5000, "https://www.bilibili.com/", client);
        job.start(NativeDanmakuRequest.DOCUMENTS, () -> BilibiliDanmaku.comments(job, "123", 1080, value -> {}), new IqiyiDanmaku.Callback<byte[]>() {
            public void onSuccess(byte[] value) { done.countDown(); }
            public void onFailure(IOException error) { failure.set(error); done.countDown(); }
        });
        try {
            assertTrue(done.await(6, TimeUnit.SECONDS));
            if (rejected) assertTrue(String.valueOf(failure.get()), failure.get() instanceof PlatformDanmaku.RejectedComments);
            else assertNull(failure.get());
        } finally { job.cancel(); }
    }
}
