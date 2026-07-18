package com.fongmi.android.tv.ui.search;

import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.InstallmentKind;
import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.MediaVariant;
import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.NormalizedTitle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Conservative query/title relevance filter. */
public final class SearchRelevance {

    public static final int DEFAULT_THRESHOLD = 72;
    private static final int DERIVATIVE_NEWS = 1;
    private static final int DERIVATIVE_INTERVIEW = 1 << 1;
    private static final int DERIVATIVE_CLIP = 1 << 2;
    private static final int DERIVATIVE_PROMO = 1 << 3;
    private static final int DERIVATIVE_AUDIO = 1 << 4;
    private static final int DERIVATIVE_NON_VIDEO = 1 << 5;
    private static final int DERIVATIVE_STATUS = 1 << 6;

    private final int threshold;

    public SearchRelevance() {
        this(DEFAULT_THRESHOLD);
    }

    public SearchRelevance(int threshold) {
        this.threshold = Math.clamp(threshold, 0, 100);
    }

    public boolean isRelevant(String keyword, SearchSource source) {
        return score(keyword, source) >= threshold;
    }

    public boolean isRelevant(String keyword, String title) {
        return score(keyword, title) >= threshold;
    }

    /**
     * Fast strict gate for untrusted provider result sets. This method deliberately stays free of
     * {@code java.util.regex}: some providers return hundreds of noisy rows, and allocating a
     * matcher for every title adds avoidable native/Java pressure on older TV devices. The full
     * relevance check still runs later in {@link SearchAggregator}, after this inexpensive
     * containment gate has discarded catalog noise.
     */
    public boolean isPotentiallyRelevant(String keyword, String title) {
        String expected = SearchTitleNormalizer.fastKey(keyword);
        String actual = SearchTitleNormalizer.fastKey(title);
        if (expected.isEmpty() || actual.isEmpty()) return false;
        if (actual.contains(expected) || expected.contains(actual)) return true;
        String expectedStem = installmentStem(expected);
        String actualStem = installmentStem(actual);
        return expectedStem.length() >= 2 && actualStem.length() >= 2
                && (actualStem.contains(expectedStem) || expectedStem.contains(actualStem));
    }

    private String installmentStem(String value) {
        String numbers = "零〇一二两三四五六七八九十百千万第季部";
        StringBuilder result = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isDigit(codePoint) && numbers.indexOf(codePoint) < 0) result.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    public int score(String keyword, SearchSource source) {
        if (source == null) return 0;
        NormalizedTitle query = SearchTitleNormalizer.parse(keyword);
        if (isUnrequestedAudioSource(query.displayTitle(), source)) return 0;
        return score(query, source.normalizedTitle(), source.mediaVariant());
    }

    public int score(String keyword, String title) {
        NormalizedTitle candidate = SearchTitleNormalizer.parse(title);
        return score(SearchTitleNormalizer.parse(keyword), candidate, candidate.mediaVariant());
    }

    private int score(NormalizedTitle query, NormalizedTitle candidate, MediaVariant candidateVariant) {
        if (query.isEmpty() || candidate.isEmpty()) return 0;
        if (!installmentCompatible(query, candidate)) return 0;
        if (!variantCompatible(query.mediaVariant(), candidateVariant)) return 0;
        if (isUnrequestedDerivative(query, candidate, candidateVariant)) return 0;

        String expected = query.baseKey();
        String actual = candidate.baseKey();
        int score;
        if (expected.equals(actual)) {
            score = query.installment() > 0 ? 100 : 96;
        } else {
            int shorter = Math.min(expected.length(), actual.length());
            if (shorter >= 2 && actual.contains(expected)) {
                // Do not give every arbitrarily long headline a passing floor merely because it
                // contains the query. Normal subtitles remain eligible, while article-like titles
                // naturally fall below the threshold as unrelated text grows.
                score = Math.max(0, 86 - Math.abs(expected.length() - actual.length()) * 2);
            } else {
                score = tokenScore(expected, actual);
            }
        }
        if (query.mediaVariant() == MediaVariant.UNSPECIFIED && isSupplement(candidateVariant)) score -= 8;
        return Math.clamp(score, 0, 100);
    }

    private boolean installmentCompatible(NormalizedTitle query, NormalizedTitle candidate) {
        if (query.installmentKind() == InstallmentKind.NONE) return true;
        return query.installmentKind() == candidate.installmentKind()
                && query.installment() == candidate.installment();
    }

