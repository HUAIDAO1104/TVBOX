package com.fongmi.android.tv.player.exo;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.util.concurrent.atomic.AtomicLong;

/** Counts this player's reads only. Background preload traffic must not keep startup alive. */
final class PlaybackTransferProgress implements TransferListener {
    private final AtomicLong bytes = new AtomicLong();

    long bytes() { return bytes.get(); }

    @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
        if (count > 0) bytes.addAndGet(count);
    }
    @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {}
    @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) {}
    @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {}
}
