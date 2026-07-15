package com.fongmi.android.tv.ui.search;

import com.github.catvod.utils.Trans;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java title normalization used by aggregate search. */
public final class SearchTitleNormalizer {

    private static final String CHINESE_NUMBER = "零〇一二两三四五六七八九十百千万";
    private static final Pattern WRAPPED = Pattern.compile("[\\[【（(｛{]([^\\]】）)｝}]{1,40})[\\]】）)｝}]");
    private static final Pattern LEADING_CATALOG_SYMBOL = Pattern.compile("^[\\p{So}\\p{Sk}\\p{Sm}]+\\s+(?=\\S)");
    private static final Pattern LEADING_CATALOG_BADGE = Pattern.compile(
            "^\\s*[【\\[](?:新|最新|new|国剧|國劇|国产剧|國產劇|电视剧|電視劇|剧集|劇集|电影|電影|综艺|綜藝|动漫|動漫|短剧|短劇)[】\\]]\\s*(?=\\S)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_WRAPPED = Pattern.compile("^[\\[【《（(｛{]\\s*(.+?)\\s*[\\]】》）)｝}]$");
    private static final Pattern EXPLICIT_INSTALLMENT = Pattern.compile("^(.+?)(?:第)?([0-9" + CHINESE_NUMBER + "]+)(季|部)$");
    private static final Pattern ENGLISH_INSTALLMENT = Pattern.compile("^(.+?)(?:season|part|pt|s)([0-9]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_INSTALLMENT = Pattern.compile("^(.+?)([0-9]+|[ivxlcdm]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VARIANT_SUFFIX = Pattern.compile("(?:电影版|劇場版|剧场版|電視劇版|电视剧版|電視版|电视版|劇集版|剧集版|綜藝版|综艺版|真人秀版|動畫版|动画版|動漫版|动漫版|正片|花絮|幕後|幕后|彩蛋|解說|解说|講解|讲解|預告片|预告片|預告|预告)$", Pattern.CASE_INSENSITIVE);
    private static final String EDGE_SEPARATOR = "[-+|｜·•_/:：.,，。！!~～【】\\[\\]（）()\\s]";
    private static final String EPISODE_LABEL = "(?:第)?[0-9" + CHINESE_NUMBER + "]+(?:[-~～—–至到][0-9" + CHINESE_NUMBER + "]+)?集(?:已更|完结|完結)?(?:上|下)?";
    private static final String RELEASE_LABEL = "(?:4k|8k|2160p|1080p|720p|uhd|fhd|hd|bd|web(?:-?dl|-)?|h\\.?26[45]|hevc|hdr10?|dolby|dovi|dv|imax|remux|60fps|120fps|杜比|藍光|蓝光|超清|高清|標清|标清|臻彩|高碼率|高码率|高碼|高码|高幀率|簡體字幕|简体字幕|繁體字幕|繁体字幕|简中|簡中|繁中|字幕|修復版|修复版|導演剪輯版|导演剪辑版|完整版|無刪減|无删减|搶先版|抢先版|國語|国语|普通話|普通话|粵語|粤语|英語|英语|中字|中英雙字|中英双字|雙語|双语|原聲|原声|配音版|完整合集|完整合辑|合集|合辑|連載中|连载中|全集|完結|完结|已完結|已完结|已更|夸克网盘|夸克網盤|百度网盘|百度網盤|阿里云盘|阿里雲盤|阿里网盘|阿里網盤|迅雷云盘|迅雷雲盤|uc网盘|uc網盤|天翼云盘|天翼雲盤|115网盘|115網盤|123云盘|123雲盤|城通网盘|城通網盤|蓝奏云盘|藍奏雲盤|pikpak网盘|pikpak網盤|移动云盘|移動雲盤|更新至?(?:第)?[0-9" + CHINESE_NUMBER + "]+(?:集)?|更(?:至)?(?:ep)?[0-9" + CHINESE_NUMBER + "]*(?:集)?|s[0-9]{1,2}e[0-9]{1,3}(?:" + EDGE_SEPARATOR + "*(?:-|~|～|—|–|至)" + EDGE_SEPARATOR + "*e?[0-9]{1,3})?|" + EPISODE_LABEL + "|全[0-9" + CHINESE_NUMBER + "~～-]*集|(?:19|20)[0-9]{2})";
    private static final Pattern EDGE_NOISE = Pattern.compile(
            "(?:" + EDGE_SEPARATOR + "*(?:" + RELEASE_LABEL + "))+" + EDGE_SEPARATOR + "*$",
            Pattern.CASE_INSENSITIVE);
    // Some cloud/catalog providers append genre facets directly to the display title. Only strip
    // facets that are separated from the real title, so legitimate names such as 《父母爱情》 stay intact.
    private static final String CATALOG_GENRE = "(?:剧情|愛情|爱情|喜劇|喜剧|動作|动作|古裝|古装|玄幻|武俠|犯罪|懸疑|悬疑|驚悚|惊悚|恐怖|科幻|奇幻|冒險|冒险|戰爭|战争|歷史|历史|傳記|传记|家庭|兒童|儿童|音樂|音乐|歌舞|運動|运动|真人秀|紀錄片|纪录片|動畫|动画|短片|同性|災難|灾难|西部|國產劇|国产剧|大陸劇|大陆剧|電視劇|电视剧|電影|电影)";
    private static final Pattern CATALOG_GENRE_TOKEN = Pattern.compile("^" + CATALOG_GENRE + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_CATALOG_METADATA = Pattern.compile(
            "(?:" + EDGE_SEPARATOR + "+" + CATALOG_GENRE + "){2,}" + EDGE_SEPARATOR + "*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_QUALITY_CATALOG = Pattern.compile(
            EDGE_SEPARATOR + "+(?:4k|8k|2160p|1080p|720p)" + EDGE_SEPARATOR
                    + "*(?:首发?|首|超|新|荐|推荐)?" + EDGE_SEPARATOR + "*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_NOISE = Pattern.compile(
            "^(?:" + EDGE_SEPARATOR + "*(?:全[0-9" + CHINESE_NUMBER + "~～-]*集|更新至?(?:第)?[0-9" + CHINESE_NUMBER + "]+集|" + EPISODE_LABEL + "))+" + EDGE_SEPARATOR + "*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL_NOISE = Pattern.compile(
            "(?:4k|8k|2160p|1080p|720p|uhd|fhd|hd|bd|web(?:-?dl|-)?|h\\.?26[45]|hevc|hdr10?|dolby|dovi|imax|remux|60fps|120fps|杜比|藍光|蓝光|超清|高清|標清|标清|臻彩|高碼率|高码率|高幀率|簡體字幕|简体字幕|繁體字幕|繁体字幕|字幕|夸克网盘|夸克網盤|百度网盘|阿里云盘|迅雷云盘|网盘|網盤|完整版|無刪減|无删减|搶先版|抢先版|國語|国语|普通話|普通话|粵語|粤语|英語|英语|中字|中英雙字|中英双字|雙語|双语|原聲|原声|配音|更新|連載|连载|全集|完結|完结|全[0-9" + CHINESE_NUMBER + "~～-]*集|(?:19|20)[0-9]{2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTRA_SPACE = Pattern.compile("\\s+");

    public enum InstallmentKind {
        NONE, SEASON, PART
    }

    public enum MediaVariant {
        UNSPECIFIED, MAIN, MOVIE, SERIES, VARIETY, ANIMATION, EXTRA, COMMENTARY
    }

    public record NormalizedTitle(
            String original,
            String displayTitle,
            String baseKey,
            InstallmentKind installmentKind,
            int installment,
            MediaVariant mediaVariant) {

        public String identityKey() {
            StringBuilder key = new StringBuilder(baseKey);
            if (installmentKind != InstallmentKind.NONE && installment > 0) {
                key.append('#').append(installmentKind.name().toLowerCase(Locale.ROOT)).append(installment);
            }
            if (mediaVariant != MediaVariant.UNSPECIFIED) {
                key.append('@').append(mediaVariant.name().toLowerCase(Locale.ROOT));
            }
            return key.toString();
        }

        public boolean isEmpty() {
            return baseKey.isEmpty();
        }
    }

    private SearchTitleNormalizer() {
    }

    /** Returns a deterministic comparison key, not a user-facing title. */
    public static String normalize(String value) {
        return parse(value).identityKey();
    }

    public static NormalizedTitle parse(String value) {
        String original = value == null ? "" : value;
        String text = prepare(original);
        text = stripWrappedNoise(text);
        text = stripEdgeNoise(text);
        text = stripTitleWrapper(text);
        String display = normalizeSpaces(text);
        MediaVariant variant = detectVariant(text);
        String compact = compact(text);
        compact = VARIANT_SUFFIX.matcher(compact).replaceFirst("");

        InstallmentKind kind = InstallmentKind.NONE;
        int installment = 0;
        Matcher explicit = EXPLICIT_INSTALLMENT.matcher(compact);
        if (explicit.matches()) {
            int parsed = parseNumber(explicit.group(2));
            if (parsed > 0) {
                compact = explicit.group(1);
                installment = parsed;
                kind = "季".equals(explicit.group(3)) ? InstallmentKind.SEASON : InstallmentKind.PART;
            }
        } else {
            Matcher english = ENGLISH_INSTALLMENT.matcher(compact);
            if (english.matches()) {
                int parsed = parseNumber(english.group(2));
                if (parsed > 0) {
                    String marker = compact.substring(english.group(1).length()).toLowerCase(Locale.ROOT);
                    compact = english.group(1);
                    installment = parsed;
                    kind = marker.startsWith("s") && !marker.startsWith("season")
                            || marker.startsWith("season") ? InstallmentKind.SEASON : InstallmentKind.PART;
                }
            } else {
                Matcher trailing = TRAILING_INSTALLMENT.matcher(compact);
                if (trailing.matches() && hasLexicalPrefix(trailing.group(1))) {
                    int parsed = parseNumber(trailing.group(2));
                    if (parsed > 0 && parsed <= 100) {
                        compact = trailing.group(1);
                        installment = parsed;
                        kind = InstallmentKind.PART;
                    }
                }
            }
        }
        return new NormalizedTitle(original, display, compact, kind, installment, variant);
    }

    /**
     * Bare trailing numerals are only installment markers when a real title precedes them.
     * This deliberately keeps numeric and Roman-numeral titles such as "2046" and "III"
     * intact instead of guessing a sequel relationship.
     */
    private static boolean hasLexicalPrefix(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isDigit(codePoint)
                    && "ivxlcdm".indexOf(Character.toLowerCase(codePoint)) < 0) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    static int parseNumber(String value) {
        if (value == null || value.isEmpty()) return -1;
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        if (value.matches("(?i)[ivxlcdm]+")) return parseRoman(value);
        return parseChinese(value);
    }

    private static String prepare(String value) {
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("&nbsp;", " ")
                .replaceAll("<[^>]{1,80}>", " ");
        // The shared character table intentionally preserves a few lexical variants (for
        // example 餘 -> 馀). Aggregate identity needs one canonical form for those variants.
        return Trans.t2s(false, text).replace('馀', '余').toLowerCase(Locale.ROOT).trim();
    }

    private static String stripWrappedNoise(String value) {
        String current = stripCatalogLead(value);
        for (int pass = 0; pass < 3; pass++) {
            Matcher matcher = WRAPPED.matcher(current);
            StringBuffer result = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                if (isNoiseLabel(matcher.group(1))) {
                    matcher.appendReplacement(result, " ");
                    changed = true;
                } else {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(result);
            current = result.toString();
            if (!changed) break;
        }
        return current;
    }

    private static boolean isNoiseLabel(String value) {
        return LABEL_NOISE.matcher(compact(value)).find();
    }

    private static String stripEdgeNoise(String value) {
        String current = value;
        String previous;
        do {
            previous = current;
            String candidate = LEADING_NOISE.matcher(current.trim()).replaceFirst("").trim();
            candidate = TRAILING_QUALITY_CATALOG.matcher(candidate).replaceFirst("").trim();
            candidate = EDGE_NOISE.matcher(candidate).replaceFirst("").trim();
            candidate = TRAILING_CATALOG_METADATA.matcher(candidate).replaceFirst("").trim();
            if (compact(candidate).isEmpty()) break;
            current = candidate;
        } while (!previous.equals(current));
        return current;
    }

    private static String stripTitleWrapper(String value) {
        Matcher matcher = TITLE_WRAPPED.matcher(value.trim());
        return matcher.matches() ? matcher.group(1).trim() : value.trim();
    }

    private static MediaVariant detectVariant(String value) {
        String compact = compact(value);
        if (compact.matches(".*(?:解说|讲解)$")) return MediaVariant.COMMENTARY;
        if (compact.matches(".*(?:花絮|幕后|彩蛋|预告片?|预告)$")) return MediaVariant.EXTRA;
        if (compact.endsWith("综艺版")) return MediaVariant.VARIETY;
        if (compact.matches(".*(?:动画版|动漫版)$")) return MediaVariant.ANIMATION;
        if (compact.matches(".*(?:电影版|剧场版)$")) return MediaVariant.MOVIE;
        if (compact.matches(".*(?:电视剧版|电视版|剧集版)$")) return MediaVariant.SERIES;
        if (compact.endsWith("正片")) return MediaVariant.MAIN;
        return MediaVariant.UNSPECIFIED;
    }

    private static String compact(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) result.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String normalizeSpaces(String value) {
        return EXTRA_SPACE.matcher(value.trim()).replaceAll(" ");
    }

    /**
     * Detects provider catalog titles such as "野狗骨头 剧情 爱情 张婧仪". This is deliberately
     * query-relative and requires at least two suffix facets, including a known genre, so an
     * ordinary spaced title such as "父母 爱情" is not silently shortened.
     */
    static boolean hasCatalogMetadataSuffix(String keyword, String candidate) {
        String expected = prepare(keyword);
        String actual = stripCatalogLead(prepare(candidate));
        if (expected.isEmpty() || actual.length() <= expected.length() || !actual.startsWith(expected)) return false;
        int boundary = actual.codePointAt(expected.length());
        if (Character.isLetterOrDigit(boundary)) return false;
        String suffix = actual.substring(expected.length()).trim();
        if (suffix.isEmpty()) return false;
        int tokenCount = 0;
        boolean hasGenre = false;
        for (String token : suffix.split(EDGE_SEPARATOR + "+")) {
            if (token.isEmpty()) continue;
            tokenCount++;
            if (CATALOG_GENRE_TOKEN.matcher(token).matches()) hasGenre = true;
        }
        if (tokenCount >= 2 && hasGenre) return true;
        String[] people = suffix.split("\\s*[/|｜、,，]\\s*");
        if (people.length < 2) return false;
        for (String person : people) {
            String compact = person.replaceAll("\\s+", "");
            if (!compact.matches("[\\p{L}·•]{2,10}")) return false;
        }
        return true;
    }

    private static String stripCatalogLead(String value) {
        String current = LEADING_CATALOG_SYMBOL.matcher(value).replaceFirst("");
        return LEADING_CATALOG_BADGE.matcher(current).replaceFirst("");
    }

    private static int parseChinese(String value) {
        boolean hasUnit = value.chars().anyMatch(c -> c == '十' || c == '百' || c == '千' || c == '万');
        if (!hasUnit) {
            int result = 0;
            for (int i = 0; i < value.length(); i++) {
                int digit = chineseDigit(value.charAt(i));
                if (digit < 0) return -1;
                result = result * 10 + digit;
            }
            return result;
        }
        int total = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int digit = chineseDigit(c);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = chineseUnit(c);
            if (unit < 0) return -1;
            if (unit == 10_000) {
                section = (section + number) * unit;
                total += section;
                section = 0;
            } else {
                if (number == 0) number = 1;
                section += number * unit;
            }
            number = 0;
        }
        return total + section + number;
    }

    private static int chineseDigit(char value) {
        return switch (value) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
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

    private static int chineseUnit(char value) {
        return switch (value) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1_000;
            case '万' -> 10_000;
            default -> -1;
        };
    }

    private static int parseRoman(String value) {
        String roman = value.toUpperCase(Locale.ROOT);
        int total = 0;
        int previous = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int current = romanValue(roman.charAt(i));
            if (current == 0) return -1;
            if (current < previous) total -= current;
            else {
                total += current;
                previous = current;
            }
        }
        return total > 0 && roman.equals(toRoman(total)) ? total : -1;
    }

    private static int romanValue(char value) {
        return switch (value) {
            case 'I' -> 1;
            case 'V' -> 5;
            case 'X' -> 10;
            case 'L' -> 50;
            case 'C' -> 100;
            case 'D' -> 500;
            case 'M' -> 1_000;
            default -> 0;
        };
    }

    private static String toRoman(int value) {
        if (value <= 0 || value > 3_999) return "";
        int[] numbers = {1_000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < numbers.length; i++) {
            while (value >= numbers[i]) {
                value -= numbers[i];
                result.append(symbols[i]);
            }
        }
        return result.toString();
    }
}
