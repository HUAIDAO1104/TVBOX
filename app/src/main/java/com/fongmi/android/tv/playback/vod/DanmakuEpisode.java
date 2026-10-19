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
    private static final Pattern MONTH_DAY = Pattern.compile("(?<![\\d年./-])(?:(\\d{1,2})[月./-](\\d{1,2})(?:日)?|(\\d{2})(\\d{2})(?=期|[上下]|$))(?!\\d|\\s*(?i:gb|mb|tb|kb))");
    private static final Pattern NUMBER = Pattern.compile("(?<![全共\\d])(?:第\\s*)?([0-9零〇一二两三四五六七八九十百]+)\\s*[集期话回](?!\\s*全)");
    private static final Pattern EP = Pattern.compile("(?i)(?:S\\s*\\d{1,2}\\s*E|(?<![a-z])EP?\\.?\\s*)(\\d{1,4})(?!\\d)");
    private static final Pattern FILE = Pattern.compile("(?:^|[\\]）】}\\s._-])0*(\\d{1,3})(?=\\s*\\.(?:mkv|mp4|avi|mov|ts|m2ts|webm)(?:$|[\\s【\\[]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile("(?:[集期话回日]|^|[（(【\\[\\s._-]|(?<!\\d)\\d{1,3})\\s*([上中下])(?:半[集期场部]|集|篇|部|半场)?(?=$|[（(）)】\\]\\s:：一二三四五六七八九十]|[^\\p{L}])");
    private static final Pattern SUBPART = Pattern.compile("[上中下](?:集|篇|部)?\\s*[（(【\\[]([一二三四五六七八九十0-9]+)[）)】\\]]");
    private static final Pattern EXTRA = Pattern.compile("还有加更|会员加更|加更|纯享|衍生|先导|预告|花絮|特辑|特别篇|幕后|训练室|观演区|预热直播|预热|直播|抢先|超前企划|超前营业|超前|精编|陪看|彩蛋|彩排全纪录|彩排|直拍机位|直拍|加料|番外|(?i:plus)");
    private static final Pattern MOVIE_LABEL = Pattern.compile("(?i)^(?:(?:正片|全集|全片|完整版|蓝光|超清|高清|国语|粤语|普通话|中英双字|中字|字幕|简中|繁中|双语|HD|BD|1080P|720P|2160P|4K|修复版|版)[\\s._\\-()（）\\[\\]【】]*)+$");

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
        text = text.replaceFirst("(?i)^.*?\\s*from\\s+[^\\s]+\\s*(?:-\\s*)?(?:【[^】]*】\\s*)?", "");
        return text.replaceFirst("^.*?【(?:综艺|综艺节目|电视剧|电影|动漫|纪录片)】\\s*", "");
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
        String withoutDate = DATE.matcher(text).replaceAll("日");
        if (date.isEmpty()) {
            Matcher shortDate = MONTH_DAY.matcher(text);
            while (shortDate.find()) {
                try {
                    int month = Integer.parseInt(shortDate.group(1) == null ? shortDate.group(3) : shortDate.group(1));
                    int day = Integer.parseInt(shortDate.group(2) == null ? shortDate.group(4) : shortDate.group(2));
                    LocalDate parsed = LocalDate.of(2000, month, day);
                    date = "--" + parsed.toString().substring(5).replace("-", "");
                    withoutDate = text.substring(0, shortDate.start()) + "日" + text.substring(shortDate.end());
                    break;
                } catch (DateTimeException ignored) { }
            }
        }
        String numbered = withoutDate
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
        if (number == null && numbered.trim().matches("\\d{1,3}\\s*[上下中].*"))
            number = Integer.valueOf(numbered.trim().replaceFirst("[^0-9].*$", ""));
        Matcher parts = PART.matcher(EXTRA.matcher(withoutDate.replaceFirst("[:：].*$", "")).replaceAll(" "));
        String part = parts.find() ? parts.group(1) : "";
        // Some providers write 第1期：初舞台（上）. Bracketed parts remain meaningful.
        if (part.isEmpty()) { parts = PART.matcher(withoutDate); if (parts.find()) part = parts.group(1); }
        Matcher sub = SUBPART.matcher(text);
        if (!part.isEmpty() && sub.find()) part += Objects.toString(chineseNumber(sub.group(1)), sub.group(1));
        // Promotional descriptions after a colon may mention extras without being extras.
        String edition = withoutDate.replaceFirst("[:：].*$", "");
        Matcher extras = EXTRA.matcher(edition);
        String extra = extras.find() ? extras.group().toLowerCase(java.util.Locale.ROOT) : "";
        if (extra.equals("会员加更")) extra = "加更";
        Matcher prefix = NUMBER.matcher(edition);
        if (extra.isEmpty() && prefix.find() && prefix.group().endsWith("期")) {
            String namedEdition = edition.substring(0, prefix.start()).replaceAll("^[日\\s._-]+|[\\s._-]+$", "");
            if (!namedEdition.isEmpty() && !namedEdition.matches("正片|完整版")) extra = namedEdition;
        }
        return new DanmakuEpisode(date, number, part, extra);
    }

    static boolean isMovieLabel(String value) { return MOVIE_LABEL.matcher(label(value)).matches(); }

    boolean matches(DanmakuEpisode actual) {
        if (!date.isEmpty() && !sameDate(actual)) return false;
        // A release date is sometimes duplicated across a whole season by aggregators.
        // When both sides also identify a period, an explicit conflict must still reject it.
        if (number != null && actual.number != null && !number.equals(actual.number)) return false;
        if (date.isEmpty() && number != null && !number.equals(actual.number)) return false;
        if (!part.isEmpty() && !part.equals(actual.part)) return false;
        // Main programme, extras and previews are different timelines even on the same day.
        return extra.equals(actual.extra);
    }

    boolean sameDate(DanmakuEpisode actual) {
        if (date.isEmpty() || actual.date.isEmpty()) return false;
        return date.equals(actual.date) || (date.startsWith("--") || actual.date.startsWith("--"))
                && date.substring(date.length() - 4).equals(actual.date.substring(actual.date.length() - 4));
    }

    boolean identified() { return !date.isEmpty() || number != null; }

    String timelineKey() { return key() + (!date.isEmpty() && number != null ? "|period:" + number : ""); }

    boolean sameTimeline(DanmakuEpisode other) {
        if (!part.equals(other.part) || !extra.equals(other.extra)) return false;
        if (number != null && other.number != null && !number.equals(other.number)) return false;
        if (!date.isEmpty() && !other.date.isEmpty() && !sameDate(other)) return false;
        // A dated platform row and an undated mirror row can still identify the same
        // numbered period. A date-only row and a number-only row cannot establish that link.
        return number != null && number.equals(other.number)
                || !date.isEmpty() && sameDate(other)
                || !identified() && !other.identified();
    }

    String key() {
        String identity = !date.isEmpty() ? "date:" + date : number == null ? "" : number.toString();
        if (identity.isEmpty() && !extra.isEmpty()) identity = "edition";
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
