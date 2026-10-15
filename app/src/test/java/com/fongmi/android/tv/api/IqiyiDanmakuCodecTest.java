package com.fongmi.android.tv.api;

import static org.junit.Assert.*;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuResultGrouper;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class IqiyiDanmakuCodecTest {
    private byte[] fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/danmaku/iqiyi-" + name)) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    @Test public void officialCatalogueFindsDramaEpisode14WithoutTrailersOrOtherMedium() throws Exception {
        List<Danmaku> rows = IqiyiDanmakuCodec.catalogue(new String(fixture("catalogue.json"), StandardCharsets.UTF_8));
        Danmaku episode = DanmakuMatch.best("喜剧之王", "2026", "电视剧", "14", rows, Danmaku::getName);
        assertNotNull(episode);
        assertEquals("iqiyi-danmaku://video/2586684050154100", episode.getUrl());
        var works = DanmakuResultGrouper.works(DanmakuResultGrouper.browse("喜剧之王", "2026", "电视剧", "14", rows));
        var drama = works.stream().filter(w -> w.name().equals("喜剧之王(2026)【电视剧】")).findFirst().orElseThrow();
        assertEquals(24, drama.episodes().size());
        assertSame(episode, drama.episodes().get(13));
        assertFalse(rows.stream().anyMatch(d -> d.getName().contains("花絮")));
        assertFalse(rows.stream().anyMatch(d -> d.getName().startsWith("喜剧之王(1999)【电影】")));
        assertNull(DanmakuMatch.best("喜剧之王", "2026", "电视剧", "25", rows, Danmaku::getName));
        assertTrue(works.size() > 1); // manual browsing keeps movies/other seasons visible
    }

    @Test public void nativeMovieHasARealVideoIdAndNoInventedEpisode() throws Exception {
        List<Danmaku> rows = IqiyiDanmakuCodec.catalogue(new String(fixture("catalogue.json"), StandardCharsets.UTF_8));
        Danmaku movie = DanmakuMatch.best("新喜剧之王", "2019", "电影", "正片", rows, Danmaku::getName);
        assertNotNull(movie);
        assertEquals("iqiyi-danmaku://video/1630560500", movie.getUrl());
    }

    @Test public void protobufPreservesTimelineColorAndEscapesXml() throws Exception {
        var comments = IqiyiDanmakuCodec.decode(fixture("segment.br"));
        assertEquals(1, comments.size());
        assertEquals(12.5, comments.get(0).seconds(), 0.001);
        assertEquals(0x12ab34, comments.get(0).color());
        String xml = new String(IqiyiDanmakuCodec.document(comments), StandardCharsets.UTF_8);
        assertTrue(xml.contains("测试 &amp; &lt;hello&gt; 😀"));
        assertTrue(xml.contains("12.5,1,25,1223476"));
    }

    @Test public void oldXmlSegmentsRemainSupported() throws Exception {
        var comments = IqiyiDanmakuCodec.decode(fixture("xml.br"));
        assertEquals(1, comments.size());
        assertEquals(4, comments.get(0).seconds(), 0);
        assertEquals(0xff0000, comments.get(0).color());
    }

    @Test public void malformedAndOversizedSegmentsCannotBecomeUsableDocuments() throws Exception {
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.decode(fixture("bad.br")));
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.decode(fixture("dtd.br")));
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.decode(fixture("bomb.br")));
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.document(new ArrayList<>()));
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.catalogue("{}"));
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.duration("{\"code\":\"A00000\",\"data\":{\"durationSec\":99999999}}"));
    }

    @Test public void sourceIdentifiersCannotRedirectTheDownloader() throws Exception {
        assertTrue(IqiyiDanmakuCodec.supports("iqiyi-danmaku://video/2586684050154100"));
        assertFalse(IqiyiDanmakuCodec.supports("iqiyi-danmaku://video/2586684050154100?url=http://localhost"));
        assertFalse(IqiyiDanmakuCodec.supports("https://iqiyi-danmaku/video/2586684050154100"));
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.segmentUrl("../bad", 1));
        assertThrows(IOException.class, () -> IqiyiDanmakuCodec.segmentUrl("123456", 0));
        assertTrue(IqiyiDanmakuCodec.segmentUrl("2586684050154100", 1).startsWith("https://cmts.iqiyi.com/bullet/41/00/2586684050154100_60_1_"));
    }
}
