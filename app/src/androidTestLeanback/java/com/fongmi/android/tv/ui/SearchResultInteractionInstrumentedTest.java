package com.fongmi.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SearchProgress;
import com.fongmi.android.tv.model.SearchSnapshot;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.adapter.SearchSourceFamilyAdapter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Real result-page layout and focus, using deterministic snapshots without provider requests. */
@RunWith(AndroidJUnit4.class)
public class SearchResultInteractionInstrumentedTest {
    private static final String ALL = "source-family-all";
    private ActivityScenario<CollectActivity> scenario;
    private List<Site> previousSites;
    private String previousKeywords;
    private String previousSelection;
    private String scope;
    private SearchProgress progress;
    private static volatile SearchSnapshot submitted;

    @Before
    public void setUp() throws Exception {
        submitted = null;
        previousSites = VodConfig.get().getSites();
        previousKeywords = Setting.getKeyword();
        String url = VodConfig.getUrl();
        Site home = VodConfig.get().getHome();
        scope = url != null && !url.isBlank() ? url.trim()
                : home == null ? "active-default" : "active-default:" + home.getKey();
        previousSelection = Setting.getSearchSources(scope);
        Field sites = VodConfig.class.getDeclaredField("sites");
        sites.setAccessible(true);
        sites.set(VodConfig.get(), List.of());
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        scenario = ActivityScenario.launch(new Intent(context, CollectActivity.class).putExtra("keyword", "庆余年"));
        idle();
        scenario.onActivity(activity -> {
            Set<String> families = field(activity, "enabledSearchFamilies");
            families.clear();
            families.addAll(List.of("甲源", "乙源", "丙源"));
            invoke(activity, "initializeFamilyCaches");
            invoke(activity, "updateSourceFilterLabel");
            progress = SearchProgress.started(100, 3).result(Result.empty())
                    .result(Result.empty()).result(Result.empty());
            publishProgress(activity, progress);
            snapshot(activity, List.of(
                    Result.list(List.of(vod("甲源", "1", "庆余年"), vod("甲源", "1", "庆余年"),
                            vod("甲源", "2", "庆余年预告"))),
                    Result.list(List.of(vod("乙源", "1", "庆余年花絮"))),
                    Result.list(List.of(vod("丙源", "1", "庆余年"), vod("丙源", "2", "庆余年")))));
        });
        idle();
    }

    @After
    public void tearDown() throws Exception {
        if (scenario != null) scenario.close();
        Field sites = VodConfig.class.getDeclaredField("sites");
        sites.setAccessible(true);
        sites.set(VodConfig.get(), previousSites);
        Setting.putKeyword(previousKeywords);
        Setting.putSearchSources(scope, previousSelection);
    }

    @Test
    public void focusSwitchesWithoutConfirmAndCountsMatchVisibleCardsIncludingEmptySource() {
        for (String id : List.of("甲源", "乙源", "丙源", ALL)) {
            focus(id);
            int expected = id.equals("甲源") ? 1 : id.equals("乙源") ? 0 : id.equals("丙源") ? 2 : 3;
            assertSelection(id, expected);
        }
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        java.io.File file = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "qa-search-results.png");
        try (java.io.OutputStream output = new java.io.FileOutputStream(file)) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    @Test
    public void rapidFocusMovementKeepsLatestSourceAndConfirmDoesNotChangeIt() {
        scenario.onActivity(activity -> {
            row(activity, "甲源").requestFocus();
            row(activity, "丙源").requestFocus();
        });
        idle();
        assertSelection("丙源", 2);
        scenario.onActivity(activity -> row(activity, "丙源").performClick());
        idle();
        assertSelection("丙源", 2);
    }

    @Test
    public void countsStayConsistentAfterStreamingDuplicatesAndSnapshotReplacement() {
        focus("甲源");
        scenario.onActivity(activity -> {
            SearchSnapshot current = field(activity, "latestSnapshot");
            var results = new java.util.ArrayList<>(current.results());
            results.add(Result.list(List.of(vod("甲源", "3", "庆余年第二季"),
                    vod("甲源", "3", "庆余年第二季"), vod("甲源", "4", "庆余年预告"))));
            snapshot(activity, results);
        });
        idle();
        assertSelection("甲源", 2);
        focus(ALL);
        assertSelection(ALL, 4);
        scenario.onActivity(activity -> snapshot(activity,
                List.of(Result.list(List.of(vod("甲源", "5", "庆余年"))))));
        idle();
        assertSelection(ALL, 1);
        focus("丙源");
        assertSelection("丙源", 0);
    }

