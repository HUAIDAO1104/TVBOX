package com.fongmi.android.tv.playback.vod;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic ranking for automatic danmaku selection. */
public final class DanmakuMatch {

    private static final Pattern NUMBER = Pattern.compile("(?:第\\s*)?0*(\\d{1,4})\\s*[集期话回]");
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)S\\s*0*(\\d{1,2})\\s*E\\s*0*(\\d{1,3})");
    private static final Pattern FILE_EPISODE = Pattern.compile("(?:^|[\\]）】}\\s._-])0*(\\d{1,3})(?=\\s*\\.(?:mkv|mp4|avi|mov|ts|m2ts|webm)(?:$|[\\s【\\[]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_EPISODE = Pattern.compile("^\\s*0*(\\d{1,3})\\s*$");
    private static final Pattern SEASON = Pattern.compile("(?:第\\s*)?([0-9一二三四五六七八九十百壹贰叁肆伍陆柒捌玖拾]+)\\s*[季部]");
    private static final Pattern SEASON_SUFFIX = Pattern.compile("(?:第)?([0-9一二三四五六七八九十百壹贰叁肆伍陆柒捌玖拾]+)[季部]$");
    private static final Pattern TRAILING_ROMAN = Pattern.compile("([ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ])$");
    private static final Pattern TRAILING_ASCII_ROMAN = Pattern.compile("(?i)(viii|vii|vi|iv|iii|ii|ix|x|v|i)$");
    private static final Pattern TRAILING_ARABIC = Pattern.compile("^(.{2,}?)([1-9][0-9]?)$");
    private static final Pattern NOISE = Pattern.compile("预告|花絮|解说|reaction|片段|剪辑|专访|采访|综艺|真人秀|mv|番外|特别篇|彩蛋|幕后", Pattern.CASE_INSENSITIVE);
    private static final String TITLE_TAG_VALUE = "(?:4k|8k|2160p|1080p|720p|hdr(?:10)?|sdr|uhd|web[-_. ]?dl|blu[-_. ]?ray|国语|粤语|中字|双语|全集|完结|超清|高清|官源)";
    private static final Pattern TITLE_TAG = Pattern.compile("(?i)" + TITLE_TAG_VALUE);
    private static final Pattern LEADING_TITLE_TAG = Pattern.compile("(?i)^" + TITLE_TAG_VALUE + "[\\s._·:：|/\\-]+");
    private static final Pattern TRAILING_TITLE_TAG = Pattern.compile("(?i)[\\s._·:：|/\\-]*" + TITLE_TAG_VALUE + "$");
    private static final Pattern PREFERRED_360 = Pattern.compile("(?i)(?:^|\\b)from\\s*360(?:\\b|$)");
    private static final Pattern PROVIDER_SUFFIX = Pattern.compile("(?i)\\s*from\\s+.*$");
    private static final Pattern YEAR_META = Pattern.compile("[（(]\\s*((?:19|20)\\d{2})\\s*[）)]");
    private static final Pattern TYPE_META = Pattern.compile("【([^】]{1,20})】");
    private static final Pattern EPISODE_META = Pattern.compile("(?i)(?:S\\s*0*\\d{1,2}\\s*E\\s*0*\\d{1,3}|(?:第\\s*)?0*\\d{1,4}\\s*[集期话回]|(?:EP?|E)\\s*0*\\d{1,3})(?=$|[\\s._-])");

    private DanmakuMatch() {
    }

    public static int score(String title, String episode, String candidate) {
        String cleanTitle = canonicalTitle(title);
        String cleanEpisode = normalize(episode);
        String cleanCandidate = canonicalTitle(candidate);
        if (cleanCandidate.isEmpty()) return Integer.MIN_VALUE;
        if (!cleanTitle.equals(cleanCandidate)) return Integer.MIN_VALUE / 2;
        int score = 0;
        score += 100;
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
        String cleanCandidate = canonicalTitle(candidate);
        if (cleanCandidate.isEmpty()) return false;
        String cleanTitle = canonicalTitle(title);
        // Automatic loading must never guess when playback metadata is missing. Previously an
        // empty title accepted any API result, which allowed a late response from another show
        // (for example "百花杀") to become the new episode's default danmaku.
        if (cleanTitle.length() < 2) return false;
        if (!cleanCandidate.equals(cleanTitle)) return false;
        if (NOISE.matcher(candidate).find()
                && !NOISE.matcher((title == null ? "" : title) + " " + (episode == null ? "" : episode)).find()) return false;

        Integer expectedEpisode = episodeNumber(episode);
        Integer candidateEpisode = episodeNumber(candidate);
        // The bundled API returns an entire season even when an episode parameter is supplied.
        // When the request has an exact episode, candidates without an exact parseable episode
        // are unsafe too; accepting one is how episode 1/10/11 leaked into later episodes.
        if (expectedEpisode != null && !expectedEpisode.equals(candidateEpisode)) return false;

        Integer expectedSeason = firstNonNull(seasonNumber(episode), seasonNumber(title));
        Integer candidateSeason = seasonNumber(candidate);
        return expectedSeason == null || candidateSeason == null || expectedSeason.equals(candidateSeason);
    }

    /** Selects a reliable result independently of provider response order. */
    public static <T> T best(String title, String episode, List<T> items, Function<T, String> name) {
        return best(title, "", episode, items, name);
    }

    /**
     * Selects only an exact work identity. If playback has no year/type and the provider returns
     * several editions of the same normalized work, automatic loading is deliberately skipped.
     */
    public static <T> T best(String title, String year, String episode, List<T> items, Function<T, String> name) {
        return best(title, year, "", episode, items, name);
    }

    public static <T> T best(String title, String year, String type, String episode, List<T> items, Function<T, String> name) {
        T best = null;
        int bestScore = Integer.MIN_VALUE;
        Set<String> years = new HashSet<>();
        Set<String> types = new HashSet<>();
        String expectedYear = normalizeYear(year);
        String expectedType = normalizeMediaType(type);
        if (items == null) return null;
        for (T item : items) {
            if (item == null) continue;
            String candidate = name.apply(item);
            if (!isReliable(title, episode, candidate)) continue;
            String candidateYear = candidateYear(candidate);
            if (!expectedYear.isEmpty() && !expectedYear.equals(candidateYear)) continue;
            if (expectedYear.isEmpty() && !candidateYear.isEmpty()) years.add(candidateYear);
            String candidateMediaType = candidateType(candidate);
            if (!expectedType.isEmpty() && !expectedType.equals(candidateMediaType)) continue;
            if (expectedType.isEmpty() && !candidateMediaType.isEmpty()) types.add(candidateMediaType);
            int score = score(title, episode, candidate);
            if (best == null || score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        if (expectedYear.isEmpty() && years.size() > 1) return null;
        if (expectedType.isEmpty() && types.size() > 1) return null;
        return best;
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

    static String canonicalTitle(String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("<[^>]*>", "")
                .replaceAll("[\\p{Cf}\\u200B-\\u200D\\u2060\\uFEFF]", "");
        text = PROVIDER_SUFFIX.matcher(text).replaceFirst("");
        text = YEAR_META.matcher(text).replaceAll("");
        text = stripMetadataBrackets(text);
        text = EPISODE_META.matcher(text).replaceAll(" ");
        text = normalizeTitle(text);
        if (text.length() < 2) return text;

        Matcher season = SEASON_SUFFIX.matcher(text);
        if (season.find()) {
            Integer number = parseNumber(season.group(1));
            if (number != null) return text.substring(0, season.start()) + "#" + number;
        }
        Matcher roman = TRAILING_ROMAN.matcher(text);
        if (roman.find()) return text.substring(0, roman.start()) + "#" + romanNumber(roman.group(1).charAt(0));
        Matcher asciiRoman = TRAILING_ASCII_ROMAN.matcher(text);
        if (asciiRoman.find() && asciiRoman.start() >= 2) {
            return text.substring(0, asciiRoman.start()) + "#" + asciiRomanNumber(asciiRoman.group(1));
        }
        Matcher arabic = TRAILING_ARABIC.matcher(text);
        if (arabic.matches()) return arabic.group(1) + "#" + Integer.parseInt(arabic.group(2));
        return text;
    }

    static String candidateYear(String value) {
        Matcher matcher = YEAR_META.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String normalizeYear(String value) {
        Matcher matcher = Pattern.compile("(?:19|20)\\d{2}").matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group() : "";
    }

    private static String candidateType(String value) {
        Matcher matcher = TYPE_META.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String type = normalizeMediaType(matcher.group(1));
            if (!type.isEmpty()) return type;
        }
        return "";
    }

    private static String normalizeMediaType(String value) {
        String type = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (type.contains("综艺") || type.contains("专访") || type.contains("真人秀")) return "variety";
        if (type.contains("动漫") || type.contains("动画")) return "anime";
        if (type.contains("电影") || type.matches(".*(?:动作|喜剧|爱情|科幻|恐怖|纪录|故事)片.*")) return "movie";
        if (type.contains("电视剧") || type.contains("连续剧") || type.contains("短剧") || type.contains("国剧")
                || type.matches(".*(?:国产|大陆|内地|美|英|韩|日|泰|港|台)剧.*")) return "series";
        return "";
    }

    private static String stripMetadataBrackets(String value) {
        Matcher matcher = TYPE_META.matcher(value == null ? "" : value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String content = matcher.group(1);
            String residue = TITLE_TAG.matcher(content).replaceAll("");
            String normalizedContent = normalize(content);
            boolean metadata = !normalizeMediaType(content).isEmpty()
                    || normalizedContent.matches("(?:19|20)\\d{2}年?")
                    || normalize(residue).isEmpty();
            matcher.appendReplacement(result, metadata ? " " : Matcher.quoteReplacement(matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static int romanNumber(char value) {
        return switch (value) {
            case 'Ⅰ' -> 1;
            case 'Ⅱ' -> 2;
            case 'Ⅲ' -> 3;
            case 'Ⅳ' -> 4;
            case 'Ⅴ' -> 5;
            case 'Ⅵ' -> 6;
            case 'Ⅶ' -> 7;
            case 'Ⅷ' -> 8;
            case 'Ⅸ' -> 9;
            case 'Ⅹ' -> 10;
            default -> -1;
        };
    }

    private static int asciiRomanNumber(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "i" -> 1;
            case "ii" -> 2;
            case "iii" -> 3;
            case "iv" -> 4;
            case "v" -> 5;
            case "vi" -> 6;
            case "vii" -> 7;
            case "viii" -> 8;
            case "ix" -> 9;
            case "x" -> 10;
            default -> -1;
        };
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
        String text = value == null ? "" : value;
        for (int i = 0; i < 4; i++) {
            String previous = text;
            text = LEADING_TITLE_TAG.matcher(text).replaceFirst("");
            text = TRAILING_TITLE_TAG.matcher(text).replaceFirst("");
            if (previous.equals(text)) break;
        }
        return normalize(text);
    }
}
