package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.*;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.setting.PlayerSetting;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.Test;

public class ChapterSkipInstrumentedTest {
    @Test public void skipsEmbeddedOpeningAndCreditsButPlaysBonusScene() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        File file = new File(context.getFilesDir(), "chapters-" + System.nanoTime() + ".mkv");
        try (var stream = instrumentation.getContext().getAssets().open("skip-chapters.mkv")) {
            Files.copy(stream, file.toPath());
        }
        int engine = PlayerSetting.getEngine();
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
        try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(new Intent(context, VideoActivity.class)
                .putExtra("key", "push_agent").putExtra("id", Uri.fromFile(file).toString()).putExtra("name", file.getName()))) {
            await(scenario, a -> a.service() != null && a.player().isPlaying(), "start", 15000);
            await(scenario, a -> a.player().getPosition() >= 12000, "skip opening", 4500);
            scenario.onActivity(a -> a.player().seekTo(101000));
            await(scenario, a -> a.player().getPosition() >= 110000 && a.player().isPlaying(), "reach bonus", 4500);
            scenario.onActivity(a -> a.player().seekTo(2000));
            SystemClock.sleep(1500);
            scenario.onActivity(a -> assertTrue("explicit rewind must play intro", a.player().getPosition() < 10000));
        } finally {
            PlayerSetting.putEngine(engine);
            file.delete();
        }
    }

    private static void await(ActivityScenario<VideoActivity> scenario, Predicate<VideoActivity> check,
                              String message, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(a -> ready.set(check.test(a)));
            if (ready.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
        scenario.onActivity(a -> fail(message + " position=" + a.player().getPosition() + " chapters=" + a.player().getCurrentMediaChapters().stream().map(c -> c.label + "@" + c.timeUs).toList()));
    }
}
