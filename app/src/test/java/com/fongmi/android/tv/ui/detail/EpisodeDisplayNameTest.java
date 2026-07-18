package com.fongmi.android.tv.ui.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EpisodeDisplayNameTest {

    @Test
    public void formatsCompactDatesWithoutLosingUpdateMeaning() {
        assertEquals("2026-02-01 · 特辑", EpisodeDisplayName.format("20260201特辑"));
        assertEquals("2026-01-31", EpisodeDisplayName.format("20260131"));
        assertEquals("第08集", EpisodeDisplayName.format("第08集"));
    }

    @Test
    public void wideCellsAreUsedOnlyForLongLabels() {
        assertTrue(EpisodeDisplayName.needsWideCell("20260201特辑"));
        assertFalse(EpisodeDisplayName.needsWideCell("第8集"));
    }

    @Test
    public void playbackActionDoesNotExposeAFullTechnicalFilename() {
        String technicalName = "[5 GB] Love.For.You.2026.S01E01.2160p.WEB-DL.H.264.AAC.mp4";
        assertEquals("第1集", EpisodeDisplayName.format(technicalName));
        assertEquals("第1集", EpisodeDisplayName.compactActionLabel(technicalName));
        assertFalse(EpisodeDisplayName.needsWideCell(technicalName));
        assertEquals("第2季 · 第8集", EpisodeDisplayName.compactActionLabel("Demo.S02E08.4K.mkv"));
        assertEquals("第2季第8集", EpisodeDisplayName.compactGridLabel("Demo.S02E08.4K.mkv"));
        assertEquals("", EpisodeDisplayName.compactActionLabel("一个非常非常非常长且不适合放在主按钮里的名称"));
    }

    @Test
    public void cleansCloudDriveEpisodeNamesWithoutDroppingTheirMeaning() {
        assertEquals("第9集 · 百花杀", EpisodeDisplayName.format("[2.1 GB]9.mp4【百花杀】"));
        assertEquals("第9集", EpisodeDisplayName.compactGridLabel("[2.1 GB]9.mp4【百花杀】"));
        assertEquals("第16集", EpisodeDisplayName.format("[1.9 GB] 第16集.mkv"));
        assertEquals("第14集 · 加更", EpisodeDisplayName.format("【2 GB】14.mp4（加更）"));
    }
}
