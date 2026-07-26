package com.fongmi.android.tv.bean;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    @Test
    public void pagedLocalIndexesBecomeGlobalIndexes() {
        Flag flag = flagWith("第1集", "第2集");
        Episode third = episode("第3集", "url-3", 1);
        Episode fourth = episode("第4集", "url-4", 2);

        flag.mergeEpisodes(Arrays.asList(third, fourth), false);

        assertEquals(4, flag.getEpisodes().size());
        assertEquals(3, third.getIndex());
        assertEquals(4, fourth.getIndex());
        assertEquals(third, flag.getEpisodes().get(2));
        assertEquals(fourth, flag.getEpisodes().get(3));
    }

    @Test
    public void pagedLocalIndexesStayStableWhenUiIsReversed() {
        Flag flag = flagWith("第1集", "第2集");
        Collections.reverse(flag.getEpisodes());
        Episode third = episode("第3集", "url-3", 1);
        Episode fourth = episode("第4集", "url-4", 2);

        flag.mergeEpisodes(Arrays.asList(third, fourth), true);

        assertEquals(4, fourth.getIndex());
        assertEquals(3, third.getIndex());
        assertEquals(fourth, flag.getEpisodes().get(0));
        assertEquals(third, flag.getEpisodes().get(1));
        assertEquals(2, flag.getEpisodes().get(2).getIndex());
        assertEquals(1, flag.getEpisodes().get(3).getIndex());
    }

    @Test
    public void duplicatePageItemDoesNotConsumeGlobalIndex() {
        Flag flag = flagWith("第1集", "第2集");
        Episode duplicate = episode("第2集", "url-2", 1);
        Episode third = episode("第3集", "url-3", 2);

        flag.mergeEpisodes(Arrays.asList(duplicate, third), false);

        assertEquals(3, flag.getEpisodes().size());
        assertEquals(3, third.getIndex());
    }

    @Test
    public void selectingFromLongPlaylistPerformsOneNormalizationScan() {
        Flag flag = new Flag("长剧线路");
        List<Episode> episodes = flag.getEpisodes();
        for (int i = 0; i < 800; i++) episodes.add(new CountingEpisode("第" + (i + 1) + "集", "url-" + i));
        // Normalize legacy indexes once, then measure only the hot selection path.
        flag.getEpisodes();
        CountingEpisode.indexReads = 0;

        flag.toggle(true, episodes.get(630));

        // A cached list reference reads each legacy index once. Repeated getEpisodes() calls in
        // the selection loop would exceed 600k reads for this fixture.
        org.junit.Assert.assertTrue(CountingEpisode.indexReads <= episodes.size() * 2);
        assertEquals(630, flag.getPosition());
    }

    private static Flag flagWith(String... names) {
        Flag flag = new Flag("测试线路");
        for (int i = 0; i < names.length; i++) flag.getEpisodes().add(episode(names[i], "url-" + (i + 1), i + 1));
        return flag;
    }

    private static Episode episode(String name, String url, int index) {
        Episode episode = new TestEpisode(name, url);
        episode.setIndex(index);
        return episode;
    }

    private static class TestEpisode extends Episode {

        private final String name;
        private final String url;

        private TestEpisode(String name, String url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getUrl() {
            return url;
        }
    }

    private static final class CountingEpisode extends TestEpisode {

        private static int indexReads;

        private CountingEpisode(String name, String url) {
            super(name, url);
        }

        @Override
        public int getIndex() {
            indexReads++;
            return super.getIndex();
        }
    }
}
