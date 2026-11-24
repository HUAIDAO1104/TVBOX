package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.history.HistoryKey;
import com.fongmi.android.tv.history.AccountSession;
import com.fongmi.android.tv.setting.PlayerSetting;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class PairedPlaybackTest {
    @Test public void openSameFileAcrossDevices() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        String stage = args.getString("historyStage", "");
        assumeTrue(stage.startsWith("media"));
        var inst = InstrumentationRegistry.getInstrumentation();
        var context = inst.getTargetContext();
        VodConfig.get().config(Config.find("https://fixture.invalid/paired-history-v2", 0).update());
        int cid = VodConfig.getCid();
        File file = new File(context.getFilesDir(), "paired-progress.mp4");
        try (var input = inst.getContext().getAssets().open("timeline-1163.mp4")) {
            Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String id = Uri.fromFile(file).toString();
        String key = HistoryKey.local("push_agent@@@" + id, cid);
        boolean first = stage.equals("mediaPhoneStart");
        boolean television = stage.equals("mediaTV");
        String account = args.getString("accountUser", "");
        if (!account.isEmpty() && (television || first)) {
            assertTrue(com.fongmi.android.tv.BuildConfig.ACCOUNT_SERVICE_URL.isEmpty());
            boolean existing = args.getString("accountFixture", "").equals("personal");
            String password = existing ? "fixture-local-accounts-test" : "test-" + account;
            AccountSession.login("http://127.0.0.1:18782", account, password, first && !existing);
        }
        if (!first && (!television || !account.isEmpty())) {
            // Coming back to the app must pull the TV rewind without pressing a sync button.
            try (var home = ActivityScenario.launch(HomeActivity.class)) {
                for (int i = 0; i < 180; i++) {
                    History received = History.find(key);
                    if (received != null && Math.abs(received.getPosition() - (television ? 68000 : 18000)) < 6000) break;
                    SystemClock.sleep(100);
                }
            }
        }
        History prior = History.find(key);
        if (!first) {
            assertNotNull("synced record exists before opening player", prior);
            assertTrue("persisted resume: " + prior.getPosition(), Math.abs(prior.getPosition() - (television ? 68000 : 18000)) < 6000);
        }
        var acknowledgements = context.getSharedPreferences(account.isEmpty() ? "paired_history" : "pdd_account", 0);
        String previousRevision = acknowledged(acknowledgements, HistoryKey.content(key));
        int engine = PlayerSetting.getEngine();
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
        try (ActivityScenario<VideoActivity> screen = ActivityScenario.launch(new Intent(context, VideoActivity.class)
                .putExtra("start_mode", "PLAY_NOW").putExtra("key", "push_agent").putExtra("id", id).putExtra("name", "双端续播片段"))) {
            long initial = position(screen, first ? 0 : prior.getPosition() - 2000);
            if (!first) assertTrue("actual playback starts near received position: " + initial, Math.abs(initial - prior.getPosition()) < 4500);
            if (first || television) {
                long target = first ? 68000 : 18000;
                screen.onActivity(a -> a.player().seekTo(target));
                SystemClock.sleep(650);
                long now = position(screen, target - 1000);
                assertTrue("seek reached", Math.abs(now - target) < 3500);
            }
        } finally { PlayerSetting.putEngine(engine); }
        long target = first ? 68000 : 18000;
        for (int i = 0; i < 60; i++) {
            History saved = History.find(key);
            if (saved != null && Math.abs(saved.getPosition() - target) < 6500) {
                if (first || (television && !account.isEmpty())) {
                    // Wait for the exit-triggered exchange to acknowledge the new video.
                    for (int j = 0; j < 180; j++) {
                        String ack = acknowledged(acknowledgements, HistoryKey.content(key));
                        if (!ack.isEmpty() && !previousRevision.equals(ack)) return;
                        SystemClock.sleep(100);
                    }
                    fail("phone exit did not sync its playback record");
                }
                return;
            }
            SystemClock.sleep(100);
        }
        fail("player exit did not persist the expected position");
    }

    private static String acknowledged(android.content.SharedPreferences prefs, String key) {
        String config = okio.ByteString.encodeUtf8("https://fixture.invalid/paired-history-v2").sha256().base64();
        String field = "common." + AccountSession.profile() + config;
        var index = com.google.gson.JsonParser.parseString(prefs.getString(field, "{}")).getAsJsonObject();
        return index.has(key) ? index.get(key).getAsString() : "";
    }

    private static long position(ActivityScenario<VideoActivity> screen, long minimum) {
        AtomicLong now = new AtomicLong(-1);
        for (int i = 0; i < 180; i++) {
            screen.onActivity(a -> { if (a.service() != null && a.player().isPlaying()) now.set(a.player().getPosition()); });
            if (now.get() >= minimum) return now.get();
            SystemClock.sleep(100);
        }
        throw new AssertionError("player did not start near " + minimum + ", last position " + now.get());
    }

}
