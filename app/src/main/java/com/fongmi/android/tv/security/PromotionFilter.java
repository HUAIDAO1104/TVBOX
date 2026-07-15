package com.fongmi.android.tv.security;

import java.text.Normalizer;
import java.util.Locale;

public final class PromotionFilter {

    private static final int MAX_CONTINUATION_LENGTH = 48;

    private PromotionFilter() {
    }

    public static boolean shouldSuppress(String value) {
        if (value == null || value.isEmpty()) return false;
        String text = normalize(value);
        boolean promotion = containsAny(text, "免费", "不迷路", "更新快", "推广", "接口", "福利", "容量", "分享");
        boolean sourceBrand = containsAny(text, "王二小", "王小二", "放牛娃");
        boolean explicitSolicitation = containsAny(text,
                "关注公众号", "关注gzh", "扫码加群", "扫码入群", "加入qq群", "加入微信群", "加qq群", "加微信群");
        boolean community = containsAny(text, "公众号", "gzh", "加群", "qq群", "微信群");
        boolean followBrand = text.contains("关注") && sourceBrand;
        boolean marketingAction = containsAny(text, "扫码", "领取", "回复")
                && (sourceBrand || containsAny(text, "免费", "不迷路", "推广", "福利", "容量"));
        boolean unsolicitedInterfaceAd = containsAny(text, "接口免费", "免费接口", "免费线路", "线路免费", "请勿上当", "谨防受骗");
        return explicitSolicitation || (community && promotion) || followBrand || (sourceBrand && promotion)
                || marketingAction || unsolicitedInterfaceAd;
    }

    /**
     * Sanitizes remote action/config messages before they reach Toast or dialog code.
     * Functional status and error messages are preserved verbatim apart from surrounding space.
     */
    public static String sanitizeMessage(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return shouldSuppress(clean) ? "" : clean;
    }

    /**
     * Removes complete promotional sentences or lines while retaining an adjacent synopsis.
     * This deliberately works on sentence boundaries instead of deleting keywords from titles.
     */
    public static String sanitizeDisplayText(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder clean = new StringBuilder();
        boolean removedPromotion = false;
        for (String segment : value.trim().split("(?<=[。！？!?；;\\n])")) {
            String candidate = segment.trim();
            if (candidate.isEmpty()) continue;
            if (shouldSuppress(candidate)) {
                removedPromotion = true;
                continue;
            }
            if (removedPromotion && isContinuation(candidate)) continue;
            removedPromotion = false;
            if (clean.length() > 0 && !startsWithPunctuation(candidate)) clean.append(' ');
            clean.append(candidate);
        }
        return clean.toString().replaceAll("[\\s　]+", " ").trim();
    }

    /** Only call this for repository/site labels, never for movie or series titles. */
    public static String sanitizeSourceLabel(String value, String fallback) {
        String label = value == null ? "" : value.trim();
        String normalized = normalize(label);
        boolean instruction = containsAny(normalized, "先扫码", "扫码关注", "关注公众号", "公众号", "gzh", "加群", "领取", "更新日期");
        boolean branded = containsAny(normalized, "王二小", "王小二", "放牛娃");
        if (label.isEmpty() || shouldSuppress(label) || instruction || branded) {
            return fallback == null ? "" : fallback.trim();
        }
        return label;
    }

    /** Detects a standalone home action/announcement card, without changing normal work data. */
    public static boolean isPromotionalEntry(String name, String remarks, String content) {
        if (shouldSuppress(name) || shouldSuppress(remarks) || shouldSuppress(content)) return true;
        String title = normalize(name);
        String combined = normalize(join(name, remarks, content));
        boolean announcementTitle = containsAny(title, "公告", "通知", "关注", "加群", "扫码", "领取");
        boolean solicitation = containsAny(combined, "关注公众号", "公众号", "gzh", "加群", "qq群", "微信群", "先扫码", "领取");
        boolean promotion = containsAny(combined, "免费", "不迷路", "更新快", "推广", "接口", "福利", "容量", "分享");
        return announcementTitle && solicitation && promotion;
    }

    private static boolean isContinuation(String value) {
        String normalized = normalize(value);
        return normalized.length() <= MAX_CONTINUATION_LENGTH
                && containsAny(normalized, "更新快", "不迷路", "完全免费", "永久免费", "无广告", "接口免费");
    }

    private static boolean startsWithPunctuation(String value) {
        return !value.isEmpty() && "，。！？!?；;：:,".indexOf(value.charAt(0)) >= 0;
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) if (value != null && !value.isEmpty()) result.append(value).append(' ');
        return result.toString();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s　]+", "");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