    @Test
    public void searchCompletionKeepsSelectedSourceAndItsGridInSync() {
        focus("丙源");
        scenario.onActivity(activity -> {
            publishProgress(activity, SearchProgress.started(100, 3));
            ActivityCollectBinding binding = field(activity, "binding");
            binding.sourceFilter.requestFocus();
            publishProgress(activity, progress);
        });
        idle();
        assertSelection("丙源", 2);
    }

    @Test
    public void firstStreamingResultsAreVisibleWithoutWaitingForTheBatchDelay() {
        scenario.onActivity(activity -> {
            try {
                Field latest = CollectActivity.class.getDeclaredField("latestSnapshot");
                latest.setAccessible(true); latest.set(activity, null);
            } catch (Exception e) { throw new AssertionError(e); }
            publishProgress(activity, SearchProgress.started(100, 3));
            snapshot(activity, List.of(Result.list(List.of(vod("甲源", "first", "庆余年")))));
            assertTrue("First batch must be submitted immediately", field(activity, "pendingSnapshot") == null);
        });
        idle();
        scenario.onActivity(activity -> {
            ActivityCollectBinding binding = field(activity, "binding");
            assertEquals(1, binding.resultRecycler.getAdapter().getItemCount());
        });
    }

    @Test
    public void largeSourceSnapshotKeepsAllCardsAndReportsProcessingTime() {
        scenario.onActivity(activity -> {
            Set<String> families = field(activity, "enabledSearchFamilies");
            families.clear();
            var results = new java.util.ArrayList<Result>();
            for (int source = 0; source < 40; source++) {
                String family = String.format(java.util.Locale.ROOT, "测试源%03d", source);
                families.add(family);
                Site site = new Site();
                site.setKey(family);
                site.setName(family);
                var items = new java.util.ArrayList<Vod>();
                for (int item = 1; item <= 20; item++) {
                    Vod vod = vod(family, String.valueOf(item), "庆余年第" + item + "季");
                    vod.setSite(site);
                    items.add(vod);
                }
                results.add(Result.list(items));
            }
            long start = android.os.SystemClock.elapsedRealtime();
            long cpu = android.os.Debug.threadCpuTimeNanos();
            snapshot(activity, results);
            long elapsed = android.os.SystemClock.elapsedRealtime() - start;
            long cpuMs = (android.os.Debug.threadCpuTimeNanos() - cpu) / 1_000_000;
            System.out.println("SEARCH_BENCHMARK sources=40 rows=800 mainWallMs=" + elapsed + " mainCpuMs=" + cpuMs);
        });
        idle();
        scenario.onActivity(activity -> {
            Set<String> families = field(activity, "enabledSearchFamilies");
            ActivityCollectBinding binding = field(activity, "binding");
            assertEquals(800, binding.resultRecycler.getAdapter().getItemCount());
            SearchSourceFamilyAdapter adapter = (SearchSourceFamilyAdapter) binding.sourceFamilyRecycler.getAdapter();
            assertEquals("800", adapter.get(adapter.positionOf(ALL)).status());
            for (String family : families) assertEquals("20", adapter.get(adapter.positionOf(family)).status());
        });
    }

