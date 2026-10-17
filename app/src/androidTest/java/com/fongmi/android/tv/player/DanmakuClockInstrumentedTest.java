package com.fongmi.android.tv.player;

import static org.junit.Assert.*;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.ui.danmaku.DanmakuController;
import androidx.media3.ui.danmaku.DanmakuView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DanmakuClockInstrumentedTest {
    @Test public void loadedWhileSuppressedStartsOnIsPlayingChange() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FakePlayer state = new FakePlayer();
            DanmakuController controller = new DanmakuController();
            DanmakuView view = new DanmakuView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            var sync = new com.fongmi.android.tv.player.danmaku.DanmakuPlaybackSync(view);
            try {
                controller.setView(view); controller.setPlayer(state.player); sync.bind(state.player);
                controller.setItems(List.of(new androidx.media3.ui.danmaku.Danmaku("真实时间轴", 601000)));
                assertFalse(view.isStarted());
                state.playing = true;
                for (Player.Listener listener : List.copyOf(state.listeners)) listener.onIsPlayingChanged(true);
                assertTrue("Loaded comments must start when audio focus / playback suppression ends", view.isStarted());
                state.playing = false;
                for (Player.Listener listener : List.copyOf(state.listeners)) listener.onIsPlayingChanged(false);
                assertTrue("Suppression must pause the overlay too", view.isPaused());
                state.playing = true;
                for (Player.Listener listener : List.copyOf(state.listeners)) listener.onIsPlayingChanged(true);
                assertFalse("Focus recovery must resume loaded comments", view.isPaused());
                controller.setEnabled(false);
                sync.sync();
                assertFalse("A user-hidden overlay must stay hidden", view.isStarted());
                sync.bind(null);
                assertFalse(state.listeners.contains(sync));
            } finally { sync.bind(null); controller.release(); view.release(); }
        });
    }
    private static class FakePlayer {
        boolean playing;
        final List<Player.Listener> listeners = new ArrayList<>();
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p,m,args) -> {
            return switch (m.getName()) {
                case "addListener" -> { listeners.add((Player.Listener)args[0]); yield null; }
                case "removeListener" -> { listeners.remove(args[0]); yield null; }
                case "isPlaying" -> playing;
                case "getCurrentPosition" -> 600000L;
                case "getPlaybackParameters" -> PlaybackParameters.DEFAULT;
                case "getApplicationLooper" -> android.os.Looper.getMainLooper();
                case "equals" -> p == args[0];
                case "hashCode" -> System.identityHashCode(p);
                case "toString" -> "PlaybackSuppressionFixture";
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }
}
