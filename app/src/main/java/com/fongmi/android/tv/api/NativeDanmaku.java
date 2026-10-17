package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import java.util.List;
import java.util.function.IntConsumer;

/** Shared routing for public platform catalogues and bounded document downloads. */
public final class NativeDanmaku {
    public interface Job { void cancel(); }
    public static List<String> sources() { return List.of(IqiyiDanmakuCodec.SOURCE, TencentDanmakuCodec.SOURCE, MangoDanmaku.SOURCE); }
    public static List<String> sources(String query) {
        return MangoDanmaku.supports(query) ? List.of(MangoDanmaku.SOURCE)
                : PlatformDanmaku.supports(query) ? List.of(PlatformDanmaku.SOURCE) : sources();
    }
    public static boolean isSource(String value) { return sources().contains(value) || PlatformDanmaku.SOURCE.equals(value); }
    public static boolean supports(String value) {
        return IqiyiDanmakuCodec.supports(value) || TencentDanmakuCodec.supports(value)
                || MangoDanmaku.supports(value) || PlatformDanmaku.supports(value);
    }
    public static Job search(String source, String query, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        if (MangoDanmaku.SOURCE.equals(source)) return MangoDanmaku.search(query, callback);
        if (PlatformDanmaku.SOURCE.equals(source)) return PlatformDanmaku.search(query, callback);
        if (TencentDanmakuCodec.SOURCE.equals(source)) return TencentDanmaku.search(query, callback);
        return IqiyiDanmaku.search(query, callback)::cancel;
    }
    public static Job search(String source, String query, String year, String type, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        if (MangoDanmaku.SOURCE.equals(source)) return MangoDanmaku.searchTarget(query, year, type, callback);
        if (TencentDanmakuCodec.SOURCE.equals(source)) return TencentDanmaku.searchTarget(query, year, type, callback);
        return search(source, query, callback);
    }
    static boolean acceptsWork(String query, String year, String type, String title, String workYear, String workType) {
        Integer expected = com.fongmi.android.tv.playback.vod.DanmakuMatch.resultSeason(query);
        Integer actual = com.fongmi.android.tv.playback.vod.DanmakuMatch.resultSeason(title);
        if (expected != null && actual != null && !expected.equals(actual)) return false;
        if (year != null && year.matches("(?:19|20)\\d{2}") && !workYear.isEmpty() && !year.equals(workYear)) return false;
        String requested = type == null ? "" : type;
        if (requested.contains("综艺") && !workType.isEmpty() && !workType.contains("综艺")) return false;
        if (requested.contains("电影") && !workType.isEmpty() && !workType.contains("电影") && !workType.contains("动漫")) return false;
        return true;
    }

    public static Job document(String source, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback) {
        if (MangoDanmaku.supports(source)) return MangoDanmaku.document(source, progress, callback);
        String alias = PlatformDanmaku.nativeAlias(source);
        if (!alias.isEmpty()) return TencentDanmaku.document(alias, progress, callback);
        if (PlatformDanmaku.isIqiyi(source)) return PlatformDanmaku.iqiyiDocument(source, progress, callback);
        if (PlatformDanmaku.supports(source)) return PlatformDanmaku.document(source, progress, callback);
        if (TencentDanmakuCodec.supports(source)) return TencentDanmaku.document(source, progress, callback);
        return IqiyiDanmaku.document(source, progress, callback)::cancel;
    }
    private NativeDanmaku() { }
}
