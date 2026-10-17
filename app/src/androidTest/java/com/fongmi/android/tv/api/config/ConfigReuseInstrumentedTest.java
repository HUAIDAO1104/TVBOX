package com.fongmi.android.tv.api.config;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ConfigReuseInstrumentedTest {
    private String savedUrl;
    @org.junit.Before public void saveConfig() { savedUrl = com.github.catvod.utils.Prefers.getString("config_0"); }
    @org.junit.After public void restoreConfig() {
        for (String suffix : new String[]{"one", "two", "changed"}) Config.delete("https://fixture.invalid/" + suffix, 0);
        com.github.catvod.utils.Prefers.put("config_0", savedUrl);
    }
    private static class Fixture extends BaseConfig {
        final AtomicInteger loads = new AtomicInteger();
        Fixture() { config = Config.create(0, "https://fixture.invalid/one", "fixture"); }
        protected String getTag() { return "config-reuse-fixture"; }
        protected Config defaultConfig() { return config; }
        protected void load(Config requested) { loads.incrementAndGet(); }
        protected boolean isLoaded() { return loads.get() > 0; }
        protected void postEvent() { }
    }
    private void load(Fixture config, boolean cached) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        Callback callback = new Callback() {
            public void success() { done.countDown(); }
            public void error(String message) { failure.set(message); done.countDown(); }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (cached) config.loadIfNeeded(callback); else config.load(callback);
        });
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(failure.get());
        // The success callback can run just before the background finally block returns.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        var field = BaseConfig.class.getDeclaredField("future"); field.setAccessible(true);
        while (!((Future<?>) field.get(config)).isDone() && System.nanoTime() < until) Thread.sleep(1);
    }
    @Test public void sameLoadedSourceReusesButExplicitRefreshAndNewSourceReload() throws Exception {
        Fixture fixture = new Fixture(); load(fixture, false); assertEquals(1, fixture.loads.get());
        load(fixture, true); assertEquals(1, fixture.loads.get());
        load(fixture, false); assertEquals(2, fixture.loads.get());
        fixture.config = Config.create(0, "https://fixture.invalid/two", "changed");
        load(fixture, true); assertEquals(3, fixture.loads.get());
    }
    @Test public void cachedSuccessCannotFollowAConfigSwitch() throws Exception {
        Fixture fixture = new Fixture(); load(fixture, false); AtomicInteger stale = new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            fixture.loadIfNeeded(new Callback() { public void success() { stale.incrementAndGet(); } });
            fixture.config = Config.create(0, "https://fixture.invalid/changed", "changed");
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, stale.get());
    }
}
