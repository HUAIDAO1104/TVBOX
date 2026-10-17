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
                    onActivity(scenario, a -> { a.player().setRepeatOne(true); a.player().getPlayer().seekTo(0); a.player().getPlayer().play(); });
                    await(scenario, a -> a.service() != null && a.player().isPlaying(), "Exo playback");
                    onActivity(scenario, a -> a.player().setRepeatOne(true));
                    AtomicBoolean applied = new AtomicBoolean(), failed = new AtomicBoolean();
                    onActivity(scenario, a -> VodPlaybackMedia.applyManualMatch(a.player(), sample[0], item, List.of(item),
                            selected -> applied.set(true), () -> failed.set(true)));
                    await(scenario, a -> applied.get() || failed.get(), "native document", 60000);
                    assertFalse(sample[0], failed.get());
                    await(scenario, a -> hasPixels(a.getPlayerView().getDanmakuView()), "real comments drawn");
                    var bitmap = instrumentation.getUiAutomation().takeScreenshot();
                    File screenshot = new File(context.getExternalFilesDir(null), "575-visible-" + sample[2] + ".png");
                    try (var out = new java.io.FileOutputStream(screenshot)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                    bitmap.recycle();
                    android.util.Log.i("Danmaku575QA", "visible pixels confirmed " + sample[2]);
                    onActivity(scenario, a -> a.player().getPlayer().pause());
                }
            }
        } finally {
            DanmakuSetting.putShow(show); DanmakuSetting.putAuto(auto);
            PlayerSetting.putEngine(engine); PlayerSetting.putBackground(background); video.delete();
        }
    }

    // Generated black, silent 2-hour fixture: ffmpeg -f lavfi -i color=c=black:s=320x180:r=1:d=7200
    // -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 1 -movflags +faststart playback-timeline.mp4
    @Test public void mangoAutomaticCommentsSurviveLongTimelineAndViewLifecycle() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        boolean show = DanmakuSetting.isShow(), auto = DanmakuSetting.isAuto();
        int engine = PlayerSetting.getEngine(), background = PlayerSetting.getBackground();
        File video = new File(context.getFilesDir(), "timeline-" + System.nanoTime() + ".mp4");
        try (var input = instrumentation.getContext().getAssets().open("playback-timeline.mp4")) {
            Files.write(video.toPath(), input.readAllBytes());
        }
        DanmakuSetting.putAuto(true); DanmakuSetting.putShow(true);
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO); PlayerSetting.putBackground(0);
        Intent intent = new Intent(context, VideoActivity.class).putExtra("key", "push_agent")
                .putExtra("id", Uri.fromFile(video).toString()).putExtra("name", "披荆斩棘2026");
        try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.service() != null && a.player().isPlaying(), "Exo long playback");
            onActivity(scenario, a -> {
                var metadata = new androidx.media3.common.MediaMetadata.Builder().setTitle("披荆斩棘2026").setArtist("第10期上").build();
                a.player().setMetadata(metadata);
            });
            await(scenario, a -> a.player().isPlaying(), "new playback identity");
            onActivity(scenario, a -> {
                a.player().getPlayer().seekTo(600000);
                var history = new com.fongmi.android.tv.bean.History();
                history.setVodName("披荆斩棘2026"); history.setKey("timeline-test");
                VodPlaybackMedia.searchDanmaku(new com.fongmi.android.tv.bean.Result(), history,
                        com.fongmi.android.tv.bean.Episode.create("第10期上", "timeline"), 1, "2026", "综艺", a.player());
            });
            await(scenario, a -> a.player().haveDanmaku(), "automatic native match", 90000);
            for (long position : new long[]{600000, 2400000, 60000}) {
                onActivity(scenario, a -> a.player().getPlayer().seekTo(position));
                awaitScreenComments(scenario, "comments at " + position);
                onActivity(scenario, a -> android.util.Log.i("Danmaku577QA", "pixels position=" + a.player().getPosition() + " source=" + a.player().getSelectedDanmakuUri()));
            }
            onActivity(scenario, a -> a.player().getPlayer().pause());
            SystemClock.sleep(500);
            onActivity(scenario, a -> a.player().getPlayer().play());
            awaitScreenComments(scenario, "comments after pause/resume");
            onActivity(scenario, a -> {
                try { var method = VideoActivity.class.getDeclaredMethod("enterFullscreen"); method.setAccessible(true); method.invoke(a); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            awaitScreenComments(scenario, "comments after fullscreen");
            onActivity(scenario, a -> a.player().getPlayer().pause());
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            onActivity(scenario, a -> a.player().getPlayer().play());
            awaitScreenComments(scenario, "comments after background return");
            var bitmap = instrumentation.getUiAutomation().takeScreenshot();
            try (var out = new java.io.FileOutputStream(new File(context.getExternalFilesDir(null), "577-timeline.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
            onActivity(scenario, a -> a.player().getPlayer().pause());
        } finally {
            DanmakuSetting.putShow(show); DanmakuSetting.putAuto(auto);
            PlayerSetting.putEngine(engine); PlayerSetting.putBackground(background); video.delete();
        }
    }

    private static void onActivity(ActivityScenario<VideoActivity> scenario, ActivityScenario.ActivityAction<VideoActivity> action) {
        // Continuous danmaku frames need not make the UI queue idle for a test action.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> scenario.onActivity(action));
    }

    private static void awaitScreenComments(ActivityScenario<VideoActivity> scenario, String label) {
        // Read the composed screen, never call DanmakuView.draw(): forcing a draw can hide a
        // broken render loop. The fixture is black and silent, so moving bright pixels are text.
        onActivity(scenario, a -> {
            try { var method = VideoActivity.class.getDeclaredMethod("hideControl"); method.setAccessible(true); method.invoke(a); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(700); // Ignore window/fullscreen transition frames.
        long deadline = SystemClock.elapsedRealtime() + 15000;
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        while (SystemClock.elapsedRealtime() < deadline) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            onActivity(scenario, a -> a.getPlayerView().getDanmakuView().getGlobalVisibleRect(bounds));
            Bitmap first = automation.takeScreenshot(); SystemClock.sleep(450);
            Bitmap second = automation.takeScreenshot(); int changed = 0, brightFirst = 0, brightSecond = 0, sampled = 0;
            if (first != null && second != null && first.getWidth() == second.getWidth() && first.getHeight() == second.getHeight()) {
                for (int y = Math.max(0, bounds.top); y < Math.min(first.getHeight(), bounds.top + bounds.height()/2); y += 2)
                    for (int x = Math.max(0, bounds.left + bounds.width()/10); x < Math.min(first.getWidth(), bounds.right - bounds.width()/10); x += 2) {
                        int a = first.getPixel(x,y), b = second.getPixel(x,y);
                        sampled++;
                        if ((a & 255) > 170) brightFirst++;
                        if ((b & 255) > 170) brightSecond++;
                        if ((a & 255) > 170 && ((a >> 8) & 255) > 170 && ((a >> 16) & 255) > 170 && (b & 255) < 60) changed++;
                    }
            }
            if (first != null) first.recycle(); if (second != null) second.recycle();
            if (changed > 30 && brightFirst > 30 && brightSecond > 30 && brightFirst < sampled / 3 && brightSecond < sampled / 3) { android.util.Log.i("Danmaku577QA", label + " composed moving pixels=" + changed); return; }
        }
        await(scenario, a -> false, label + " on composed screen", 1);
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
            onActivity(scenario, a -> ready.set(check.test(a)));
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        onActivity(scenario, a -> {
            var v = a.getPlayerView().getDanmakuView(); var c = a.getPlayerView().getDanmakuController();
            android.util.Log.e("Danmaku577QA", label + " position=" + a.player().getPosition() + " playing=" + a.player().isPlaying()
                    + " enabled=" + a.player().isDanmakuEnabled() + " source=" + a.player().getSelectedDanmakuUri()
                    + " view=" + v.getWidth() + "x" + v.getHeight() + " visible=" + v.isShown() + " attached=" + v.isAttachedToWindow()
                    + " started=" + v.isStarted() + " paused=" + v.isPaused() + " draw=" + v.isDrawEnabled());
            for (Object object : new Object[]{c, v}) for (String name : new String[]{"sortedItems", "pool", "loadedFrom", "loadedTo", "activeItems", "player", "dataSourceUri", "clock", "lastActivationPositionMs"}) {
                try { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); Object value = f.get(object);
                    String info = value instanceof Object[] ? "count=" + ((Object[])value).length : value instanceof java.util.Map ? "count=" + ((java.util.Map)value).size() : value instanceof java.util.List ? "count=" + ((java.util.List)value).size() : String.valueOf(value);
                    android.util.Log.e("Danmaku577QA", object.getClass().getSimpleName() + "." + name + "=" + info);
                } catch (ReflectiveOperationException ignored) { }
            }
        });
        var shot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (var out = new java.io.FileOutputStream(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "577-failed.png"))) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); }
        catch (Exception ignored) {} finally { shot.recycle(); }
        fail("Timed out: " + label);
    }
}
