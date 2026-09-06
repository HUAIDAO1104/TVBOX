package com.fongmi.android.tv.ui.search;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.model.SearchSnapshot;
import com.fongmi.android.tv.utils.PosterResolver;
import java.util.*;
import java.util.regex.*;

/** Worker-owned index. Only detached snapshots cross into the UI. */
public final class SearchResultIndex {
    public static final String ALL = "source-family-all";
    private static final Pattern EPISODE_COUNT = Pattern.compile("(?:全|更新至|至)?\\s*(\\d{1,4})\\s*(?:集|期)");
    private final String keyword;
    private final Set<String> families;
    private final Map<String, SearchAggregator> aggregators = new LinkedHashMap<>();
    private final Map<String, Map<String, Vod>> vods = new LinkedHashMap<>();
    private final Map<String, Vod> allVodBySource = new LinkedHashMap<>();
    private final Map<String, List<Vod>> fallbackCandidatesBySource = new LinkedHashMap<>();
    private final Map<String, String> borrowedPosterBySource = new LinkedHashMap<>();
    private final Map<String, SearchWork> groupsBySource = new LinkedHashMap<>();
    private final SearchAggregator fallbackAggregator;
    private SearchSnapshot previous;

    public SearchResultIndex(String keyword, Set<String> families) {
        this.keyword = keyword;
        this.families = Set.copyOf(families);
        fallbackAggregator = new SearchAggregator(keyword);
        addFamily(ALL);
        families.forEach(this::addFamily);
    }

    private void addFamily(String family) {
        aggregators.put(family, new SearchAggregator(keyword));
        vods.put(family, new LinkedHashMap<>());
    }

    public boolean accepts(String keyword, Set<String> families, SearchSnapshot next) {
        if (!this.keyword.equals(keyword) || !this.families.equals(families)) return false;
        if (previous == null) return true;
        if (previous.session() != next.session() || previous.results().size() > next.results().size()) return false;
        for (int i = 0; i < previous.results().size(); i++) if (previous.results().get(i) != next.results().get(i)) return false;
        return true;
    }

    public Snapshot append(SearchSnapshot next) {
        int from = previous == null ? 0 : previous.results().size();
        Map<Site, List<String>> membership = new IdentityHashMap<>();
        Map<String, SearchWork> changed = new LinkedHashMap<>();
        for (Result result : next.results().subList(from, next.results().size())) {
            for (Vod vod : result.getList()) {
                SearchSource source = sourceFrom(vod);
                SearchAggregator.Update added = aggregators.get(ALL).addUnaggregated(source);
                if (!added.changed()) continue;
                allVodBySource.put(source.stableId(), vod);
                vods.get(ALL).put(source.stableId(), vod);
                for (String family : membership.computeIfAbsent(vod.getSite(), site -> {
                    String id = site == null ? "" : SearchSourcePreference.id(site);
                    if (families.contains(id)) return List.of(id);
                    return families.stream().filter(value -> SearchSourcePreference.isEnabled(site, Set.of(value))).toList();
                })) {
                    aggregators.get(family).addUnaggregated(source);
                    vods.get(family).put(source.stableId(), vod);
                }
                SearchAggregator.Update grouped = fallbackAggregator.add(source);
                if (grouped.changed()) changed.put(grouped.work().stableId(), grouped.work());
            }
        }
        changed.values().forEach(work -> {
            refreshBorrowedPosters(work);
            for (SearchSource source : work.sources()) groupsBySource.put(source.stableId(), work);
        });
        rebuildFallbackCandidates(fallbackAggregator, allVodBySource);
        previous = next;
        Map<String, SearchAggregator> copies = new LinkedHashMap<>();
        aggregators.forEach((family, value) -> copies.put(family, value.copy()));
        Map<String, Map<String, Vod>> sources = new LinkedHashMap<>();
        vods.forEach((family, value) -> sources.put(family, Map.copyOf(value)));
        return new Snapshot(next, Map.copyOf(copies), Map.copyOf(sources), Map.copyOf(allVodBySource),
                Map.copyOf(fallbackCandidatesBySource), Map.copyOf(borrowedPosterBySource), Map.copyOf(groupsBySource));
    }

    public record Snapshot(SearchSnapshot raw, Map<String, SearchAggregator> families,
                           Map<String, Map<String, Vod>> vods, Map<String, Vod> allVods,
                           Map<String, List<Vod>> candidates, Map<String, String> posters,
                           Map<String, SearchWork> groups) { }

