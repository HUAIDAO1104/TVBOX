package com.fongmi.android.tv.search;

import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.search.SearchSourcePreference;
import com.fongmi.android.tv.setting.Setting;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Opt-in network benchmark. Not part of deterministic regression tests. */
@RunWith(AndroidJUnit4.class)
public class RealSearchLatencyInstrumentedTest {
    @Test public void measureCurrentConfiguredSources() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("realSearch")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var home = instrumentation.startActivitySync(new Intent(App.get(), HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            long deadline = SystemClock.elapsedRealtime() + 45000;
            while (VodConfig.get().getSites().size() < 5 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            System.out.println("REAL_SEARCH configured=" + VodConfig.get().getSites().size());
            for (String keyword : List.of("庆余年", "庆余年第二季")) {
                long start = SystemClock.elapsedRealtime();
                AtomicLong first = new AtomicLong();
                AtomicLong visible = new AtomicLong();
                AtomicBoolean done = new AtomicBoolean();
                var page = (CollectActivity) instrumentation.startActivitySync(new Intent(App.get(), CollectActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("keyword", keyword));
                try {
                    long entered = SystemClock.elapsedRealtime() - start;
                    AtomicReference<SiteViewModel> model = new AtomicReference<>();
                    instrumentation.runOnMainSync(() -> {
                        try {
                            var field = CollectActivity.class.getDeclaredField("viewModel"); field.setAccessible(true);
                            model.set((SiteViewModel) field.get(page));
                            androidx.recyclerview.widget.RecyclerView grid = page.findViewById(com.fongmi.android.tv.R.id.resultRecycler);
                            grid.getViewTreeObserver().addOnDrawListener(() -> {
                                if (grid.getAdapter().getItemCount() > 0) visible.compareAndSet(0, SystemClock.elapsedRealtime() - start);
                            });
                            model.get().getAggregateSearch().observe(page, snapshot -> {
                                int count = snapshot.results().stream().mapToInt(r -> r.getList().size()).sum();
                                if (count > 0 && first.compareAndSet(0, SystemClock.elapsedRealtime() - start))
                                    System.out.println("REAL_SEARCH first keyword=" + keyword + " ms=" + first.get() + " rows=" + count);
                            });
                            model.get().getSearchProgress().observe(page, progress -> {
                                if (progress.session() > 0 && !progress.running()) done.set(true);
                            });
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    while (!done.get() && SystemClock.elapsedRealtime() - start < 125000) Thread.sleep(50);
                    instrumentation.runOnMainSync(() -> {
                        System.out.println("REAL_SEARCH summary keyword=" + keyword + " enterMs=" + entered + " firstMs=" + first.get()
                                + " visibleMs=" + visible.get() + " finishMs=" + (SystemClock.elapsedRealtime() - start) + " progress=" + model.get().getSearchProgress().getValue());
                        for (var site : VodConfig.get().getSites()) {
                            var state = model.get().sourceState(site);
                            if (state != null) System.out.println("REAL_SEARCH source=" + site.getName() + " state=" + state);
                        }
                        model.get().stopSearch();
                    });
                } finally { instrumentation.runOnMainSync(page::finish); instrumentation.waitForIdleSync(); }
            }
        } finally { instrumentation.runOnMainSync(home::finish); }
    }
}
