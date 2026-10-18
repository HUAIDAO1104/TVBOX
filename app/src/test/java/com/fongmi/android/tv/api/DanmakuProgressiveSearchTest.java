package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import com.fongmi.android.tv.bean.Danmaku;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import org.junit.Test;

public class DanmakuProgressiveSearchTest {
    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/danmaku/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test public void mangoFirstPageIsUsableBeforeHistoryCompletesOrFails() throws Exception {
        exercise(true);
    }
    @Test public void tencentPreviewIsUsableBeforeSupplementaryEpisodesComplete() throws Exception {
        exercise(false);
    }
    private void exercise(boolean mango) throws Exception {
        String search = fixture(mango ? "mgtv-search-signed" : "tencent-variety");
        String page = fixture("mgtv-master");
        var partial = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var result = new AtomicReference<List<Danmaku>>();
        var requests = new AtomicInteger();
        var client = new OkHttpClient.Builder().addInterceptor(chain -> {
            int number = requests.incrementAndGet();
            if (number > (mango ? 2 : 1)) {
                blocked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { throw new IOException(error); }
                throw new IOException("Historical page unavailable");
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(number == 1 ? search : page, MediaType.get("application/json"))).build();
        }).build();
        var callback = new IqiyiDanmaku.Callback<List<Danmaku>>() {
            public void onPartial(List<Danmaku> rows) { result.set(rows); partial.countDown(); }
            public void onSuccess(List<Danmaku> rows) { done.countDown(); }
            public void onFailure(IOException error) { done.countDown(); }
        };
        var job = mango ? MangoDanmaku.search("披荆斩棘2026", "2026", "综艺", "", callback, client)
                : TencentDanmaku.search("现在就出发第四季", "2026", "综艺", callback, client);
        try {
            assertTrue("Results must arrive without waiting for history", partial.await(1, TimeUnit.SECONDS));
            assertFalse(result.get().isEmpty());
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            assertEquals("Catalogue is still loading while results are selectable", 1, done.getCount());
            release.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertFalse("A failed history page must not erase the earlier snapshot", result.get().isEmpty());
        } finally { release.countDown(); job.cancel(); }
    }
}
