package com.fongmi.android.tv.ui.detail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DetailTitlePolicyTest {

    @Test
    public void longTitlesUseTwoLinesAndProgressivelySmallerType() {
        assertEquals(9.0f, DetailTitlePolicy.textSizeSp("庆余年"), 0.01f);
        assertEquals(1, DetailTitlePolicy.maxLines("庆余年"));
        assertEquals(8.4f, DetailTitlePolicy.textSizeSp("现在就出发第三季特别篇"), 0.01f);
        assertEquals(2, DetailTitlePolicy.maxLines("现在就出发第三季特别篇"));
        assertEquals(7.2f, DetailTitlePolicy.textSizeSp("这是一个字数特别长需要稳定排版的节目标题名称"), 0.01f);
    }
}
