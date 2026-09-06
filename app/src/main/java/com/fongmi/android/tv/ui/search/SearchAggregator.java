package com.fongmi.android.tv.ui.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Incremental, insertion-order-preserving aggregate search state.
 * New sources update one work in place; they never reorder existing works.
 */
public final class SearchAggregator {

    public enum UpdateType {
        ADDED_WORK, UPDATED_WORK, UNCHANGED, FILTERED
    }

    public record Update(UpdateType type, int position, SearchWork work) {
        public boolean changed() {
            return type == UpdateType.ADDED_WORK || type == UpdateType.UPDATED_WORK;
        }

        public boolean inserted() {
            return type == UpdateType.ADDED_WORK;
        }
    }

    private final String keyword;
    private final SearchTitleNormalizer.NormalizedTitle normalizedKeyword;
    private final SearchRelevance relevance;
    private final SourceRanker ranker;
    private final LongSupplier nowMillis;
    private final List<SearchWork> works;
    private final Map<String, Integer> workPositions;
    private final Map<String, String> sourceToWork;
    private final Map<String, List<Integer>> positionsByBaseKey;

    public SearchAggregator(String keyword) {
        this(keyword, new SearchRelevance(), new SourceRanker(), System::currentTimeMillis);
    }

    public SearchAggregator(String keyword, SearchRelevance relevance, SourceRanker ranker, LongSupplier nowMillis) {
        this.keyword = keyword == null ? "" : keyword.trim();
        this.normalizedKeyword = SearchTitleNormalizer.parse(this.keyword);
        this.relevance = Objects.requireNonNull(relevance);
        this.ranker = Objects.requireNonNull(ranker);
        this.nowMillis = Objects.requireNonNull(nowMillis);
        this.works = new ArrayList<>();
        this.workPositions = new LinkedHashMap<>();
        this.sourceToWork = new HashMap<>();
        this.positionsByBaseKey = new HashMap<>();
    }

    /** Detached containers; SearchWork and SearchSource values are immutable. */
    public synchronized SearchAggregator copy() {
        SearchAggregator copy = new SearchAggregator(keyword, relevance, ranker, nowMillis);
        copy.works.addAll(works);
        copy.workPositions.putAll(workPositions);
        copy.sourceToWork.putAll(sourceToWork);
        positionsByBaseKey.forEach((key, positions) -> copy.positionsByBaseKey.put(key, new ArrayList<>(positions)));
        return copy;
    }

    public synchronized Update add(SearchSource source) {
        if (source == null) return filtered();
        // A provider can reuse a stable id while returning a different item on a later page or
        // refresh. Never let that update bypass the same relevance gate used for new sources.
        if (!relevance.isRelevant(normalizedKeyword, source)) return filtered();
        String existingWorkId = sourceToWork.get(source.stableId());
        if (existingWorkId != null) return updateExisting(existingWorkId, source);

        SearchTitleNormalizer.NormalizedTitle groupingTitle = groupingTitle(source);
        int target = findCompatibleWork(source, groupingTitle);
        if (target >= 0) {
            SearchWork updated = works.get(target).withSource(source, ranker, nowMillis.getAsLong());
            works.set(target, updated);
            sourceToWork.put(source.stableId(), updated.stableId());
            return new Update(UpdateType.UPDATED_WORK, target, updated);
        }

        String workId = uniqueWorkId(source, groupingTitle);
        SearchWork work = SearchWork.create(workId, source, groupingTitle, ranker, nowMillis.getAsLong());
        int position = works.size();
        works.add(work);
        workPositions.put(workId, position);
        positionsByBaseKey.computeIfAbsent(groupingTitle.baseKey(), ignored -> new ArrayList<>()).add(position);
        sourceToWork.put(source.stableId(), workId);
        return new Update(UpdateType.ADDED_WORK, position, work);
    }

