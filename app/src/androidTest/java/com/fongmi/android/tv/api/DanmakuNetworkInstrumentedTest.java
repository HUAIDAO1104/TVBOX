package com.fongmi.android.tv.api;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.fongmi.android.tv.player.danmaku.DanmakuHttp;
import java.net.*;
import java.util.List;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DanmakuNetworkInstrumentedTest {
    @Test public void brokenFirstDnsAddressDoesNotHideHealthyService() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            for (OkHttpClient base : List.of(NativeDanmakuRequest.CLIENT, DanmakuHttp.client())) {
                server.enqueue(new MockResponse().setBody("connected"));
                var client = base.newBuilder().dns(host -> List.of(
                        InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))).build();
                try (var response = client.newCall(new Request.Builder()
                        .url(server.url("/check").newBuilder().host("fixture.invalid").build()).build()).execute()) {
                    assertEquals(200, response.code());
                    assertEquals("connected", response.body().string());
                }
            }
        }
    }
}
