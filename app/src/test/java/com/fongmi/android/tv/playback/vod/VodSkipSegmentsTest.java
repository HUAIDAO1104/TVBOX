package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;
import androidx.media3.common.MediaChapter;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VodSkipSegmentsTest {
    private static final long AUTO = C.TIME_UNSET;
    private final VodSkipSegments skip = new VodSkipSegments();
    private MediaChapter at(int seconds, String name) {
        return MediaChapter.chapter(seconds, seconds * 1000000L, name, false);
    }

    @Test public void coldOpenAndPostCreditsArePreserved() {
        var chapters = List.of(at(0, "序章"), at(25, "OP1"), at(115, "正片"), at(1100, "片尾曲"), at(1190, "彩蛋"));
        assertEquals(-1, skip.target(chapters, 2000, 1200000, AUTO, AUTO));
        assertEquals(115000, skip.target(chapters, 26000, 1200000, AUTO, AUTO));
        assertEquals(-1, skip.target(chapters, 26000, 1200000, AUTO, AUTO));
        assertEquals(1190000, skip.target(chapters, 1102000, 1200000, AUTO, AUTO));
    }

    @Test public void missingOrBrokenTimesDoNotTriggerAGuess() {
        assertEquals(-1, skip.target(List.of(), 1000, 1200000, AUTO, AUTO));
        assertEquals(-1, skip.target(List.of(at(0,"Opening"),at(100,"Main"),at(90,"Main")), 1000, 1200000, AUTO, AUTO));
        assertEquals(-1, skip.target(List.of(at(0,"Opening")), 1000, 1200000, AUTO, AUTO));
    }

    @Test public void independentManualAndOffSettingsOverrideMarkers() {
        var chapters = List.of(at(0,"片头"), at(90,"剧情"), at(1100,"ED"));
        assertEquals(-1, skip.target(chapters, 1000, 1200000, 0, AUTO));
        assertEquals(1200000, skip.target(chapters, 1101000, 1200000, 0, AUTO));
        skip.reset();
        assertEquals(30000, skip.target(chapters, 1000, 1200000, 30000, 0));
        assertEquals(-1, skip.target(chapters, 1150000, 1200000, 30000, 0));
        skip.reset();
        assertEquals(-1, skip.target(List.of(), 100, 30000, 0, 90000));
    }
}
