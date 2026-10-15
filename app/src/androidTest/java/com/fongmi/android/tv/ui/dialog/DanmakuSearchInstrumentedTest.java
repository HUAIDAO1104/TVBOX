package com.fongmi.android.tv.ui.dialog;

import static org.junit.Assert.*;

import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.common.MediaMetadata;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.databinding.ViewDanmakuSearchEmbeddedBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.player.danmaku.FilteringBiliParser;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public class DanmakuSearchInstrumentedTest {
    private MockWebServer server;
    private String previousApi;
    private volatile int status;
    private volatile boolean slowDocument;
    private final CountDownLatch documentStarted = new CountDownLatch(1);
    private ActivityScenario<HomeActivity> scenario;
    private DanmakuSearchPanel panel;
    private ViewDanmakuSearchEmbeddedBinding binding;
    private PlayerManager player;

    @Before public void setup() throws Exception {
        previousApi = DanmakuSetting.getApiUrl();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().startsWith("/api/v2/comment/")) {
                    if (request.getPath().contains("/bad")) return new MockResponse().setResponseCode(404);
                    documentStarted.countDown();
                    return new MockResponse().setBodyDelay(slowDocument ? 5 : 0, TimeUnit.SECONDS).setBody(
                        "<?xml version=\"1.0\"?><i><d p=\"0,1,25,16777215,0,0,0,1\">弹幕测试</d><d p=\"1,1,25,16777215,0,0,0,2\">下一条</d></i>");
                }
                if (status != 0) return new MockResponse().setResponseCode(status).setBody("denied");
                String name = request.getRequestUrl().queryParameter("name");
                if ("弹幕备选回归片".equals(name)) return new MockResponse().setBody(App.gson().toJson(
                        java.util.stream.IntStream.range(0, 5).mapToObj(i -> item(name + "(2026)【电影】from fixture - 正片",
                                i < 4 ? "bad" + i : "fifth")).toList()));
                return new MockResponse().setBody(App.gson().toJson(List.of(
                        item(name + "(2026)【电影】from fixture - 正片", "movie"),
                        item(name + "(2026)【综艺】from fixture - 第1期上 2026-08-16期", "upper"),
                        item(name + "(2026)【综艺】from fixture - 第1期下 2026-08-16期", "lower"),
                        item(name + "(2026)【综艺】from fixture - 第1期加更 2026-08-16期", "extra"))));
            }
        });
        server.start();
        DanmakuSetting.putApiUrl(server.url("/search?name={name}&episode={episode}").toString()
                .replace("%7B", "{").replace("%7D", "}"));
    }

    private Danmaku item(String name, String id) {
        Danmaku item = Danmaku.from(server.url("/api/v2/comment/" + id + "?format=xml").toString());
        item.setName(name); return item;
    }

    @After public void cleanup() throws Exception {
        DanmakuApi.cancel();
        main(() -> { if (panel != null) panel.destroy(); if (player != null) player.release(); });
        if (scenario != null) scenario.close();
        DanmakuSetting.putApiUrl(previousApi);
        server.shutdown();
    }

    @Test public void automaticMovieAndDatedVarietyDownloadParseAndCache() throws Exception {
        for (String[] spec : List.of(new String[]{"电影", "正片", "/movie?"},
                new String[]{"综艺", "20260816上", "/upper?"}, new String[]{"综艺", "20260816下", "/lower?"})) {
            Danmaku found = search("弹幕验收虚构片", "2026", spec[0], spec[1]);
            assertTrue(found.getUrl(), found.getUrl().contains(spec[2]));
            assertEquals(2, parse(found));
        }
    }

    @Test public void switchingSearchCancelsObsoleteDocumentValidation() throws Exception {
        slowDocument = true;
        java.util.concurrent.atomic.AtomicInteger oldCallbacks = new java.util.concurrent.atomic.AtomicInteger();
        main(() -> DanmakuApi.searchDetailed("弹幕旧回归片", "2026", "电影", "正片", new DanmakuApi.SearchCallback() {
            @Override public void onFound(Danmaku item, List<Danmaku> catalogue) { oldCallbacks.incrementAndGet(); }
            @Override public void onFailure(DanmakuApi.SearchFailure error) { oldCallbacks.incrementAndGet(); }
        }));
        assertTrue(documentStarted.await(10, TimeUnit.SECONDS));
        slowDocument = false;
        long start = SystemClock.elapsedRealtime();
        Danmaku next = search("弹幕新回归片", "2026", "综艺", "20260816下");
        assertTrue(next.getName().startsWith("弹幕新回归片"));
        assertTrue(SystemClock.elapsedRealtime() - start < 4_000);
        SystemClock.sleep(5_100);
        assertEquals(0, oldCallbacks.get());
    }

    @Test public void unavailableFirstFourDocumentsDoNotHideTheWorkingFifth() throws Exception {
        Danmaku found = search("弹幕备选回归片", "2026", "电影", "正片");
        assertTrue(found.getUrl().contains("/fifth?"));
        assertEquals(2, parse(found));
    }

    @Test public void manualSearchShowsCurrentHalfAndKeepsSearchAvailable() throws Exception {
        openPanel("20260816下");
        await(() -> binding.recycler.getAdapter().getItemCount() > 0, 15_000);
        main(() -> {
            assertEquals(1, binding.recycler.getAdapter().getItemCount());
            assertEquals(View.VISIBLE, binding.recycler.getVisibility());
            assertTrue(binding.submit.isEnabled());
        });
        screenshot("results");
    }

    @Test public void accessDeniedIsVisibleAndRetryRecovers() throws Exception {
        status = 403; openPanel("20260816上");
        await(() -> binding.empty.getVisibility() == View.VISIBLE
                && !binding.empty.getText().toString().equals(App.get().getString(R.string.danmaku_search_ready)), 50_000);
        main(() -> assertEquals(App.get().getString(R.string.danmaku_failure_denied), binding.empty.getText().toString()));
        screenshot("denied");
        status = 0; submitSearch();
        await(() -> binding.recycler.getAdapter().getItemCount() > 0, 15_000);
        main(() -> assertEquals(View.GONE, binding.empty.getVisibility()));
    }

    @Test public void liveProviderSearchesAndParsesUserExamples() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        DanmakuSetting.putApiUrl("");
        for (String[] spec : List.of(
                new String[]{"流浪地球2", "2023", "电影", "正片"},
                new String[]{"现在就出发 第一季", "2023", "综艺", "20230813上"},
                new String[]{"现在就出发 第三季", "2025", "综艺", "第1期上"},
                new String[]{"披荆斩棘2026", "2026", "综艺", "20260816"},
                new String[]{"喜剧之王单口季", "2024", "综艺", "第1期上(一)"})) {
            long started = SystemClock.elapsedRealtime();
            Danmaku found = search(spec[0], spec[1], spec[2], spec[3]);
            int count = parse(found);
            assertTrue(found.getName(), count > 0);
            System.out.println("DANMAKU_LIVE title=" + spec[0] + " episode=" + spec[3]
                    + " comments=" + count + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                    + " matched=" + found.getName());
        }
    }

    private Danmaku search(String title, String year, String type, String episode) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Danmaku> found = new AtomicReference<>();
        AtomicReference<DanmakuApi.SearchFailure> failure = new AtomicReference<>();
        main(() -> DanmakuApi.searchDetailed(title, year, type, episode, new DanmakuApi.SearchCallback() {
            @Override public void onFound(Danmaku item, List<Danmaku> catalogue) { found.set(item); done.countDown(); }
            @Override public void onFailure(DanmakuApi.SearchFailure error) { failure.set(error); done.countDown(); }
        }));
        assertTrue(title + " search timeout", done.await(100, TimeUnit.SECONDS));
        assertNotNull(title + " " + episode + " failure=" + failure.get(), found.get());
        return found.get();
    }

    private int parse(Danmaku item) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Uri> local = new AtomicReference<>();
        DanmakuDocumentCache.load(item.getUri(), new DanmakuDocumentCache.Listener() {
            @Override public void onReady(Uri source, Uri file) { local.set(file); done.countDown(); }
            @Override public void onFailure(Uri source, java.io.IOException error) { done.countDown(); }
        });
        assertTrue(done.await(50, TimeUnit.SECONDS)); assertNotNull(local.get());
        try (var stream = App.get().getContentResolver().openInputStream(local.get())) {
            int parsed = FilteringBiliParser.INSTANCE.parse(stream).size();
            CountDownLatch loaded = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger mounted = new java.util.concurrent.atomic.AtomicInteger(-1);
            var controller = new androidx.media3.ui.danmaku.DanmakuController();
            main(() -> {
                controller.registerParser(FilteringBiliParser.INSTANCE);
                controller.setListener(new androidx.media3.ui.danmaku.DanmakuController.Listener() {
                    @Override public void onLoadCompleted(Uri source, int count) { mounted.set(count); loaded.countDown(); }
                    @Override public void onLoadError(Uri source, java.io.IOException error) { loaded.countDown(); }
                });
                controller.setDataSource(local.get(), new androidx.media3.datasource.DefaultDataSource.Factory(App.get()));
            });
            try {
                assertTrue(loaded.await(20, TimeUnit.SECONDS));
                assertEquals(parsed, mounted.get());
            } finally { main(controller::release); }
            return parsed;
        }
    }

    private void openPanel(String episode) throws Exception {
        scenario = ActivityScenario.launch(HomeActivity.class);
        scenario.onActivity(activity -> {
            player = new PlayerManager(null);
            try {
                Field spec = PlayerManager.class.getDeclaredField("spec"); spec.setAccessible(true);
                spec.set(player, PlaySpec.from("fixture", server.url("/video").toString(), null,
                        new MediaMetadata.Builder().setTitle("弹幕验收虚构片").setArtist(episode).build()));
            } catch (Exception e) { throw new AssertionError(e); }
            DanmakuSettingDialog.create().player(player).search(true).show(activity);
            activity.getSupportFragmentManager().executePendingTransactions();
            try {
                Object dialog = activity.getSupportFragmentManager().getFragments().stream()
                        .filter(f -> f instanceof DanmakuSettingDialog.BottomSheet || f instanceof DanmakuSettingDialog.SideSheet)
                        .findFirst().orElseThrow();
                Field controller = dialog.getClass().getDeclaredField("searchPanel"); controller.setAccessible(true);
                panel = (DanmakuSearchPanel) controller.get(dialog);
                Field view = DanmakuSearchPanel.class.getDeclaredField("binding"); view.setAccessible(true);
                binding = (ViewDanmakuSearchEmbeddedBinding) view.get(panel);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        await(() -> binding.submit.isShown() && binding.submit.hasWindowFocus()
                && binding.submit.getWidth() > 0, 10_000);
        submitSearch();
    }

    private void submitSearch() throws Exception {
        int before = server.getRequestCount();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync();
        if (com.fongmi.android.tv.utils.Util.isLeanback()) {
            instrumentation.setInTouchMode(false);
            main(() -> assertTrue("Search button must accept remote focus", binding.submit.requestFocus()));
            await(() -> binding.submit.hasFocus(), 5_000);
            instrumentation.waitForIdleSync();
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        } else {
            int[] location = new int[2];
            main(() -> {
                binding.submit.getLocationOnScreen(location);
                location[0] += binding.submit.getWidth() / 2;
                location[1] += binding.submit.getHeight() / 2;
            });
            long now = SystemClock.uptimeMillis();
            var down = android.view.MotionEvent.obtain(now, now, 0, location[0], location[1], 0);
            var up = android.view.MotionEvent.obtain(now, now + 80, 1, location[0], location[1], 0);
            instrumentation.getUiAutomation().injectInputEvent(down, true);
            instrumentation.getUiAutomation().injectInputEvent(up, true);
            down.recycle(); up.recycle();
        }
        // Injected input is asynchronous. Await an actual outgoing request before inspecting
        // the empty view; the initial ready prompt is visible before the key-up is processed.
        await(() -> server.getRequestCount() > before, 5_000);
    }

    private void screenshot(String name) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync();
        // View assertions can pass before SurfaceFlinger has presented the next frame.
        SystemClock.sleep(400);
        var bitmap = instrumentation.getUiAutomation().takeScreenshot();
        var file = new java.io.File(App.get().getExternalFilesDir(null), "danmaku-" + name + ".png");
        try (var output = new java.io.FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    private void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] result = {false}; main(() -> result[0] = condition.getAsBoolean());
            if (result[0]) return;
            SystemClock.sleep(100);
        }
        fail("UI condition timed out");
    }
}
