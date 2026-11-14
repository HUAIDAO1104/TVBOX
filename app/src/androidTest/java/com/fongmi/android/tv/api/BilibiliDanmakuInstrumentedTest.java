package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import android.net.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.utils.InputStreamCompat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BilibiliDanmakuInstrumentedTest {
    @Before public void directNetwork() {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var manager = (ConnectivityManager) context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        var capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        assertNotNull(capabilities); assertFalse("Test must not run over a VPN", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        var proxies = NativeDanmakuRequest.CLIENT.proxySelector().select(java.net.URI.create("https://api.bilibili.com/"));
        assertTrue("Test must not use a proxy: " + proxies, proxies.stream().allMatch(p -> p.type() == java.net.Proxy.Type.DIRECT));
    }
    @Test public void automaticAnimeAcrossEpisodeTenSurvivesUnavailableAggregate() throws Exception {
        try (var backup = new MockWebServer()) {
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(503); } });
            backup.start();
            for (int episode : new int[]{8, 9, 10, 11, 12, 22, 77}) {
                long start = android.os.SystemClock.elapsedRealtime();
                var done = new CountDownLatch(1); var selected = new AtomicReference<Danmaku>(); var error = new AtomicReference<DanmakuApi.SearchFailure>();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> DanmakuApi.searchStaged(
                        "凡人修仙传", "2020", "国产动漫", "第" + episode + "集", "", NativeDanmaku.sources(),
                        List.of(backup.url("/search?name={name}").toString().replace("%7B", "{").replace("%7D", "}")), new DanmakuApi.SearchCallback() {
                            public void onFound(Danmaku item, List<Danmaku> catalogue) { selected.set(item); done.countDown(); }
                            public void onFailure(DanmakuApi.SearchFailure failure) { error.set(failure); done.countDown(); }
                        }));
                assertTrue(done.await(95, TimeUnit.SECONDS)); assertNotNull("Episode " + episode + ": " + error.get(), selected.get());
                assertTrue(selected.get().getUrl(), BilibiliDanmaku.supports(selected.get().getUrl()));
                var loaded = new CountDownLatch(1); var local = new AtomicReference<Uri>();
                DanmakuDocumentCache.load(selected.get().getUri(), new DanmakuDocumentCache.Listener() {
                    public void onReady(Uri source, Uri file) { local.set(file); loaded.countDown(); }
                    public void onFailure(Uri source, IOException failure) { loaded.countDown(); }
                });
                assertTrue(loaded.await(5, TimeUnit.SECONDS)); assertNotNull(local.get());
                String xml;
                try (var in = new FileInputStream(local.get().getPath())) { xml = new String(InputStreamCompat.readUpTo(in, 20_000_000), StandardCharsets.UTF_8); }
                int count = xml.split("<d ").length - 1; assertTrue(count > 100);
                android.util.Log.i("Danmaku595QA", "DIRECT automatic episode=" + episode + " source=" + selected.get().getUrl()
                        + " comments=" + count + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start));
                DanmakuApi.cancel();
            }
        } finally { DanmakuApi.cancel(); }
    }
    @Test public void legacyProviderListCannotFindBilibiliExclusiveAnimation() throws Exception {
        try (var backup = new MockWebServer()) {
            backup.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest r) { return new MockResponse().setResponseCode(503); } }); backup.start();
            var done = new CountDownLatch(1); var found = new AtomicReference<Danmaku>(); var error = new AtomicReference<DanmakuApi.SearchFailure>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> DanmakuApi.searchStaged("凡人修仙传", "2020", "国产动漫", "第10集", "",
                    List.of(IqiyiDanmakuCodec.SOURCE, TencentDanmakuCodec.SOURCE, MangoDanmaku.SOURCE),
                    List.of(backup.url("/search?name={name}").toString().replace("%7B", "{").replace("%7D", "}")), new DanmakuApi.SearchCallback() {
                        public void onFound(Danmaku row, List<Danmaku> all) { found.set(row); done.countDown(); }
                        public void onFailure(DanmakuApi.SearchFailure failure) { error.set(failure); done.countDown(); }
                    }));
            assertTrue(done.await(95, TimeUnit.SECONDS)); assertNull("Old provider list unexpectedly found a usable document", found.get()); assertNotNull(error.get());
            android.util.Log.i("Danmaku595QA", "legacy provider list reproduces failure=" + error.get());
        } finally { DanmakuApi.cancel(); }
    }
}
