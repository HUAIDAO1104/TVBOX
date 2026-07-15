package com.fongmi.android.tv.ui.search;

import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.MediaVariant;
import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.NormalizedTitle;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable source candidate. Repository and config scope are part of its identity. */
public final class SearchSource {

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2}|21\\d{2})(?!\\d)");
    // Android's java.util.regex.Pattern does not implement UNICODE_CHARACTER_CLASS, even though
    // the desktop JDK used by local unit tests does. Explicit Unicode separator categories keep
    // actor splitting consistent without crashing when the first search result is aggregated.
    private static final Pattern ACTOR_SPLIT = Pattern.compile("[\\s\\p{Z},，、/|｜;；·]+");

    public enum Availability {
        AVAILABLE, UNKNOWN, UNAVAILABLE
    }

    private final String stableId;
    private final String repositoryId;
    private final String repositoryName;
    private final String configId;
    private final String siteKey;
    private final String siteName;
    private final String vodId;
    private final String title;
    private final String posterUrl;
    private final String year;
    private final String area;
    private final String type;
    private final String actors;
    private final String remarks;
    private final int episodeCount;
    private final Availability availability;
    private final boolean detailsAvailable;
    private final boolean deviceCompatible;
    private final boolean requiresLogin;
    private final long lastSuccessAtMillis;
    private final long responseTimeMillis;
    private final int recentFailureCount;
    private final int dataCompleteness;
    private final int repositoryPriority;
    private final NormalizedTitle normalizedTitle;
    private final MediaVariant mediaVariant;
    private final Set<String> actorKeys;
    private final int yearValue;

    private SearchSource(Builder builder) {
        repositoryId = clean(builder.repositoryId);
        repositoryName = clean(builder.repositoryName);
        configId = clean(builder.configId);
        siteKey = clean(builder.siteKey);
        siteName = clean(builder.siteName);
        vodId = clean(builder.vodId);
        title = clean(builder.title);
        posterUrl = clean(builder.posterUrl);
        year = clean(builder.year);
        area = clean(builder.area);
        type = clean(builder.type);
        actors = clean(builder.actors);
        remarks = clean(builder.remarks);
        episodeCount = Math.max(0, builder.episodeCount);
        availability = builder.availability == null ? Availability.UNKNOWN : builder.availability;
        detailsAvailable = builder.detailsAvailable;
        deviceCompatible = builder.deviceCompatible;
        requiresLogin = builder.requiresLogin;
        lastSuccessAtMillis = Math.max(0, builder.lastSuccessAtMillis);
        responseTimeMillis = Math.max(0, builder.responseTimeMillis);
        recentFailureCount = Math.max(0, builder.recentFailureCount);
        repositoryPriority = Math.max(0, builder.repositoryPriority);
        normalizedTitle = SearchTitleNormalizer.parse(stripKnownTrailingMetadata(title, year, area, type, actors));
        mediaVariant = resolveMediaVariant(normalizedTitle.mediaVariant(), type, remarks);
        actorKeys = Collections.unmodifiableSet(actorKeys(actors));
        yearValue = parseYear(year);
        dataCompleteness = builder.dataCompleteness < 0
                ? calculateCompleteness()
                : Math.clamp(builder.dataCompleteness, 0, 100);
        stableId = clean(builder.stableId).isEmpty() ? buildStableId() : clean(builder.stableId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String stableId() {
        return stableId;
    }

    public String repositoryId() {
        return repositoryId;
    }

    public String repositoryName() {
        return repositoryName;
    }

    public String configId() {
        return configId;
    }

    public String siteKey() {
        return siteKey;
    }

    public String siteName() {
        return siteName;
    }

    public String vodId() {
        return vodId;
    }

    public String title() {
        return title;
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

    public Availability availability() {
        return availability;
    }

    public boolean detailsAvailable() {
        return detailsAvailable;
    }

    public boolean deviceCompatible() {
        return deviceCompatible;
    }

    public boolean requiresLogin() {
        return requiresLogin;
    }

    public long lastSuccessAtMillis() {
        return lastSuccessAtMillis;
    }

    public long responseTimeMillis() {
        return responseTimeMillis;
    }

    public int recentFailureCount() {
        return recentFailureCount;
    }

    public int dataCompleteness() {
        return dataCompleteness;
    }

    public int repositoryPriority() {
        return repositoryPriority;
    }

    public NormalizedTitle normalizedTitle() {
        return normalizedTitle;
    }

    public MediaVariant mediaVariant() {
        return mediaVariant;
    }

    public Set<String> actorKeys() {
        return actorKeys;
    }

    public int yearValue() {
        return yearValue;
    }

    private String buildStableId() {
        String scope = firstNonEmpty(repositoryId, repositoryName) + '\u001f'
                + configId + '\u001f' + siteKey + '\u001f' + siteName;
        String item = vodId.isEmpty()
                ? normalizedTitle.identityKey() + '\u001f' + yearValue + '\u001f' + actors
                : vodId;
        return SearchStableIds.create("source", scope + '\u001e' + item);
    }

    private int calculateCompleteness() {
        int score = title.isEmpty() ? 0 : 20;
        if (!posterUrl.isEmpty()) score += 10;
        if (yearValue > 0) score += 10;
        if (!area.isEmpty()) score += 10;
        if (!type.isEmpty()) score += 15;
        if (!actors.isEmpty()) score += 15;
        if (!remarks.isEmpty()) score += 10;
        if (episodeCount > 0) score += 10;
        if (detailsAvailable) score += 10;
        return Math.min(100, score);
    }

    private static MediaVariant resolveMediaVariant(MediaVariant titleVariant, String type, String remarks) {
        if (titleVariant != MediaVariant.UNSPECIFIED) return titleVariant;
        String value = normalizeMetadata(type + ' ' + remarks);
        if (containsAny(value, "解说", "讲解")) return MediaVariant.COMMENTARY;
        if (containsAny(value, "花絮", "幕后", "彩蛋", "预告")) return MediaVariant.EXTRA;
        if (containsAny(value, "综艺", "真人秀")) return MediaVariant.VARIETY;
        if (containsAny(value, "动漫", "动画")) return MediaVariant.ANIMATION;
        if (containsAny(value, "电影", "劇場", "剧场")) return MediaVariant.MOVIE;
        if (containsAny(value, "电视剧", "電視劇", "连续剧", "連續劇", "剧集", "劇集", "短剧", "短劇",
                "国产剧", "國產劇", "美剧", "美劇", "英剧", "英劇", "韩剧", "韓劇", "日剧", "日劇", "港剧", "港劇", "台剧", "台劇", "网剧", "網劇")) return MediaVariant.SERIES;
        if (containsAny(value, "动作片", "動作片", "喜剧片", "喜劇片", "剧情片", "劇情片", "科幻片", "恐怖片", "纪录片", "紀錄片")) return MediaVariant.MOVIE;
        if (containsAny(value, "正片")) return MediaVariant.MAIN;
        return MediaVariant.UNSPECIFIED;
    }

    private static Set<String> actorKeys(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String actor : ACTOR_SPLIT.split(normalizeMetadata(value))) {
            String key = actor.replaceAll("[（(].{0,20}[）)]", "").replaceAll("[^\\p{L}\\p{N}]", "");
            if (key.length() >= 2) result.add(key);
        }
        return result;
    }

    private static int parseYear(String value) {
        Matcher matcher = YEAR.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String stripKnownTrailingMetadata(String title, String... metadata) {
        String candidate = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC).trim();
        Set<String> tokens = new LinkedHashSet<>();
        for (String value : metadata) {
            String normalized = normalizeMetadata(value);
            for (String token : ACTOR_SPLIT.split(normalized)) {
                String clean = token.replaceAll("^[\\p{Punct}，、；·]+|[\\p{Punct}，、；·]+$", "");
                if (clean.length() >= 2) tokens.add(clean);
            }
        }
        boolean changed;
        do {
            changed = false;
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (!lower.endsWith(token)) continue;
                int start = lower.length() - token.length();
                if (start > 0 && Character.isLetterOrDigit(lower.codePointBefore(start))) continue;
                String shortened = candidate.substring(0, start).replaceFirst("[\\s\\p{Punct}，、；·]+$", "").trim();
                if (shortened.isEmpty()) continue;
                candidate = shortened;
                changed = true;
                break;
            }
        } while (changed);
        return candidate;
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    private static String normalizeMetadata(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim();
    }

    private static String firstNonEmpty(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof SearchSource other)) return false;
        return episodeCount == other.episodeCount
                && detailsAvailable == other.detailsAvailable
                && deviceCompatible == other.deviceCompatible
                && requiresLogin == other.requiresLogin
                && lastSuccessAtMillis == other.lastSuccessAtMillis
                && responseTimeMillis == other.responseTimeMillis
                && recentFailureCount == other.recentFailureCount
                && dataCompleteness == other.dataCompleteness
                && repositoryPriority == other.repositoryPriority
                && stableId.equals(other.stableId)
                && repositoryId.equals(other.repositoryId)
                && repositoryName.equals(other.repositoryName)
                && configId.equals(other.configId)
                && siteKey.equals(other.siteKey)
                && siteName.equals(other.siteName)
                && vodId.equals(other.vodId)
                && title.equals(other.title)
                && posterUrl.equals(other.posterUrl)
                && year.equals(other.year)
                && area.equals(other.area)
                && type.equals(other.type)
                && actors.equals(other.actors)
                && remarks.equals(other.remarks)
                && availability == other.availability;
    }

    @Override
    public int hashCode() {
        return Objects.hash(stableId, repositoryId, repositoryName, configId, siteKey, siteName, vodId,
                title, posterUrl, year, area, type, actors, remarks, episodeCount, availability, detailsAvailable,
                deviceCompatible, requiresLogin, lastSuccessAtMillis, responseTimeMillis,
                recentFailureCount, dataCompleteness, repositoryPriority);
    }

    public static final class Builder {

        private String stableId = "";
        private String repositoryId = "";
        private String repositoryName = "";
        private String configId = "";
        private String siteKey = "";
        private String siteName = "";
        private String vodId = "";
        private String title = "";
        private String posterUrl = "";
        private String year = "";
        private String area = "";
        private String type = "";
        private String actors = "";
        private String remarks = "";
        private int episodeCount;
        private Availability availability = Availability.UNKNOWN;
        private boolean detailsAvailable;
        private boolean deviceCompatible = true;
        private boolean requiresLogin;
        private long lastSuccessAtMillis;
        private long responseTimeMillis;
        private int recentFailureCount;
        private int dataCompleteness = -1;
        private int repositoryPriority = 1_000;

        private Builder() {
        }

        public Builder stableId(String value) { stableId = value; return this; }
        public Builder repositoryId(String value) { repositoryId = value; return this; }
        public Builder repositoryName(String value) { repositoryName = value; return this; }
        public Builder configId(String value) { configId = value; return this; }
        public Builder siteKey(String value) { siteKey = value; return this; }
        public Builder siteName(String value) { siteName = value; return this; }
        public Builder vodId(String value) { vodId = value; return this; }
        public Builder title(String value) { title = value; return this; }
        public Builder posterUrl(String value) { posterUrl = value; return this; }
        public Builder year(String value) { year = value; return this; }
        public Builder area(String value) { area = value; return this; }
        public Builder type(String value) { type = value; return this; }
        public Builder actors(String value) { actors = value; return this; }
        public Builder remarks(String value) { remarks = value; return this; }
        public Builder episodeCount(int value) { episodeCount = value; return this; }
        public Builder availability(Availability value) { availability = value; return this; }
        public Builder detailsAvailable(boolean value) { detailsAvailable = value; return this; }
        public Builder deviceCompatible(boolean value) { deviceCompatible = value; return this; }
        public Builder requiresLogin(boolean value) { requiresLogin = value; return this; }
        public Builder lastSuccessAtMillis(long value) { lastSuccessAtMillis = value; return this; }
        public Builder responseTimeMillis(long value) { responseTimeMillis = value; return this; }
        public Builder recentFailureCount(int value) { recentFailureCount = value; return this; }
        public Builder dataCompleteness(int value) { dataCompleteness = value; return this; }
        public Builder repositoryPriority(int value) { repositoryPriority = value; return this; }

        public SearchSource build() {
            return new SearchSource(this);
        }
    }
}
