package com.fongmi.android.tv.bean;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;

public class FlagEpisodeIndexTest {

    @Test
    public void episodeIdentitySurvivesUiReversal() {
        Flag flag = new Flag("测试线路");
        Episode first = new Episode();
        first.setName("上");
        Episode second = new Episode();
        second.setName("下");
        flag.getEpisodes().add(first);
        flag.getEpisodes().add(second);

        // Access assigns the stable one-based identity before presentation is reversed.
        flag.getEpisodes();
        Collections.reverse(flag.getEpisodes());

        assertEquals(2, flag.getEpisodes().get(0).getIndex());
        assertEquals(1, flag.getEpisodes().get(1).getIndex());
    }
}
