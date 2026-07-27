package com.fongmi.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.databinding.ViewDanmakuSearchEmbeddedBinding;
import com.fongmi.android.tv.player.media.PlaySpec;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PlayerManagerDanmakuInstrumentedTest {

    @Test
    public void explicitManualSelectionDetachesThenReloadsSameSource() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        List<Uri> changes = new ArrayList<>();
        Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            PlayerManager manager = null;
            try {
                manager = new PlayerManager(new Callback(changes));
                PlaySpec spec = PlaySpec.from(
                        "fixture",
                        "https://example.invalid/video.mp4",
                        null,
                        new MediaMetadata.Builder().setTitle("测试剧").setArtist("第3集").build());
                Field field = PlayerManager.class.getDeclaredField("spec");
                field.setAccessible(true);
                field.set(manager, spec);

                Danmaku source = Danmaku.from("https://example.invalid/episode-3.xml");
                manager.setDanmaku(source);
                changes.clear();

                manager.setDanmaku(source, true);

                assertEquals(2, changes.size());
                assertNull(changes.get(0));
                assertEquals(source.getUri(), changes.get(1));
                assertTrue(manager.haveDanmaku());
            } catch (Throwable throwable) {
                failure[0] = throwable;
            } finally {
                if (manager != null) manager.release();
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    @Test
    public void compactSearchButtonFitsCompleteLabel() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                var targetContext = instrumentation.getTargetContext();
                var context = new ContextThemeWrapper(targetContext, R.style.Theme_App);
                var binding = ViewDanmakuSearchEmbeddedBinding.inflate(LayoutInflater.from(context));
                int width = Math.round(targetContext.getResources().getDisplayMetrics().widthPixels * 0.35f);
                int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
                int heightSpec = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY);
                binding.getRoot().measure(widthSpec, heightSpec);
                binding.getRoot().layout(0, 0, width, 480);

                float textWidth = binding.submit.getPaint().measureText(binding.submit.getText().toString());
                int available = binding.submit.getMeasuredWidth()
                        - binding.submit.getCompoundPaddingLeft()
                        - binding.submit.getCompoundPaddingRight();
                assertTrue("complete search label must fit in the compact side sheet", textWidth <= available);
                assertEquals(targetContext.getString(R.string.danmaku_search_action),
                        binding.submit.getText().toString());
            } catch (Throwable throwable) {
                failure[0] = throwable;
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static final class Callback implements PlayerManager.Callback {

        private final List<Uri> changes;

        private Callback(List<Uri> changes) {
            this.changes = changes;
        }

        @Override public void onPrepare() {}
        @Override public void onTracksChanged() {}
        @Override public void onDecodeChanged() {}
        @Override public void onMediaOptionsChanged() {}
        @Override public void onError(String msg) {}
        @Override public void onPlayerRebuild(Player newPlayer) {}
        @Override public void onDanmakuSourceChanged(Uri uri) { changes.add(uri); }
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) {}
        @Override public void onDanmakuEnabledChanged(boolean enabled) {}
        @Override public void onDanmakuSent(String text) {}
    }
}
