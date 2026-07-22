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
}
