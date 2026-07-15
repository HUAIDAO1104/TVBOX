package com.fongmi.android.tv.ui.detail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DetailTitlePolicyTest {

    @Test
    public void longTitlesUseTwoLinesAndProgressivelySmallerType() {
        assertEquals(39, DetailTitlePolicy.textSizeSp("庆余年"));
        assertEquals(1, DetailTitlePolicy.maxLines("庆余年"));
        assertEquals(35, DetailTitlePolicy.textSizeSp("现在就出发第三季特别篇"));
        assertEquals(2, DetailTitlePolicy.maxLines("现在就出发第三季特别篇"));
        assertEquals(27, DetailTitlePolicy.textSizeSp("这是一个字数特别长需要稳定排版的节目标题名称"));
    }
}
