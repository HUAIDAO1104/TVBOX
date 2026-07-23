package com.fongmi.android.tv.setting;

import android.os.Build;

import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.api.config.VodConfig;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class DanmakuSetting {

    static final String DEFAULT_API_URL = "https://danmu.xyy.red/api/v2/fongmi/danmaku?name={name}&episode={episode}";

    private static final float MIN_TEXT_SCALE = 0.5f;
    private static final float MAX_TEXT_SCALE = 3.0f;
    private static final float MIN_TRANSPARENCY = 0.0f;
    private static final float MAX_TRANSPARENCY = 0.9f;
    private static final float MIN_STROKE_WIDTH_MULTIPLIER = 0.05f;
    private static final float MAX_STROKE_WIDTH_MULTIPLIER = 0.3f;
    private static final float MIN_PROJECTION_OFFSET = 0.02f;
    private static final float MAX_PROJECTION_OFFSET = 0.15f;
    private static final long MIN_TIME_OFFSET_MS = -300000L;
    private static final long MAX_TIME_OFFSET_MS = 300000L;
    private static final long MIN_DURATION_MS = 3000L;
    private static final long MAX_DURATION_MS = 15000L;
    private static final long MIN_FIXED_DURATION_MS = 2000L;
    private static final long MAX_FIXED_DURATION_MS = 10000L;
    private static final int MIN_MAX_ON_SCREEN = 10;
    private static final int MAX_MAX_ON_SCREEN = 500;
    private static final float MIN_SCROLL_AREA_RATIO = 0.1f;
    private static final float MAX_SCROLL_AREA_RATIO = 1.0f;
    private static final float MIN_SCROLL_GAP_RATIO = 0.0f;
    private static final float MAX_SCROLL_GAP_RATIO = 5.0f;
    private static final float MIN_LINE_SPACING = 1.0f;
    private static final float MAX_LINE_SPACING = 2.0f;
    private static final int MIN_MAX_SCROLL_LINES = 0;
    private static final int MAX_MAX_SCROLL_LINES = 20;
    private static final int MIN_MAX_FIXED_LINES = 0;
    private static final int MAX_MAX_FIXED_LINES = 10;

    public static boolean isLoad() {
        return Prefers.getBoolean("danmaku_load", true);
    }

    public static void putLoad(boolean danmakuLoad) {
        Prefers.put("danmaku_load", danmakuLoad);
        if (danmakuLoad) putShow(true);
    }

    public static boolean isAuto() {
        return Prefers.getBoolean("danmaku_auto", true);
    }

    public static void putAuto(boolean auto) {
        Prefers.put("danmaku_auto", auto);
    }

    public static boolean isSpiderFirst() {
        return Prefers.getBoolean("danmaku_spider_first");
    }

    public static void putSpiderFirst(boolean spiderFirst) {
        Prefers.put("danmaku_spider_first", spiderFirst);
    }

    public static String getApiUrl() {
        return Prefers.getString("danmaku_api_url", "");
    }

    public static void putApiUrl(String url) {
        Prefers.put("danmaku_api_url", url);
    }

    public static boolean isShow() {
        return Prefers.getBoolean("danmaku_show", true);
    }

    public static void putShow(boolean danmakuShow) {
        Prefers.put("danmaku_show", danmakuShow);
    }

    public static float getTextScale() {
        return clamp(Prefers.getFloat("danmaku_text_scale", 1f), MIN_TEXT_SCALE, MAX_TEXT_SCALE);
    }

    public static void putTextScale(float value) {
        Prefers.put("danmaku_text_scale", clamp(value, MIN_TEXT_SCALE, MAX_TEXT_SCALE));
    }

    public static float getTransparency() {
        return clamp(Prefers.getFloat("danmaku_transparency", 0f), MIN_TRANSPARENCY, MAX_TRANSPARENCY);
    }

    public static void putTransparency(float value) {
        Prefers.put("danmaku_transparency", clamp(value, MIN_TRANSPARENCY, MAX_TRANSPARENCY));
    }

    public static boolean isTextBold() {
        return Prefers.getBoolean("danmaku_text_bold");
    }

    public static void putTextBold(boolean value) {
        Prefers.put("danmaku_text_bold", value);
    }

    public static int getStyleMode() {
        return Prefers.getInt("danmaku_style_mode", DanmakuConfig.STYLE_STROKE);
    }

    public static void putStyleMode(int value) {
        Prefers.put("danmaku_style_mode", value);
    }

    public static int getColorMode() {
        return Prefers.getInt("danmaku_color_mode", DanmakuConfig.COLOR_MODE_DEFAULT);
    }

    public static void putColorMode(int value) {
        Prefers.put("danmaku_color_mode", value);
    }

    public static float getShadowTransparency() {
        return clamp(Prefers.getFloat("danmaku_shadow_transparency", 0.1f), MIN_TRANSPARENCY, MAX_TRANSPARENCY);
    }

    public static void putShadowTransparency(float value) {
        Prefers.put("danmaku_shadow_transparency", clamp(value, MIN_TRANSPARENCY, MAX_TRANSPARENCY));
    }

    public static float getStrokeWidthMultiplier() {
        return clamp(Prefers.getFloat("danmaku_stroke_width_multiplier", 0.12f), MIN_STROKE_WIDTH_MULTIPLIER, MAX_STROKE_WIDTH_MULTIPLIER);
    }

    public static void putStrokeWidthMultiplier(float value) {
        Prefers.put("danmaku_stroke_width_multiplier", clamp(value, MIN_STROKE_WIDTH_MULTIPLIER, MAX_STROKE_WIDTH_MULTIPLIER));
    }

    public static float getProjectionOffsetX() {
        return clamp(Prefers.getFloat("danmaku_projection_offset_x", 0.08f), MIN_PROJECTION_OFFSET, MAX_PROJECTION_OFFSET);
    }

    public static void putProjectionOffsetX(float value) {
        Prefers.put("danmaku_projection_offset_x", clamp(value, MIN_PROJECTION_OFFSET, MAX_PROJECTION_OFFSET));
    }

    public static float getProjectionOffsetY() {
        return clamp(Prefers.getFloat("danmaku_projection_offset_y", 0.08f), MIN_PROJECTION_OFFSET, MAX_PROJECTION_OFFSET);
    }

    public static void putProjectionOffsetY(float value) {
        Prefers.put("danmaku_projection_offset_y", clamp(value, MIN_PROJECTION_OFFSET, MAX_PROJECTION_OFFSET));
    }

    public static float getProjectionTransparency() {
        return clamp(Prefers.getFloat("danmaku_projection_transparency", 0.2f), MIN_TRANSPARENCY, MAX_TRANSPARENCY);
    }

    public static void putProjectionTransparency(float value) {
        Prefers.put("danmaku_projection_transparency", clamp(value, MIN_TRANSPARENCY, MAX_TRANSPARENCY));
    }

    public static long getDurationMs() {
        return clamp(Prefers.getLong("danmaku_duration", 8000L), MIN_DURATION_MS, MAX_DURATION_MS);
    }

    public static void putDurationMs(long value) {
        Prefers.put("danmaku_duration", clamp(value, MIN_DURATION_MS, MAX_DURATION_MS));
    }

    public static long getFixedDurationMs() {
        return clamp(Prefers.getLong("danmaku_fixed_duration", 5000L), MIN_FIXED_DURATION_MS, MAX_FIXED_DURATION_MS);
    }

    public static void putFixedDurationMs(long value) {
        Prefers.put("danmaku_fixed_duration", clamp(value, MIN_FIXED_DURATION_MS, MAX_FIXED_DURATION_MS));
    }

    public static long getTimeOffsetMs() {
        return clamp(Prefers.getLong("danmaku_time_offset", 0L), MIN_TIME_OFFSET_MS, MAX_TIME_OFFSET_MS);
    }

    public static void putTimeOffsetMs(long value) {
        Prefers.put("danmaku_time_offset", clamp(value, MIN_TIME_OFFSET_MS, MAX_TIME_OFFSET_MS));
    }

    public static int getMaxOnScreen() {
        return clamp(Prefers.getInt("danmaku_max_on_screen", 150), MIN_MAX_ON_SCREEN, MAX_MAX_ON_SCREEN);
    }

    public static void putMaxOnScreen(int value) {
        Prefers.put("danmaku_max_on_screen", clamp(value, MIN_MAX_ON_SCREEN, MAX_MAX_ON_SCREEN));
    }

    public static float getScrollAreaRatio() {
        return clamp(Prefers.getFloat("danmaku_scroll_area_ratio", 0.5f), MIN_SCROLL_AREA_RATIO, MAX_SCROLL_AREA_RATIO);
    }

    public static void putScrollAreaRatio(float value) {
        Prefers.put("danmaku_scroll_area_ratio", clamp(value, MIN_SCROLL_AREA_RATIO, MAX_SCROLL_AREA_RATIO));
    }

    public static int getMaxScrollLines() {
        return clamp(Prefers.getInt("danmaku_max_scroll_lines", 0), MIN_MAX_SCROLL_LINES, MAX_MAX_SCROLL_LINES);
    }

    public static void putMaxScrollLines(int value) {
        Prefers.put("danmaku_max_scroll_lines", clamp(value, MIN_MAX_SCROLL_LINES, MAX_MAX_SCROLL_LINES));
    }

    public static int getMaxTopLines() {
        return clamp(Prefers.getInt("danmaku_max_top_lines", 0), MIN_MAX_FIXED_LINES, MAX_MAX_FIXED_LINES);
    }

    public static void putMaxTopLines(int value) {
        Prefers.put("danmaku_max_top_lines", clamp(value, MIN_MAX_FIXED_LINES, MAX_MAX_FIXED_LINES));
    }

    public static int getMaxBottomLines() {
        return clamp(Prefers.getInt("danmaku_max_bottom_lines", 0), MIN_MAX_FIXED_LINES, MAX_MAX_FIXED_LINES);
    }

    public static void putMaxBottomLines(int value) {
        Prefers.put("danmaku_max_bottom_lines", clamp(value, MIN_MAX_FIXED_LINES, MAX_MAX_FIXED_LINES));
    }

    public static float getLineSpacing() {
        return clamp(Prefers.getFloat("danmaku_line_spacing", 1.4f), MIN_LINE_SPACING, MAX_LINE_SPACING);
    }

    public static void putLineSpacing(float value) {
        Prefers.put("danmaku_line_spacing", clamp(value, MIN_LINE_SPACING, MAX_LINE_SPACING));
    }

    public static float getScrollGapRatio() {
        return clamp(Prefers.getFloat("danmaku_scroll_gap_ratio", 0f), MIN_SCROLL_GAP_RATIO, MAX_SCROLL_GAP_RATIO);
    }

    public static void putScrollGapRatio(float value) {
        Prefers.put("danmaku_scroll_gap_ratio", clamp(value, MIN_SCROLL_GAP_RATIO, MAX_SCROLL_GAP_RATIO));
    }

    public static boolean isShowScroll() {
        return Prefers.getBoolean("danmaku_show_scroll", true);
    }

    public static void putShowScroll(boolean value) {
        Prefers.put("danmaku_show_scroll", value);
    }

    public static boolean isShowTop() {
        return Prefers.getBoolean("danmaku_show_top", true);
    }

    public static void putShowTop(boolean value) {
        Prefers.put("danmaku_show_top", value);
    }

    public static boolean isShowBottom() {
        return Prefers.getBoolean("danmaku_show_bottom", true);
    }

    public static void putShowBottom(boolean value) {
        Prefers.put("danmaku_show_bottom", value);
    }

    public static boolean isShowReverse() {
        return Prefers.getBoolean("danmaku_show_reverse", true);
    }

    public static void putShowReverse(boolean value) {
        Prefers.put("danmaku_show_reverse", value);
    }

    public static boolean isShowPositioned() {
        return Prefers.getBoolean("danmaku_show_positioned", true);
    }

    public static void putShowPositioned(boolean value) {
        Prefers.put("danmaku_show_positioned", value);
    }

    public static boolean isShowSubtitle() {
        return Prefers.getBoolean("danmaku_show_subtitle", true);
    }

    public static void putShowSubtitle(boolean value) {
        Prefers.put("danmaku_show_subtitle", value);
    }

    public static boolean isShowSpecial() {
        return Prefers.getBoolean("danmaku_show_special", true);
    }

    public static void putShowSpecial(boolean value) {
        Prefers.put("danmaku_show_special", value);
    }

    public static String getEffectiveApiUrl() {
        return resolveApiUrl(getApiUrl(), VodConfig.get().getConfig().getDanmaku());
    }

    /**
     * Automatic matching intentionally uses the built-in catalogue. Repository supplied and
     * user supplied endpoints remain available to manual search, where a person can inspect the
     * candidates before applying one. Letting the active repository replace the automatic
     * endpoint made a repository switch silently change the identity rules of playback.
     */
    public static String getAutomaticApiUrl() {
        return DEFAULT_API_URL;
    }

    /** Ordered, de-duplicated endpoints used by manual matching. */
    public static List<String> getSearchApiUrls() {
        return resolveSearchApiUrls(getApiUrl(), VodConfig.get().getConfig().getDanmaku(), DEFAULT_API_URL);
    }

    static List<String> resolveSearchApiUrls(String userUrl, String repositoryUrl, String defaultUrl) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addUrl(urls, userUrl);
        addUrl(urls, repositoryUrl);
        addUrl(urls, defaultUrl);
        return new ArrayList<>(urls);
    }

    private static void addUrl(LinkedHashSet<String> urls, String value) {
        if (!isBlank(value)) urls.add(value.trim());
    }

    static String resolveApiUrl(String userUrl, String repositoryUrl) {
        if (!isBlank(userUrl)) return userUrl.trim();
        if (!isBlank(repositoryUrl)) return repositoryUrl.trim();
        return DEFAULT_API_URL;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static void resetAppearance() {
        DanmakuConfig config = DanmakuConfig.DEFAULT;
        putTextScale(config.textScale);
        putTransparency(config.transparency);
        putTextBold(config.textBold);
        putStyleMode(config.styleMode);
        putShadowTransparency(config.shadowTransparency);
        putStrokeWidthMultiplier(config.strokeWidthMultiplier);
        putProjectionOffsetX(config.projectionOffsetXMultiplier);
        putProjectionOffsetY(config.projectionOffsetYMultiplier);
        putProjectionTransparency(config.projectionTransparency);
        putColorMode(config.colorMode);
    }

    public static void resetTiming() {
        DanmakuConfig config = DanmakuConfig.DEFAULT;
        putDurationMs(config.durationMs);
        putFixedDurationMs(config.fixedDurationMs);
        putTimeOffsetMs(config.timeOffsetMs);
    }

    public static void resetDensity() {
        DanmakuConfig config = DanmakuConfig.DEFAULT;
        putMaxOnScreen(config.maxOnScreen);
        putScrollAreaRatio(config.scrollAreaRatio);
        putScrollGapRatio(config.scrollGapRatio);
        putLineSpacing(config.lineSpacing);
        putMaxScrollLines(config.maxScrollLines);
        putMaxTopLines(config.maxTopLines);
        putMaxBottomLines(config.maxBottomLines);
    }

    public static void resetDisplay() {
        DanmakuConfig config = DanmakuConfig.DEFAULT;
        putShowScroll(config.showScroll);
        putShowTop(config.showTop);
        putShowBottom(config.showBottom);
        putShowReverse(config.showReverse);
        putShowPositioned(config.showPositioned);
        putShowSubtitle(config.showSubtitle);
        putShowSpecial(config.showSpecial);
    }

    public static DanmakuConfig getConfig() {
        return getConfig(Build.VERSION.SDK_INT);
    }

    static DanmakuConfig getConfig(int sdkInt) {
        return new DanmakuConfig.Builder()
                .setTextScale(DanmakuPlaybackTuning.textScale(getTextScale(), sdkInt))
                .setTransparency(getTransparency())
                .setTextBold(isTextBold())
                .setStyleMode(DanmakuPlaybackTuning.styleMode(getStyleMode(), sdkInt))
                .setShadowTransparency(getShadowTransparency())
                .setStrokeWidthMultiplier(DanmakuPlaybackTuning.strokeWidth(getStrokeWidthMultiplier(), sdkInt))
                .setProjectionOffsetXMultiplier(getProjectionOffsetX())
                .setProjectionOffsetYMultiplier(getProjectionOffsetY())
                .setProjectionTransparency(getProjectionTransparency())
                .setColorMode(getColorMode())
                .setDurationMs(DanmakuPlaybackTuning.scrollDuration(getDurationMs(), sdkInt))
                .setFixedDurationMs(DanmakuPlaybackTuning.fixedDuration(getFixedDurationMs(), sdkInt))
                .setTimeOffsetMs(getTimeOffsetMs())
                .setMaxOnScreen(DanmakuPlaybackTuning.maxOnScreen(getMaxOnScreen(), sdkInt))
                .setScrollAreaRatio(getScrollAreaRatio())
                .setScrollGapRatio(DanmakuPlaybackTuning.scrollGap(getScrollGapRatio(), sdkInt))
                .setLineSpacing(getLineSpacing())
                .setMaxScrollLines(DanmakuPlaybackTuning.maxScrollLines(getMaxScrollLines(), sdkInt))
                .setMaxTopLines(DanmakuPlaybackTuning.maxFixedLines(getMaxTopLines(), sdkInt))
                .setMaxBottomLines(DanmakuPlaybackTuning.maxFixedLines(getMaxBottomLines(), sdkInt))
                .setShowScroll(isShowScroll())
                .setShowTop(isShowTop())
                .setShowBottom(isShowBottom())
                .setShowReverse(isShowReverse())
                .setShowPositioned(isShowPositioned())
                .setShowSubtitle(isShowSubtitle())
                .setShowSpecial(isShowSpecial())
                .build();
    }

    /*
     * java.lang.Math.clamp was added after Android 9.  Calling it directly can compile against
     * the modern SDK yet still fail with NoSuchMethodError on API 28 vendor images.  Keep these
     * tiny overloads local so every danmaku preference is safe before creating the player view.
     */
    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