    private SearchSource sourceFrom(Vod vod) {
        Site site = vod.getSite() == null ? new Site() : vod.getSite();
        SearchSourceHealthStore.Snapshot health = SearchSourceHealthStore.get().snapshot(site.getKey());
        String scope = site.getRepositoryId() == 0
                ? "current:" + site.getConfigUrl()
                : String.valueOf(site.getRepositoryId());
        String config = site.getConfigName().isEmpty() ? App.get().getString(R.string.search_v2_current_repository) : site.getConfigName();
        return SearchSource.builder()
                .repositoryId(scope)
                .repositoryName(site.getRepositoryName())
                .configId(config)
                .siteKey(site.getKey())
                .siteName(site.getName())
                .vodId(vod.getId())
                .title(vod.getName())
                .posterUrl(vod.getPic())
                .year(vod.getYear())
                .area(vod.getArea())
                .type(vod.getTypeName())
                .actors(vod.getActor())
                .remarks(vod.getRemarks())
                .episodeCount(parseEpisodeCount(vod.getRemarks()))
                .availability(health.availability())
                .detailsAvailable(!vod.getId().isEmpty())
                .deviceCompatible(true)
                .requiresLogin(requiresLogin(site))
                .lastSuccessAtMillis(health.lastSuccessAtMillis())
                .responseTimeMillis(health.responseTimeMillis())
                .recentFailureCount(health.recentFailureCount())
                .repositoryPriority(site.getRepositoryPriority())
                .build();
    }

    private String rawSourceKey(Vod vod) {
        Site site = vod.getSite() == null ? new Site() : vod.getSite();
        return site.getRepositoryId() + "\u0000" + site.getConfigUrl() + "\u0000" + site.getKey()
                + "\u0000" + vod.getId() + "\u0000" + vod.getName();
    }

    private int parseEpisodeCount(String remarks) {
        if (remarks == null || remarks.isBlank()) return 0;
        Matcher matcher = EPISODE_COUNT.matcher(remarks);
        int result = 0;
        while (matcher.find()) result = Math.max(result, Integer.parseInt(matcher.group(1)));
        return result;
    }

    private boolean requiresLogin(Site site) {
        String value = (site.getName() + " " + site.getConfigName()).toLowerCase(Locale.ROOT);
        return value.contains("网盘") || value.contains("云盘") || value.contains("夸克")
                || value.contains("uc") || value.contains("阿里") || value.contains("迅雷");
    }

    private void refreshBorrowedPosters(SearchWork work) {
        if (work == null || work.sources().isEmpty()) return;
        // Keep every real candidate. A provider often returns an expiring or blocked image while
        // another source in the same group has valid artwork; ImgUtil can then fail over without
        // ever showing the branded last-resort tile.
        for (SearchSource source : work.rankedSources()) {
            String candidate = source.posterUrl();
            if (candidate == null || candidate.isBlank()) continue;
            PosterResolver.remember(work.displayTitle(), candidate);
        }
        for (SearchSource source : work.sources()) {
            String candidate = source.posterUrl();
            if (candidate != null && !candidate.isBlank()) PosterResolver.remember(source.title(), candidate);
        }
        String poster = "";
        for (SearchSource source : work.rankedSources()) {
            if (!source.requiresLogin() && source.posterUrl() != null && !source.posterUrl().isBlank()) {
                poster = source.posterUrl();
                break;
            }
        }
        if (poster.isBlank()) {
            for (SearchSource source : work.rankedSources()) {
                if (source.posterUrl() != null && !source.posterUrl().isBlank()) {
                    poster = source.posterUrl();
                    break;
                }
            }
        }
        if (poster.isBlank()) return;
        for (SearchSource source : work.sources()) borrowedPosterBySource.put(source.stableId(), poster);
    }

    private void rebuildFallbackCandidates(SearchAggregator grouped, Map<String, Vod> allSources) {
        fallbackCandidatesBySource.clear();
        for (SearchWork work : grouped.works()) {
            List<Vod> candidates = new ArrayList<>();
            for (SearchSource source : work.rankedSources()) {
                Vod vod = allSources.get(source.stableId());
                if (vod != null && vod.getSite() != null) candidates.add(vod);
            }
            if (candidates.isEmpty()) continue;
            List<Vod> snapshot = List.copyOf(candidates);
            for (SearchSource source : work.sources()) fallbackCandidatesBySource.put(source.stableId(), snapshot);
        }
    }
}
