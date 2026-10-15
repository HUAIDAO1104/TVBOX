package com.fongmi.android.tv.playback.vod;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Episode identity shared by matching, manual results and saved selections. */
final class DanmakuEpisode {
    private static final Pattern DATE = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})[年./-]?(\\d{2}|\\d(?=[月./-]))[月./-]?(\\d{2}|\\d(?=日|期|\\D|$))(?!\\d)");
    private static final Pattern NUMBER = Pattern.compile("(?<![全共\\d])(?:第\\s*)?([0-9零〇一二两三四五六七八九十百]+)\\s*[集期话回](?!\\s*全)");
    private static final Pattern EP = Pattern.compile("(?i)(?:S\\s*\\d{1,2}\\s*E|(?<![a-z])EP?\\.?\\s*)(\\d{1,4})(?!\\d)");
    private static final Pattern FILE = Pattern.compile("(?:^|[\\]）】}\\s._-])0*(\\d{1,3})(?=\\s*\\.(?:mkv|mp4|avi|mov|ts|m2ts|webm)(?:$|[\\s【\\[]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile("(?:[集期话回日]|(?<!\\d)\\d{8}|^|[（(【\\[])\\s*([上中下])(?:集|篇|部|半场)?(?=$|[（(）)】\\]\\s:：一二三四五六七八九十]|[^\\p{L}])");
    private static final Pattern SUBPART = Pattern.compile("[上中下](?:集|篇|部)?\\s*[（(【\\[]([一二三四五六七八九十0-9]+)[）)】\\]]");
    private static final Pattern EXTRA = Pattern.compile("还有加更|会员加更|加更|纯享|衍生|先导|预告|花絮|特辑|特别篇|幕后|训练室|观演区|预热|直播|抢先|超前|精编|陪看|彩蛋");
    private static final Pattern MOVIE_LABEL = Pattern.compile("(?i)^(?:正片|全集|全片|完整版|蓝光|超清|高清|国语|粤语|中字|中字版|HD|BD|HD中字|HD国语|HD粤语|1080P|720P|2160P|4K)(?:[\\s._-]*(?:中字|国语|粤语|高清|修复版|版|1080P|4K))*$");

    final String date;
    final Integer number;
    final String part;
    final String extra;

    private DanmakuEpisode(String date, Integer number, String part, String extra) {
        this.date = date; this.number = number; this.part = part; this.extra = extra;
    }

    static String label(String value) {
        String text = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC).trim();
        // A provider's work title/year must not be parsed as an episode or release date.
        return text.replaceFirst("(?i)^.*?\\s*from\\s+[^\\s]+\\s*(?:-\\s*)?(?:【[^】]*】\\s*)?", "");
    }

    static DanmakuEpisode from(String value) {
        String text = label(value);
        Matcher dates = DATE.matcher(text);
        String date = "";
        while (dates.find()) {
            try {
                LocalDate parsed = LocalDate.of(Integer.parseInt(dates.group(1)),
                        Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3)));
                date = parsed.toString().replace("-", "");
                break;
            } catch (DateTimeException ignored) { }
        }
        String numbered = DATE.matcher(text).replaceAll(" ")
                .replaceAll("(?:[全共]\\s*\\d{1,4}\\s*[集期话回](?:\\s*全)?|\\d{1,4}\\s*[集期话回]\\s*全)", " ");
        Integer number = null;
        Matcher match = EP.matcher(numbered);
        if (match.find()) number = Integer.valueOf(match.group(1));
        if (number == null) {
            match = NUMBER.matcher(numbered);
            if (match.find()) number = chineseNumber(match.group(1));
        }
        if (number == null) {
            match = FILE.matcher(numbered);
            if (match.find()) number = Integer.valueOf(match.group(1));
        }
        if (number == null && numbered.trim().matches("\\d{1,3}")) number = Integer.valueOf(numbered.trim());
        Matcher parts = PART.matcher(DATE.matcher(text).replaceAll("$1$2$3"));
        String part = parts.find() ? parts.group(1) : "";
        Matcher sub = SUBPART.matcher(text);
        if (sub.find()) part += Objects.toString(chineseNumber(sub.group(1)), sub.group(1));
        Matcher extras = EXTRA.matcher(text);
        return new DanmakuEpisode(date, number, part, extras.find() ? extras.group() : "");
    }

    static boolean isMovieLabel(String value) { return MOVIE_LABEL.matcher(label(value)).matches(); }

    boolean matches(DanmakuEpisode actual) {
        if (!date.isEmpty() && !date.equals(actual.date)) return false;
        if (date.isEmpty() && number != null && !number.equals(actual.number)) return false;
        if (!part.isEmpty() && !part.equals(actual.part)) return false;
        // Main programme, extras and previews are different timelines even on the same day.
        return extra.equals(actual.extra);
    }

    boolean identified() { return !date.isEmpty() || number != null; }

    String key() {
        String identity = !date.isEmpty() ? "date:" + date : number == null ? "" : number.toString();
        return identity.isEmpty() ? "" : identity + (part.isEmpty() ? "" : "|" + part)
                + (extra.isEmpty() ? "" : "|" + extra);
    }

    private static Integer chineseNumber(String value) {
        try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { }
        int total = 0, digit = 0;
        for (char c : value.toCharArray()) {
            if (c == '百') { total += Math.max(1, digit) * 100; digit = 0; }
            else if (c == '十') { total += Math.max(1, digit) * 10; digit = 0; }
            else {
                digit = "零一二三四五六七八九".indexOf(c == '两' ? '二' : c == '〇' ? '零' : c);
                if (digit < 0) return null;
            }
        }
        return total + digit;
    }
}