    private boolean variantCompatible(MediaVariant query, MediaVariant candidate) {
        if (query == MediaVariant.UNSPECIFIED || query == MediaVariant.MAIN) return true;
        if (candidate == MediaVariant.UNSPECIFIED || candidate == MediaVariant.MAIN) return false;
        return query == candidate;
    }

    private boolean isUnrequestedDerivative(NormalizedTitle query, NormalizedTitle candidate,
                                            MediaVariant candidateVariant) {
        MediaVariant queryVariant = query.mediaVariant();
        if ((queryVariant == MediaVariant.UNSPECIFIED || queryVariant == MediaVariant.MAIN)
                && isSupplement(candidateVariant)) return true;

        int candidateMarkers = derivativeMarkers(candidate.displayTitle());
        int requestedMarkers = derivativeMarkers(query.displayTitle());
        if ((candidateMarkers & ~requestedMarkers) == 0) return false;

        // Marker words can be part of a legitimate title. Treat them as derivative copy only when
        // the searched title leads the candidate ("三体发布会"), or when a sufficiently specific
        // query occurs inside a longer news-style heading. This keeps normal titles such as
        // "新闻女王" searchable by "女王".
        String expected = query.baseKey();
        String actual = candidate.baseKey();
        return actual.startsWith(expected) || expected.codePointCount(0, expected.length()) >= 3;
    }

    private int derivativeMarkers(String value) {
        String text = value == null ? "" : value.toLowerCase();
        int result = 0;
        if (containsAny(text, "发布会", "首映礼", "见面会", "路演", "开机仪式", "杀青宴", "演员新闻")) {
            result |= DERIVATIVE_NEWS;
        }
        if (containsAny(text, "演员采访", "主创采访", "导演采访", "采访", "专访")) {
            result |= DERIVATIVE_INTERVIEW;
        }
        if (containsAny(text, "精彩片段", "删减片段", "片段", "横版视频", "竖版视频", "横屏", "竖屏", "cut")) {
            result |= DERIVATIVE_CLIP;
        }
        if (containsAny(text, "解说", "讲解", "预告", "花絮", "幕后", "彩蛋", "速看", "盘点")) {
            result |= DERIVATIVE_PROMO;
        }
        if (containsAny(text, "ost", "mv", "主题曲", "片头曲", "片尾曲", "插曲")) {
            result |= DERIVATIVE_AUDIO;
        }
        if (containsAny(text, ".txt", "txt", "mp3", "lrc", "作者:", "作者：", "电子书", "有声书", "有声小说", "小说合集", "的搜索有", "搜索结果有")) {
            result |= DERIVATIVE_NON_VIDEO;
        }
        if (containsAny(text, "预约", "待播", "未播", "敬请期待", "即将上线", "即将播出", "即将开播", "暂无片源", "暂无资源", "暂无播放", "暂无视频", "已下架", "已失效", "已过期")) {
            result |= DERIVATIVE_STATUS;
        }
        return result;
    }

    private boolean containsAny(String value, String... markers) {
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    private boolean isUnrequestedAudioSource(String query, SearchSource source) {
        String[] markers = {"音乐", "音樂", "音频", "音頻", "歌曲", "听书", "聽書",
                "有声", "有聲", "广播剧", "廣播劇", "电台", "電台", "播客", "audio", "music", "podcast"};
        String requested = query == null ? "" : query.toLowerCase();
        if (containsAny(requested, markers)) return false;
        String site = source.siteName().toLowerCase();
        if (containsAny(site, markers)) return true;
        String type = source.type().toLowerCase().trim();
        return containsAny(type, "音频", "音頻", "歌曲", "听书", "聽書", "有声", "有聲", "audio", "podcast")
                || type.equals("音乐") || type.equals("音樂");
    }

    private int tokenScore(String expected, String actual) {
        Set<String> first = tokens(expected);
        Set<String> second = tokens(actual);
        if (first.isEmpty() || second.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        if (intersection.isEmpty()) return 0;
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        double similarity = (double) intersection.size() / union.size();
        return similarity >= 0.75 ? (int) Math.round(70 + similarity * 20) : 0;
    }

    private Set<String> tokens(String value) {
        if (value.indexOf(' ') >= 0) return new HashSet<>(Arrays.asList(value.split("\\s+")));
        Set<String> result = new HashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) result.add(value.substring(index, index + 2));
        return result;
    }

    private boolean isSupplement(MediaVariant variant) {
        return variant == MediaVariant.EXTRA || variant == MediaVariant.COMMENTARY || variant == MediaVariant.VARIETY;
    }
}
