package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Danmaku;

import org.junit.Test;

import java.util.List;

public class DanmakuResultGrouperTest {

    @Test
    public void keepsOnlyCurrentEpisodeAndGroupsExpectedSeasonFirst() {
        Danmaku seasonThree = item("一起同过窗第三季(2022)【电视剧】from 360 - 【qq】 第1集", "https://example/3-1.xml");
        Danmaku seasonTwoQq = item("一起同过窗Ⅱ(2017)【电视剧】from qq - 【qq】 第1集", "https://example/2-qq.xml");
        Danmaku seasonTwo360 = item("一起同过窗Ⅱ(2017)【电视剧】from 360 - 【qq】 第1集", "https://example/2-360.xml");
        Danmaku wrongEpisode = item("一起同过窗Ⅱ(2017)【电视剧】from 360 - 【qq】 第2集", "https://example/2-2.xml");
        Danmaku aggregate = item("一起同过窗Ⅱ(2017)【电视剧】from 360 - 【qq】 全52集", "https://example/aggregate.xml");
        Danmaku unrelated = item("百花杀(2026)【电视剧】from 360 - 【qq】 第1集", "https://example/other.xml");
        Danmaku duplicate = item("一起同过窗Ⅱ duplicate from 360 第1集", "https://example/2-360.xml");

        List<Danmaku> result = DanmakuResultGrouper.prepare(
                "一起同过窗", "2017", "电视剧 第二季", "1",
                List.of(seasonThree, seasonTwoQq, wrongEpisode, aggregate, unrelated,
                        seasonTwo360, duplicate));

        assertEquals(List.of(seasonTwo360, seasonTwoQq, seasonThree), result);
        assertTrue(DanmakuResultGrouper.groupKey(seasonTwo360).startsWith("2|360|qq"));
        assertFalse(result.contains(wrongEpisode));
        assertFalse(result.contains(aggregate));
        assertFalse(result.contains(unrelated));
    }

    private static Danmaku item(String name, String url) {
        Danmaku item = new Danmaku();
        item.setName(name);
        item.setUrl(url);
        return item;
    }
}
