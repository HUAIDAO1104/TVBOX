package com.fongmi.android.tv.ui.detail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats date-like update labels into a stable, readable TV episode grid. */
public final class EpisodeDisplayName {

    private static final Pattern COMPACT_DATE = Pattern.compile("^(20\\d{2})(\\d{2})(\\d{2})(.*)$");
    private static final Pattern FILE_EPISODE = Pattern.compile("(?i).*?S(\\d{1,2})E(\\d{1,3}).*");

    private EpisodeDisplayName() {
    }

    public static String format(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        Matcher fileEpisode = FILE_EPISODE.matcher(clean);
        if (fileEpisode.matches()) return episodeLabel(fileEpisode);
        Matcher matcher = COMPACT_DATE.matcher(clean);
        if (!matcher.matches()) return clean;
        String suffix = matcher.group(4).trim();
        String date = matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
        return suffix.isEmpty() ? date : date + " · " + suffix;
    }

    public static boolean needsWideCell(String value) {
        return format(value).codePointCount(0, format(value).length()) > 7;
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
}
