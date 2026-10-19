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
    private static final Map<PlayerManager, Integer> MANUAL_REQUESTS = new WeakHashMap<>();
    private static final Map<PlayerManager, DanmakuDocumentCache.Ticket> MANUAL_DOWNLOADS = new WeakHashMap<>();
    private static final Map<PlayerManager, DanmakuDocumentCache.Ticket> RESTORE_DOWNLOADS = new WeakHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger MANUAL_SEQUENCE = new java.util.concurrent.atomic.AtomicInteger();

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
        String episodeQuery = resolveEpisodeQuery(episodeName, stableEpisodeIndex, type);
        // Preserve the source's complete label for AI and manual recovery. Reducing an
        // unfamiliar "EP01 Part B" to "1" destroys the evidence needed to identify its half.
        DanmakuMatchContext matchContext = new DanmakuMatchContext(title, year, type,
                episodeName.isEmpty() ? episodeQuery : episodeName, episodeQuery);
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
                String query = preferred == null || preferred.isVideoLink()
                        ? DanmakuQuery.from(title).searchTitle() : preferred.query();
                DanmakuManualMatchStore.get().remember(matchContext, query, danmaku, catalogue);
                apply.accept(danmaku);
            }

            @Override
            public void onFailure(DanmakuApi.SearchFailure failure) {
                if (!isCurrentIdentity(player, identity)) return;
                DanmakuStatus.Failure reason = switch (failure) {
                    case NO_MATCH -> DanmakuStatus.Failure.NO_MATCH;
                    case NETWORK -> DanmakuStatus.Failure.NETWORK;
                    case ACCESS_DENIED -> DanmakuStatus.Failure.ACCESS_DENIED;
                    case INVALID_RESPONSE -> DanmakuStatus.Failure.INVALID_RESPONSE;
                    case DOWNLOAD -> DanmakuStatus.Failure.DOWNLOAD;
                };
                if (failure != DanmakuApi.SearchFailure.DOWNLOAD
                        && failure != DanmakuApi.SearchFailure.ACCESS_DENIED
                        && DanmakuAiMatcher.search(matchContext, new DanmakuAiMatcher.Callback() {
                    @Override public void onFound(String query, Danmaku item, java.util.List<Danmaku> catalogue) {
                        if (!isCurrentIdentity(player, identity)) return;
                        DanmakuManualMatchStore.get().remember(matchContext, query, item, catalogue);
                        apply.accept(item);
                    }
                    @Override public void onFailure() {
                        onFailure(DanmakuStatus.Failure.NO_MATCH);
                    }
                    @Override public void onFailure(DanmakuStatus.Failure aiFailure) {
                        if (isCurrentIdentity(player, identity)) player.notifyDanmakuStatus(DanmakuStatus.failed(aiFailure));
                    }
                    @Override public void onProgress(int percent) {
                        if (isCurrentIdentity(player, identity)) player.notifyDanmakuStatus(DanmakuStatus.downloading(percent));
                    }
                })) {
                    player.notifyDanmakuStatus(DanmakuStatus.aiMatching());
                    return;
                }
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
            Runnable resolveFresh = () -> {
                if (preferred.isVideoLink()) DanmakuApi.searchDetailed(title, year, type, episodeQuery, callback);
                else DanmakuApi.searchPreferredDetailed(preferred.query(), year, type, episodeQuery,
                        preferred.selectedName(), preferred.sourceKey(), preferredCallback);
            };
            if (cached == null) {
                resolveFresh.run();
            } else {
                // A saved episode URL is an identity hint, not proof that the provider still
                // serves its document. Verify it before mounting; on 404/5xx/empty XML, discard
                // only that episode and transparently resolve the same confirmed season through
                // the remaining providers.
                DanmakuDocumentCache.Ticket restore = DanmakuDocumentCache.load(cached.getUri(), new DanmakuDocumentCache.Listener() {
                    @Override
                    public void onProgress(android.net.Uri source, int percent) {
                        if (isCurrentIdentity(player, identity)) {
                            player.notifyDanmakuStatus(DanmakuStatus.downloading(percent));
                        }
                    }

                    @Override
                    public void onReady(android.net.Uri source, android.net.Uri local) {
                        if (!isCurrentIdentity(player, identity)) return;
                        synchronized (RESTORE_DOWNLOADS) { RESTORE_DOWNLOADS.remove(player); }
                        apply.accept(cached);
                    }

                    @Override
                    public void onFailure(android.net.Uri source, java.io.IOException error) {
                        if (!isCurrentIdentity(player, identity)) return;
                        synchronized (RESTORE_DOWNLOADS) { RESTORE_DOWNLOADS.remove(player); }
                        DanmakuDocumentCache.invalidate(source);
                        DanmakuManualMatchStore.get().forgetEpisode(matchContext);
                        player.notifyDanmakuStatus(DanmakuStatus.matching());
                        resolveFresh.run();
                    }
                });
                synchronized (RESTORE_DOWNLOADS) {
                    if (isCurrentIdentity(player, identity)) RESTORE_DOWNLOADS.put(player, restore);
                    else restore.cancel();
                }
            }
        }
    }

    /** Invalidates both the network request and the renderer source before a media transition. */
    public static void invalidate(PlayerManager player) {
        cancelSelection(player);
        if (player == null) return;
        synchronized (MATCH_CONTEXTS) {
            MATCH_CONTEXTS.remove(player);
        }
        player.setDanmaku(Danmaku.empty());
        player.notifyDanmakuStatus(DanmakuStatus.hidden());
    }

    /** Manual search/selection takes ownership while retaining playback identity and its source. */
    public static void cancelSelection(PlayerManager player) {
        DanmakuApi.cancel();
        synchronized (REQUEST_IDENTITIES) { REQUEST_IDENTITIES.remove(player); }
        synchronized (RESTORE_DOWNLOADS) {
            DanmakuDocumentCache.Ticket restore = RESTORE_DOWNLOADS.remove(player);
            if (restore != null) restore.cancel();
        }
        synchronized (MANUAL_REQUESTS) {
            MANUAL_REQUESTS.remove(player);
            DanmakuDocumentCache.Ticket ticket = MANUAL_DOWNLOADS.remove(player);
            if (ticket != null) ticket.cancel();
        }
    }

    public static DanmakuMatchContext contextOf(PlayerManager player) {
        if (player == null) return DanmakuMatchContext.empty();
        synchronized (MATCH_CONTEXTS) {
            DanmakuMatchContext context = MATCH_CONTEXTS.get(player);
            return context == null ? DanmakuMatchContext.empty() : context;
        }
    }

    /** An external link or local file is an explicit selection, just like a search result. */
    public static void applyExternalDanmaku(PlayerManager player, Danmaku item) {
        if (player == null) return;
        cancelSelection(player);
        if (item != null && !item.isEmpty()) player.setDanmakuEnabled(true);
        player.setDanmaku(item == null ? Danmaku.empty() : item, true);
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

    /**
     * Applies a manual row only after its document is available.
     *
     * <p>The search service returns rotating comment ids. Persisting a clicked row before its
     * document was verified poisoned every following episode with the same stale id. A failed
     * click now resolves the confirmed work/episode across providers, then stores only the verified
     * replacement.</p>
     */
    public static void applyManualMatch(PlayerManager player, String query, Danmaku selected,
                                        java.util.List<Danmaku> catalogue,
                                        java.util.function.Consumer<Danmaku> onApplied,
                                        Runnable onFailure) {
        if (player == null || selected == null || selected.getUri() == null) {
            if (onFailure != null) onFailure.run();
            return;
        }
        cancelSelection(player);
        DanmakuMatchContext context = contextOf(player);
        String effectiveQuery = Objects.toString(query, "").trim();
        if (effectiveQuery.isEmpty()) effectiveQuery = DanmakuQuery.from(context.getTitle()).searchTitle();
        int request = beginManualRequest(player);
        player.notifyDanmakuStatus(DanmakuStatus.downloading(0));
        String finalQuery = effectiveQuery;
        java.util.List<Danmaku> initialCatalogue = catalogue == null
                ? java.util.Collections.emptyList() : catalogue;
        java.util.function.BiConsumer<Danmaku, java.util.List<Danmaku>> commit = (item, items) -> {
            if (!isCurrentManualRequest(player, request)) return;
            finishManualDownload(player, request);
            java.util.List<Danmaku> resolved = items == null || items.isEmpty()
                    ? initialCatalogue : items;
            DanmakuManualMatchStore.get().remember(context, finalQuery, item, resolved);
            // Selecting an episode is an explicit request to show its comments, including
            // recovery from the historical PiP bug that persisted a hidden state.
            player.setDanmakuEnabled(true);
            player.setDanmaku(item, true);
            if (onApplied != null) onApplied.accept(item);
        };
        Runnable failed = () -> {
            if (!isCurrentManualRequest(player, request)) return;
            finishManualDownload(player, request);
            player.notifyDanmakuStatus(DanmakuStatus.failed(DanmakuStatus.Failure.DOWNLOAD));
            if (onFailure != null) onFailure.run();
        };
        DanmakuDocumentCache.Ticket ticket = DanmakuDocumentCache.load(selected.getUri(), new DanmakuDocumentCache.Listener() {
            @Override
            public void onProgress(android.net.Uri source, int percent) {
                if (isCurrentManualRequest(player, request)) {
                    player.notifyDanmakuStatus(DanmakuStatus.downloading(percent));
                }
            }

            @Override
            public void onReady(android.net.Uri source, android.net.Uri local) {
                commit.accept(selected, initialCatalogue);
            }

            @Override
            public void onFailure(android.net.Uri source, java.io.IOException error) {
                if (!isCurrentManualRequest(player, request)) return;
                finishManualDownload(player, request);
                DanmakuDocumentCache.invalidate(source);
                // An explicit video link has no alternative episode to infer. Surface its
                // download failure directly instead of spending another search timeout on it.
                if (com.fongmi.android.tv.api.NativeDanmaku.supports(finalQuery)) {
                    failed.run();
                    return;
                }
                // A user already confirmed the exact work and episode. Resolve that identity
                // across providers, never reapply the source site's potentially wrong season,
                // title alias or ordinal playlist position to the manual choice.
                String title = selected.getName().split("(?i)\\s*from\\s+", 2)[0];
                String episode = "movie".equals(DanmakuMatch.candidateType(selected.getName()))
                        ? "正片" : DanmakuEpisode.label(selected.getName());
                DanmakuApi.searchDetailed(DanmakuQuery.from(title.replaceAll("【[^】]*】", "")).searchTitle(),
                        DanmakuMatch.candidateYear(selected.getName()), DanmakuMatch.candidateType(selected.getName()), episode,
                        new DanmakuApi.SearchCallback() {
                            @Override
                            public void onProgress(int percent) {
                                if (isCurrentManualRequest(player, request)) {
                                    player.notifyDanmakuStatus(DanmakuStatus.downloading(percent));
                                }
                            }

                            @Override
                            public void onFound(Danmaku item, java.util.List<Danmaku> items) {
                                commit.accept(item, items);
                            }

                            @Override
                            public void onFailure(DanmakuApi.SearchFailure failure) {
                                failed.run();
                            }
                        });
            }
        });
        synchronized (MANUAL_REQUESTS) {
            if (isCurrentManualRequest(player, request)) MANUAL_DOWNLOADS.put(player, ticket);
            else ticket.cancel();
        }
    }

    private static void finishManualDownload(PlayerManager player, int request) {
        synchronized (MANUAL_REQUESTS) {
            if (isCurrentManualRequest(player, request)) MANUAL_DOWNLOADS.remove(player);
        }
    }

    private static int beginManualRequest(PlayerManager player) {
        synchronized (MANUAL_REQUESTS) {
            DanmakuDocumentCache.Ticket previous = MANUAL_DOWNLOADS.remove(player);
            if (previous != null) previous.cancel();
            int next = MANUAL_SEQUENCE.incrementAndGet();
            MANUAL_REQUESTS.put(player, next);
            return next;
        }
    }

    private static boolean isCurrentManualRequest(PlayerManager player, int request) {
        synchronized (MANUAL_REQUESTS) {
            return MANUAL_REQUESTS.getOrDefault(player, 0) == request;
        }
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
        String nextEpisode = String.valueOf(current + 1);
        Danmaku next = selection.episode(nextEpisode);
        if (next == null || next.getUri() == null) return;
        DanmakuDocumentCache.load(next.getUri(), new DanmakuDocumentCache.Listener() {
            @Override
            public void onReady(android.net.Uri source, android.net.Uri local) {
            }

            @Override
            public void onFailure(android.net.Uri source, java.io.IOException error) {
                DanmakuDocumentCache.invalidate(source);
                // A background prefetch must never start a foreground search: the shared
                // generation would cancel a user's new search or next episode in flight.
                // Resolve expired IDs when that episode is actually requested.

            }
        });
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
        return resolveEpisodeQuery(episodeName, stableEpisodeIndex, "");
    }

    static String resolveEpisodeQuery(String episodeName, int stableEpisodeIndex, String type) {
        String label = Objects.toString(episodeName, "").trim();
        DanmakuEpisode identity = DanmakuEpisode.from(label);
        if (!identity.date.isEmpty() || !identity.part.isEmpty() || !identity.extra.isEmpty()) return label;
        if ("variety".equals(DanmakuMatch.normalizeMediaType(type))) return label;
        if (identity.number != null && identity.number > 0) return String.valueOf(identity.number);
        String mediaType = DanmakuMatch.normalizeMediaType(type);
        if ("movie".equals(mediaType) || mediaType.isEmpty() && DanmakuEpisode.isMovieLabel(label)) return "正片";
        // Variety playlist positions include trailers, extras and split episodes. An ordinal
        // index is not a broadcast period; keep the label for exact date/edition matching.
        if (stableEpisodeIndex > 0) return String.valueOf(stableEpisodeIndex);
        return label;
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
