package com.fongmi.android.tv.history;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.setting.Setting;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class PairedHistoryTest {
    private static final String A = "fixture@@@series";
    private static final String B = "alternate@@@series";
    private static final String SOURCE = "https://fixture.invalid/paired-history-v2";

    private History row(String key, long ms) {
        History h = new History();
        h.setKey(key);
        h.setVodName("同名剧");
        h.setVodRemarks("第8集");
        h.setDuration(3600000L);
        h.setPosition(ms);
        h.setCreateTime(System.currentTimeMillis());
        return h.save(VodConfig.getCid());
    }

    @Test public void threeWayPreservesRewindAndDeletion() {
        var push = HistoryReconcile.Decision.PUSH;
        var pull = HistoryReconcile.Decision.PULL;
        assertEquals(push, HistoryReconcile.decide("120s", "30s", "120s"));
        assertEquals(pull, HistoryReconcile.decide("120s", "120s", "-"));
        assertEquals(HistoryReconcile.Decision.CONFLICT, HistoryReconcile.decide("120s", "-", "160s"));
        assertEquals(HistoryReconcile.Decision.SAME, HistoryReconcile.decide("-", "s1", "s1"));
    }

    // The host runs each stage on its corresponding emulator with isolated test data.
    @Test public void acrossDevices() throws Exception {
        String stage = InstrumentationRegistry.getArguments().getString("historyStage", "");
        assumeTrue(!stage.isEmpty());
        if (stage.equals("tvStart")) {
            Config.find("https://fixture.invalid/tv-only", 0);
        }
        VodConfig.get().config(Config.find(SOURCE, "History fixture", 0));
        Files.write(new File(App.get().getFilesDir(), "history-test-cid").toPath(), Integer.toString(VodConfig.getCid()).getBytes());
        File input = new File(App.get().getFilesDir(), "history-test-peer");
        if (stage.equals("tvStart")) {
            HistoryPeer.forget();
            History.delete(VodConfig.getCid());
            row(B, 10000);
            String code = HistoryPeer.pairingCode();
            assertFalse(code.isEmpty());
            Files.write(input.toPath(), code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (stage.equals("tvAdvance")) {
            assertEquals(80000, History.find(HistoryKey.local(A, VodConfig.getCid())).getPosition());
            assertEquals(10000, History.find(HistoryKey.local(B, VodConfig.getCid())).getPosition());
            row(A, 20000);
            return;
        }
        if (stage.equals("tvConflict")) { row(A, 50000); return; }
        if (stage.equals("tvCheck")) {
            assertEquals(35000, History.find(HistoryKey.local(A, VodConfig.getCid())).getPosition());
            assertNull(History.find(HistoryKey.local(B, VodConfig.getCid())));
            return;
        }
        var results = new ArrayBlockingQueue<String>(1);
        if (stage.equals("phonePair")) {
            HistoryLink.forget();
            History.delete(VodConfig.getCid());
            row(A, 80000);
            HistoryLink.pair(new String(Files.readAllBytes(input.toPath())).trim(), results::add);
        } else if (stage.equals("phonePull")) {
            HistoryLink.sync(results::add);
        } else if (stage.equals("phoneResolve")) {
            row(A, 35000);
            History.find(HistoryKey.local(B, VodConfig.getCid())).delete();
            HistoryLink.sync(results::add);
            String report = results.poll(25, TimeUnit.SECONDS);
            assertTrue(report, report != null && report.contains("都有改动"));
            assertEquals(35000, History.find(HistoryKey.local(A, VodConfig.getCid())).getPosition());
            HistoryLink.resolve(HistoryLink.conflict(), true, results::add);
        } else throw new AssertionError(stage);
        String report = results.poll(25, TimeUnit.SECONDS);
        assertTrue(report, report != null && report.startsWith("同步完成"));
        if (stage.equals("phonePair")) assertEquals(10000, History.find(HistoryKey.local(B, VodConfig.getCid())).getPosition());
        if (stage.equals("phonePull")) assertEquals(20000, History.find(HistoryKey.local(A, VodConfig.getCid())).getPosition());
    }
}
