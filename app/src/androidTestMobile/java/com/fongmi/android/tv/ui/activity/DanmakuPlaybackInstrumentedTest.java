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
public class DanmakuPlaybackInstrumentedTest {
    @Test public void manualSelectionRendersAndPipNeverPersistsTemporaryHiding() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        boolean show = DanmakuSetting.isShow(), auto = DanmakuSetting.isAuto();
        int engine = PlayerSetting.getEngine(), background = PlayerSetting.getBackground();
        File video = new File(context.getFilesDir(), "danmaku-playback-test.mp4");
        File xml = new File(context.getFilesDir(), "danmaku-playback-test.xml");
        try (var input = instrumentation.getContext().getAssets().open("playback-buffering.mp4")) {
            Files.write(video.toPath(), input.readAllBytes());
        }
        StringBuilder comments = new StringBuilder("<i>");
        for (int i = 0; i < 48; i++) comments.append("<d p=\"").append(i / 4.0)
                .append(",1,25,16777215,0,0,test,0\">VISIBLE ").append(i).append("</d>");
        Files.writeString(xml.toPath(), comments.append("</i>").toString());
        Danmaku item = Danmaku.from(Uri.fromFile(xml).toString());
        item.setName("弹幕回归测试 第1集");
        DanmakuSetting.putAuto(false);
        DanmakuSetting.putShow(false); // Reproduce the preference poisoned by the old PiP path.
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
        PlayerSetting.putBackground(0);
        Intent intent = new Intent(context, VideoActivity.class).putExtra("key", "push_agent")
                .putExtra("id", Uri.fromFile(video).toString()).putExtra("name", "弹幕回归测试");
        try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.service() != null && a.player().isPlaying(), "Exo playback");
            scenario.onActivity(a -> a.player().setRepeatOne(true));
            AtomicBoolean applied = new AtomicBoolean();
            scenario.onActivity(a -> VodPlaybackMedia.applyManualMatch(a.player(), "弹幕回归测试", item,
                    List.of(item), selected -> applied.set(true), () -> fail("Document failed")));
            await(scenario, a -> applied.get() && hasPixels(a.getPlayerView().getDanmakuView()), "manual comments drawn");
            scenario.onActivity(a -> {
                assertTrue(DanmakuSetting.isShow());
                assertTrue(a.player().isDanmakuEnabled());
                a.onPictureInPictureModeChanged(true, a.getResources().getConfiguration());
                assertFalse(a.getPlayerView().getDanmakuView().isDrawEnabled());
                assertTrue("PiP must not change the preference", DanmakuSetting.isShow());
                assertTrue("PiP must not change logical enablement", a.player().isDanmakuEnabled());
                a.onPictureInPictureModeChanged(false, a.getResources().getConfiguration());
            });
            await(scenario, a -> hasPixels(a.getPlayerView().getDanmakuView()), "PiP return renders");
            scenario.onActivity(a -> a.player().getPlayer().pause());
            await(scenario, a -> a.getPlayerView().getDanmakuView().isPaused(), "pause synchronized");
            scenario.onActivity(a -> { a.player().getPlayer().seekTo(4000); a.player().getPlayer().play(); });
            await(scenario, a -> !a.getPlayerView().getDanmakuView().isPaused()
                    && hasPixels(a.getPlayerView().getDanmakuView()), "seek and resume render");
            scenario.onActivity(a -> {
                a.player().setDanmakuEnabled(false);
                a.onPictureInPictureModeChanged(true, a.getResources().getConfiguration());
                a.onPictureInPictureModeChanged(false, a.getResources().getConfiguration());
                assertFalse("Respect an explicit off setting", DanmakuSetting.isShow());
                assertFalse(a.getPlayerView().getDanmakuView().isDrawEnabled());
            });
        } finally {
            DanmakuSetting.putShow(show); DanmakuSetting.putAuto(auto);
            PlayerSetting.putEngine(engine); PlayerSetting.putBackground(background);
            video.delete(); xml.delete();
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
        long deadline = SystemClock.elapsedRealtime() + 20000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(a -> ready.set(check.test(a)));
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        fail("Timed out: " + label);
    }
}
