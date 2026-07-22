package com.fongmi.android.tv.playback.vod;

import com.github.catvod.utils.Trans;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds conservative search terms from provider supplied VOD titles.
 *
 * <p>The raw title remains the playback identity.  Only the query sent to a danmaku provider is
 * cleaned, so a delayed response can still be rejected when the user has switched videos.</p>
 */
public final class DanmakuQuery {

    private static final String YEAR = "(?:19|20)\\d{2}";
    private static final String CATEGORY = "(?:国产(?:剧)?|国剧|大陆(?:剧)?|内地(?:剧)?|华语|港剧|台剧|美剧|英剧|韩剧|日剧|泰剧|海外剧|电视剧|连续剧|剧集|电影|综艺|动漫|动画|短剧)";
    private static final String RELEASE = "(?:高清修复(?:版)?|蓝光修复(?:版)?|4k修复(?:版)?|修复版|4k|8k|2160p|1080p|720p|hdr(?:10)?|sdr|uhd|fhd|hd|bd|web[-_. ]?dl|blu[-_. ]?ray|杜比|蓝光|超清|高清|国语|粤语|普通话|中字|双语|中英双字|简中|繁中|字幕|全集|完结|已完结|完整版|无删减版?)";
    private static final Pattern LEADING_WRAPPED = Pattern.compile("^\\s*[【\\[（(]\\s*([^】\\]）)]{1,40})\\s*[】\\]）)]\\s*");
    private static final Pattern TRAILING_WRAPPED = Pattern.compile("\\s*[【\\[（(]\\s*([^】\\]）)]{1,40})\\s*[】\\]）)]\\s*$");
    private static final Pattern TITLE_WRAPPER = Pattern.compile("^[《〈【\\[（(]\\s*(.+?)\\s*[》〉】\\]）)]$");
    private static final Pattern LEADING_YEAR = Pattern.compile("^\\s*(" + YEAR + ")(?=\\s*[._·:：|/\\-]+|\\s+)(?:\\s*[._·:：|/\\-]+\\s*|\\s+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_RELEASE = Pattern.compile("(?:\\s*[._·:：|/\\-]+\\s*|\\s+)(" + RELEASE + ")\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG_TRAILING_RELEASE = Pattern.compile("(?:高清修复(?:版)?|蓝光修复(?:版)?|4k修复(?:版)?|修复版|全集|完结|已完结|完整版|无删减版?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_TOKEN = Pattern.compile(CATEGORY + "|" + RELEASE + "|" + YEAR, Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_SEPARATOR = Pattern.compile("[\\s._·:：|/\\-]+");
    private static final Pattern EDGE_SYMBOL = Pattern.compile("^[\\p{So}\\p{Sk}\\p{Sm}\\p{Cf}\\s]+|[\\p{So}\\p{Sk}\\p{Sm}\\p{Cf}\\s]+$");
    private static final Pattern EXTRA_SEPARATOR = Pattern.compile("^[\\s._·:：|/\\-]+|[\\s._·:：|/\\-]+$");
    private static final Pattern EXTRA_SPACE = Pattern.compile("\\s+");
    private final String rawTitle;
    private final String searchTitle;
    private final String year;
    private final List<String> candidates;

    private DanmakuQuery(String rawTitle, String searchTitle, String year, List<String> candidates) {
        this.rawTitle = rawTitle;
        this.searchTitle = searchTitle;
        this.year = year;
        this.candidates = candidates;
    }

    public static DanmakuQuery from(String value) {
        String raw = value == null ? "" : value.trim();
        String text = Trans.t2s(false, Normalizer.normalize(raw, Normalizer.Form.NFKC))
                .replace("&nbsp;", " ")
                .replaceAll("<[^>]{1,80}>", " ")
                .trim();
        String year = "";

        for (int pass = 0; pass < 4; pass++) {
            Matcher matcher = LEADING_WRAPPED.matcher(text);
            if (!matcher.find() || !isMetadata(matcher.group(1))) break;
            if (year.isEmpty()) year = extractYear(matcher.group(1));
            text = text.substring(matcher.end()).trim();
        }

        Matcher leadingYear = LEADING_YEAR.matcher(text);
        if (leadingYear.find()) {
            year = leadingYear.group(1);
            text = text.substring(leadingYear.end()).trim();
        }

        for (int pass = 0; pass < 4; pass++) {
            Matcher matcher = TRAILING_WRAPPED.matcher(text);
            if (!matcher.find() || !isMetadata(matcher.group(1))) break;
            if (year.isEmpty()) year = extractYear(matcher.group(1));
            text = text.substring(0, matcher.start()).trim();
        }

        for (int pass = 0; pass < 5; pass++) {
            String previous = text;
            text = stripTrailingRelease(text, TRAILING_RELEASE);
            text = stripTrailingRelease(text, STRONG_TRAILING_RELEASE);
            if (previous.equals(text)) break;
        }

        text = EDGE_SYMBOL.matcher(text).replaceAll("");
        text = EXTRA_SEPARATOR.matcher(text).replaceAll("").trim();
        Matcher wrapper = TITLE_WRAPPER.matcher(text);
        if (wrapper.matches()) text = wrapper.group(1).trim();
        text = EXTRA_SPACE.matcher(text).replaceAll(" ").trim();

        String primary = text.isEmpty() ? raw : text;
        Set<String> ordered = new LinkedHashSet<>();
        add(ordered, primary);
        if (!year.isEmpty()) add(ordered, primary + " " + year);
        if (!raw.equals(primary)) add(ordered, raw);
        return new DanmakuQuery(raw, primary, year, new ArrayList<>(ordered));
    }

    private static String stripTrailingRelease(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) return value;
        String candidate = value.substring(0, matcher.start()).trim();
        return candidate.isEmpty() ? value : candidate;
    }

    private static boolean isMetadata(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String residue = METADATA_TOKEN.matcher(normalized).replaceAll("");
        return METADATA_SEPARATOR.matcher(residue).replaceAll("").isEmpty();
    }

    private static String extractYear(String value) {
        if (value == null) return "";
        Matcher matcher = Pattern.compile(YEAR).matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.trim());
    }

    public String rawTitle() {
        return rawTitle;
    }

    public String searchTitle() {
        return searchTitle;
    }

    public String year() {
        return year;
    }

    public List<String> candidates() {
        return List.copyOf(candidates);
    }
}
