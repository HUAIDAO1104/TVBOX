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
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
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
    private long lastSubmittedAt;
    private final List<com.fongmi.android.tv.player.danmaku.DanmakuStatus> statusEvents = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Before public void setup() throws Exception {
        if (com.fongmi.android.tv.utils.Util.isLeanback()) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        previousApi = DanmakuSetting.getApiUrl();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().startsWith("/api/v2/comment/")) {
                    if (request.getPath().contains("/restore-fail")) {
                        documentStarted.countDown();
                        return new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setResponseCode(404);
                    }
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
        if (scenario != null) scenario.onActivity(activity -> {
            for (var fragment : activity.getSupportFragmentManager().getFragments()) {
                if (fragment instanceof androidx.fragment.app.DialogFragment dialog) dialog.dismissNow();
            }
        });
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

    @Test public void manualSearchGroupsWorksAndKeepsAllEpisodesSelectable() throws Exception {
        openPanel("20260816下");
        await(() -> binding.recycler.getAdapter().getItemCount() > 0, 15_000);
        main(() -> {
            assertEquals(2, binding.recycler.getAdapter().getItemCount());
            assertEquals(View.VISIBLE, binding.recycler.getVisibility());
            assertTrue(binding.submit.isEnabled());
        });
        screenshot("works");
        var adapter = (com.fongmi.android.tv.ui.adapter.DanmakuAdapter) binding.recycler.getAdapter();
        int variety = adapter.getItem(0).getName().contains("综艺") ? 0 : 1;
        clickRow(variety);
        await(() -> adapter.getItemCount() == 3, 5_000);
        screenshot("results");
        main(() -> { assertTrue(panel.onBack()); assertEquals(2, adapter.getItemCount()); });
    }

    @Test public void manualFailureRecoversTheConfirmedMovieOrPartAcrossProviders() throws Exception {
        openPanel("错误片源名", "14");
        for (String[] spec : List.of(new String[]{"电影", "正片", "/movie?"},
                new String[]{"综艺", "2026-08-16 第1期上", "/upper?"},
                new String[]{"综艺", "2026-08-16 第1期下", "/lower?"})) {
            Danmaku selected = item("手选确认回归片(2026)【" + spec[0] + "】from stale - 【old-platform】 " + spec[1], "bad-manual");
            CountDownLatch done = new CountDownLatch(1); AtomicReference<Danmaku> applied = new AtomicReference<>();
            main(() -> VodPlaybackMedia.applyManualMatch(player, "片源的错误搜索词", selected, List.of(selected),
                    value -> { applied.set(value); done.countDown(); }, done::countDown));
            assertTrue(done.await(20, TimeUnit.SECONDS)); assertNotNull(spec[1], applied.get());
            assertTrue(applied.get().getUrl(), applied.get().getUrl().contains(spec[2]));
            assertEquals(2, parse(applied.get()));
        }
    }

    @Test public void savedAutomaticRestoreCannotOverwriteANewerManualChoice() throws Exception {
        openPanel("恢复竞态回归片", "正片");
        var context = new com.fongmi.android.tv.playback.vod.DanmakuMatchContext("恢复竞态回归片", "2026", "电影", "正片");
        Danmaku stale = item("恢复竞态回归片(2026)【电影】from stale - 正片", "restore-fail");
        com.fongmi.android.tv.playback.vod.DanmakuManualMatchStore.get().remember(context, "恢复竞态回归片", stale, List.of(stale));
        var history = new com.fongmi.android.tv.bean.History(); history.setVodName("恢复竞态回归片");
        var episode = new com.fongmi.android.tv.bean.Episode(); episode.setName("正片");
        boolean auto = DanmakuSetting.isAuto(); DanmakuSetting.putAuto(true);
        try {
            main(() -> VodPlaybackMedia.searchDanmaku(new com.fongmi.android.tv.bean.Result(), history, episode, 1, "2026", "电影", player));
            assertTrue(documentStarted.await(5, TimeUnit.SECONDS));
            Danmaku confirmed = item("用户最终选择(2026)【电影】from fixture - 正片", "manual-winner");
            CountDownLatch done = new CountDownLatch(1);
            main(() -> VodPlaybackMedia.applyManualMatch(player, "用户最终选择", confirmed, List.of(confirmed), value -> done.countDown(), () -> fail("manual failed")));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            SystemClock.sleep(2500);
            main(() -> assertEquals(confirmed.getUrl(), player.getSelectedDanmaku().getUrl()));
        } finally { DanmakuSetting.putAuto(auto); }
    }

    @Test public void externalLinkCancelsOldRestoreFailureAndEnablesRendering() throws Exception {
        openPanel("外部弹幕回归片", "正片");
        var context = new com.fongmi.android.tv.playback.vod.DanmakuMatchContext("外部弹幕回归片", "2026", "电影", "正片");
        Danmaku stale = item("外部弹幕回归片(2026)【电影】from stale - 正片", "restore-fail");
        com.fongmi.android.tv.playback.vod.DanmakuManualMatchStore.get().remember(context, "外部弹幕回归片", stale, List.of(stale));
        var history = new com.fongmi.android.tv.bean.History(); history.setVodName("外部弹幕回归片");
        var episode = new com.fongmi.android.tv.bean.Episode(); episode.setName("正片");
        boolean auto = DanmakuSetting.isAuto(), show = DanmakuSetting.isShow(); DanmakuSetting.putAuto(true);
        try {
            main(() -> VodPlaybackMedia.searchDanmaku(new com.fongmi.android.tv.bean.Result(), history, episode, 1, "2026", "电影", player));
            assertTrue(documentStarted.await(5, TimeUnit.SECONDS));
            Danmaku confirmed = item("外部确认弹幕", "external-winner");
            main(() -> { player.setDanmakuEnabled(false); VodPlaybackMedia.applyExternalDanmaku(player, confirmed); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); statusEvents.clear();
            SystemClock.sleep(2500);
            main(() -> assertEquals(confirmed.getUrl(), player.getSelectedDanmaku().getUrl()));
            assertTrue(DanmakuSetting.isShow());
            assertTrue(statusEvents.stream().noneMatch(s -> s.stage() == com.fongmi.android.tv.player.danmaku.DanmakuStatus.Stage.FAILED));
        } finally { DanmakuSetting.putAuto(auto); DanmakuSetting.putShow(show); }
    }

    @Test public void aiKeyAndRecommendedModelAreReachableFromPlaybackSearch() throws Exception {
        openPanel("正片");
        main(() -> {
            assertTrue(binding.ai.isShown());
            if (com.fongmi.android.tv.utils.Util.isLeanback()) assertTrue(binding.ai.requestFocus());
            binding.ai.performClick();
        });
        final DanmakuAiDialog[] dialog = new DanmakuAiDialog[1];
        scenario.onActivity(a -> {
            a.getSupportFragmentManager().executePendingTransactions();
            dialog[0] = (DanmakuAiDialog) a.getSupportFragmentManager().findFragmentByTag("danmaku_ai");
            assertNotNull(dialog[0]);
        });
        main(() -> {
            var window = dialog[0].requireDialog();
            assertEquals("qwen3.5-flash", ((android.widget.EditText) window.findViewById(R.id.model)).getText().toString());
            assertNotNull(window.findViewById(R.id.key));
            window.findViewById(R.id.key).requestFocus();
        });
        screenshot("ai-playback-entry");
        if (!com.fongmi.android.tv.utils.Util.isLeanback()) {
            main(() -> {
                View key = dialog[0].requireDialog().findViewById(R.id.key);
                ((android.view.inputmethod.InputMethodManager) key.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                        .showSoftInput(key, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
            SystemClock.sleep(1000);
            screenshot("ai-playback-keyboard");
            main(() -> {
                var window = dialog[0].requireDialog().getWindow();
                android.graphics.Rect frame = new android.graphics.Rect();
                window.getDecorView().getWindowVisibleDisplayFrame(frame);
                View save = ((androidx.appcompat.app.AlertDialog) dialog[0].requireDialog()).getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
                int[] position = new int[2]; save.getLocationOnScreen(position);
                assertTrue("Save below keyboard: " + position[1] + "/" + frame.bottom, position[1] + save.getHeight() <= frame.bottom);
                View key = dialog[0].requireDialog().findViewById(R.id.key); key.getLocationOnScreen(position);
                assertTrue("Key below keyboard", position[1] + key.getHeight() <= frame.bottom);
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue(key.getGlobalVisibleRect(visible));
                // Material's edit text may return the containing TextInputLayout rectangle,
                // which also includes helper text. Require containment, not equal heights.
                assertTrue("Entire key field must remain visible", visible.contains(new android.graphics.Rect(
                        position[0], position[1], position[0] + key.getWidth(), position[1] + key.getHeight())));
            });
            screenshot("ai-playback-keyboard");
        }
    }

    @Test public void liveSeasonFourAndMovieAutomaticThenManualSelection() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        DanmakuSetting.putApiUrl("");
        for (String[] spec : List.of(new String[]{"现在就出发第四季", "2026", "综艺", "第1期上", "r4102opm7x3"},
                new String[]{"现在就出发第四季", "2026", "综艺", "第1期下", "z41023tinp7"},
                new String[]{"哪吒2", "2025", "动画电影", "正片", "y4101qnn3jo"})) {
            long start = SystemClock.elapsedRealtime();
            Danmaku found = search(spec[0], spec[1], spec[2], spec[3]);
            long matched = SystemClock.elapsedRealtime();
            assertTrue(found.getUrl(), found.getUrl().endsWith(spec[4])); assertTrue(parse(found) > 1000);
            android.util.Log.i("Danmaku574QA", "automatic " + spec[0] + spec[3] + " matchAndDocumentMs=" + (matched - start)
                    + " parseAndControllerMs=" + (SystemClock.elapsedRealtime() - matched));
        }
        openPanel("现在就出发第四季", "第1期上");
        var adapter = (com.fongmi.android.tv.ui.adapter.DanmakuAdapter) binding.recycler.getAdapter();
        await(() -> indexOf(adapter, "现在就出发 第4季(2026)") >= 0, 20000);
        screenshot("season-four-works");
        clickRow(indexOf(adapter, "现在就出发 第4季(2026)"));
        await(() -> adapter.getItemCount() > 1, 5000);
        for (String vid : List.of("r4102opm7x3", "z41023tinp7")) {
            int index = -1;
            for (int i = 0; i < adapter.getItemCount(); i++) if (adapter.getItem(i).getUrl().endsWith(vid)) { index = i; break; }
            assertTrue("Missing episode " + vid, index >= 0); clickRow(index);
            await(() -> player.getSelectedDanmaku() != null && player.getSelectedDanmaku().getUrl().endsWith(vid), 15000);
            assertTrue(parse(player.getSelectedDanmaku()) > 1000);
        }
        screenshot("season-four-episodes");
    }

    @Test public void liveMangoAutomaticAndManualBonusThenDirectLink() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        DanmakuSetting.putApiUrl("");
        for (String[] spec : List.of(new String[]{"第1期上", "24566777"}, new String[]{"0816下", "24567586"},
                new String[]{"会员加更第1期", "24579459"})) {
            Danmaku found = search("披荆斩棘2026", "2026", "综艺", spec[0]);
            assertTrue(found.getName(), found.getUrl().endsWith(spec[1])); assertTrue(parse(found) > 100);
        }
        openPanel("披荆斩棘2026", "第1期上");
        boolean auto = DanmakuSetting.isAuto(); DanmakuSetting.putAuto(false);
        try {
            var history = new com.fongmi.android.tv.bean.History(); history.setVodName("披荆斩棘2026");
            var episode = new com.fongmi.android.tv.bean.Episode(); episode.setName("第1期上");
            main(() -> VodPlaybackMedia.searchDanmaku(new com.fongmi.android.tv.bean.Result(), history, episode, 1, "", "", player));
        } finally { DanmakuSetting.putAuto(auto); }
        main(() -> binding.keyword.setText("披荆斩棘2026"));
        submitSearch();
        var adapter = (com.fongmi.android.tv.ui.adapter.DanmakuAdapter) binding.recycler.getAdapter();
        await(() -> indexOfSource(adapter, "mango-direct") >= 0, 22000);
        clickRow(indexOfSource(adapter, "mango-direct"));
        await(() -> adapter.getItemCount() > 10, 5000);
        int bonus = -1;
        for (int i = 0; i < adapter.getItemCount(); i++) if (adapter.getItem(i).getUrl().endsWith("24579459")) { bonus = i; break; }
        assertTrue(bonus >= 0); clickRow(bonus);
        await(() -> player.getSelectedDanmaku() != null && player.getSelectedDanmaku().getUrl().endsWith("24579459"), 50000);
        assertTrue(parse(player.getSelectedDanmaku()) > 100); screenshot("575-mango-manual-bonus");
        for (String link : List.of("https://www.bilibili.com/bangumi/play/ep691614",
                "https://v.youku.com/v_show/id_XNTkxNDY2Nzg2MA==.html",
                "https://v.qq.com/x/cover/mzc00200tjkzeps/y4101qnn3jo.html",
                "https://www.iqiyi.com/v_pp7j21xlp4.html",
                "https://ani.gamer.com.tw/animeVideo.php?sn=41645")) {
            main(() -> { panel.onBack(); binding.keyword.setText(link); });
            submitSearch(); await(() -> indexOfSource(adapter, "platform-link") >= 0, 20000);
            clickRow(indexOfSource(adapter, "platform-link"));
            // A one-item work list and its one-item episode list have the same count.
            // Wait for the episode identity before the next remote-control click.
            await(() -> adapter.getItemCount() == 1 && link.equals(adapter.getItem(0).getUrl()), 5000); clickRow(0);
            await(() -> player.getSelectedDanmaku() != null && player.getSelectedDanmaku().getUrl().equals(link), 50000);
            assertTrue(link, parse(player.getSelectedDanmaku()) > 100);
            var context = VodPlaybackMedia.contextOf(player);
            var saved = com.fongmi.android.tv.playback.vod.DanmakuManualMatchStore.get().find(context);
            assertNotNull(saved); assertEquals(link, saved.episode(context.getEpisode()).getUrl());
            assertNull(com.fongmi.android.tv.playback.vod.DanmakuManualMatchStore.get().find(
                    new com.fongmi.android.tv.playback.vod.DanmakuMatchContext(context.getTitle(), context.getYear(), context.getType(), "第2期上")));
            screenshot("575-direct-" + android.net.Uri.parse(link).getHost());
        }
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

    @Test public void liveDramaEpisode14AutomaticAndManualWorkEpisodeFlow() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        DanmakuSetting.putApiUrl("");
        String expected = "iqiyi-danmaku://video/2586684050154100";
        DanmakuDocumentCache.invalidate(Uri.parse(expected));
        long started = SystemClock.elapsedRealtime();
        Danmaku found = search("喜剧之王", "2026", "电视剧", "14");
        long elapsed = SystemClock.elapsedRealtime() - started;
        assertEquals(expected, found.getUrl());
        int count = parse(found);
        assertTrue("Episode 14 must have real comments", count > 100);
        System.out.println("DANMAKU_DRAMA14 comments=" + count + " coldSearchAndDownloadMs=" + elapsed);
        long manualStarted = SystemClock.elapsedRealtime();
        openPanel("喜剧之王", "14");
        var adapter = (com.fongmi.android.tv.ui.adapter.DanmakuAdapter) binding.recycler.getAdapter();
        await(() -> indexOf(adapter, "喜剧之王(2026)【电视剧】") >= 0, 20_000);
        System.out.println("DANMAKU_DRAMA14 manualOpenAndSearchMs=" + (SystemClock.elapsedRealtime() - manualStarted));
        System.out.println("DANMAKU_DRAMA14 manualSearchResultsMs=" + (SystemClock.elapsedRealtime() - lastSubmittedAt));
        screenshot("drama-works");
        clickRow(indexOf(adapter, "喜剧之王(2026)【电视剧】"));
        await(() -> adapter.getItemCount() == 24, 5_000);
        assertEquals(expected, adapter.getItem(13).getUrl());
        screenshot("drama-episodes");
        clickRow(13);
        await(() -> player.getSelectedDanmaku() != null
                && expected.equals(player.getSelectedDanmaku().getUrl()), 10_000);
        assertEquals(count, parse(player.getSelectedDanmaku()));
        scrollToLastEpisode();
        clickRow(23);
        await(() -> player.getSelectedDanmaku() != null
                && player.getSelectedDanmaku().getName().contains("第24集"), 50_000);
        main(() -> {
            var rect = new android.graphics.Rect();
            assertTrue(binding.keyword.getGlobalVisibleRect(rect));
            assertEquals("Search stays fully visible after scrolling", binding.keyword.getHeight(), rect.height());
        });
        screenshot("drama-last-episode");
        main(() -> { assertTrue(panel.onBack()); assertEquals("喜剧之王", binding.keyword.getText().toString()); });
    }

    private void scrollToLastEpisode() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        if (com.fongmi.android.tv.utils.Util.isLeanback()) {
            for (int i = 0; i < 10; i++) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                instrumentation.waitForIdleSync();
            }
            main(() -> {
                View child = binding.recycler.getFocusedChild();
                assertNotNull(child);
                assertEquals(23, binding.recycler.getChildAdapterPosition(child));
            });
        } else {
            for (int swipe = 0; swipe < 8; swipe++) {
                boolean[] more = {false};
                android.graphics.Rect rect = new android.graphics.Rect();
                main(() -> { more[0] = binding.recycler.canScrollVertically(1); binding.recycler.getGlobalVisibleRect(rect); });
                if (!more[0]) break;
                long now = SystemClock.uptimeMillis();
                for (int step = 0; step <= 12; step++) {
                    int action = step == 0 ? 0 : step == 12 ? 1 : 2;
                    float y = rect.bottom - 25 - (rect.height() - 50) * step / 12f;
                    var event = android.view.MotionEvent.obtain(now, now + step * 25L, action, rect.centerX(), y, 0);
                    instrumentation.getUiAutomation().injectInputEvent(event, true);
                    event.recycle();
                }
                instrumentation.waitForIdleSync();
                SystemClock.sleep(350);
            }
            main(() -> assertFalse("Last episode must be reachable by swiping", binding.recycler.canScrollVertically(1)));
        }
    }

    private int indexOf(com.fongmi.android.tv.ui.adapter.DanmakuAdapter adapter, String prefix) {
        for (int i = 0; i < adapter.getItemCount(); i++) if (adapter.getItem(i).getName().startsWith(prefix)) return i;
        return -1;
    }

    private int indexOfSource(com.fongmi.android.tv.ui.adapter.DanmakuAdapter adapter, String source) {
        for (int i = 0; i < adapter.getItemCount(); i++) if (source.equals(adapter.getItem(i).getSourceKey())) return i;
        return -1;
    }

    private void clickRow(int position) throws Exception {
        main(() -> binding.recycler.scrollToPosition(position));
        await(() -> binding.recycler.findViewHolderForAdapterPosition(position) != null, 5_000);
        View[] row = new View[1];
        main(() -> row[0] = binding.recycler.findViewHolderForAdapterPosition(position).itemView);
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        if (com.fongmi.android.tv.utils.Util.isLeanback()) {
            instrumentation.setInTouchMode(false);
            main(() -> assertTrue(row[0].requestFocus()));
            instrumentation.waitForIdleSync();
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        } else {
            int[] location = new int[2];
            main(() -> { row[0].getLocationOnScreen(location); location[0] += row[0].getWidth() / 2; location[1] += row[0].getHeight() / 2; });
            long now = SystemClock.uptimeMillis();
            var down = android.view.MotionEvent.obtain(now, now, 0, location[0], location[1], 0);
            var up = android.view.MotionEvent.obtain(now, now + 80, 1, location[0], location[1], 0);
            instrumentation.getUiAutomation().injectInputEvent(down, true);
            instrumentation.getUiAutomation().injectInputEvent(up, true);
            down.recycle(); up.recycle();
        }
        instrumentation.waitForIdleSync();
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

    private void openPanel(String episode) throws Exception { openPanel("弹幕验收虚构片", episode); }

    private void openPanel(String title, String episode) throws Exception {
        scenario = ActivityScenario.launch(HomeActivity.class);
        scenario.onActivity(activity -> {
            player = new PlayerManager((PlayerManager.Callback) java.lang.reflect.Proxy.newProxyInstance(
                    PlayerManager.Callback.class.getClassLoader(), new Class[]{PlayerManager.Callback.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onDanmakuStatusChanged")) statusEvents.add((com.fongmi.android.tv.player.danmaku.DanmakuStatus) args[0]);
                        return null;
                    }));
            try {
                Field spec = PlayerManager.class.getDeclaredField("spec"); spec.setAccessible(true);
                spec.set(player, PlaySpec.from("fixture", server.url("/video").toString(), null,
                        new MediaMetadata.Builder().setTitle(title).setArtist(episode).build()));
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
        // Wait for the keyboard/sheet entrance animation before targeting screen coordinates.
        SystemClock.sleep(600);
        submitSearch();
    }

    private void submitSearch() throws Exception {
        Field field = DanmakuSearchPanel.class.getDeclaredField("requestId"); field.setAccessible(true);
        var requests = (java.util.concurrent.atomic.AtomicInteger) field.get(panel);
        int before = requests.get();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync();
        SystemClock.sleep(350);
        lastSubmittedAt = SystemClock.elapsedRealtime();
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
        await(() -> requests.get() > before, 5_000);
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
        if (binding != null) {
            main(() -> android.util.Log.e("Danmaku576QA", "Timeout state: shown=" + binding.submit.isShown()
                    + " windowFocus=" + binding.submit.hasWindowFocus() + " status=" + binding.providerStatus.getText()
                    + " selected=" + (player == null || player.getSelectedDanmaku() == null ? "none" : player.getSelectedDanmaku().getUrl())));
            screenshot("timeout-" + SystemClock.elapsedRealtime());
        }
        fail("UI condition timed out");
    }
}
