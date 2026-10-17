package com.fongmi.android.tv.player.danmaku;

import androidx.media3.common.Player;
import androidx.media3.ui.danmaku.DanmakuView;

/** Keeps the overlay clock in step when playback suppression changes without a READY event. */
public final class DanmakuPlaybackSync implements Player.Listener {
    private final DanmakuView view;
    private Player player;

    public DanmakuPlaybackSync(DanmakuView view) {
        this.view = view;
    }

    public void bind(Player next) {
        if (player != next) {
            if (player != null) player.removeListener(this);
            player = next;
            if (player != null) player.addListener(this);
        }
        sync();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        sync();
    }

    public void sync() {
        if (player == null || !view.isDrawEnabled()) return;
        long position = player.getCurrentPosition();
        view.setPlaybackSpeed(player.getPlaybackParameters().speed);
        if (player.isPlaying()) {
            if (!view.isStarted()) view.start(position);
            else {
                view.syncPosition(position);
                if (view.isPaused()) view.resume();
            }
        } else if (view.isStarted()) {
            if (!view.isPaused()) view.pause();
            view.syncPosition(position);
        }
    }
}