    /**
     * Adds one provider result as one card. This keeps every source-visible result available to
     * the source lane without weakening the conservative title aggregation used elsewhere.
     */
    public synchronized Update addUnaggregated(SearchSource source) {
        if (source == null || !relevance.isRelevant(normalizedKeyword, source)) return filtered();
        String existingWorkId = sourceToWork.get(source.stableId());
        if (existingWorkId != null) return updateExisting(existingWorkId, source);
        String workId = SearchStableIds.create("raw", source.stableId());
        if (workPositions.containsKey(workId)) {
            workId = SearchStableIds.create("raw", source.stableId() + '\u001f' + works.size());
        }
        SearchWork work = SearchWork.create(workId, source, ranker, nowMillis.getAsLong());
        int position = works.size();
        works.add(work);
        workPositions.put(workId, position);
        sourceToWork.put(source.stableId(), workId);
        return new Update(UpdateType.ADDED_WORK, position, work);
    }

    public synchronized List<Update> addAll(Collection<SearchSource> sources) {
        if (sources == null || sources.isEmpty()) return Collections.emptyList();
        List<Update> updates = new ArrayList<>(sources.size());
        for (SearchSource source : sources) updates.add(add(source));
        return Collections.unmodifiableList(updates);
    }

    public synchronized List<SearchWork> works() {
        return Collections.unmodifiableList(new ArrayList<>(works));
    }

    public synchronized List<SearchWork> snapshot() {
        return works();
    }

    public synchronized SearchWork findWork(String stableId) {
        Integer position = workPositions.get(stableId);
        return position == null ? null : works.get(position);
    }

    public synchronized int workCount() {
        return works.size();
    }

    public synchronized int sourceCount() {
        return sourceToWork.size();
    }

    private Update updateExisting(String workId, SearchSource source) {
        Integer position = workPositions.get(workId);
        if (position == null) {
            sourceToWork.remove(source.stableId());
            return add(source);
        }
        SearchWork current = works.get(position);
        for (SearchSource existing : current.sources()) {
            if (existing.stableId().equals(source.stableId()) && existing.equals(source)) {
                return new Update(UpdateType.UNCHANGED, position, current);
            }
        }
        SearchWork updated = current.withSource(source, ranker, nowMillis.getAsLong());
        works.set(position, updated);
        return new Update(UpdateType.UPDATED_WORK, position, updated);
    }

    private int findCompatibleWork(SearchSource source, SearchTitleNormalizer.NormalizedTitle groupingTitle) {
        int bestPosition = -1;
        int bestScore = -1;
        boolean ambiguous = false;
        for (int position : positionsByBaseKey.getOrDefault(groupingTitle.baseKey(), List.of())) {
            int score = works.get(position).compatibility(source, groupingTitle);
            if (score < 0) continue;
            if (score > bestScore) {
                bestScore = score;
                bestPosition = position;
                ambiguous = false;
            } else if (score == bestScore) {
                ambiguous = true;
            }
        }
        return ambiguous ? -1 : bestPosition;
    }

    private String uniqueWorkId(SearchSource source, SearchTitleNormalizer.NormalizedTitle groupingTitle) {
        String seed = groupingTitle.identityKey() + '\u001f'
                + source.yearValue() + '\u001f' + source.mediaVariant();
        String candidate = SearchStableIds.create("work", seed);
        if (!workPositions.containsKey(candidate)) return candidate;
        return SearchStableIds.create("work", seed + '\u001f' + source.stableId());
    }

    private Update filtered() {
        return new Update(UpdateType.FILTERED, -1, null);
    }

    private SearchTitleNormalizer.NormalizedTitle groupingTitle(SearchSource source) {
        SearchTitleNormalizer.NormalizedTitle candidate = source.normalizedTitle();
        if (candidate.installmentKind() != normalizedKeyword.installmentKind()
                || candidate.installment() != normalizedKeyword.installment()) return candidate;
        return SearchTitleNormalizer.hasCatalogMetadataSuffix(keyword, source.title())
                ? normalizedKeyword : candidate;
    }
}
