package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.PlayerManager;
import java.util.Objects;

public final class VodPlaybackMedia {

    public static MediaMetadata metadata(History history, Episode episode) {
        String title = history.getVodName();
        String name = episode.getName();
        boolean empty = name.isEmpty() || title.equals(name);
        String artist = empty ? "" : name;
        return PlayerManager.buildMetadata(title, artist, history.getVodPic());
    }

    public static void searchDanmaku(Result result, History history, Episode episode, PlayerManager player) {
        searchDanmaku(result, history, episode, episode.getIndex(), player);
    }

    public static void searchDanmaku(Result result, History history, Episode episode, int stableEpisodeIndex, PlayerManager player) {
        // Invalidate an older title's pending response even when automatic matching is disabled
        // or the new item lacks enough metadata to start another request.
        DanmakuApi.cancel();
        if (!DanmakuApi.canSearch()) return;
        String title = history.getVodName();
        String episodeName = episode.getName();
        String episodeQuery = resolveEpisodeQuery(episodeName, stableEpisodeIndex);
        DanmakuApi.search(title, episodeQuery, danmaku -> {
            if (!matchesCurrent(player, title, episodeName)) return;
            // Automatic matching is expected to choose the correct episode. Embedded spider
            // sources remain available as alternatives, but must never keep a stale/wrong source
            // selected after an exact 360 match was found.
            player.setDanmaku(danmaku);
        });
    }

    static String resolveEpisodeQuery(String episodeName, int stableEpisodeIndex) {
        Integer explicit = DanmakuMatch.episodeNumber(episodeName);
        if (explicit != null && explicit > 0) return String.valueOf(explicit);
        // Generic digit extraction also mistakes sizes, dates and resolutions for episode
        // numbers. Only explicit episode syntax or the stable pre-reversal order is safe.
        if (stableEpisodeIndex > 0) return String.valueOf(stableEpisodeIndex);
        return Objects.toString(episodeName, "").trim();
    }

    static boolean matchesMetadata(String expectedTitle, String expectedEpisode, CharSequence currentTitle, CharSequence currentEpisode) {
        return Objects.equals(clean(expectedTitle), clean(currentTitle))
                && Objects.equals(cleanEpisode(expectedTitle, expectedEpisode), clean(currentEpisode));
    }

    private static boolean matchesCurrent(PlayerManager player, String title, String episode) {
        MediaMetadata metadata = player == null ? null : player.getMetadata();
        return metadata != null && matchesMetadata(title, episode, metadata.title, metadata.artist);
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String cleanEpisode(String title, String episode) {
        String value = clean(episode);
        return value.isEmpty() || value.equals(clean(title)) ? "" : value;
    }
}
