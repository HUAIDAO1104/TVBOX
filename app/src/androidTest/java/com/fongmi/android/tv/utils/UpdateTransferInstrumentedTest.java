package com.fongmi.android.tv.utils;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.fongmi.android.tv.App;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public class UpdateTransferInstrumentedTest {
    private final byte[] data = new byte[4 * 1024 * 1024 + 133];
    public UpdateTransferInstrumentedTest() { new Random(576).nextBytes(data); }
    private MockResponse range(RecordedRequest request) {
        String value = request.getHeader("Range");
        if (value == null) return new MockResponse().setBody(new Buffer().write(data));
        String[] parts = value.substring(6).split("-");
        int start = Integer.parseInt(parts[0]), end = parts.length == 1 ? data.length - 1 : Integer.parseInt(parts[1]);
        return new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes " + start + "-" + end + "/" + data.length)
                .setBody(new Buffer().write(data, start, end - start + 1));
    }
    private File file() throws IOException { return File.createTempFile("update576-", ".payload", App.get().getCacheDir()); }
    private RangedDownload transfer() { return new RangedDownload(new OkHttpClient()); }

    @Test public void winningBytesAreReusedWithoutWaitingForSlowMirrors() throws Exception {
        try (var slow = new MockWebServer(); var fast = new MockWebServer()) {
            slow.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) { return range(r).setHeadersDelay(3, TimeUnit.SECONDS); }
            });
            fast.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return range(r); } });
            slow.start(); fast.start(); File file = file(); long start = android.os.SystemClock.elapsedRealtime(); AtomicLong first = new AtomicLong();
            try {
                assertTrue(transfer().transfer(List.of(slow.url("/apk").toString(), fast.url("/apk").toString()), file, data.length,
                        (url, speed, done, total) -> first.compareAndSet(0, android.os.SystemClock.elapsedRealtime() - start)));
                assertArrayEquals(data, Files.readAllBytes(file.toPath()));
                assertTrue("First useful bytes must not wait for 3 second mirror: " + first.get(), first.get() < 2000);
                List<String> ranges = new ArrayList<>(); RecordedRequest request;
                while ((request = fast.takeRequest(100, TimeUnit.MILLISECONDS)) != null) ranges.add(request.getHeader("Range"));
                assertEquals(1, ranges.stream().filter(v -> v.startsWith("bytes=0-")).count());
                assertTrue(ranges.toString(), ranges.stream().anyMatch(v -> v.startsWith("bytes=32768-")));
                System.out.println("UPDATE_FAST_RACE firstBytesMs=" + first.get() + " totalMs=" + (android.os.SystemClock.elapsedRealtime() - start));
            } finally { file.delete(); }
        }
    }
    @Test public void earlyBurstThenThirtyKilobytesSwitchesMirrorAndPreservesBytes() throws Exception {
        try (var burst = new MockWebServer(); var reliable = new MockWebServer()) {
            burst.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) {
                    MockResponse response = range(r);
                    return r.getHeader("Range").startsWith("bytes=0-") ? response : response.throttleBody(8192, 250, TimeUnit.MILLISECONDS);
                }
            });
            reliable.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) { return range(r).setHeadersDelay(200, TimeUnit.MILLISECONDS); }
            });
            burst.start(); reliable.start(); File file = file(); long start = android.os.SystemClock.elapsedRealtime(); Set<String> used = ConcurrentHashMap.newKeySet();
            try {
                assertTrue(transfer().transfer(List.of(burst.url("/apk").toString(), reliable.url("/apk").toString()), file, data.length,
                        (url, speed, done, total) -> used.add(url)));
                assertArrayEquals(data, Files.readAllBytes(file.toPath()));
                assertTrue(used.contains(reliable.url("/apk").toString()));
                long elapsed = android.os.SystemClock.elapsedRealtime() - start;
                assertTrue("Must abandon 32 KiB/s rather than finish there: " + elapsed, elapsed < 12000);
                System.out.println("UPDATE_BURST_RECOVERY totalMs=" + elapsed + " bytes=" + data.length);
            } finally { file.delete(); }
        }
    }
    @Test public void resumedBytesAndWrongRangesNeverCreateAHole() throws Exception {
        try (var wrong = new MockWebServer(); var valid = new MockWebServer()) {
            wrong.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) { return range(r).setHeader("Content-Range", "bytes 0-1/2"); }
            });
            valid.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return range(r); } });
            wrong.start(); valid.start(); File file = file(); Files.write(file.toPath(), Arrays.copyOf(data, 12345));
            try {
                assertTrue(transfer().transfer(List.of(wrong.url("/apk").toString(), valid.url("/apk").toString()), file, data.length, (u,s,d,t) -> {}));
                assertArrayEquals(data, Files.readAllBytes(file.toPath()));
                assertTrue(valid.takeRequest().getHeader("Range").startsWith("bytes=12345-"));
            } finally { file.delete(); }
        }
    }
    @Test public void cancellationKeepsOnlyAValidPrefixAndHasNoCompletion() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return range(r).setBodyDelay(50, TimeUnit.MILLISECONDS); } });
            server.start(); File file = file(); RangedDownload job = transfer(); AtomicInteger updates = new AtomicInteger();
            try {
                try {
                    job.transfer(List.of(server.url("/apk").toString()), file, data.length, (u,s,d,t) -> { updates.incrementAndGet(); job.cancel(); });
                    fail("Cancelled transfer must not succeed");
                } catch (InterruptedIOException expected) { }
                assertEquals(1, updates.get()); assertTrue(file.length() > 0 && file.length() < data.length);
                assertArrayEquals(Arrays.copyOf(data, (int)file.length()), Files.readAllBytes(file.toPath()));
            } finally { file.delete(); }
        }
    }
    @Test public void noRangeServerFallsBackToVerifiedSequentialDownload() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return new MockResponse().setBody(new Buffer().write(data)); } });
            server.start(); File file = file();
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data); StringBuilder sha = new StringBuilder();
                for (byte b : digest) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                Download.create(List.of(server.url("/apk").toString()), file).size(data.length).sha256(sha.toString()).resume().fastestFirst().get();
                assertArrayEquals(data, Files.readAllBytes(file.toPath()));
            } finally { file.delete(); }
        }
    }
    @Test public void corruptedMirrorCannotCompleteEvenWithCorrectLength() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return range(r); } });
            server.start(); File file = file();
            try {
                try {
                    Download.create(List.of(server.url("/apk").toString()), file).size(data.length)
                            .sha256("0".repeat(64)).resume().fastestFirst().get();
                    fail("A length-correct but corrupt APK must never complete");
                } catch (RuntimeException expected) { assertTrue(expected.getMessage().contains("checksum")); }
                assertFalse(file.exists());
            } finally { file.delete(); new File(file + ".sha256").delete(); }
        }
    }
    @Test public void livePublishedApkDownloadsWithItsExactDigest() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("updateLive")));
        String github = "https://github.com/HUAIDAO1104/TVBOX/releases/download/v5.5.76/pianduoduo-leanback-arm64_v8a.apk";
        var urls = List.of("https://v4.gh-proxy.org/" + github, "https://axisnow.gh-proxy.org/" + github, "https://ghfast.top/" + github, "https://gh-proxy.com/" + github, "https://ghproxy.net/" + github, github);
        File file = file(); CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        AtomicLong first = new AtomicLong(); AtomicInteger updates = new AtomicInteger(); Set<String> sources = ConcurrentHashMap.newKeySet();
        long started = android.os.SystemClock.elapsedRealtime();
        Download job = Download.create(urls, file).size(50621784L)
                .sha256("eb8e1d8d00e317d94a3d00799643870c27b3b8cf2770a0729900d1491a644d8e").resume().fastestFirst().readTimeout(12000);
        job.start(new Download.Callback() {
            public void progress(int value) { updates.incrementAndGet(); }
            public void status(String source, long speed, long downloaded, long total) {
                if (downloaded > 0) first.compareAndSet(0, android.os.SystemClock.elapsedRealtime() - started);
                sources.add(android.net.Uri.parse(source).getHost());
            }
            public void error(String message) { error.set(message); done.countDown(); }
            public void success(File downloaded) { done.countDown(); }
        });
        try {
            assertTrue("Published APK timed out", done.await(120, TimeUnit.SECONDS));
            assertNull(error.get()); assertEquals(50621784L, file.length()); assertTrue(updates.get() <= 101);
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            android.util.Log.i("Performance577QA", "UPDATE_FULL_APK firstBytesMs=" + first.get() + " elapsedMs=" + elapsed
                    + " KiBps=" + file.length() * 1000L / elapsed / 1024 + " sources=" + sources + " progressEvents=" + updates.get());
        } finally { job.cancel(); file.delete(); new File(file + ".sha256").delete(); }
    }
    @Test public void updateStartsWhileAllMaintenanceWorkersAreBlocked() throws Exception {
        CountDownLatch release = new CountDownLatch(1), done = new CountDownLatch(1);
        List<Future<?>> blockers = new ArrayList<>(); AtomicReference<String> error = new AtomicReference<>();
        for (int i = 0; i < 5; i++) blockers.add(Task.submit(() -> {
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        File file = file(); Download job = null;
        try (var server = new MockWebServer()) {
            // Existing cold-start work may already occupy workers; queued blockers saturate the rest.
            Future<?> probe = Task.submit(() -> {});
            try { probe.get(500, TimeUnit.MILLISECONDS); fail("Maintenance queue must be blocked"); }
            catch (java.util.concurrent.TimeoutException expected) { }
            finally { probe.cancel(true); }
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return range(r); } });
            server.start();
            StringBuilder sha = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data)) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            job = Download.create(List.of(server.url("/apk").toString()),file).size(data.length).sha256(sha.toString()).fastestFirst().resume();
            job.start(new Download.Callback() {
                public void progress(int p) { }
                public void error(String message) { error.set(message); done.countDown(); }
                public void success(File f) { done.countDown(); }
            });
            assertTrue("An update must not wait behind cold-start work", done.await(5, TimeUnit.SECONDS));
            assertNull(error.get()); assertArrayEquals(data, Files.readAllBytes(file.toPath()));
        } finally {
            release.countDown(); blockers.forEach(f -> f.cancel(true)); if (job != null) job.cancel();
            file.delete(); new File(file + ".sha256").delete();
        }
    }

    @Test public void fullLengthCorruptDownloadIsRestartedInsteadOfResumedPastEnd() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return range(r); } });
            server.start(); File file = file();
            StringBuilder sha = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data)) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            Files.write(file.toPath(), new byte[data.length]);
            Files.writeString(new File(file + ".sha256").toPath(), sha.toString());
            try {
                Download.create(List.of(server.url("/apk").toString()),file).size(data.length).sha256(sha.toString()).fastestFirst().resume().get();
                assertArrayEquals(data, Files.readAllBytes(file.toPath()));
                assertEquals("bytes=0-32767", server.takeRequest().getHeader("Range"));
            } finally { file.delete(); new File(file + ".sha256").delete(); }
        }
    }

}
