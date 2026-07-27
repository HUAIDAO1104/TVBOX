package com.fongmi.android.tv.player.danmaku;

import com.fongmi.android.tv.bean.Danmaku;

/** Filters repository-injected labels that are delivered as actual danmaku comments. */
public final class DanmakuContentFilter {

    private DanmakuContentFilter() {
    }

    public static boolean isBlocked(CharSequence text) {
        return Danmaku.isBlockedSourceLabel(text);
    }
}
