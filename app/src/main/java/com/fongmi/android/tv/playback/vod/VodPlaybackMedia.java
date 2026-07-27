package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.PlayerManager;
import java.util.Objects;
import java.util.Map;
import java.util.WeakHashMap;

public final class VodPlaybackMedia {

    private static final Map<PlayerManager, String> REQUEST_IDENTITIES = new WeakHashMap<>();
    private static final Map<PlayerManager, DanmakuMatchContext> MATCH_CONTEXTS = new WeakHashMap<>();

    public static MediaMetadata metadata(History history, Episode episode) {
        String title = history.getVodName();
        String name = episode.getName();
        boolean empty = name.isEmpty() || title.equals(name);
        String artist = empty ? "" : name;
        return PlayerManager.buildMetadata(title, artist, history.getVodPic());
    }

    public static void searchDanmaku(Result result, History history, Episode episode, PlayerManager player) {
        searchDanmaku(result, history, episode, episode.getIndex(), "", "", player);
    }

    public static void searchDanmaku(Result result, History history, Episode episode, int stableEpisodeIndex, PlayerManager player) {
        searchDanmaku(result, history, episode, stableEpisodeIndex, "", "", player);
    }

    public static void searchDanmaku(Result result, History history, Episode episode, int stableEpisodeIndex,
                                     String year, String type, PlayerManager player) {
        invalidate(player);
        String title = history.getVodName();
        String episodeName = episode.getName();
        String episodeQuery = resolveEpisodeQuery(episodeName, stableEpisodeIndex);
        synchronized (MATCH_CONTEXTS) {
            MATCH_CONTEXTS.put(player, new DanmakuMatchContext(title, year, type, episodeQuery));
        }
        // Keep the context even when automatic matching is disabled so manual search can still
        // rank animation/live-action editions correctly.
        if (!DanmakuApi.canSearch()) return;
        String identity = identityOf(history, episode, stableEpisodeIndex) + '\u001f'
                + Objects.toString(year, "") + '\u001f' + Objects.toString(type, "");
        synchronized (REQUEST_IDENTITIES) {
            REQUEST_IDENTITIES.put(player, identity);
        }
        DanmakuApi.search(title, year, type, episodeQuery, danmaku -> {
            if (!isCurrentIdentity(player, identity)) return;
            if (!matchesCurrent(player, title, episodeName)) return;
            // Remount the source for every verified episode. A previous episode or an early
            // renderer failure may have left the same URI cached as selected even though no
            // comments were attached to the current PlayerView.
            player.setDanmaku(danmaku, true);
        });
    }

    /** Invalidates both the network request and the renderer source before a media transition. */
    public static void invalidate(PlayerManager player) {
        DanmakuApi.cancel();
        if (player == null) return;
        synchronized (REQUEST_IDENTITIES) {
            REQUEST_IDENTITIES.remove(player);
        }
        synchronized (MATCH_CONTEXTS) {
            MATCH_CONTEXTS.remove(player);
        }
        player.setDanmaku(Danmaku.empty());
    }

    public static DanmakuMatchContext contextOf(PlayerManager player) {
        if (player == null) return DanmakuMatchContext.empty();
        synchronized (MATCH_CONTEXTS) {
            DanmakuMatchContext context = MATCH_CONTEXTS.get(player);
            return context == null ? DanmakuMatchContext.empty() : context;
        }
    }

    static String identityOf(History history, Episode episode, int stableEpisodeIndex) {
        String url = episode == null ? "" : episode.getUrl();
        return Objects.toString(history == null ? null : history.getKey(), "") + '\u001f'
                + Objects.toString(history == null ? null : history.getVodFlag(), "") + '\u001f'
                + Objects.toString(episode == null ? null : episode.getName(), "") + '\u001f'
                + stableEpisodeIndex + '\u001f' + url.length() + ':' + url.hashCode();
    }

    private static boolean isCurrentIdentity(PlayerManager player, String identity) {
        synchronized (REQUEST_IDENTITIES) {
            return Objects.equals(identity, REQUEST_IDENTITIES.get(player));
        }
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
