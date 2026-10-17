package com.fongmi.android.tv.ui;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.*;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.model.*;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.*;
import com.fongmi.android.tv.ui.fragment.*;
import com.fongmi.android.tv.ui.search.SearchSourcePreference;
import com.google.android.material.tabs.TabLayout;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Deterministic mobile workflows, real Activity/Fragment, HTTP, input and layout. */
@RunWith(AndroidJUnit4.class)
public class MobileExperienceInstrumentedTest {
    private MockWebServer server;
    private ActivityScenario<?> scenario;
    private Config previousConfig;
    private List<Site> previousSites;
    private Site previousHome;
    private String keywords;
    private Config fixture;
    private List<Site> sites;
    private volatile int slowSeconds;
    private volatile boolean failFast;
    private int episodeCount = 1;
    private boolean multipleFlags;
    private int bulkRows;
    private final java.util.concurrent.CountDownLatch slowGate = new java.util.concurrent.CountDownLatch(1);
    private final AtomicInteger searchRequests = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean castHasHistory = new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicInteger pageRequests = new AtomicInteger();

    @Before public void setup() throws Exception {
        previousConfig = VodConfig.get().getConfig(); previousSites = VodConfig.get().getSites(); previousHome = VodConfig.get().getHome(); keywords = Setting.getKeyword();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/config")) return json("{\"sites\":" + App.gson().toJson(sites) + "}");
                if (path.equals("/action")) { castHasHistory.set(request.getBody().readUtf8().contains("history=")); return new MockResponse().setResponseCode(503).setBody("offline"); }
                if (path.equals("/poster")) return new MockResponse().setResponseCode(404);
                if (request.getRequestUrl().queryParameter("play") != null) return json("{\"parse\":0,\"url\":\"" + server.url("/video.mp4") + "\"}");
                if (path.equals("/video.mp4")) {
                    try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("playback-buffering.mp4")) {
                        return new MockResponse().setHeader("Content-Type", "video/mp4").setBody(new okio.Buffer().readFrom(input));
                    } catch (Exception e) { return new MockResponse().setResponseCode(500); }
                }
                boolean search = request.getRequestUrl().queryParameter("wd") != null;
                if (search) searchRequests.incrementAndGet();
                if (search && bulkRows > 0) {
                    var list = new com.google.gson.JsonArray();
                    for (int i = 0; i < bulkRows; i++) {
                        var item = new com.google.gson.JsonObject();
                        item.addProperty("vod_id", "bulk-" + i); item.addProperty("vod_name", "测试电影" + i);
                        list.add(item); list.add(item.deepCopy());
                    }
                    var result = new com.google.gson.JsonObject(); result.addProperty("pagecount", 1); result.add("list", list);
                    return json(result.toString());
                }
                if (search && failFast && path.equals("/fast")) return new MockResponse().setResponseCode(500).setBody("invalid-json");
                String pg = request.getRequestUrl().queryParameter("pg");
                if ("2".equals(pg)) { pageRequests.incrementAndGet(); return json(results("3", false)); }
                if (path.equals("/empty")) return json("{\"list\":[]}");
                if (path.equals("/slow")) {
                    if (slowSeconds > 0) try { slowGate.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return json(results("2", false));
                }
                return json(results("1", true));
            }
        });
        server.start();
        sites = List.of(site("fast", "快速源"), site("slow", "慢速源"), site("empty", "空结果源"));
        fixture = Config.create(0, server.url("/config").toString(), "手机体验测试").update();
        VodConfig.get().config(fixture);
        setField(VodConfig.get(), "sites", sites); setField(VodConfig.get(), "home", sites.get(0));
        Setting.putSearchSources(fixture.getUrl(), SearchSourcePreference.serialize(new LinkedHashSet<>(sites.stream().map(SearchSourcePreference::id).toList())));
    }
    private Site site(String key, String name) {
        return App.gson().fromJson("{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"type\":4,\"api\":\"" + server.url("/" + key) + "\",\"searchable\":1}", Site.class);
    }
    private MockResponse json(String value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(value); }
    private String results(String id, boolean duplicate) {
        StringBuilder episodes = new StringBuilder();
        for (int i = 1; i <= episodeCount; i++) {
            if (i > 1) episodes.append("#");
            episodes.append("第").append(i).append("集$").append(server.url("/video.mp4?episode=" + i));
        }
        String from = multipleFlags ? "测试线路$$$备用线路" : "测试线路";
        if (multipleFlags) episodes.append("$$$").append(episodes.toString());
        String vod = "{\"vod_id\":\"" + id + "\",\"vod_name\":\"测试电影" + id + "\",\"vod_pic\":\"" + server.url("/poster") + "\",\"vod_remarks\":\"1080P\",\"vod_content\":\"这是一部用于验证手机版搜索、选集、播放和返回行为的测试影片。\",\"vod_play_from\":\"" + from + "\",\"vod_play_url\":\"" + episodes + "\"}";
        return "{\"page\":1,\"pagecount\":2,\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\"}],\"list\":[" + vod + (duplicate ? "," + vod : "") + "]}";
    }
    @After public void cleanup() throws Exception {
        slowGate.countDown();
        if (scenario != null) scenario.close();
        if (previousConfig != null) { previousConfig.update(); VodConfig.get().config(previousConfig); }
        setField(VodConfig.get(), "sites", previousSites); setField(VodConfig.get(), "home", previousHome);
        Setting.putKeyword(keywords); Config.delete(fixture.getUrl(), 0);
        server.shutdown();
    }
    private ActivityScenario<SearchActivity> search() {
        long start = SystemClock.elapsedRealtime();
        var value = ActivityScenario.<SearchActivity>launch(new Intent(App.get(), SearchActivity.class).putExtra("keyword", "")); scenario = value;
        onActivity(value, a -> { ((EditText) a.findViewById(R.id.keyword)).setText("测试电影"); a.findViewById(R.id.submit).performClick(); });
        android.util.Log.i("MobileQA", "Search entry and submit: " + (SystemClock.elapsedRealtime() - start) + "ms");
        return value;
    }
    private CollectFragment collect(SearchActivity activity) { return (CollectFragment) activity.getSupportFragmentManager().findFragmentByTag("CollectFragment"); }
    private int count(ActivityScenario<SearchActivity> value) {
        int[] count = {-1}; onActivity(value, a -> { Fragment f = collect(a); if (f != null && f.getView() != null) count[0] = ((RecyclerView) f.requireView().findViewById(R.id.recycler)).getAdapter().getItemCount(); }); return count[0];
    }
    // ActivityScenario.onActivity waits for a completely idle looper when called off-main.
    // An indeterminate progress animation need not become idle; post assertions as real input.
    private <A extends android.app.Activity> void onActivity(ActivityScenario<A> value, ActivityScenario.ActivityAction<A> action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.onActivity(action));
    }
    private void await(BooleanSupplier check) {
        long end = SystemClock.elapsedRealtime() + 12000;
        do { if (check.getAsBoolean()) return; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < end);
        fail("Timed out waiting for UI state");
    }
    @Test public void firstResultsDoNotWaitForSlowSourceAndSourceCountsMatch() {
        slowSeconds = 5; var value = search(); long began = SystemClock.elapsedRealtime();
        await(() -> count(value) >= 1);
        assertTrue("Fast source should render before 5s slow source", SystemClock.elapsedRealtime() - began < 3500);
        android.util.Log.i("MobileQA", "First partial result: " + (SystemClock.elapsedRealtime() - began) + "ms, slow source still blocked");
        assertEquals(1, count(value));
        onActivity(value, a -> {
            View v = collect(a).requireView(); assertEquals(View.VISIBLE, v.findViewById(R.id.stop).getVisibility());
            assertTrue(((TabLayout) v.findViewById(R.id.tabs)).getTabAt(1).getText().toString().contains("1"));
        });
        slowGate.countDown();
        await(() -> count(value) == 2);
        onActivity(value, a -> ((TabLayout) collect(a).requireView().findViewById(R.id.tabs)).selectTab(((TabLayout) collect(a).requireView().findViewById(R.id.tabs)).getTabAt(2)));
        await(() -> count(value) == 1);
        onActivity(value, a -> ((TabLayout) collect(a).requireView().findViewById(R.id.tabs)).selectTab(((TabLayout) collect(a).requireView().findViewById(R.id.tabs)).getTabAt(3)));
        await(() -> count(value) == 0);
        onActivity(value, a -> assertEquals(View.VISIBLE, collect(a).requireView().findViewById(R.id.empty).getVisibility()));
        screenshot("search-empty-source");
    }
    @Test public void manySourcesStreamWithoutBlockingInputOrLosingRows() throws Exception {
        bulkRows = 40;
        sites = new ArrayList<>();
        for (int i = 0; i < 60; i++) sites.add(site("bulk" + i, "测试源" + i));
        setField(VodConfig.get(), "sites", sites); setField(VodConfig.get(), "home", sites.get(0));
        Setting.putSearchSources(fixture.getUrl(), SearchSourcePreference.serialize(new LinkedHashSet<>(sites.stream().map(SearchSourcePreference::id).toList())));
        var value = search(); long start = SystemClock.elapsedRealtime(); long worstInputMs = 0;
        while (count(value) < 2400 && SystemClock.elapsedRealtime() - start < 20000) {
            long posted = SystemClock.elapsedRealtime();
            onActivity(value, a -> assertNotNull(collect(a).requireView().findViewById(R.id.stop)));
            worstInputMs = Math.max(worstInputMs, SystemClock.elapsedRealtime() - posted);
            SystemClock.sleep(25);
        }
        assertEquals(2400, count(value));
        assertTrue("Main-thread input latency: " + worstInputMs, worstInputMs < 800);
        onActivity(value, a -> {
            TabLayout tabs = collect(a).requireView().findViewById(R.id.tabs);
            assertEquals(61, tabs.getTabCount()); tabs.selectTab(tabs.getTabAt(60));
        });
        await(() -> count(value) == 40);
        android.util.Log.i("Performance576QA", "MOBILE_BULK_SEARCH sources=60 rawRows=4800 visibleRows=2400 elapsedMs="
                + (SystemClock.elapsedRealtime() - start) + " worstInputMs=" + worstInputMs);
    }
    @Test public void sourceSwitchRotationAndBackPreserveContextWithoutNewSearch() {
        var value = search(); await(() -> count(value) == 2);
        onActivity(value, a -> { TabLayout tabs = collect(a).requireView().findViewById(R.id.tabs); tabs.selectTab(tabs.getTabAt(2)); });
        await(() -> count(value) == 1);
        int requests = searchRequests.get();
        value.recreate(); await(() -> count(value) == 1);
        onActivity(value, a -> assertEquals(2, ((TabLayout) collect(a).requireView().findViewById(R.id.tabs)).getSelectedTabPosition()));
        assertEquals(requests, searchRequests.get()); screenshot("search-results");
        onActivity(value, a -> a.getOnBackPressedDispatcher().onBackPressed());
        await(() -> { boolean[] visible = {false}; onActivity(value, a -> { Fragment f = a.getSupportFragmentManager().findFragmentByTag("SearchFragment"); visible[0] = f != null && !f.isHidden(); }); return visible[0]; });
        onActivity(value, a -> assertEquals("测试电影", ((EditText) a.findViewById(R.id.keyword)).getText().toString()));
    }
    @Test public void loadMoreIsDeduplicatedAndStopKeepsUsableResults() {
        slowSeconds = 5; var value = search(); await(() -> count(value) == 1);
        onActivity(value, a -> collect(a).requireView().findViewById(R.id.stop).performClick());
        onActivity(value, a -> { TabLayout tabs = collect(a).requireView().findViewById(R.id.tabs); tabs.selectTab(tabs.getTabAt(1)); });
        await(() -> count(value) == 1);
        onActivity(value, a -> { View more = collect(a).requireView().findViewById(R.id.more); more.performClick(); more.performClick(); });
        await(() -> count(value) == 2); assertEquals(1, pageRequests.get());
    }
    @Test public void searchInputClearsAndRepeatedSubmitCreatesOneResultsPage() {
        var value = ActivityScenario.<SearchActivity>launch(new Intent(App.get(), SearchActivity.class).putExtra("keyword", "")); scenario = value;
        onActivity(value, a -> { EditText text = a.findViewById(R.id.keyword); text.setText("测试电影"); a.findViewById(com.google.android.material.R.id.text_input_end_icon).performClick(); assertEquals("", text.getText().toString()); text.setText("测试电影"); a.findViewById(R.id.submit).performClick(); a.findViewById(R.id.submit).performClick(); });
        await(() -> count(value) == 2);
        onActivity(value, a -> assertEquals(1, a.getSupportFragmentManager().getBackStackEntryCount()));
    }
    @Test public void homeLibraryAndSettingsAreTouchDestinationsWithSafeInsets() {
        var value = ActivityScenario.launch(HomeActivity.class); scenario = value;
        onActivity(value, a -> {
            var nav = (com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.navigation);
            assertEquals(3, nav.getMenu().size()); assertEquals("首页", nav.getMenu().getItem(0).getTitle().toString());
            nav.setSelectedItemId(R.id.library);
            assertNotNull(a.findViewById(R.id.tabs));
            nav.setSelectedItemId(R.id.setting); assertTrue(a.findViewById(R.id.cloud).isShown());
        }); screenshot("mine");
        onActivity(value, a -> ((com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.navigation)).setSelectedItemId(R.id.library)); screenshot("library");
        onActivity(value, a -> ((com.google.android.material.bottomnavigation.BottomNavigationView) a.findViewById(R.id.navigation)).setSelectedItemId(R.id.vod));
        SystemClock.sleep(1500);
        onActivity(value, a -> {
            float density = a.getResources().getDisplayMetrics().density;
            for (int id : new int[]{R.id.searchEntry, R.id.liveEntry, R.id.configEntry, R.id.linkEntry}) {
                View target = a.findViewById(id);
                assertTrue("Touch target height", target.getHeight() >= 48 * density - 1);
                assertTrue("Touch target width", target.getWidth() >= 48 * density - 1);
            }
            View nav = a.findViewById(R.id.navigation);
            int[] xy = new int[2]; nav.getLocationOnScreen(xy);
            int bottom = androidx.core.view.ViewCompat.getRootWindowInsets(a.getWindow().getDecorView()).getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            assertTrue("Navigation must be above system gesture area", xy[1] + nav.getHeight() <= a.getResources().getDisplayMetrics().heightPixels - bottom + 1);
        }); screenshot("home");
    }
    @Test public void videoStartsInPortraitAndOneBackExitsFullscreen() throws Exception {
        var value = ActivityScenario.<VideoActivity>launch(new Intent(App.get(), VideoActivity.class).putExtra("key", "push_agent").putExtra("id", server.url("/video.mp4").toString()).putExtra("name", "手机播放体验测试")); scenario = value;
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> ready[0] = a.findViewById(R.id.detailFull).isEnabled()); return ready[0]; });
        onActivity(value, a -> { assertTrue(a.findViewById(R.id.detailActions).isShown()); a.findViewById(R.id.detailFull).performClick(); assertEquals(View.GONE, a.findViewById(R.id.detailActions).getVisibility()); });
        SystemClock.sleep(1000); screenshot("player-fullscreen");
        onActivity(value, a -> a.getOnBackPressedDispatcher().onBackPressed());
        onActivity(value, a -> { assertFalse(a.isFinishing()); assertEquals(View.VISIBLE, a.findViewById(R.id.detailActions).getVisibility()); });
        SystemClock.sleep(500); screenshot("player-portrait");
    }
    @Test public void failedSourceRetriesWithoutClearingOtherResults() {
        failFast = true; var value = search();
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> { var f = collect(a); ready[0] = f != null && f.requireView().findViewById(R.id.retry).isShown(); }); return ready[0]; });
        await(() -> count(value) == 1);
        failFast = false;
        onActivity(value, a -> collect(a).requireView().findViewById(R.id.retry).performClick());
        assertTrue(count(value) >= 1);
        await(() -> count(value) == 2);
    }
    @Test public void draftAndImeCompositionNeverSubmitOnRecreation() {
        var value = ActivityScenario.<SearchActivity>launch(new Intent(App.get(), SearchActivity.class).putExtra("keyword", "")); scenario = value;
        onActivity(value, a -> {
            EditText input = a.findViewById(R.id.keyword); input.setText("未完成的电影");
            android.view.inputmethod.BaseInputConnection.setComposingSpans(input.getText());
            input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            assertEquals(0, a.getSupportFragmentManager().getBackStackEntryCount());
        });
        value.recreate();
        onActivity(value, a -> { assertEquals("未完成的电影", ((EditText) a.findViewById(R.id.keyword)).getText().toString()); assertNull(collect(a)); });
        assertEquals(0, searchRequests.get()); screenshot("search-keyboard");
    }
    @Test public void repositoryEditorValidatesAndRetainsDraftAcrossRotation() {
        var value = ActivityScenario.launch(RepositoryActivity.class); scenario = value;
        onActivity(value, a -> a.findViewById(R.id.add).performClick());
        await(() -> { boolean[] shown = {false}; onActivity(value, a -> shown[0] = !a.getSupportFragmentManager().getFragments().isEmpty()); return shown[0]; });
        onActivity(value, a -> {
            var f = (androidx.fragment.app.DialogFragment) a.getSupportFragmentManager().getFragments().get(0);
            f.requireDialog().findViewById(R.id.positive).performClick(); assertTrue(f.requireDialog().isShowing());
            ((EditText) f.requireDialog().findViewById(R.id.name)).setText("手机测试仓库");
            ((EditText) f.requireDialog().findViewById(R.id.url)).setText("https://example.com/config.json");
        });
        value.recreate();
        onActivity(value, a -> {
            var f = (androidx.fragment.app.DialogFragment) a.getSupportFragmentManager().getFragments().get(0);
            assertEquals("手机测试仓库", ((EditText) f.requireDialog().findViewById(R.id.name)).getText().toString());
            assertEquals("https://example.com/config.json", ((EditText) f.requireDialog().findViewById(R.id.url)).getText().toString());
        }); screenshot("repository-editor");
    }
    private static void setField(Object object, String name, Object value) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    @Test public void episodeSheetGroupsLongSeriesAndLocatesCurrentEpisode() {
        episodeCount = 120;
        var value = ActivityScenario.<VideoActivity>launch(new Intent(App.get(), VideoActivity.class).putExtra("key", "fast").putExtra("id", "1").putExtra("name", "长剧选集体验")); scenario = value;
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> ready[0] = ((TextView) a.findViewById(R.id.more)).getText().toString().contains("120")); return ready[0]; });
        onActivity(value, a -> a.findViewById(R.id.more).performClick());
        await(() -> { boolean[] open = {false}; onActivity(value, a -> open[0] = a.getSupportFragmentManager().findFragmentByTag("episode_picker") != null); return open[0]; });
        onActivity(value, a -> {
            View sheet = a.getSupportFragmentManager().findFragmentByTag("episode_picker").requireView();
            TabLayout ranges = sheet.findViewById(R.id.ranges);
            assertEquals(3, ranges.getTabCount());
            ranges.selectTab(ranges.getTabAt(2));
            RecyclerView episodes = sheet.findViewById(R.id.episode);
            assertEquals(20, episodes.getAdapter().getItemCount());
        });
        screenshot("episodes-last-group");
        onActivity(value, a -> {
            var picker = (com.fongmi.android.tv.ui.dialog.EpisodeListDialog) a.getSupportFragmentManager().findFragmentByTag("episode_picker");
            RecyclerView list = picker.requireView().findViewById(R.id.episode);
            var adapter = (com.fongmi.android.tv.ui.adapter.EpisodeAdapter) list.getAdapter();
            picker.onItemClick(adapter.getItems().get(10));
        });
        await(() -> { boolean[] selected = {false}; onActivity(value, a -> selected[0] = a.getEpisodePickerFlags().get(0).getEpisodes().stream().anyMatch(e -> e.isSelected() && e.getName().contains("111"))); return selected[0]; });
        onActivity(value, a -> { assertTrue(a.findViewById(R.id.detailActions).isShown()); a.findViewById(R.id.more).performClick(); });
        SystemClock.sleep(250);
        onActivity(value, a -> {
            View sheet = a.getSupportFragmentManager().findFragmentByTag("episode_picker").requireView();
            TabLayout ranges = sheet.findViewById(R.id.ranges);
            assertEquals(2, ranges.getSelectedTabPosition());
            ranges.selectTab(ranges.getTabAt(0)); sheet.findViewById(R.id.locate).performClick();
            assertEquals(2, ranges.getSelectedTabPosition());
            sheet.findViewById(R.id.reverse).performClick();
            assertEquals("120–71", ranges.getTabAt(0).getText().toString());
            assertEquals(0, ranges.getSelectedTabPosition());
        });
        screenshot("episodes-current-reversed");
    }

    @Test public void episodeListAcceptsRealVerticalSwipesAndLastEpisodeTap() {
        episodeCount = 120; multipleFlags = true;
        var value = ActivityScenario.<VideoActivity>launch(new Intent(App.get(), VideoActivity.class).putExtra("key", "fast").putExtra("id", "1").putExtra("name", "真实触摸选集")); scenario = value;
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> ready[0] = ((TextView) a.findViewById(R.id.more)).getText().toString().contains("120")); return ready[0]; });
        if ("true".equals(InstrumentationRegistry.getArguments().getString("qaFullscreen"))) {
            onActivity(value, a -> a.findViewById(R.id.detailFull).performClick());
            SystemClock.sleep(2500);
            onActivity(value, a -> assertEquals("Explicit fullscreen must stay landscape", android.content.res.Configuration.ORIENTATION_LANDSCAPE, a.getResources().getConfiguration().orientation));
        }
        onActivity(value, a -> a.findViewById(R.id.more).performClick());
        SystemClock.sleep(500);
        int[] bounds = new int[4];
        onActivity(value, a -> {
            View sheet = a.getSupportFragmentManager().findFragmentByTag("episode_picker").requireView();
            assertTrue(sheet.findViewById(R.id.sourceSection).isShown());
            RecyclerView list = sheet.findViewById(R.id.episode);
            int[] xy = new int[2]; list.getLocationOnScreen(xy);
            bounds[0] = xy[0]; bounds[1] = xy[1]; bounds[2] = list.getWidth(); bounds[3] = list.getHeight();
            assertTrue("Episode viewport must remain usable", list.getHeight() > 48 * a.getResources().getDisplayMetrics().density);
        });
        scrollEpisodesToEnd(value, bounds);
        screenshot("episodes-page-one-end");
        onActivity(value, a -> {
            RecyclerView list = a.getSupportFragmentManager().findFragmentByTag("episode_picker").requireView().findViewById(R.id.episode);
            assertNotNull("Real swipes must reach episode 50", list.getLayoutManager().findViewByPosition(49));
            TabLayout tabs = a.getSupportFragmentManager().findFragmentByTag("episode_picker").requireView().findViewById(R.id.ranges);
            tabs.selectTab(tabs.getTabAt(2));
        });
        SystemClock.sleep(250);
        scrollEpisodesToEnd(value, bounds);
        float[] point = new float[2];
        onActivity(value, a -> {
            RecyclerView list = a.getSupportFragmentManager().findFragmentByTag("episode_picker").requireView().findViewById(R.id.episode);
            View last = list.getLayoutManager().findViewByPosition(19);
            assertNotNull("Last episode is reachable", last);
            android.graphics.Rect visible = new android.graphics.Rect(); assertTrue(last.getGlobalVisibleRect(visible));
            assertEquals("Last episode is fully visible", last.getHeight(), visible.height());
            point[0] = visible.exactCenterX(); point[1] = visible.exactCenterY();
        });
        screenshot("episodes-touch-last");
        touch(point[0], point[1], point[1], 0);
        await(() -> { boolean[] selected = {false}; onActivity(value, a -> selected[0] = a.getEpisodePickerFlags().get(0).getEpisodes().stream().anyMatch(e -> e.isSelected() && e.getName().contains("120"))); return selected[0]; });
    }

    @Test public void castEntryAndSynopsisAreVisibleWithoutCoveringVideo() {
        var value = ActivityScenario.<VideoActivity>launch(new Intent(App.get(), VideoActivity.class).putExtra("key", "fast").putExtra("id", "1").putExtra("name", "投屏与详情")); scenario = value;
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> ready[0] = a.playbackHistory() != null && !a.playbackPlayer().isEmpty()); return ready[0]; });
        onActivity(value, a -> {
            int[] film = new int[2], toolbar = new int[2];
            a.findViewById(R.id.video).getLocationOnScreen(film);
            a.findViewById(R.id.detailCast).getLocationOnScreen(toolbar);
            assertTrue(a.findViewById(R.id.detailCast).isShown());
            assertTrue(toolbar[1] >= film[1] + a.findViewById(R.id.video).getHeight());
            a.findViewById(R.id.contentToggle).performClick();
            assertEquals(Integer.MAX_VALUE, ((TextView)a.findViewById(R.id.content)).getMaxLines());
            assertEquals(View.GONE, a.findViewById(R.id.actor).getVisibility());
            assertEquals(View.GONE, a.findViewById(R.id.director).getVisibility());
            a.findViewById(R.id.contentToggle).performClick();
            assertEquals(2, ((TextView)a.findViewById(R.id.content)).getMaxLines());
        });
        screenshot("playback-hierarchy");
        onActivity(value, a -> a.findViewById(R.id.detailCast).performClick());
        await(() -> { boolean[] open = {false}; onActivity(value, a -> open[0] = a.getSupportFragmentManager().getFragments().stream().anyMatch(f -> f instanceof com.fongmi.android.tv.ui.dialog.CastDialog && f.getView() != null)); return open[0]; });
        value.recreate();
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> ready[0] = a.playbackHistory() != null && !a.playbackPlayer().isEmpty() && a.getSupportFragmentManager().getFragments().stream().anyMatch(f -> f instanceof com.fongmi.android.tv.ui.dialog.CastDialog && f.getView() != null)); return ready[0]; });
        onActivity(value, a -> {
            var cast = (com.fongmi.android.tv.ui.dialog.CastDialog)a.getSupportFragmentManager().getFragments().stream().filter(f -> f instanceof com.fongmi.android.tv.ui.dialog.CastDialog).findFirst().orElseThrow();
            Device device = new Device(); device.setUuid("local-test-tv"); device.setName("客厅电视（测试）"); device.setType(0); device.setIp(server.url("/").toString().replaceAll("/$", ""));
            cast.onFind(device);
        });
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> {
            var f = a.getSupportFragmentManager().getFragments().stream().filter(v -> v instanceof com.fongmi.android.tv.ui.dialog.CastDialog).findFirst().orElseThrow();
            RecyclerView list = f.requireView().findViewById(R.id.recycler);
            assertTrue("Device list must have a visible viewport", list.getHeight() > 48 * a.getResources().getDisplayMetrics().density);
            var adapter = (com.fongmi.android.tv.ui.adapter.DeviceAdapter) list.getAdapter();
            int index = java.util.stream.IntStream.range(0, adapter.getItemCount()).filter(n -> adapter.getItem(n).getUuid().equals("local-test-tv")).findFirst().orElse(-1);
            ready[0] = index >= 0 && list.getLayoutManager().findViewByPosition(index) != null;
            assertFalse("Do not list this phone as a cast target", adapter.getItems().stream().anyMatch(d -> d.getUuid().equals(Device.get().getUuid())));
        }); return ready[0]; });
        screenshot("cast-discovery");
        float[] devicePoint = new float[2];
        onActivity(value, a -> {
            Fragment f = a.getSupportFragmentManager().getFragments().stream().filter(v -> v instanceof com.fongmi.android.tv.ui.dialog.CastDialog).findFirst().orElseThrow();
            RecyclerView list = f.requireView().findViewById(R.id.recycler);
            var adapter = (com.fongmi.android.tv.ui.adapter.DeviceAdapter)list.getAdapter();
            int index = java.util.stream.IntStream.range(0,adapter.getItemCount()).filter(n -> adapter.getItem(n).getUuid().equals("local-test-tv")).findFirst().orElseThrow();
            android.graphics.Rect bounds = new android.graphics.Rect(); list.getLayoutManager().findViewByPosition(index).getGlobalVisibleRect(bounds);
            devicePoint[0] = bounds.exactCenterX(); devicePoint[1] = bounds.exactCenterY();
        });
        touch(devicePoint[0], devicePoint[1], devicePoint[1], 0);
        await(() -> { boolean[] failed = {false}; onActivity(value, a -> {
            Fragment f = a.getSupportFragmentManager().getFragments().stream().filter(v -> v instanceof com.fongmi.android.tv.ui.dialog.CastDialog).findFirst().orElseThrow();
            failed[0] = ((TextView)f.requireView().findViewById(R.id.status)).getText().toString().contains("设备未响应");
        }); return failed[0]; });
        onActivity(value, a -> {
            var f = (com.fongmi.android.tv.ui.dialog.CastDialog)a.getSupportFragmentManager().getFragments().stream().filter(v -> v instanceof com.fongmi.android.tv.ui.dialog.CastDialog).findFirst().orElseThrow();
            f.onFind(Device.get());
        });
        SystemClock.sleep(150);
        assertTrue("Restored cast request must include current playback history", castHasHistory.get());
        onActivity(value, a -> {
            Fragment f = a.getSupportFragmentManager().getFragments().stream().filter(v -> v instanceof com.fongmi.android.tv.ui.dialog.CastDialog).findFirst().orElseThrow();
            assertTrue("Discovery callbacks must not erase a connection error", ((TextView)f.requireView().findViewById(R.id.status)).getText().toString().contains("设备未响应"));
            assertTrue(f.requireView().findViewById(R.id.refresh).isShown());
            assertTrue(f.requireView().findViewById(R.id.close).isShown());
            f.requireView().findViewById(R.id.close).performClick();
        });
    }

    @Test public void providerProgressReservesSpaceAndReleasesItAfterRemoval() {
        var value = ActivityScenario.<VideoActivity>launch(new Intent(App.get(), VideoActivity.class).putExtra("key", "fast").putExtra("id", "1")); scenario = value;
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> ready[0] = a.playbackHistory() != null); return ready[0]; });
        android.widget.LinearLayout[] panel = new android.widget.LinearLayout[1];
        int[] original = new int[1];
        onActivity(value, a -> {
            android.widget.FrameLayout content = a.findViewById(android.R.id.content);
            View page = a.findViewById(R.id.video).getRootView().findViewWithTag("port");
            original[0] = ((android.widget.FrameLayout.LayoutParams) page.getLayoutParams()).bottomMargin;
            panel[0] = new android.widget.LinearLayout(a); panel[0].setOrientation(1);
            TextView label = new TextView(a); label.setText("来源组件加载中"); panel[0].addView(label);
            panel[0].addView(new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal));
            android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(-1, -2); params.gravity = android.view.Gravity.TOP;
            content.addView(panel[0], params);
        });
        SystemClock.sleep(300);
        onActivity(value, a -> {
            View page = a.findViewById(R.id.video).getRootView().findViewWithTag("port");
            android.widget.FrameLayout content = a.findViewById(android.R.id.content);
            assertSame(content, panel[0].getParent());
            int[] xy = new int[2]; page.getLocationOnScreen(xy);
            assertTrue("Provider progress has its own reserved space", panel[0].getTop() >= xy[1] + page.getHeight() - page.getPaddingBottom());
            a.findViewById(R.id.detailFull).performClick();
        });
        SystemClock.sleep(700);
        onActivity(value, a -> {
            assertEquals("Fullscreen picture is never covered by provider progress", 0f, panel[0].getAlpha(), .01f);
            a.getOnBackPressedDispatcher().onBackPressed();
        });
        SystemClock.sleep(500);
        onActivity(value, a -> ((android.widget.FrameLayout)a.findViewById(android.R.id.content)).removeView(panel[0]));
        SystemClock.sleep(200);
        onActivity(value, a -> {
            View page = a.findViewById(R.id.video).getRootView().findViewWithTag("port");
            assertEquals(original[0], ((android.widget.FrameLayout.LayoutParams)page.getLayoutParams()).bottomMargin);
        });
    }

    private void scrollEpisodesToEnd(ActivityScenario<VideoActivity> value, int[] bounds) {
        for (int i = 0; i < 40; i++) {
            boolean[] more = {false};
            onActivity(value, a -> {
                RecyclerView list = a.getSupportFragmentManager().findFragmentByTag("episode_picker").requireView().findViewById(R.id.episode);
                more[0] = list.canScrollVertically(1);
            });
            if (!more[0]) return;
            touchSwipe(bounds[0] + bounds[2] / 2f, bounds[1] + bounds[3] * .85f, bounds[1] + bounds[3] * .15f);
        }
        fail("List must reach its end with real gestures");
    }
    private void touchSwipe(float x, float from, float to) { touch(x, from, to, 400); SystemClock.sleep(250); }
    private void touch(float x, float from, float to, int duration) {
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long down = SystemClock.uptimeMillis();
        int steps = Math.max(1, duration / 20);
        for (int i = 0; i <= steps + 1; i++) {
            int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN : i == steps + 1 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE;
            float y = from + (to - from) * Math.min(1, i / (float) steps);
            var event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(automation.injectInputEvent(event, true)); event.recycle();
            if (duration > 0) SystemClock.sleep(20);
        }
    }

    @Test public void playbackSettingsExposeSpeedAndAdvancedOptionsWithoutLeavingVideo() {
        var value = ActivityScenario.<VideoActivity>launch(new Intent(App.get(), VideoActivity.class).putExtra("key", "fast").putExtra("id", "1").putExtra("name", "播放设置体验")); scenario = value;
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> ready[0] = a.playbackHistory() != null && a.findViewById(R.id.detailFull).isEnabled()); return ready[0]; });
        onActivity(value, a -> a.findViewById(R.id.detailSettings).performClick());
        SystemClock.sleep(250);
        onActivity(value, a -> {
            Fragment dialog = a.getSupportFragmentManager().getFragments().stream().filter(f -> f instanceof com.fongmi.android.tv.ui.dialog.ControlDialog).findFirst().orElseThrow();
            View sheet = dialog.requireView();
            assertEquals(View.GONE, sheet.findViewById(R.id.advanced).getVisibility());
            android.view.ViewGroup presets = sheet.findViewById(R.id.speedPresets);
            presets.getChildAt(4).performClick(); assertEquals(2f, a.playbackPlayer().getSpeed(), 0.01f);
            assertTrue(presets.getChildAt(4).isSelected());
        });
        screenshot("playback-settings");
        onActivity(value, a -> {
            Fragment dialog = a.getSupportFragmentManager().getFragments().stream().filter(f -> f instanceof com.fongmi.android.tv.ui.dialog.ControlDialog).findFirst().orElseThrow();
            View sheet = dialog.requireView(); sheet.findViewById(R.id.advancedToggle).performClick();
            assertEquals(View.VISIBLE, sheet.findViewById(R.id.advanced).getVisibility());
            sheet.findViewById(R.id.resetSkip).performClick();
            assertEquals(0, a.playbackHistory().getOpening()); assertEquals(0, a.playbackHistory().getEnding());
            sheet.findViewById(R.id.close).performClick();
        });
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, value.getState());
    }

    @Test public void filterSheetRetainsChoicesWhenActivityIsRecreated() {
        var value = ActivityScenario.launch(HomeActivity.class); scenario = value;
        await(() -> { boolean[] ready = {false}; onActivity(value, a -> { RecyclerView types = a.findViewById(R.id.type); ready[0] = types != null && types.getAdapter() != null && types.getAdapter().getItemCount() > 1; }); return ready[0]; });
        onActivity(value, a -> {
            Fragment home = a.getSupportFragmentManager().getFragments().stream().filter(f -> f instanceof VodFragment).findFirst().orElseThrow();
            var filters = Filter.arrayFrom("[{\"key\":\"class\",\"name\":\"类型\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"剧情\",\"v\":\"drama\"}]}]");
            filters.get(0).setSelected("drama");
            com.fongmi.android.tv.ui.dialog.FilterDialog.create().filter(filters).show(home);
        });
        SystemClock.sleep(250); screenshot("filter-sheet");
        value.recreate();
        await(() -> { boolean[] restored = {false}; onActivity(value, a -> {
            Fragment home = a.getSupportFragmentManager().getFragments().stream().filter(f -> f instanceof VodFragment).findFirst().orElse(null);
            if (home == null) return;
            Fragment sheet = home.getChildFragmentManager().getFragments().stream().filter(f -> f instanceof com.fongmi.android.tv.ui.dialog.FilterDialog).findFirst().orElse(null);
            if (sheet == null || sheet.getView() == null) return;
            RecyclerView filters = sheet.requireView().findViewById(R.id.recycler);
            restored[0] = filters.getAdapter().getItemCount() == 1;
            if (restored[0]) {
                java.util.ArrayList<Filter> saved = sheet.requireArguments().getParcelableArrayList("filters");
                assertTrue(saved.get(0).getValue().get(1).isSelected());
                sheet.requireView().findViewById(R.id.close).performClick();
            }
        }); return restored[0]; });
    }

    private void screenshot(String name) {
        SystemClock.sleep(500); // Capture the committed frame, not the preceding tab.
        var bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        var directory = new java.io.File(App.get().getExternalFilesDir(null), "mobile-qa"); directory.mkdirs();
        try (var out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
