package com.fongmi.android.tv.ui.detail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats date-like update labels into a stable, readable TV episode grid. */
public final class EpisodeDisplayName {

    private static final Pattern COMPACT_DATE = Pattern.compile("^(20\\d{2})(\\d{2})(\\d{2})(.*)$");
    private static final Pattern FILE_EPISODE = Pattern.compile("(?i).*?S(\\d{1,2})E(\\d{1,3}).*");
    private static final Pattern SIZE_PREFIX = Pattern.compile("(?i)^\\s*[\\[【（(]\\s*\\d+(?:\\.\\d+)?\\s*(?:TB|GB|MB|KB|T|G|M|K)\\s*[\\]】）)]\\s*");
    private static final Pattern MEDIA_EXTENSION = Pattern.compile("(?i)\\.(?:mkv|mp4|m4v|avi|mov|wmv|flv|ts|m2ts|webm)(?=\\s|$|[【（(\\[])");
    private static final Pattern LEADING_EPISODE = Pattern.compile("^(?:第\\s*)?(\\d{1,4})(?:\\s*[集期话回])?(?:\\s*[·._-]\\s*|\\s+)?(.*)$");
    private static final Pattern CHINESE_EPISODE = Pattern.compile("^第\\s*(\\d{1,4})\\s*[集期话回](.*)$");
    private static final Pattern SEASON_EPISODE_LABEL = Pattern.compile("^第\\d+季 · 第\\d+集$");

    private EpisodeDisplayName() {
    }

    public static String format(String value) {
        String clean = sanitize(value);
        Matcher fileEpisode = FILE_EPISODE.matcher(clean);
        if (fileEpisode.matches()) return episodeLabel(fileEpisode);
        Matcher matcher = COMPACT_DATE.matcher(clean);
        if (matcher.matches()) {
            String suffix = cleanSuffix(matcher.group(4));
            String date = matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
            return suffix.isEmpty() ? date : date + " · " + suffix;
        }
        Matcher chineseEpisode = CHINESE_EPISODE.matcher(clean);
        if (chineseEpisode.matches()) return joinEpisode(chineseEpisode.group(1), chineseEpisode.group(2));
        Matcher leadingEpisode = LEADING_EPISODE.matcher(clean);
        if (leadingEpisode.matches() && isLikelyEpisodeNumber(leadingEpisode.group(1), leadingEpisode.group(2))) {
            return joinEpisode(Integer.parseInt(leadingEpisode.group(1)), leadingEpisode.group(2));
        }
        return clean;
    }

    public static boolean needsWideCell(String value) {
        String label = compactGridLabel(value);
        return label.codePointCount(0, label.length()) > 7;
    }

    /**
     * Keeps the grid scannable while the full, cleaned label is exposed through the
     * dedicated focus preview and accessibility description.
     */
    public static String compactGridLabel(String value) {
        String formatted = format(value);
        // S02E08 is formatted as "第2季 · 第8集".  The separator is structural rather than a
        // verbose filename suffix, so keep both numbers; otherwise every episode in a season
        // collapses to the indistinguishable label "第2季".
        if (SEASON_EPISODE_LABEL.matcher(formatted).matches()) return formatted.replace(" · ", "");
        int separator = formatted.indexOf(" · ");
        if (separator > 0 && formatted.startsWith("第")) return formatted.substring(0, separator);
        return formatted;
    }

    public static String compactActionLabel(String value) {
        String formatted = format(value);
        Matcher episode = FILE_EPISODE.matcher(formatted);
        if (episode.matches()) return episodeLabel(episode);
        return formatted.codePointCount(0, formatted.length()) <= 16 ? formatted : "";
    }

    private static String episodeLabel(Matcher episode) {
        int season = Integer.parseInt(episode.group(1));
        int number = Integer.parseInt(episode.group(2));
        return season > 1 ? "第" + season + "季 · 第" + number + "集" : "第" + number + "集";
    }

    private static String sanitize(String value) {
        String clean = value == null ? "" : value.trim();
        Matcher size = SIZE_PREFIX.matcher(clean);
        while (size.find()) {
            clean = clean.substring(size.end()).trim();
            size = SIZE_PREFIX.matcher(clean);
        }
        clean = MEDIA_EXTENSION.matcher(clean).replaceAll(" ");
        clean = clean.replace('_', ' ').replaceAll("\\s+", " ").trim();
        return clean;
    }

    private static boolean isLikelyEpisodeNumber(String number, String suffix) {
        if (number.length() > 3) return false;
        String cleanSuffix = cleanSuffix(suffix);
        return cleanSuffix.isEmpty() || suffix.startsWith("【") || suffix.startsWith("（")
                || suffix.startsWith("(") || suffix.startsWith("[") || suffix.startsWith("-")
                || suffix.startsWith("·");
    }

    private static String joinEpisode(int number, String suffix) {
        return joinEpisode(String.valueOf(number), suffix);
    }

    private static String joinEpisode(String number, String suffix) {
        String cleanSuffix = cleanSuffix(suffix);
        return cleanSuffix.isEmpty() ? "第" + number + "集" : "第" + number + "集 · " + cleanSuffix;
    }

    private static String cleanSuffix(String suffix) {
        if (suffix == null) return "";
        return suffix.trim()
                .replaceFirst("^[·._\\-\\s]+", "")
                .replaceFirst("^[【（(\\[]+", "")
                .replaceFirst("[】）)\\]]+$", "")
                .trim();
    }
}