    @Test public void largeSnapshotsKeepMainLoopResponsive() throws Exception {
        for (int rows : new int[]{500, 1000, 3000}) {
            java.util.concurrent.atomic.AtomicBoolean sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.List<Long> delays = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Thread sampler = new Thread(() -> {
                while (sampling.get()) {
                    long start = android.os.SystemClock.elapsedRealtime();
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {});
                    delays.add(android.os.SystemClock.elapsedRealtime() - start);
                    android.os.SystemClock.sleep(16);
                }
            });
            long start = android.os.SystemClock.elapsedRealtime();
            sampler.start();
            scenario.onActivity(activity -> {
                Set<String> families = field(activity, "enabledSearchFamilies");
                families.clear();
                var results = new java.util.ArrayList<Result>();
                int added = 0;
                for (int source = 0; source < 40 && added < rows; source++) {
                    String name = "压力源" + source;
                    Site site = new Site(); site.setKey(name); site.setName(name);
                    String family = com.fongmi.android.tv.ui.search.SearchSourcePreference.id(site);
                    families.add(family);
                    var items = new java.util.ArrayList<Vod>();
                    for (int item = 1; item <= (rows + 39) / 40 && added < rows; item++, added++) {
                        Vod vod = vod(name, String.valueOf(item), "庆余年第" + item + "季");
                        vod.setSite(site); items.add(vod);
                    }
                    results.add(Result.list(items));
                }
                snapshot(activity, results);
            });
            idle();
            sampling.set(false); sampler.join(2000);
            scenario.onActivity(activity -> {
                ActivityCollectBinding binding = field(activity, "binding");
                assertEquals(rows, binding.resultRecycler.getAdapter().getItemCount());
            });
            delays.sort(Long::compareTo);
            long p95 = delays.get(Math.min(delays.size() - 1, (int) (delays.size() * 0.95)));
            long max = delays.get(delays.size() - 1);
            System.out.println("SEARCH_RESPONSIVENESS rows=" + rows + " totalMs="
                    + (android.os.SystemClock.elapsedRealtime() - start) + " samples=" + delays.size() + " mainP95Ms=" + p95 + " mainMaxMs=" + max);
            assertTrue("Main thread should remain responsive during indexing", max < 1000);
        }
    }

    private void focus(String id) {
        scenario.onActivity(activity -> assertTrue(row(activity, id).requestFocus()));
        idle();
    }

    private void assertSelection(String id, int count) {
        scenario.onActivity(activity -> {
            ActivityCollectBinding binding = field(activity, "binding");
            SearchSourceFamilyAdapter adapter = (SearchSourceFamilyAdapter) binding.sourceFamilyRecycler.getAdapter();
            assertEquals(id, field(activity, "activeSourceFamily"));
            assertTrue(adapter.get(adapter.positionOf(id)).active());
            assertEquals(String.valueOf(count), adapter.get(adapter.positionOf(id)).status());
            assertEquals(count, binding.resultRecycler.getAdapter().getItemCount());
            assertTrue(row(activity, id).hasFocus());
            assertEquals(count == 0 ? View.INVISIBLE : View.VISIBLE, binding.resultRecycler.getVisibility());
            assertEquals(count == 0 ? View.VISIBLE : View.GONE, binding.statePanel.getVisibility());
        });
    }

    private static View row(CollectActivity activity, String id) {
        ActivityCollectBinding binding = field(activity, "binding");
        SearchSourceFamilyAdapter adapter = (SearchSourceFamilyAdapter) binding.sourceFamilyRecycler.getAdapter();
        RecyclerView.ViewHolder holder = binding.sourceFamilyRecycler.findViewHolderForAdapterPosition(adapter.positionOf(id));
        assertNotNull(holder);
        return holder.itemView;
    }

    private static void snapshot(CollectActivity activity, List<Result> results) {
        submitted = new SearchSnapshot(100, results);
        invoke(activity, "applySearchSnapshot", new Class<?>[]{SearchSnapshot.class}, submitted);
    }

    private static void publishProgress(CollectActivity activity, SearchProgress value) {
        SiteViewModel model = new ViewModelProvider(activity).get(SiteViewModel.class);
        ((MutableLiveData<SearchProgress>) model.getSearchProgress()).setValue(value);
    }

    private static Vod vod(String family, String id, String name) {
        Site site = new Site();
        site.setKey(family);
        site.setName(family);
        Vod vod = new Vod();
        vod.setId(id);
        vod.setName(name);
        vod.setSite(site);
        return vod;
    }

    private void idle() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 12000;
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ActivityCollectBinding binding = field(activity, "binding");
                com.fongmi.android.tv.ui.search.SearchAggregator index = field(activity, "aggregator");
                done.set((submitted == null || field(activity, "latestSnapshot") == submitted)
                        && binding.resultRecycler.getAdapter().getItemCount() == index.workCount());
            });
            if (done.get()) break;
            android.os.SystemClock.sleep(20);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        assertTrue("Background index must finish", done.get());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(CollectActivity activity, String name) {
        try {
            Field field = CollectActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(activity);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void invoke(CollectActivity activity, String name) {
        invoke(activity, name, new Class<?>[0]);
    }

    private static void invoke(CollectActivity activity, String name, Class<?>[] types, Object... args) {
        try {
            Method method = CollectActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
