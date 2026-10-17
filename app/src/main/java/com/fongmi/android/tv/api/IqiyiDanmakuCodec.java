package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.brotli.dec.BrotliInputStream;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParserFactory;

/** Public catalogue and bullet-segment formats, independent of the Android UI/network stack. */
public final class IqiyiDanmakuCodec {
    public static final String SOURCE = "iqiyi-direct";
    private static final String PREFIX = "iqiyi-danmaku://video/";
    private static final Pattern TVID = Pattern.compile("(?:qips://|;)tvid=([0-9]{5,20})(?:;|$)");
    private static final int MAX_SEGMENT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_COMMENTS = 150_000;

    private IqiyiDanmakuCodec() { }

    public static boolean supports(String url) {
        return url != null && url.startsWith(PREFIX) && url.substring(PREFIX.length()).matches("[0-9]{5,20}");
    }

    public static String videoId(String url) throws IOException {
        if (!supports(url)) throw new IOException("Invalid iQiyi danmaku source");
        return url.substring(PREFIX.length());
    }

    public static List<Danmaku> catalogue(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!"0".equals(string(root, "code"))) throw new IOException("iQiyi search rejected: " + string(root, "code"));
            JsonObject data = root.getAsJsonObject("data");
            if (data == null || !data.has("templates")) throw new IOException("Missing iQiyi catalogue");
            Map<String, Danmaku> rows = new LinkedHashMap<>();
            for (JsonElement element : array(data, "templates")) {
                JsonObject template = element.getAsJsonObject();
                for (JsonElement album : array(template, "intentAlbumInfos")) addAlbum(album.getAsJsonObject(), rows);
                if (template.has("albumInfo") && template.get("albumInfo").isJsonObject()) {
                    addAlbum(template.getAsJsonObject("albumInfo"), rows);
                }
            }
            return new ArrayList<>(rows.values());
        } catch (RuntimeException error) {
            throw new IOException("Invalid iQiyi catalogue", error);
        }
    }

    private static void addAlbum(JsonObject album, Map<String, Danmaku> rows) {
        if (!"iqiyi".equals(string(album, "siteId"))) return;
        String channel = string(album, "channel").split(",")[0];
        if (!List.of("电视剧", "电影", "综艺", "动漫", "纪录片").contains(channel)) return;
        String title = string(album, "title");
        if (title.isEmpty()) return;
        String year = album.has("year") && album.get("year").isJsonObject()
                ? string(album.getAsJsonObject("year"), "value") : "";
        String work = title + (year.matches("[12][0-9]{3}")
                && !title.matches(".*[（(]" + year + "[）)].*") ? "(" + year + ")" : "")
                + "【" + channel + "】from iqiyi - 【qiyi】 ";
        if ("电影".equals(channel)) {
            addRow(work + "正片", string(album, "playUrl"), rows);
            return;
        }
        for (JsonElement element : array(album, "videos")) {
            JsonObject video = element.getAsJsonObject();
            String number = string(video, "number"), label;
            String play = string(video, "playUrl");
            if (play.contains("playertype=preview")) continue;
            if ("综艺".equals(channel)) {
                // Platform list positions are NOT episode numbers. Keep dates/parts and the
                // original labels so matching cannot mistake a bonus clip for episode 1.
                String date = string(video, "year");
                label = (date.matches("20[0-9]{6}") ? date + " " : "") + string(video, "title")
                        + " " + string(video, "subtitle");
            } else {
                if (!number.matches("[1-9][0-9]{0,3}")) continue; // trailers/behind-the-scenes
                label = "第" + number + "集 " + string(video, "subtitle");
            }
            addRow(work + label.trim(), play, rows);
        }
    }

    private static void addRow(String name, String play, Map<String, Danmaku> rows) {
        Matcher matcher = TVID.matcher(play);
        if (!matcher.find() || play.contains("third_play_url=")) return;
        Danmaku item = new Danmaku();
        item.setName(name);
        item.setUrl(PREFIX + matcher.group(1));
        item.setSourceKey(SOURCE);
        rows.putIfAbsent(item.getUrl(), item);
    }

    public static int duration(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!"A00000".equals(string(root, "code"))) throw new IOException("iQiyi video unavailable");
            JsonObject data = root.getAsJsonObject("data");
            if (data == null || !data.has("durationSec")) throw new IOException("Missing iQiyi duration");
            int duration = data.get("durationSec").getAsInt();
            if (duration <= 0 || duration > 6 * 3600) throw new IOException("Invalid iQiyi duration");
            return duration;
        } catch (RuntimeException error) {
            throw new IOException("Invalid iQiyi video metadata", error);
        }
    }

    public static String segmentUrl(String id, int page) throws IOException {
        if (!id.matches("[0-9]{5,20}") || page < 1 || page > 360) throw new IOException("Invalid segment");
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(
                    (id + "_60_" + page + "cbzuw1259a").getBytes(StandardCharsets.UTF_8));
            StringBuilder signature = new StringBuilder();
            for (int i = hash.length - 4; i < hash.length; i++) signature.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 255));
            return "https://cmts.iqiyi.com/bullet/" + id.substring(id.length() - 4, id.length() - 2)
                    + "/" + id.substring(id.length() - 2) + "/" + id + "_60_" + page + "_" + signature + ".br";
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    public record Comment(double seconds, int color, String text) { }

    public static List<Comment> decode(byte[] compressed) throws IOException {
        byte[] bytes;
        try (InputStream input = new BrotliInputStream(new ByteArrayInputStream(compressed))) {
            bytes = readBounded(input, MAX_SEGMENT_BYTES);
        } catch (RuntimeException error) {
            throw new IOException("Invalid iQiyi Brotli segment", error);
        }
        int start = bytes.length >= 3 && (bytes[0] & 255) == 0xef && (bytes[1] & 255) == 0xbb
                && (bytes[2] & 255) == 0xbf ? 3 : 0;
        while (start < bytes.length && Character.isWhitespace((char) bytes[start])) start++;
        if (start < bytes.length && bytes[start] == '<') return xmlComments(bytes);
        List<Comment> comments = new ArrayList<>();
        Proto root = new Proto(bytes, 0, bytes.length);
        while (root.next()) {
            if (root.field != 6 || root.wire != 2) continue;
            Proto block = root.child();
            String time = "0";
            List<Proto> items = new ArrayList<>();
            while (block.next()) {
                if (block.field == 1) time = block.string();
                else if (block.field == 2 && block.wire == 2) {
                    if (items.size() >= 50_000) throw new IOException("Too many segment entries");
                    items.add(block.child());
                }
            }
            for (Proto item : items) {
                String text = "", at = time, color = "ffffff";
                while (item.next()) {
                    if (item.field == 2) text = item.string();
                    else if (item.field == 6) at = item.string();
                    else if (item.field == 8) color = item.string();
                }
                addComment(comments, at, color, text);
            }
        }
        return comments;
    }

    private static List<Comment> xmlComments(byte[] bytes) throws IOException {
        List<Comment> result = new ArrayList<>();
        try {
            String xml = new String(bytes, StandardCharsets.UTF_8);
            // Android Expat does not implement Xerces' disallow-doctype feature. Reject DTDs
            // before parsing a character stream and reject every external entity resolver call.
            if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("Unexpected XML DTD");
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.newSAXParser().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)), new DefaultHandler() {
                @Override public org.xml.sax.InputSource resolveEntity(String publicId, String systemId) throws org.xml.sax.SAXException {
                    throw new org.xml.sax.SAXException("External entities are not allowed");
                }
                String text = "", time = "0", color = "ffffff";
                final StringBuilder value = new StringBuilder();
                @Override public void startElement(String uri, String local, String qName, Attributes attrs) {
                    value.setLength(0);
                    if (qName.equals("bulletInfo")) { text = ""; time = "0"; color = "ffffff"; }
                }
                @Override public void characters(char[] chars, int offset, int length) { value.append(chars, offset, length); }
                @Override public void endElement(String uri, String local, String qName) {
                    if (qName.equals("content")) text = value.toString();
                    else if (qName.equals("showTime")) time = value.toString();
                    else if (qName.equals("color")) color = value.toString();
                    else if (qName.equals("bulletInfo")) addComment(result, time, color, text);
                }
            });
        } catch (Exception error) { throw new IOException("Invalid iQiyi XML segment", error); }
        return result;
    }

    private static void addComment(List<Comment> result, String time, String color, String text) {
        try {
            double seconds = Double.parseDouble(time);
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 6 * 3600 || text.isBlank()
                    || text.length() > 2000 || result.size() >= MAX_COMMENTS) return;
            int rgb = color.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(color, 16) : 0xffffff;
            result.add(new Comment(seconds, rgb, text));
        } catch (NumberFormatException ignored) { }
    }

    public static byte[] document(List<Comment> comments) throws IOException {
        if (comments.isEmpty()) throw new IOException("iQiyi episode has no danmaku");
        if (comments.size() > MAX_COMMENTS) throw new IOException("Too many iQiyi comments");
        comments.sort(Comparator.comparingDouble(Comment::seconds));
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>");
        int id = 0;
        for (Comment comment : comments) {
            xml.append("<d p=\"").append(comment.seconds).append(",1,25,").append(comment.color)
                    .append(",0,0,0,").append(++id).append("\">");
            for (int offset = 0; offset < comment.text.length();) {
                int cp = comment.text.codePointAt(offset);
                offset += Character.charCount(cp);
                if (cp == '&') xml.append("&amp;");
                else if (cp == '<') xml.append("&lt;");
                else if (cp == '>') xml.append("&gt;");
                else if (cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xd7ff
                        || cp >= 0xe000 && cp <= 0xfffd || cp >= 0x10000 && cp <= 0x10ffff) xml.appendCodePoint(cp);
            }
            xml.append("</d>");
            if (xml.length() > 8 * 1024 * 1024) throw new IOException("iQiyi document exceeds limit");
        }
        return xml.append("</i>").toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (output.size() + count > limit) throw new IOException("iQiyi response exceeds limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString().trim();
    }

    private static JsonArray array(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonArray() ? object.getAsJsonArray(field) : new JsonArray();
    }

    /** Bounds-checked protobuf wire reader; unknown fields are skipped without schema guesses. */
    static final class Proto {
        final byte[] bytes;
        final int end;
        int pos, field, wire, start, length;
        long number;
        Proto(byte[] bytes, int start, int end) { this.bytes = bytes; this.pos = start; this.end = end; }
        boolean next() throws IOException {
            if (pos == end) return false;
            long key = varint();
            field = (int) (key >>> 3); wire = (int) (key & 7);
            if (field <= 0) throw new IOException("Invalid protobuf field");
            if (wire == 0) { number = varint(); start = pos; length = 0; return true; }
            long count = wire == 2 ? varint() : wire == 1 ? 8 : wire == 5 ? 4 : -1;
            if (count < 0 || count > end - pos) throw new IOException("Truncated protobuf field");
            start = pos; length = (int) count; pos += length;
            return true;
        }
        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IOException("Truncated protobuf integer");
                int b = bytes[pos++] & 255;
                if (shift == 63 && b > 1) throw new IOException("Overflow protobuf integer");
                value |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw new IOException("Invalid protobuf integer");
        }
        Proto child() { return new Proto(bytes, start, start + length); }
        String string() { return wire == 0 ? Long.toString(number)
                : wire == 2 ? new String(bytes, start, length, StandardCharsets.UTF_8) : ""; }
    }
}
