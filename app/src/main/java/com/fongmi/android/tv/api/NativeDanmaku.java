package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import java.util.List;
import java.util.function.IntConsumer;

/** Shared routing for public platform catalogues and bounded document downloads. */
public final class NativeDanmaku {
    public interface Job { void cancel(); }
    public static List<String> sources() { return List.of(IqiyiDanmakuCodec.SOURCE, TencentDanmakuCodec.SOURCE); }
    public static boolean isSource(String value) { return sources().contains(value); }
    public static boolean supports(String value) {
        return IqiyiDanmakuCodec.supports(value) || TencentDanmakuCodec.supports(value);
    }
    public static Job search(String source, String query, IqiyiDanmaku.Callback<List<Danmaku>> callback) {
        if (TencentDanmakuCodec.SOURCE.equals(source)) return TencentDanmaku.search(query, callback);
        return IqiyiDanmaku.search(query, callback)::cancel;
    }
    public static Job document(String source, IntConsumer progress, IqiyiDanmaku.Callback<byte[]> callback) {
        if (TencentDanmakuCodec.supports(source)) return TencentDanmaku.document(source, progress, callback);
        return IqiyiDanmaku.document(source, progress, callback)::cancel;
    }
    private NativeDanmaku() { }
}
