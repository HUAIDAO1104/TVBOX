package com.fongmi.android.tv.player.danmaku;

import androidx.media3.ui.danmaku.parser.BiliParser;
import androidx.media3.ui.danmaku.parser.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the bundled Bilibili XML parser behavior while removing non-content advertising labels.
 *
 * <p>The affected repositories put "小白弹幕" inside the XML as a fixed bottom comment. It
 * therefore looked like an extra playback-control button even though no such View existed.</p>
 */
public final class FilteringBiliParser implements Parser {

    public static final FilteringBiliParser INSTANCE = new FilteringBiliParser();

    private FilteringBiliParser() {
    }

    @Override
    public boolean sniff(InputStream input, int length) throws IOException {
        return BiliParser.INSTANCE.sniff(input, length);
    }

    @Override
    public List<androidx.media3.ui.danmaku.Danmaku> parse(InputStream input) throws IOException {
        List<androidx.media3.ui.danmaku.Danmaku> parsed = BiliParser.INSTANCE.parse(input);
        if (parsed == null || parsed.isEmpty()) return parsed;
        List<androidx.media3.ui.danmaku.Danmaku> filtered = new ArrayList<>(parsed.size());
        for (androidx.media3.ui.danmaku.Danmaku item : parsed) {
            if (item != null && !DanmakuContentFilter.isBlocked(item.text)) filtered.add(item);
        }
        return filtered;
    }
}
