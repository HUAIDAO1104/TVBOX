package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
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
        DanmakuMatchContext matchContext = new DanmakuMatchContext(title, year, type, episodeQuery);
        synchronized (MATCH_CONTEXTS) {
            MATCH_CONTEXTS.put(player, matchContext);
        }
        // Keep the context even when automatic matching is disabled so manual search can still
        // rank animation/live-action editions correctly.
        if (!DanmakuApi.canSearch()) {
            player.notifyDanmakuStatus(DanmakuStatus.failed(DanmakuStatus.Failure.DISABLED));
            return;
        }
        DanmakuManualMatchStore.Selection preferred = DanmakuManualMatchStore.get().find(matchContext);
        String identity = identityOf(history, episode, stableEpisodeIndex) + '\u001f'
                + Objects.toString(year, "") + '\u001f' + Objects.toString(type, "") + '\u001f'
                + (preferred == null ? "" : preferred.selectedName());
        synchronized (REQUEST_IDENTITIES) {
            REQUEST_IDENTITIES.put(player, identity);
        }
        java.util.function.Consumer<Danmaku> apply = danmaku -> {
            if (!isCurrentIdentity(player, identity)) return;
            if (!matchesCurrent(player, title, episodeName)) return;
            // Remount the source for every verified episode. A previous episode or an early
            // renderer failure may have left the same URI cached as selected even though no
            // comments were attached to the current PlayerView.
            player.setDanmaku(danmaku, true);
        };
        DanmakuApi.SearchCallback callback = new DanmakuApi.SearchCallback() {
            @Override
            public void onProgress(int percent) {
                if (isCurrentIdentity(player, identity)) {
                    player.notifyDanmakuStatus(DanmakuStatus.downloading(percent));
                }
            }

            @Override
            public void onFound(Danmaku danmaku, java.util.List<Danmaku> catalogue) {
                if (!isCurrentIdentity(player, identity)) return;
                String query = preferred == null ? DanmakuQuery.from(title).searchTitle() : preferred.query();
                DanmakuManualMatchStore.get().remember(matchContext, query, danmaku, catalogue);
                apply.accept(danmaku);
            }

            @Override
            public void onFailure(DanmakuApi.SearchFailure failure) {
                if (!isCurrentIdentity(player, identity)) return;
                DanmakuStatus.Failure reason = switch (failure) {
                    case NO_MATCH -> DanmakuStatus.Failure.NO_MATCH;
                    case NETWORK -> DanmakuStatus.Failure.NETWORK;
                    case INVALID_RESPONSE -> DanmakuStatus.Failure.INVALID_RESPONSE;
                    case DOWNLOAD -> DanmakuStatus.Failure.DOWNLOAD;
                };
                player.notifyDanmakuStatus(DanmakuStatus.failed(reason));
            }
        };
        if (preferred == null) {
            player.notifyDanmakuStatus(DanmakuStatus.matching());
            DanmakuApi.searchDetailed(title, year, type, episodeQuery, callback);
        } else {
            player.notifyDanmakuStatus(DanmakuStatus.restoring());
            Danmaku cached = preferred.episode(episodeQuery);
            DanmakuApi.SearchCallback preferredCallback = new DanmakuApi.SearchCallback() {
                @Override
                public void onProgress(int percent) {
                    callback.onProgress(percent);
                }

                @Override
                public void onFound(Danmaku item, java.util.List<Danmaku> catalogue) {
                    callback.onFound(item, catalogue);
                }

                @Override
                public void onFailure(DanmakuApi.SearchFailure failure) {
                    if (!isCurrentIdentity(player, identity)) return;
                    // A saved manual/automatic catalogue can disappear or change platform IDs.
                    // Keep it as the first choice, then relax only the provider/platform while
                    // preserving strict title, season, year, type and episode matching.
                    player.notifyDanmakuStatus(DanmakuStatus.matching());
                    DanmakuApi.searchDetailed(title, year, type, episodeQuery, callback);
                }
            };
            Runnable resolveFresh = () -> DanmakuApi.searchPreferredDetailed(preferred.query(),
                    year, type, episodeQuery, preferred.selectedName(), preferred.sourceKey(),
                    preferredCallback);
            if (cached == null) {
                resolveFresh.run();
            } else {
                // A saved episode URL is an identity hint, not proof that the provider still
                // serves its document. Verify it before mounting; on 404/5xx/empty XML, discard
                // only that episode and transparently resolve the same confirmed season through
                // the remaining providers.
                DanmakuDocumentCache.load(cached.getUri(), new DanmakuDocumentCache.Listener() {
                    @Override
                    public void onProgress(android.net.Uri source, int percent) {
                        if (isCurrentIdentity(player, identity)) {
                            player.notifyDanmakuStatus(DanmakuStatus.downloading(percent));
                        }
                    }

                    @Override
                    public void onReady(android.net.Uri source, android.net.Uri local) {
                        if (isCurrentIdentity(player, identity)) apply.accept(cached);
                    }

                    @Override
                    public void onFailure(android.net.Uri source, java.io.IOException error) {
                        if (!isCurrentIdentity(player, identity)) return;
                        DanmakuDocumentCache.invalidate(source);
                        DanmakuManualMatchStore.get().forgetEpisode(matchContext);
                        player.notifyDanmakuStatus(DanmakuStatus.matching());
                        resolveFresh.run();
                    }
                });
            }
        }
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
        player.notifyDanmakuStatus(DanmakuStatus.hidden());
    }

    public static DanmakuMatchContext contextOf(PlayerManager player) {
        if (player == null) return DanmakuMatchContext.empty();
        synchronized (MATCH_CONTEXTS) {
            DanmakuMatchContext context = MATCH_CONTEXTS.get(player);
            return context == null ? DanmakuMatchContext.empty() : context;
        }
    }

    /** Records a user-confirmed work/provider mapping for this and subsequent episodes. */
    public static void rememberManualMatch(PlayerManager player, String query, Danmaku selected) {
        DanmakuManualMatchStore.get().remember(contextOf(player), query, selected);
    }

    /** Stores the full response behind a manual row so following episodes need no catalogue search. */
    public static void rememberManualMatch(PlayerManager player, String query, Danmaku selected,
                                           java.util.List<Danmaku> catalogue) {
        DanmakuManualMatchStore.get().remember(contextOf(player), query, selected, catalogue);
    }

    public static Danmaku preferredEpisode(PlayerManager player) {
        DanmakuMatchContext context = contextOf(player);
        DanmakuManualMatchStore.Selection selection = DanmakuManualMatchStore.get().find(context);
        return selection == null ? null : selection.episode(context.getEpisode());
    }

    /** Warms the next confirmed episode after the current document is mounted successfully. */
    public static void prefetchNextDanmaku(PlayerManager player) {
        DanmakuMatchContext context = contextOf(player);
        Integer current = DanmakuMatch.episodeNumber(context.getEpisode());
        if (current == null || current <= 0) return;
        DanmakuManualMatchStore.Selection selection = DanmakuManualMatchStore.get().find(context);
        if (selection == null) return;
        Danmaku next = selection.episode(String.valueOf(current + 1));
        if (next != null && next.getUri() != null) DanmakuDocumentCache.prefetch(next.getUri());
    }

    /** Returns the last successful manual query for the current work/season, when available. */
    public static String preferredSearchQuery(PlayerManager player) {
        DanmakuManualMatchStore.Selection selection = DanmakuManualMatchStore.get().find(contextOf(player));
        return selection == null ? "" : selection.query();
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
