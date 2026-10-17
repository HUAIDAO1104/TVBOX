package com.fongmi.android.tv.utils;

import static org.junit.Assert.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class RangedDownloadTest {
    private final byte[] data = new byte[512 * 1024 + 133];
    public RangedDownloadTest() { new Random(577).nextBytes(data); }
    private MockResponse range(RecordedRequest r) {
        String[] parts = r.getHeader("Range").substring(6).split("-");
        int start = Integer.parseInt(parts[0]), end = Integer.parseInt(parts[1]);
        return new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes " + start + "-" + end + "/" + data.length)
                .setBody(new Buffer().write(data, start, end - start + 1));
    }
    @Test public void allMirrorsAt32KiBStillCommitBytesInsteadOfFallingBack() throws Exception {
        try (var one = new MockWebServer(); var two = new MockWebServer()) {
            var slow = new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) { return range(r).throttleBody(8192, 250, TimeUnit.MILLISECONDS); }
            };
            one.setDispatcher(slow); two.setDispatcher(slow); one.start(); two.start();
            File file = File.createTempFile("slow-mirrors-", ".apk"); AtomicLong first = new AtomicLong(); long start = System.nanoTime();
            try {
                assertTrue("Slow valid ranges must not downgrade to an unmonitored single stream",
                        new RangedDownload(new OkHttpClient()).transfer(List.of(one.url("/apk").toString(),two.url("/apk").toString()), file, data.length,
                                (u,s,d,t) -> first.compareAndSet(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start))));
                assertArrayEquals(data, Files.readAllBytes(file.toPath()));
                assertTrue("First persisted data: " + first.get(), first.get() < 3500);
                System.out.println("ALL_SLOW firstBytesMs=" + first.get() + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            } finally { file.delete(); }
        }
    }
    @Test public void fiveSecondColdHeadersDoNotDisableRangeDownloads() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) {
                    return r.getHeader("Range").startsWith("bytes=0-") ? range(r).setHeadersDelay(5, TimeUnit.SECONDS) : range(r);
                }
            }); server.start(); File file = File.createTempFile("cold-mirror-", ".apk");
            try {
                assertTrue(new RangedDownload(new OkHttpClient()).transfer(List.of(server.url("/apk").toString()),file,data.length,(u,s,d,t)->{}));
                assertArrayEquals(data, Files.readAllBytes(file.toPath()));
            } finally { file.delete(); }
        }
    }
}
