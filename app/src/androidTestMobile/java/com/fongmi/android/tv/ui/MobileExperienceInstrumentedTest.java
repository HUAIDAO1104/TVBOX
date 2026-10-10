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
    private final java.util.concurrent.CountDownLatch slowGate = new java.util.concurrent.CountDownLatch(1);
    private final AtomicInteger searchRequests = new AtomicInteger();
    private final AtomicInteger pageRequests = new AtomicInteger();

    @Before public void setup() throws Exception {
        previousConfig = VodConfig.get().getConfig(); previousSites = VodConfig.get().getSites(); previousHome = VodConfig.get().getHome(); keywords = Setting.getKeyword();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/config")) return json("{\"sites\":" + App.gson().toJson(sites) + "}");
                if (path.equals("/poster")) return new MockResponse().setResponseCode(404);
                if (request.getRequestUrl().queryParameter("play") != null) return json("{\"parse\":0,\"url\":\"" + server.url("/video.mp4") + "\"}");
                if (path.equals("/video.mp4")) {
                    try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("playback-buffering.mp4")) {
                        return new MockResponse().setHeader("Content-Type", "video/mp4").setBody(new okio.Buffer().readFrom(input));
                    } catch (Exception e) { return new MockResponse().setResponseCode(500); }
                }
                boolean search = request.getRequestUrl().queryParameter("wd") != null;
                if (search) searchRequests.incrementAndGet();
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
        String vod = "{\"vod_id\":\"" + id + "\",\"vod_name\":\"测试电影" + id + "\",\"vod_pic\":\"" + server.url("/poster") + "\",\"vod_remarks\":\"1080P\",\"vod_content\":\"这是一部用于验证手机版搜索、选集、播放和返回行为的测试影片。\",\"vod_play_from\":\"测试线路\",\"vod_play_url\":\"第1集$" + server.url("/video.mp4") + "\"}";
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
    private void screenshot(String name) {
        SystemClock.sleep(500); // Capture the committed frame, not the preceding tab.
        var bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        var directory = new java.io.File(App.get().getExternalFilesDir(null), "mobile-qa"); directory.mkdirs();
        try (var out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
