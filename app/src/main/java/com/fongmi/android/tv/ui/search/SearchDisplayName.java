package com.fongmi.android.tv.ui.search;

import java.util.regex.Pattern;

/** Removes promotional copy from labels only. Site keys and source data are never changed. */
public final class SearchDisplayName {

    private static final String PROMOTION = "(?:极速|秒播|不卡|多线(?:路)?|无广(?:告)?|蓝光|超清|高清|4K|8K|1080P|杜比|高码|好看|推荐|优质|资源)";
    private static final String DECORATION = "[\\p{So}\\p{Sk}\\p{Cf}\\p{M}\\s]*";
    private static final Pattern BRACKETED_PROMOTION = Pattern.compile(
            "\\s*[【\\[（(][^】\\]）)]{0,20}" + PROMOTION + "[^】\\]）)]{0,20}[】\\]）)]\\s*");
    private static final Pattern SUFFIX_PROMOTION = Pattern.compile(
            "(?:\\s*[-|｜·•_/]+\\s*|\\s{2,})" + DECORATION
                    + "(?:" + PROMOTION + DECORATION + "){1,6}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PROMOTION = Pattern.compile(
            "(?:" + PROMOTION + DECORATION + "){1,3}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EDGE_DECORATION = Pattern.compile(
            "^(?:[\\p{So}\\p{Sk}\\p{Cf}\\p{M}]+\\s*)+|(?:\\s*[\\p{So}\\p{Sk}\\p{Cf}\\p{M}]+)+$");
    private static final Pattern TRAILING_SEPARATOR = Pattern.compile("\\s*[-|·•_/]+\\s*$");
    private static final Pattern EXTRA_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{20E3}]");

    private SearchDisplayName() {
    }

    public static String clean(String value) {
        if (value == null || value.isBlank()) return "";
        String result = BRACKETED_PROMOTION.matcher(removeEmoji(value).trim()).replaceAll(" ");
        String previous;
        do {
            previous = result;
            result = SUFFIX_PROMOTION.matcher(result).replaceFirst("");
        } while (!previous.equals(result));
        result = TRAILING_PROMOTION.matcher(result).replaceFirst("");
        result = TRAILING_SEPARATOR.matcher(result).replaceFirst("");
        result = EDGE_DECORATION.matcher(result).replaceAll("");
        return EXTRA_SPACE.matcher(result).replaceAll(" ").trim();
    }

    public static String removeEmoji(String value) {
        if (value == null || value.isEmpty()) return "";
        return EXTRA_SPACE.matcher(EMOJI.matcher(value).replaceAll(" ")).replaceAll(" ").trim();
    }
}
