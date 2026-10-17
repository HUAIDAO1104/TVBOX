package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.ui.danmaku.DanmakuView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real Activity/service/Exo/overlay path, including actual drawn pixels. */
@RunWith(AndroidJUnit4.class)
public class PlatformPlaybackInstrumentedTest {
    @Test public void liveCommonPlatformsRenderActualPixelsOnBothFlavors() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        boolean show = DanmakuSetting.isShow(), auto = DanmakuSetting.isAuto();
        int engine = PlayerSetting.getEngine(), background = PlayerSetting.getBackground();
        // A fresh media identity avoids restoring an earlier test run's end position.
        File video = new File(context.getFilesDir(), "danmaku-live-playback-" + System.nanoTime() + ".mp4");
        try (var input = instrumentation.getContext().getAssets().open("playback-buffering.mp4")) {
            Files.write(video.toPath(), input.readAllBytes());
        }
        DanmakuSetting.putAuto(false); DanmakuSetting.putShow(false);
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO); PlayerSetting.putBackground(0);
        try {
            for (String[] sample : List.of(new String[]{"披荆斩棘2026(2026)【综艺】from mango - 【mgtv】 第1期上", "mango-danmaku://video/896231/24566777", "mango-upper"},
                    new String[]{"披荆斩棘2026(2026)【综艺】from mango - 【mgtv】 第1期下", "mango-danmaku://video/896231/24567586", "mango-lower"},
                    new String[]{"披荆斩棘2026(2026)【综艺】from mango - 【mgtv】 加更版第1期", "mango-danmaku://video/896231/24579459", "mango-bonus"},
                    new String[]{"搜救(2022)【电影】from youku - 【youku】 正片", "https://v.youku.com/v_show/id_XNTkxNDY2Nzg2MA==.html", "youku-movie"},
                    new String[]{"间谍过家家 from bilibili - 第13话", "https://www.bilibili.com/bangumi/play/ep691614", "bili-episode"})) {
                Intent intent = new Intent(context, VideoActivity.class).putExtra("key", "push_agent")
                        .putExtra("id", Uri.fromFile(video).toString()).putExtra("name", "弹幕真实文档验收");
                Danmaku item = Danmaku.from(sample[1]); item.setName(sample[0]);
                try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(intent)) {
                    await(scenario, a -> a.service() != null, "playback service");
                    scenario.onActivity(a -> { a.player().setRepeatOne(true); a.player().getPlayer().seekTo(0); a.player().getPlayer().play(); });
                    await(scenario, a -> a.service() != null && a.player().isPlaying(), "Exo playback");
                    scenario.onActivity(a -> a.player().setRepeatOne(true));
                    AtomicBoolean applied = new AtomicBoolean(), failed = new AtomicBoolean();
                    scenario.onActivity(a -> VodPlaybackMedia.applyManualMatch(a.player(), sample[0], item, List.of(item),
                            selected -> applied.set(true), () -> failed.set(true)));
                    await(scenario, a -> applied.get() || failed.get(), "native document", 60000);
                    assertFalse(sample[0], failed.get());
                    await(scenario, a -> hasPixels(a.getPlayerView().getDanmakuView()), "real comments drawn");
                    var bitmap = instrumentation.getUiAutomation().takeScreenshot();
                    File screenshot = new File(context.getExternalFilesDir(null), "575-visible-" + sample[2] + ".png");
                    try (var out = new java.io.FileOutputStream(screenshot)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                    bitmap.recycle();
                    android.util.Log.i("Danmaku575QA", "visible pixels confirmed " + sample[2]);
                }
            }
        } finally {
            DanmakuSetting.putShow(show); DanmakuSetting.putAuto(auto);
            PlayerSetting.putEngine(engine); PlayerSetting.putBackground(background); video.delete();
        }
    }

    private static boolean hasPixels(DanmakuView view) {
        if (!view.isDrawEnabled() || !view.isStarted() || view.getWidth() == 0 || view.getHeight() == 0) return false;
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        int visible = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 3)
            for (int x = 0; x < bitmap.getWidth(); x += 3)
                if ((bitmap.getPixel(x, y) >>> 24) > 32) visible++;
        bitmap.recycle();
        return visible > 20;
    }

    private static void await(ActivityScenario<VideoActivity> scenario, Predicate<VideoActivity> check,
                              String label) {
        await(scenario, check, label, 20000);
    }
    private static void await(ActivityScenario<VideoActivity> scenario, Predicate<VideoActivity> check,
                              String label, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(a -> ready.set(check.test(a)));
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        fail("Timed out: " + label);
    }
}
