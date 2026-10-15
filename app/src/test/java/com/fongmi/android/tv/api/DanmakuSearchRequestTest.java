package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import org.junit.Test;
import okhttp3.FormBody;

public class DanmakuSearchRequestTest {
    @Test public void templateValuesCannotBecomeQueryOrFragmentSyntax() {
        var request = DanmakuSearchRequest.build("https://example.test/api?name={name}&episode={episode}&token=fixed", "喜剧 & Movie+#?", "第1期上(一)", "test");
        assertEquals("喜剧 & Movie+#?", request.url().queryParameter("name"));
        assertEquals("第1期上(一)", request.url().queryParameter("episode"));
        assertEquals("fixed", request.url().queryParameter("token"));
        assertNull(request.url().fragment());
    }

    @Test public void legacyPostContractStillCarriesBothFields() {
        var request = DanmakuSearchRequest.build("https://example.test/api", "电影", "正片", "test");
        assertEquals("POST", request.method());
        FormBody body = (FormBody) request.body();
        assertEquals("电影", body.value(0)); assertEquals("正片", body.value(1));
    }
}
