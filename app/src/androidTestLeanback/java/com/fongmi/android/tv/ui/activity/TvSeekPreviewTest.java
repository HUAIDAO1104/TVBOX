package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.*;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import androidx.media3.ui.PlayerSeekView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.R;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TvSeekPreviewTest {
    private void await(ActivityScenario<VideoActivity> scene, String state, Predicate<VideoActivity> condition) {
        long end = SystemClock.elapsedRealtime() + 15000;
        AtomicBoolean ok = new AtomicBoolean();
        do {
            scene.onActivity(a -> ok.set(condition.test(a)));
            if (ok.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < end);
        fail("Timed out: " + state);
    }

    @Test public void remoteHoldAndProgressBarBothShowDecodedFrames() throws Exception {
        var inst = InstrumentationRegistry.getInstrumentation();
        var context = inst.getTargetContext();
        File video = File.createTempFile("preview", ".mp4", context.getFilesDir());
        try (var in = inst.getContext().getAssets().open("seek-colors.mp4"); var out = new FileOutputStream(video)) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        }
        var intent = new Intent(context, VideoActivity.class).putExtra("key", "push_agent")
                .putExtra("id", Uri.fromFile(video).toString()).putExtra("name", "预览验收");
        try (ActivityScenario<VideoActivity> scene = ActivityScenario.launch(intent)) {
            await(scene, "real playback", a -> a.service() != null && a.player().isPlaying());
            scene.onActivity(a -> {
                a.findViewById(R.id.video).performClick();
                if (a.findViewById(R.id.control).getVisibility() == View.VISIBLE) a.onSingleTap();
                a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            });
            await(scene, "remote frame", a -> a.findViewById(R.id.scrubPreview).isShown()
                    && ((ImageView)a.findViewById(R.id.previewImage)).getDrawable() != null);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("captureSeek"))) {
                var screen = inst.getUiAutomation().takeScreenshot();
                try (var out = new FileOutputStream(new File(context.getExternalFilesDir(null), "tv-remote-preview.png"))) {
                    assertTrue(screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
                } finally { screen.recycle(); }
            }
            scene.onActivity(a -> {
                assertFalse(a.findViewById(R.id.center).isShown());
                assertColor(a, true);
                a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
            });
            await(scene, "release commits seek", a -> !a.findViewById(R.id.scrubPreview).isShown()
                    && a.player().getPosition() >= com.fongmi.android.tv.Constant.INTERVAL_SEEK);
            scene.onActivity(a -> a.onSingleTap());
            await(scene, "bar visible", a -> a.findViewById(R.id.seek).isShown());
            long down = SystemClock.uptimeMillis();
            scene.onActivity(a -> touch(a, MotionEvent.ACTION_DOWN, down));
            await(scene, "progress bar frame", a -> a.findViewById(R.id.scrubPreview).isShown()
                    && ((ImageView)a.findViewById(R.id.previewImage)).getDrawable() != null);
            scene.onActivity(a -> { assertColor(a, false); touch(a, MotionEvent.ACTION_UP, down); });
            await(scene, "bar released", a -> !a.findViewById(R.id.scrubPreview).isShown());
        } finally { video.delete(); }
    }
    // The fixture is red before 6 s, green at the remote's +10 s, blue at 25% of 80 s.
    private void assertColor(VideoActivity activity, boolean green) {
        var bitmap = ((android.graphics.drawable.BitmapDrawable)((ImageView) activity.findViewById(R.id.previewImage)).getDrawable()).getBitmap();
        String detail = " time=" + ((android.widget.TextView)activity.findViewById(R.id.previewTime)).getText()
                + " frame=" + bitmap.getWidth() + "x" + bitmap.getHeight();
        android.util.Log.i("TvSeekQA", "thumbnail " + detail + " colors=" + Integer.toHexString(bitmap.getPixel(20,20))
                + "/" + Integer.toHexString(bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2)));
        int color = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
        int g = android.graphics.Color.green(color), b = android.graphics.Color.blue(color);
        assertTrue("Expected " + (green ? "green" : "blue") + " thumbnail, got " + Integer.toHexString(color) + detail, green ? g > b + 80 : b > g + 80);
    }

    private void touch(VideoActivity activity, int action, long down) {
        View bar = (View) ((PlayerSeekView)activity.findViewById(R.id.seek)).getTimeBar();
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bar.getWidth() * .25f, bar.getHeight() / 2f, 0);
        try { assertTrue(bar.dispatchTouchEvent(event)); } finally { event.recycle(); }
    }
}
