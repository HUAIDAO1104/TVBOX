package com.fongmi.android.tv.api;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.Assert.*;

public class BilibiliCommentPoolTest {
    private static byte[] xml(String body) {
        return ("<i><chatid>123</chatid>" + body + "</i>").getBytes(StandardCharsets.UTF_8);
    }
    @Test public void snapshotAndSegmentsShareIdsButIdenticalMessagesCanHaveDistinctAuthors() throws Exception {
        var baseline = BilibiliCommentPool.parse(xml("<d p=\"1,5,25,16777215,0,0,0,41\">你好 &amp; 再见</d>" +
                "<d p=\"1,1,25,16777215,0,0,0,42\">同一时刻</d>"), "123");
        var request = new NativeDanmakuRequest(5000, "", NativeDanmakuRequest.CLIENT);
        var pool = Executors.newSingleThreadExecutor();
        try {
            byte[] bytes = request.segments(1, p -> {}, i -> List.of(baseline.get(1),
                    new IqiyiDanmakuCodec.Comment(1, 16777215, "同一时刻", 43, 1)), pool, 1, baseline, 150_000);
            var merged = BilibiliCommentPool.parse(new String(bytes, StandardCharsets.UTF_8)
                    .replace("<i>", "<i><chatid>123</chatid>").getBytes(StandardCharsets.UTF_8), "123");
            assertEquals(List.of(41L,42L,43L), merged.stream().map(IqiyiDanmakuCodec.Comment::id).toList());
            assertEquals("你好 & 再见", merged.get(0).text());
            assertEquals(5, merged.get(0).mode());
        } finally { request.cancel(); pool.shutdownNow(); }
    }
    @Test public void refusesForeignEpisodesAndExternalEntities() {
        assertThrows(IOException.class, () -> BilibiliCommentPool.parse(xml(""), "124"));
        assertThrows(IOException.class, () -> BilibiliCommentPool.parse(
                "<!DOCTYPE i><i><chatid>123</chatid></i>".getBytes(StandardCharsets.UTF_8), "123"));
    }
    @Test public void missingMiddleSegmentKeepsOtherSegmentsAndTheEpisodeSnapshot() throws Exception {
        var baseline = BilibiliCommentPool.parse(xml("<d p='1,1,25,1,0,0,0,7'>snapshot</d>"), "123");
        var request = new NativeDanmakuRequest(5000, "", NativeDanmakuRequest.CLIENT);
        var pool = Executors.newSingleThreadExecutor();
        try {
            byte[] bytes = request.segments(3, p -> {}, index -> {
                if (index == 1) throw new IOException("unavailable");
                return List.of(baseline.get(0), new IqiyiDanmakuCodec.Comment(index + 2, 1, "segment", 20 + index, 1));
            }, pool, 1, baseline, 150_000);
            String result = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(result.contains(",7\">snapshot"));
            assertTrue(result.contains(",20\">segment"));
            assertTrue(result.contains(",22\">segment"));
            assertEquals(3, result.split("<d ").length - 1);
        } finally { request.cancel(); pool.shutdownNow(); }
    }
    @Test(timeout = 4500) public void stalledSupplementDoesNotHoldTheSnapshotUntilTheBackupSourceWins() throws Exception {
        var baseline = BilibiliCommentPool.parse(xml("<d p='1,1,25,1,0,0,0,7'>snapshot</d>"), "123");
        var request = new NativeDanmakuRequest(75000, "", NativeDanmakuRequest.CLIENT);
        var pool = Executors.newSingleThreadExecutor();
        var stopped = new java.util.concurrent.CountDownLatch(1);
        try {
            byte[] bytes = request.segments(1, p -> {}, index -> {
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                    throw new AssertionError();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopped.countDown();
                    throw new java.io.InterruptedIOException();
                }
            }, pool, 1, baseline, 150_000);
            assertTrue(new String(bytes, StandardCharsets.UTF_8).contains(">snapshot</d>"));
            assertTrue(stopped.await(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally { request.cancel(); pool.shutdownNow(); }
    }
}
