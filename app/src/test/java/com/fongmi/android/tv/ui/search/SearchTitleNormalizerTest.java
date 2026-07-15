package com.fongmi.android.tv.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.InstallmentKind;
import com.fongmi.android.tv.ui.search.SearchTitleNormalizer.MediaVariant;

import org.junit.Test;

public class SearchTitleNormalizerTest {

    @Test
    public void normalizesPartSyntaxAcrossSpacingNumeralSystemsAndWidth() {
        String expected = SearchTitleNormalizer.normalize("庆余年第二部");

        assertEquals(expected, SearchTitleNormalizer.normalize("庆余年 第二部"));
        assertEquals(expected, SearchTitleNormalizer.normalize("庆余年第2部"));
        assertEquals(expected, SearchTitleNormalizer.normalize("庆余年2"));
        assertEquals(expected, SearchTitleNormalizer.normalize("庆余年Ⅱ"));
        assertEquals(expected, SearchTitleNormalizer.normalize("慶餘年　第二部【４Ｋ·國語】"));
    }

    @Test
    public void stripsQualityLanguageAndUpdateLabelsWithoutLosingInstallment() {
        var title = SearchTitleNormalizer.parse("庆余年：第十二部（蓝光国语） 更新至36集");

        assertEquals("庆余年", title.baseKey());
        assertEquals(InstallmentKind.PART, title.installmentKind());
        assertEquals(12, title.installment());
    }

    @Test
    public void keepsSeasonAndPartSemanticsDistinct() {
        assertNotEquals(SearchTitleNormalizer.normalize("三体第一季"), SearchTitleNormalizer.normalize("三体第二季"));
        assertNotEquals(SearchTitleNormalizer.normalize("三体第二季"), SearchTitleNormalizer.normalize("三体第二部"));
    }

    @Test
    public void extractsMediaVariantsFromEditionLabels() {
        var movie = SearchTitleNormalizer.parse("三体（电影版）");
        var series = SearchTitleNormalizer.parse("三体电视剧版");
        var extra = SearchTitleNormalizer.parse("三体·幕后花絮");

        assertEquals("三体", movie.baseKey());
        assertEquals(MediaVariant.MOVIE, movie.mediaVariant());
        assertEquals(MediaVariant.SERIES, series.mediaVariant());
        assertEquals(MediaVariant.EXTRA, extra.mediaVariant());
        assertNotEquals(movie.identityKey(), series.identityKey());
    }

    @Test
    public void canonicalizesFullWidthPunctuationAndTraditionalCharacters() {
        assertEquals(SearchTitleNormalizer.normalize("繁花"), SearchTitleNormalizer.normalize("繁・花"));
        assertEquals(SearchTitleNormalizer.normalize("庆余年"), SearchTitleNormalizer.normalize("慶餘年"));
    }

    @Test
    public void keepsPureNumericAndRomanTitlesIntact() {
        var numeric = SearchTitleNormalizer.parse("2046");
        var roman = SearchTitleNormalizer.parse("III");

        assertEquals("2046", numeric.baseKey());
        assertEquals(InstallmentKind.NONE, numeric.installmentKind());
        assertEquals(0, numeric.installment());
        assertEquals("iii", roman.baseKey());
        assertEquals(InstallmentKind.NONE, roman.installmentKind());
    }

    @Test
    public void foldsRuntimeQualityYearAndEpisodeDecorationsIntoTheCanonicalWork() {
        String expected = SearchTitleNormalizer.normalize("野狗骨头");

        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头(臻彩)"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头(2026)"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 4K高码率 ["));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 1-19集 完整合集"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 第19集 超清!!"));
        assertEquals(expected, SearchTitleNormalizer.normalize("【野狗骨头】第18-19集已更~~"));
        assertEquals(expected, SearchTitleNormalizer.normalize("全43集 · 《野狗骨头》 · 全~集"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 +更新至19集+"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 更17"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头.1080p更19"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 4k 更至ep19"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 s01e01 - e19"));
        assertEquals(expected, SearchTitleNormalizer.normalize("野狗骨头 web- 简体字幕"));
        assertEquals(expected, SearchTitleNormalizer.normalize("[夸克网盘]野狗骨头:"));
        assertEquals("2046", SearchTitleNormalizer.parse("2046").baseKey());
    }

    @Test
    public void knownCloudProviderSuffixDoesNotTurnCloudWordsIntoGlobalNoise() {
        assertEquals(SearchTitleNormalizer.normalize("野狗骨头"),
                SearchTitleNormalizer.normalize("野狗骨头 - 百度网盘"));
        assertEquals("我的云盘", SearchTitleNormalizer.parse("我的云盘").baseKey());
    }

    @Test
    public void stripsLeadingCatalogBadgeAndSeparatedGenreFacetsOnly() {
        assertEquals("wilddogbones", SearchTitleNormalizer.normalize("【新】Wild Dog Bones"));
        assertEquals("野狗骨头", SearchTitleNormalizer.normalize("野狗骨头 剧情 爱情"));
        assertEquals("父母爱情", SearchTitleNormalizer.normalize("父母爱情"));
        assertEquals("父母爱情", SearchTitleNormalizer.normalize("父母 爱情"));
        assertEquals("新", SearchTitleNormalizer.normalize("新"));
        assertEquals("野狗骨头", SearchTitleNormalizer.normalize("🗜 【国剧】野狗骨头"));
        assertEquals("野狗骨头", SearchTitleNormalizer.normalize("野狗骨头 4k 首"));
        assertEquals("野狗骨头", SearchTitleNormalizer.normalize("【国剧】野狗骨头 4k超"));
        assertEquals("野狗骨头", SearchTitleNormalizer.normalize("野狗骨头 更4集 4k 简中"));
        assertEquals(true, SearchTitleNormalizer.hasCatalogMetadataSuffix("野狗骨头", "野狗骨头 剧情 爱情 张婧仪"));
        assertEquals(true, SearchTitleNormalizer.hasCatalogMetadataSuffix("野狗骨头", "野狗骨头 宋威龙 / 张婧仪"));
        assertEquals(false, SearchTitleNormalizer.hasCatalogMetadataSuffix("父母", "父母 爱情"));
    }
}
