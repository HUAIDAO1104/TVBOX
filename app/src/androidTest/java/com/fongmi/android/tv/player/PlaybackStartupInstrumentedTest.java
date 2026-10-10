package com.fongmi.android.tv.player;

import static org.junit.Assert.*;

import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Surface;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaybackStartupInstrumentedTest {
    // Generated test-only asset: ffmpeg -f lavfi -i testsrc2=size=320x180:rate=24 -t 12
    // -c:v libx264 -preset veryfast -pix_fmt yuv420p -b:v 500k -an -movflags +faststart playback-buffering.mp4
    @Test public void firstFrameWhileSlowlyBufferingDoesNotBecomeConnectionTimeout() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        byte[] video;
        try (var input = instrumentation.getContext().getAssets().open("playback-buffering.mp4")) {
            video = input.readAllBytes();
        }
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                // Deliver a decodable first frame immediately, then keep loading below bitrate.
                var stream = new ByteArrayInputStream(video) {
                    @Override public synchronized int read(byte[] bytes, int offset, int length) {
                        if (pos >= 16384) {
                            SystemClock.sleep(600);
                            length = Math.min(length, 512);
                        } else length = Math.min(length, 16384 - pos);
                        return super.read(bytes, offset, length);
                    }
                };
                return newFixedLengthResponse(Response.Status.OK, "video/mp4", stream, video.length);
            }
        };
        server.start();
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch firstFrame = new CountDownLatch(1), finished = new CountDownLatch(1);
        PlayerManager[] manager = new PlayerManager[1];
        SurfaceTexture[] texture = new SurfaceTexture[1];
        Surface[] surface = new Surface[1];
        int oldEngine = PlayerSetting.getEngine();
        long started = SystemClock.elapsedRealtime();
        try {
            instrumentation.runOnMainSync(() -> {
                PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
                manager[0] = new PlayerManager(new Callback() {
                    @Override public void onError(String msg) { errors.add(msg); finished.countDown(); }
                });
                texture[0] = new SurfaceTexture(0);
                surface[0] = new Surface(texture[0]);
                manager[0].getPlayer().setVideoSurface(surface[0]);
                manager[0].getPlayer().addListener(new Player.Listener() {
                    @Override public void onRenderedFirstFrame() { firstFrame.countDown(); }
                    @Override public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) finished.countDown();
                    }
                });
                manager[0].start(PlaySpec.from("slow-fixture", "http://127.0.0.1:" + server.getListeningPort()
                        + "/slow.mp4", null, MediaMetadata.EMPTY), 15000);
            });
            assertTrue("Fixture must render a frame before the old deadline", firstFrame.await(10, TimeUnit.SECONDS));
            assertTrue("Steady loading must eventually reach READY", finished.await(70, TimeUnit.SECONDS));
            assertTrue("First frame + progressing buffer must not trigger a timeout: " + errors, errors.isEmpty());
            long elapsed = SystemClock.elapsedRealtime() - started;
            assertTrue("Progress, not just a longer fixed deadline, must protect startup", elapsed > 30000);
            System.out.println("PLAYBACK_SLOW_STREAM readyMs=" + elapsed + " firstFrame=true errors=" + errors.size());
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (manager[0] != null) manager[0].release();
                if (surface[0] != null) surface[0].release();
                if (texture[0] != null) texture[0].release();
                PlayerSetting.putEngine(oldEngine);
            });
            server.stop();
        }
    }

    @Test public void synchronousReadyDoesNotLeaveAStartupTimerBehind() {
        onMain(() -> {
            try (var harness = new Harness()) {
                harness.engine.readyOnStart = true;
                harness.start();
                assertFalse(harness.timeout.isActive());
                harness.tick.run();
                assertTrue(harness.errors.isEmpty());
            }
        });
    }

    @Test public void stoppedClearedAndReleasedItemsCannotReportLateTimeout() {
        onMain(() -> {
            for (int action = 0; action < 5; action++) {
                try (var harness = new Harness()) {
                    harness.start();
                    harness.expire();
                    switch (action) {
                        case 0 -> harness.manager.stop();
                        case 1 -> harness.manager.clear();
                        case 2 -> harness.manager.clearMediaItems();
                        case 3 -> harness.manager.reset();
                        case 4 -> harness.manager.release();
                    }
                    harness.tick.run();
                    assertFalse(harness.timeout.isActive());
                    assertTrue("Lifecycle action " + action, harness.errors.isEmpty());
                }
            }
        });
    }

    @Test public void freshTransferAtDeadlinePreventsFalseTimeout() {
        onMain(() -> {
            try (var harness = new Harness()) {
                harness.start();
                harness.expire();
                harness.engine.bytes += 4096;
                harness.tick.run();
                assertTrue(harness.timeout.isActive());
                assertTrue(harness.errors.isEmpty());
                assertFalse(harness.engine.stopped);
            }
        });
    }

    @Test public void firstFrameFollowedByARealStallStillFailsExactlyOnce() {
        onMain(() -> {
            try (var harness = new Harness()) {
                harness.start();
                harness.listener.onRenderedFirstFrame();
                assertTrue("Rendering a frame alone cannot disable stall protection", harness.timeout.isActive());
                harness.expire();
                harness.tick.run();
                harness.tick.run();
                assertEquals(1, harness.errors.size());
                assertTrue(harness.engine.stopped);
            }
        });
    }

    @Test public void userPauseDoesNotCountAsConnectionFailure() {
        onMain(() -> {
            try (var harness = new Harness()) {
                harness.start();
                harness.engine.playWhenReady = false;
                harness.expire();
                harness.tick.run();
                harness.engine.playWhenReady = true;
                harness.listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
                harness.tick.run();
                assertTrue(harness.timeout.isActive());
                assertTrue(harness.errors.isEmpty());
            }
        });
    }

    private interface CheckedRunnable { void run() throws Exception; }

    private static void onMain(CheckedRunnable work) {
        Throwable[] error = new Throwable[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { work.run(); } catch (Throwable e) { error[0] = e; }
        });
        if (error[0] != null) throw new AssertionError(error[0]);
    }

    private static Object field(PlayerManager manager, String name) throws Exception {
        var field = PlayerManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(manager);
    }

    private static class Harness implements AutoCloseable {
        final List<String> errors = new java.util.ArrayList<>();
        final int oldEngine = PlayerSetting.getEngine();
        final PlayerManager manager;
        final FakeEngine engine;
        final Player.Listener listener;
        final PlaybackStartupTimeout timeout;
        final Runnable tick;

        Harness() throws Exception {
            PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
            manager = new PlayerManager(new Callback() {
                @Override public void onError(String message) { errors.add(message); }
            });
            listener = (Player.Listener) field(manager, "listener");
            manager.getPlayer().removeListener(listener);
            ((com.fongmi.android.tv.player.engine.PlayerEngine) field(manager, "engine")).release();
            engine = new FakeEngine(listener);
            var engineField = PlayerManager.class.getDeclaredField("engine"); engineField.setAccessible(true); engineField.set(manager, engine);
            var playerField = PlayerManager.class.getDeclaredField("player"); playerField.setAccessible(true); playerField.set(manager, engine.player);
            timeout = (PlaybackStartupTimeout) field(manager, "startupTimeout");
            tick = (Runnable) field(manager, "runnable");
        }
        void start() { manager.start(PlaySpec.from("fixture", "https://example.invalid/video.mp4", null, MediaMetadata.EMPTY), 15000); }
        void expire() { timeout.start(SystemClock.elapsedRealtime() - 31000, 30000, engine.bytes); }
        @Override public void close() { manager.release(); PlayerSetting.putEngine(oldEngine); }
    }

    private static class FakeEngine implements com.fongmi.android.tv.player.engine.PlayerEngine {
        boolean readyOnStart, stopped;
        boolean playWhenReady = true;
        int state = Player.STATE_BUFFERING;
        long bytes;
        final Player.Listener listener;
        final Player player = (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, (proxy, method, arguments) -> {
            return switch (method.getName()) {
                case "getPlaybackState" -> state;
                case "getTotalBufferedDuration" -> 0L;
                case "getPlayWhenReady" -> playWhenReady;
                default -> null;
            };
        });
        FakeEngine(Player.Listener listener) { this.listener = listener; }
        @Override public Type getType() { return Type.EXO; }
        @Override public Player getPlayer() { return player; }
        @Override public long getTransferredBytes() { return bytes; }
        @Override public void release() {}
        @Override public Player rebuild() { return player; }
        @Override public boolean setDecode(int decode) { return false; }
        @Override public void start(PlaySpec spec, long position) {
            state = readyOnStart ? Player.STATE_READY : Player.STATE_BUFFERING;
            listener.onPlaybackStateChanged(state);
        }
        @Override public void stop() { stopped = true; state = Player.STATE_IDLE; }
        @Override public boolean isLive() { return false; }
        @Override public boolean isVod() { return true; }
        @Override public String getErrorMessage(androidx.media3.common.PlaybackException error) { return error.getMessage(); }
        @Override public ErrorAction handleError(androidx.media3.common.PlaybackException error) { return ErrorAction.FATAL; }
    }

    static class Callback implements PlayerManager.Callback {
        @Override public void onPrepare() {}
        @Override public void onTracksChanged() {}
        @Override public void onDecodeChanged() {}
        @Override public void onMediaOptionsChanged() {}
        @Override public void onError(String msg) {}
        @Override public void onPlayerRebuild(Player player) {}
        @Override public void onDanmakuSourceChanged(Uri uri) {}
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) {}
        @Override public void onDanmakuEnabledChanged(boolean enabled) {}
        @Override public void onDanmakuSent(String text) {}
        @Override public void onDanmakuStatusChanged(com.fongmi.android.tv.player.danmaku.DanmakuStatus status) {}
    }
}
