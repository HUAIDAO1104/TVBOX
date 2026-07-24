package com.fongmi.android.tv.player.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fongmi.android.tv.bean.Danmaku;

import org.junit.Test;

import java.util.List;

public class PlaySpecDanmakuTest {

    @Test
    public void embeddedRepositorySourceCannotArrivePreselected() {
        Danmaku embedded = Danmaku.from("https://example.test/wrong.xml");
        embedded.setSelected(true);

        List<Danmaku> sanitized = PlaySpec.sanitizeDanmakus(List.of(embedded));

        assertEquals(1, sanitized.size());
        assertFalse(sanitized.get(0).isSelected());
    }

    @Test
    public void repositoryGhostSubtitleNeverBecomesATrack() {
        com.fongmi.android.tv.bean.Sub ghost = com.fongmi.android.tv.bean.Sub.from("小白弹幕", "https://example.invalid/ad", "", "text/vtt");
        com.fongmi.android.tv.bean.Sub normal = com.fongmi.android.tv.bean.Sub.from("简体中文", "https://example.invalid/zhs.vtt", "zh", "text/vtt");

        List<com.fongmi.android.tv.bean.Sub> sanitized = PlaySpec.sanitizeSubs(List.of(ghost, normal));

        assertEquals(1, sanitized.size());
        org.junit.Assert.assertSame(normal, sanitized.get(0));
        org.junit.Assert.assertTrue(ghost.isBlockedSource());
        assertFalse(normal.isBlockedSource());
    }
}
