package com.fongmi.android.tv.api;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;

/** Reads the official snapshot for the same video as the segmented feed. */
final class BilibiliCommentPool extends DefaultHandler {
    private final List<IqiyiDanmakuCodec.Comment> rows = new ArrayList<>();
    private String cid = "", active = "", params = "";
    private final StringBuilder text = new StringBuilder();
    private int depth;

    static List<IqiyiDanmakuCodec.Comment> parse(byte[] input, String expectedCid) throws IOException {
        String xml = new String(input, StandardCharsets.UTF_8);
        if (xml.trim().startsWith("{")) {
            var reply = PlatformDanmaku.json(xml);
            if (reply.has("code") && reply.get("code").isJsonPrimitive())
                throw new PlatformDanmaku.RejectedComments(reply.get("code").getAsString());
        }
        if (xml.contains("<!DOCTYPE") || xml.indexOf(0) >= 0) throw new IOException("Unsupported comment XML");
        var pool = new BilibiliCommentPool();
        try {
            var reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity"); });
            reader.setContentHandler(pool);
            reader.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) { throw new IOException("Invalid Bilibili snapshot", e); }
        if (!expectedCid.equals(pool.cid)) throw new IOException("Bilibili snapshot CID mismatch");
        return pool.rows;
    }
    @Override public void startElement(String uri, String local, String name, Attributes attrs) throws SAXException {
        if (++depth == 1 && !name.equals("i")) throw new SAXException("Missing comment root");
        if (depth > 32) throw new SAXException("Nested comment markup");
        if (depth != 2) return;
        active = name;
        params = attrs.getValue("p");
        text.setLength(0);
    }
    @Override public void characters(char[] chars, int start, int length) {
        if (depth == 2 && (active.equals("chatid") || active.equals("d")) && text.length() <= 500)
            text.append(chars, start, Math.min(length, 501 - text.length()));
    }
    @Override public void endElement(String uri, String local, String name) throws SAXException {
        if (depth-- != 2) return;
        if (name.equals("chatid")) cid = text.toString().trim();
        if (!name.equals("d") || params == null || text.length() > 500) return;
        String[] fields = params.split(",", 9);
        if (fields.length < 8) return;
        try {
            double seconds = Double.parseDouble(fields[0]);
            int mode = Integer.parseInt(fields[1]), color = Integer.parseInt(fields[3]);
            long id = Long.parseLong(fields[7]);
            String message = text.toString();
            if (id <= 0 || !Double.isFinite(seconds) || seconds < 0 || seconds > 21600 ||
                    mode < 1 || mode > 6 || message.isBlank()) return;
            if (rows.size() >= 100_000) throw new SAXException("Oversized comment snapshot");
            rows.add(new IqiyiDanmakuCodec.Comment(seconds, color & 0xffffff, message, id, mode));
        } catch (NumberFormatException ignored) { }
    }
}
