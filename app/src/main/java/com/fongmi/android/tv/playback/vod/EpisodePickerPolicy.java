package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import java.util.List;

/** Small, deterministic selection rules shared by the TV and touch episode pickers. */
public final class EpisodePickerPolicy {

    private EpisodePickerPolicy() {
    }

    public static boolean shouldShowAction(int sourceCount, int episodeCount) {
        return sourceCount > 1 || episodeCount > 1;
    }

    public static int selectedFlagPosition(List<Flag> flags) {
        if (flags == null || flags.isEmpty()) return -1;
        for (int i = 0; i < flags.size(); i++) {
            Flag flag = flags.get(i);
            if (flag != null && flag.isSelected()) return i;
        }
        return 0;
    }

    public static int selectedEpisodePosition(List<Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) return -1;
        for (int i = 0; i < episodes.size(); i++) {
            Episode episode = episodes.get(i);
            if (episode != null && episode.isSelected()) return i;
        }
        return 0;
    }
}
