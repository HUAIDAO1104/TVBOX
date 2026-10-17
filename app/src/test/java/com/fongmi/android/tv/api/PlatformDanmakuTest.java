package com.fongmi.android.tv.api;
import static org.junit.Assert.*;
import org.junit.Test;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PlatformDanmakuTest {
    @Test public void iqiyiPublicUrlEncodingResolvesVerifiedVideoId() throws Exception {
        assertEquals("2586684050154100", PlatformDanmaku.iqiyiId("https://www.iqiyi.com/v_pp7j21xlp4.html"));
        assertThrows(IOException.class, () -> PlatformDanmaku.iqiyiId("https://www.iqiyi.com/v_ZZZZZZZZZZZZZZZZZZZZZZZ.html"));
        assertThrows(IOException.class, () -> PlatformDanmaku.iqiyiId("https://www.iqiyi.com.evil.test/v_pp7j21xlp4.html"));
    }
    @Test public void youkuRejectsTokenErrorsAndPreservesMilliseconds() throws Exception {
        var inner = "{\"code\":1,\"data\":{\"result\":[{\"playat\":1234,\"content\":\"弹幕 <&>\",\"propertis\":\"{\\\"color\\\":1193046}\"}]}}";
        var root = new com.google.gson.JsonObject(); var data = new com.google.gson.JsonObject();
        data.addProperty("result", inner); root.add("data", data); var ret = new com.google.gson.JsonArray(); ret.add("SUCCESS::调用成功"); root.add("ret", ret);
        var rows = PlatformDanmaku.youkuComments(root.toString()); assertEquals(1.234, rows.get(0).seconds(), .0001); assertEquals(0x123456, rows.get(0).color());
        assertThrows(IOException.class, () -> PlatformDanmaku.youkuComments("{\"ret\":[\"FAIL_SYS_TOKEN_EXPIRED\"],\"data\":{}}"));
        assertThrows(IOException.class, () -> PlatformDanmaku.youkuComments("{}"));
    }
    @Test public void biliProtobufHasBoundsAndDoesNotTreatScriptCommentsAsText() throws Exception {
        byte[] text = "真实弹幕".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream child = new ByteArrayOutputStream(); child.write(new byte[]{16,(byte)0xd2,9,24,1,58,(byte)text.length}); child.write(text);
        ByteArrayOutputStream root = new ByteArrayOutputStream();root.write(10);root.write(child.size());root.write(child.toByteArray());
        var rows = PlatformDanmaku.biliComments(root.toByteArray()); assertEquals(1, rows.size()); assertEquals(1.234, rows.get(0).seconds(), .0001);
        assertThrows(IOException.class, () -> PlatformDanmaku.biliComments(new byte[]{10,127,16,1}));
        assertThrows(IOException.class, () -> PlatformDanmaku.biliComments("{\"code\":-412}".getBytes(StandardCharsets.UTF_8)));
    }
}
