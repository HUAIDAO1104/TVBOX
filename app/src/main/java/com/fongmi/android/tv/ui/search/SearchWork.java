package com.fongmi.android.tv.ui.search;

import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.InstallmentKind;
import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.MediaVariant;
import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.NormalizedTitle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable aggregate work. Updating its sources never changes {@link #stableId()}. */
public final class SearchWork {

    private final String stableId;
    private final NormalizedTitle normalizedTitle;
    private final List<SearchSource> sources;
    private final List<SearchSource> rankedSources;
    private final SearchSource recommendedSource;
    private final String displayTitle;
    private final String posterUrl;
    private final String year;
    private final String area;
    private final String type;
    private final String actors;
    private final String remarks;
    private final int episodeCount;

    static SearchWork create(String stableId, SearchSource source, SourceRanker ranker, long nowMillis) {
        return new SearchWork(stableId, source.normalizedTitle(), List.of(source), ranker, nowMillis);
    }

    static SearchWork create(String stableId, SearchSource source, NormalizedTitle groupingTitle,
                             SourceRanker ranker, long nowMillis) {
        return new SearchWork(stableId, groupingTitle, List.of(source), ranker, nowMillis);
    }

    private SearchWork(String stableId, NormalizedTitle normalizedTitle, List<SearchSource> sources, SourceRanker ranker, long nowMillis) {
        this.stableId = stableId;
        this.normalizedTitle = normalizedTitle;
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        this.rankedSources = ranker.rank(this.sources, nowMillis);
        this.recommendedSource = rankedSources.isEmpty() ? null : rankedSources.get(0);
        SearchSource preferred = recommendedSource == null ? this.sources.get(0) : recommendedSource;
        this.displayTitle = chooseTitle(preferred, this.sources);
        this.posterUrl = choose(SearchSource::posterUrl, preferred, this.sources);
        this.year = choose(SearchSource::year, preferred, this.sources);
        this.area = choose(SearchSource::area, preferred, this.sources);
        this.type = choose(SearchSource::type, preferred, this.sources);
        this.actors = choose(SearchSource::actors, preferred, this.sources);
        this.remarks = choose(SearchSource::remarks, preferred, this.sources);
        this.episodeCount = this.sources.stream().mapToInt(SearchSource::episodeCount).max().orElse(0);
    }

    public String stableId() {
        return stableId;
    }

    public NormalizedTitle normalizedTitle() {
        return normalizedTitle;
    }

    public List<SearchSource> sources() {
        return sources;
    }

    public List<SearchSource> rankedSources() {
        return rankedSources;
    }

    public SearchSource recommendedSource() {
        return recommendedSource;
    }

    public String displayTitle() {
        return displayTitle;
    }

    public String posterUrl() {
        return posterUrl;
    }

    public String year() {
        return year;
    }

    public String area() {
        return area;
    }

    public String type() {
        return type;
    }

    public String actors() {
        return actors;
    }

    public String remarks() {
        return remarks;
    }

    public int episodeCount() {
        return episodeCount;
    }

    public int sourceCount() {
        return sources.size();
    }

    int compatibility(SearchSource candidate) {
        return compatibility(candidate, candidate.normalizedTitle());
    }

    int compatibility(SearchSource candidate, NormalizedTitle incoming) {
        if (!normalizedTitle.baseKey().equals(incoming.baseKey())) return -1;
        if (!sameInstallment(normalizedTitle, incoming)) return -1;
        if (hasYearConflict(candidate)) return -1;
        if (hasVariantConflict(candidate)) return -1;
        if (hasActorConflict(candidate)) return -1;

        int score = 100;
        if (candidate.yearValue() > 0 && knownYears().contains(candidate.yearValue())) score += 30;
        Set<MediaVariant> variants = knownVariants();
        if (candidate.mediaVariant() != MediaVariant.UNSPECIFIED && variants.contains(candidate.mediaVariant())) score += 20;
        if (!candidate.actorKeys().isEmpty() && intersects(knownActors(), candidate.actorKeys())) score += 20;
        if (candidate.normalizedTitle().identityKey().equals(normalizedTitle.identityKey())) score += 10;
        return score;
    }

    SearchWork withSource(SearchSource source, SourceRanker ranker, long nowMillis) {
        List<SearchSource> updated = new ArrayList<>(sources);
        int replace = -1;
        for (int index = 0; index < updated.size(); index++) {
            if (updated.get(index).stableId().equals(source.stableId())) {
                replace = index;
                break;
            }
        }
        if (replace >= 0) updated.set(replace, source);
        else updated.add(source);
        return new SearchWork(stableId, normalizedTitle, updated, ranker, nowMillis);
    }

    private boolean hasYearConflict(SearchSource candidate) {
        Set<Integer> years = knownYears();
        return candidate.yearValue() > 0 && !years.isEmpty() && !years.contains(candidate.yearValue());
    }

    private boolean hasVariantConflict(SearchSource candidate) {
        Set<MediaVariant> variants = knownVariants();
        MediaVariant incoming = candidate.mediaVariant();
        if (!variants.isEmpty() && incoming != MediaVariant.UNSPECIFIED && incoming != MediaVariant.MAIN) {
            return !variants.contains(incoming);
        }
        if (isSupplement(incoming) && variants.isEmpty()) return true;
        if (incoming == MediaVariant.UNSPECIFIED || incoming == MediaVariant.MAIN) {
            for (MediaVariant variant : variants) if (isSupplement(variant)) return true;
        }
        return false;
    }

    private boolean hasActorConflict(SearchSource candidate) {
        Set<String> known = knownActors();
        return !known.isEmpty() && !candidate.actorKeys().isEmpty() && !intersects(known, candidate.actorKeys());
    }

    private Set<Integer> knownYears() {
        Set<Integer> years = new LinkedHashSet<>();
        for (SearchSource source : sources) if (source.yearValue() > 0) years.add(source.yearValue());
        return years;
    }

    private Set<MediaVariant> knownVariants() {
        Set<MediaVariant> variants = new LinkedHashSet<>();
        for (SearchSource source : sources) {
            if (source.mediaVariant() != MediaVariant.UNSPECIFIED && source.mediaVariant() != MediaVariant.MAIN) {
                variants.add(source.mediaVariant());
            }
        }
        return variants;
    }

    private Set<String> knownActors() {
        Set<String> actors = new LinkedHashSet<>();
        for (SearchSource source : sources) actors.addAll(source.actorKeys());
        return actors;
    }

    private static boolean sameInstallment(NormalizedTitle first, NormalizedTitle second) {
        if (first.installmentKind() == InstallmentKind.NONE || second.installmentKind() == InstallmentKind.NONE) {
            return first.installmentKind() == second.installmentKind();
        }
        return first.installmentKind() == second.installmentKind() && first.installment() == second.installment();
    }

    private static boolean intersects(Set<?> first, Set<?> second) {
        for (Object value : first) if (second.contains(value)) return true;
        return false;
    }

    private static boolean isSupplement(MediaVariant variant) {
        return variant == MediaVariant.EXTRA || variant == MediaVariant.COMMENTARY || variant == MediaVariant.VARIETY;
    }

    private static String chooseTitle(SearchSource preferred, List<SearchSource> sources) {
        String selected = preferred.normalizedTitle().displayTitle();
        for (SearchSource source : sources) {
            String title = source.normalizedTitle().displayTitle();
            if (!title.isEmpty() && (selected.isEmpty() || title.length() < selected.length())) selected = title;
        }
        return selected.isEmpty() ? preferred.title() : selected;
    }

    private static String choose(Value value, SearchSource preferred, List<SearchSource> sources) {
        String selected = value.get(preferred);
        if (!selected.isEmpty()) return selected;
        for (SearchSource source : sources) {
            selected = value.get(source);
            if (!selected.isEmpty()) return selected;
        }
        return "";
    }

    private interface Value {
        String get(SearchSource source);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof SearchWork other)) return false;
        return stableId.equals(other.stableId) && sources.equals(other.sources)
                && Objects.equals(recommendedSource, other.recommendedSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stableId, sources, recommendedSource);
    }
}
