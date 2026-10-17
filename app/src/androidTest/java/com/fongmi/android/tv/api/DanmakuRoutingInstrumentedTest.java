package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public class DanmakuRoutingInstrumentedTest {
    private String endpoint(MockWebServer server) { return server.url("/search?name={name}&episode={episode}").toString().replace("%7B", "{").replace("%7D", "}"); }
    private MockResponse rows(MockWebServer server, String label, String path) {
        Danmaku item = Danmaku.from(server.url("/api/v2/comment/" + path + "?format=xml").toString());
        item.setName("自动线路测试(2026)【综艺】from fixture - " + label);
        return new MockResponse().setBody(App.gson().toJson(List.of(item)));
    }
    private MockResponse document() { return new MockResponse().setBody("<?xml version=\"1.0\"?><i><d p=\"1,1,25,16777215,0,0,0,1\">真实测试评论</d></i>"); }
    private Danmaku run(MockWebServer direct, MockWebServer backup) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Danmaku> found = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> DanmakuApi.searchStaged("自动线路测试", "2026", "综艺", "第1期下", "",
                List.of(endpoint(direct)), List.of(endpoint(backup)), new DanmakuApi.SearchCallback() {
                    public void onFound(Danmaku item, List<Danmaku> catalogue) { found.set(item); done.countDown(); }
                    public void onFailure(DanmakuApi.SearchFailure failure) { done.countDown(); }
                }));
        try { assertTrue(done.await(15, TimeUnit.SECONDS)); assertNotNull(found.get()); return found.get(); }
        finally { DanmakuApi.cancel(); }
    }
    @Test public void directMatchCompletesBeforeAnyBackupRequest() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start(); direct.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(direct,"第1期下","direct").setBodyDelay(250,TimeUnit.MILLISECONDS) : document(); }
            });
            assertTrue(run(direct, backup).getUrl().contains("/direct?")); assertEquals(0, backup.getRequestCount());
        }
    }
    @Test public void wrongHalfIsRejectedBeforeAutomaticBackupMatches() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start(); direct.enqueue(rows(direct,"第1期上","wrong"));
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(backup,"第1期下","backup") : document(); } });
            assertTrue(run(direct, backup).getUrl().contains("/backup?")); assertEquals(1,direct.getRequestCount());
        }
    }
    @Test public void failedDirectDocumentFallsBackWithoutManualLink() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start(); direct.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(direct,"第1期下","missing") : new MockResponse().setResponseCode(403); } });
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return r.getPath().startsWith("/search") ? rows(backup,"第1期下","recovered") : document(); } });
            assertTrue(run(direct,backup).getUrl().contains("/recovered?"));
        }
    }
}
