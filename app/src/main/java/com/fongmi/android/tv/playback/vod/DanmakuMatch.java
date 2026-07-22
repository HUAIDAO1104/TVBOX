package com.fongmi.android.tv.playback.vod;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic ranking for automatic danmaku selection. */
public final class DanmakuMatch {

    private static final Pattern NUMBER = Pattern.compile("(?:第\\s*)?0*(\\d{1,4})\\s*[集期话回]");
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)S\\s*0*(\\d{1,2})\\s*E\\s*0*(\\d{1,3})");
    private static final Pattern FILE_EPISODE = Pattern.compile("(?:^|[\\]）】}\\s._-])0*(\\d{1,3})(?=\\s*\\.(?:mkv|mp4|avi|mov|ts|m2ts|webm)(?:$|[\\s【\\[]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_EPISODE = Pattern.compile("^\\s*0*(\\d{1,3})\\s*$");
    private static final Pattern SEASON = Pattern.compile("(?:第\\s*)?([0-9一二三四五六七八九十百壹贰叁肆伍陆柒捌玖拾]+)\\s*[季部]");
    private static final Pattern SEASON_SUFFIX = Pattern.compile("(?:第)?[0-9一二三四五六七八九十百壹贰叁肆伍陆柒捌玖拾]+[季部]");
    private static final Pattern NOISE = Pattern.compile("预告|花絮|解说|reaction|片段|剪辑", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_TAG = Pattern.compile("(?i)4k|8k|2160p|1080p|720p|hdr(?:10)?|sdr|uhd|web[-_. ]?dl|blu[-_. ]?ray|国语|粤语|中字|双语|全集|完结|超清|高清|官源");
    private static final Pattern PREFERRED_360 = Pattern.compile("(?i)(?:^|\\b)from\\s*360(?:\\b|$)");

    private DanmakuMatch() {
    }

    public static int score(String title, String episode, String candidate) {
        String cleanTitle = normalizeTitle(title);
        String cleanEpisode = normalize(episode);
        String cleanCandidate = normalizeTitle(candidate);
        if (cleanCandidate.isEmpty()) return Integer.MIN_VALUE;
        int score = 0;
        boolean exactTitle = !cleanTitle.isEmpty() && cleanCandidate.contains(cleanTitle);
        boolean baseTitle = titleMatchesBase(title, candidate);
        if (exactTitle) score += 40;
        else if (baseTitle) score += 22;
        if (!cleanEpisode.isEmpty() && cleanCandidate.contains(cleanEpisode)) score += 20;
        Integer expectedEpisode = episodeNumber(episode);
        Integer candidateEpisode = episodeNumber(candidate);
        if (expectedEpisode != null && candidateEpisode != null) {
            score += expectedEpisode.equals(candidateEpisode) ? 70 : -120;
        }
        Integer expectedSeason = firstNonNull(seasonNumber(episode), seasonNumber(title));
        Integer candidateSeason = seasonNumber(candidate);
        if (expectedSeason != null && candidateSeason != null) {
            score += expectedSeason.equals(candidateSeason) ? 30 : -140;
        }
        // The built-in provider exposes several upstream catalogues. 360 has the most stable
        // episode mapping for the current API, so use it as a deterministic tie-breaker only
        // after title/episode reliability has been established.
        if (PREFERRED_360.matcher(candidate).find()) score += 30;
        if (NOISE.matcher(candidate).find() && !NOISE.matcher(episode == null ? "" : episode).find()) score -= 120;
        return score;
    }

    /**
     * Avoids silently loading a high-scoring but unrelated episode.  A candidate must still
     * belong to the requested title, while explicit season/episode conflicts are hard rejects.
     */
    public static boolean isReliable(String title, String episode, String candidate) {
        String cleanCandidate = normalizeTitle(candidate);
        if (cleanCandidate.isEmpty()) return false;
        String cleanTitle = normalizeTitle(title);
        // Automatic loading must never guess when playback metadata is missing. Previously an
        // empty title accepted any API result, which allowed a late response from another show
        // (for example "百花杀") to become the new episode's default danmaku.
        if (cleanTitle.length() < 2) return false;
        boolean titleMatches = cleanCandidate.contains(cleanTitle)
                || titleMatchesBase(title, candidate);
        if (!titleMatches) return false;
        if (NOISE.matcher(candidate).find() && !NOISE.matcher(episode == null ? "" : episode).find()) return false;

        Integer expectedEpisode = episodeNumber(episode);
        Integer candidateEpisode = episodeNumber(candidate);
        if (expectedEpisode != null && candidateEpisode != null && !expectedEpisode.equals(candidateEpisode)) return false;

        Integer expectedSeason = firstNonNull(seasonNumber(episode), seasonNumber(title));
        Integer candidateSeason = seasonNumber(candidate);
        return expectedSeason == null || candidateSeason == null || expectedSeason.equals(candidateSeason);
    }

    static Integer episodeNumber(String value) {
        if (value == null) return null;
        Matcher seasonEpisode = SEASON_EPISODE.matcher(value);
        if (seasonEpisode.find()) return Integer.parseInt(seasonEpisode.group(2));
        Matcher number = NUMBER.matcher(value);
        if (number.find()) return Integer.parseInt(number.group(1));
        Matcher fileEpisode = FILE_EPISODE.matcher(value);
        if (fileEpisode.find()) return Integer.parseInt(fileEpisode.group(1));
        Matcher shortEpisode = SHORT_EPISODE.matcher(value);
        return shortEpisode.find() ? Integer.parseInt(shortEpisode.group(1)) : null;
    }

    private static Integer seasonNumber(String value) {
        if (value == null) return null;
        Matcher seasonEpisode = SEASON_EPISODE.matcher(value);
        if (seasonEpisode.find()) return Integer.parseInt(seasonEpisode.group(1));
        Matcher season = SEASON.matcher(value);
        return season.find() ? parseNumber(season.group(1)) : null;
    }

    private static boolean titleMatchesBase(String title, String candidate) {
        String base = normalizeTitle(SEASON_SUFFIX.matcher(title == null ? "" : title).replaceAll(""));
        return base.length() >= 2 && normalizeTitle(candidate).contains(base);
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first == null ? second : first;
    }

    private static Integer parseNumber(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            // Chinese season labels in media metadata are normally below one hundred.
        }
        String normalized = value.replace('壹', '一').replace('贰', '二').replace('叁', '三')
                .replace('肆', '四').replace('伍', '五').replace('陆', '六')
                .replace('柒', '七').replace('捌', '八').replace('玖', '九').replace('拾', '十');
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int high = ten == 0 ? 1 : chineseDigit(normalized.charAt(ten - 1));
            int low = ten == normalized.length() - 1 ? 0 : chineseDigit(normalized.charAt(ten + 1));
            return high < 0 || low < 0 ? null : high * 10 + low;
        }
        return normalized.length() == 1 ? chineseDigit(normalized.charAt(0)) : null;
    }

    private static int chineseDigit(char value) {
        return switch (value) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]+", "")
                .trim();
    }

    private static String normalizeTitle(String value) {
        return normalize(TITLE_TAG.matcher(value == null ? "" : value).replaceAll(""));
    }
}
