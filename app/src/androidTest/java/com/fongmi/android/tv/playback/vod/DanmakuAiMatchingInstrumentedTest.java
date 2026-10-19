package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.NativeDanmaku;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DanmakuAiMatchingInstrumentedTest {
    @After public void cleanup() { DanmakuAiMatcher.cancel(); }
    private static MockResponse model(String json) {
        JsonObject message = new JsonObject(); message.addProperty("content", json);
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject root = new JsonObject(); root.add("choices", choices);
        return new MockResponse().setBody(root.toString());
    }
    private Danmaku row(MockWebServer server, String name, String id) {
        Danmaku item = Danmaku.from(server.url("/api/v2/comment/" + id + "?format=xml").toString());
        item.setName(name); return item;
    }
    private String endpoint(MockWebServer server) {
        return server.url("/search?name={name}&episode={episode}").toString().replace("%7B", "{").replace("%7D", "}");
    }
    private static class Result implements DanmakuAiMatcher.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Danmaku item;
        volatile DanmakuStatus.Failure error;
        volatile String query;
        public void onFound(String query, Danmaku item, List<Danmaku> rows) { this.item = item; this.query = query; done.countDown(); }
        public void onFailure() { done.countDown(); }
        public void onFailure(DanmakuStatus.Failure reason) { error = reason; done.countDown(); }
        public void onProgress(int percent) { }
        void await(int seconds) throws Exception { assertTrue("Pipeline deadline", done.await(seconds, TimeUnit.SECONDS)); }
    }

    @Test public void modelSearchesBaseTitleThenResolvesPartFromTheWholeWorkCatalogue() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); AtomicInteger modelCalls = new AtomicInteger(); AtomicReference<String> sentQuery = new AtomicReference<>();
            AtomicReference<String> sentEpisode = new AtomicReference<>();
            String work = "目录综艺第四季(2026)【综艺】from fixture - 【qq】";
            List<Danmaku> rows = List.of(row(server, work + "2026-08-16 第1期上", "upper"),
                    row(server, work + "2026-08-16 第1期下", "lower"), row(server, work + "2026-08-17 第1期加更", "extra"),
                    row(server, work.replace("第四季(2026)", "第三季(2025)") + "第1期下", "wrong-season"));
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("/chat/completions")) {
                        modelCalls.incrementAndGet();
                        var body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                        var input = JsonParser.parseString(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                        if (input.get("task").getAsString().equals("clean"))
                            return model("{\"query\":\"目录综艺\",\"episode\":\"EP01 Part B\",\"confidence\":1}");
                        JsonArray candidates = input.getAsJsonArray("candidates"); int selected = -1;
                        for (JsonElement value : candidates) {
                            var candidate = value.getAsJsonObject();
                            if (candidate.get("name").getAsString().contains("第1期下")) selected = candidate.get("id").getAsInt();
                        }
                        return model("{\"id\":" + selected + ",\"episode\":\"第1期下\",\"confidence\":1}");
                    }
                    if (request.getPath().startsWith("/search")) {
                        sentQuery.set(request.getRequestUrl().queryParameter("name"));
                        sentEpisode.set(request.getRequestUrl().queryParameter("episode"));
                        return new MockResponse().setBody(App.gson().toJson(rows));
                    }
                    return new MockResponse().setBody("<i><d p=\"1,1,25,16777215,0,0,0,1\">已验证的正确下集弹幕</d></i>");
                }
            });
            Result result = new Result();
            DanmakuAiMatcher.start(new DanmakuMatchContext("目录综艺第四季【4K】", "2026", "综艺", "EP01 Part B"),
                    new DanmakuAiClient(server.url("/v1").toString(), "fixture", "fixture-key"), List.of(endpoint(server)), List.of(), result);
            result.await(5); assertNotNull("failure=" + result.error, result.item);
            assertTrue(result.item.getUrl(), result.item.getUrl().contains("/lower?"));
            assertEquals("目录综艺", sentQuery.get()); assertEquals("", sentEpisode.get()); assertEquals(2, modelCalls.get());
        }
    }

    @Test public void wrongHalfNeverLoadsAndNoMatchIsNotReportedAsNetworkFailure() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("/chat/completions")) {
                        String input = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject()
                                .getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
                        return input.contains("candidates") ? model("{\"id\":0,\"episode\":\"第1期下\",\"confidence\":1}")
                                : model("{\"query\":\"目录综艺\",\"episode\":\"第1期上\",\"confidence\":1}");
                    }
                    return new MockResponse().setBody(App.gson().toJson(List.of(row(server,
                            "目录综艺第四季(2026)【综艺】from fixture - 第1期下", "must-not-download"))));
                }
            });
            Result result = new Result();
            DanmakuAiMatcher.start(new DanmakuMatchContext("目录综艺第四季", "2026", "综艺", "第1期上"),
                    new DanmakuAiClient(server.url("/v1").toString(), "fixture", "fixture-key"), List.of(endpoint(server)), List.of(), result);
            result.await(5); assertNull(result.item); assertEquals(DanmakuStatus.Failure.NO_MATCH, result.error);
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void modelAuthenticationFailureIsReportedAsModelFailure() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); server.enqueue(new MockResponse().setResponseCode(401)); Result result = new Result();
            DanmakuAiMatcher.start(new DanmakuMatchContext("目录综艺", "2026", "综艺", "第1期上"),
                    new DanmakuAiClient(server.url("/v1").toString(), "fixture", "fixture-key"), List.of(), List.of(), result);
            result.await(3); assertEquals(DanmakuStatus.Failure.AI_ACCESS_DENIED, result.error);
        }
    }

    @Test public void cancellingCompletedClientDoesNotBreakNextModelRequest() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(model("{\"query\":\"目录综艺\",\"confidence\":1}"));
            var first = new DanmakuAiClient(server.url("/v1").toString(), "fixture", "fixture-key");
            var second = new DanmakuAiClient(server.url("/v1").toString(), "fixture", "fixture-key");
            var input = DanmakuAiProtocol.context("clean", new DanmakuMatchContext("目录综艺", "2026", "综艺", "第1期上"));
            first.complete(input); server.takeRequest();
            server.enqueue(model("{\"query\":\"目录综艺\",\"confidence\":1}").setHeadersDelay(250, TimeUnit.MILLISECONDS));
            var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> second.complete(input));
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); first.cancel();
                assertTrue(result.get(3, TimeUnit.SECONDS).contains("目录综艺"));
            } finally { worker.shutdownNow(); second.cancel(); }
        }
    }

    @Test public void closedPooledConnectionRecoversWithinTheSameRequestBudget() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var serving = worker.submit(() -> {
                for (int i = 0; i < 2; i++) try (var socket = server.accept()) {
                    socket.setSoTimeout(3000); var input = socket.getInputStream(); var header = new StringBuilder();
                    int b; while ((b = input.read()) >= 0) { header.append((char) b); if (header.toString().endsWith("\r\n\r\n")) break; }
                    var length = java.util.regex.Pattern.compile("(?im)^Content-Length: (\\d+)").matcher(header);
                    if (length.find()) input.readNBytes(Integer.parseInt(length.group(1)));
                    byte[] body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"query\\\":\\\"test\\\",\\\"confidence\\\":1}\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body); socket.getOutputStream().flush();
                }
                return true;
            });
            var input = DanmakuAiProtocol.context("clean", new DanmakuMatchContext("test", "", "", "1"));
            for (int i = 0; i < 2; i++) {
                var client = new DanmakuAiClient("http://127.0.0.1:" + server.getLocalPort() + "/v1", "fixture", "fixture-key");
                try { assertTrue(client.complete(input).contains("test")); } finally { client.cancel(); }
            }
            assertTrue(serving.get(5, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }

    @Test public void liveModelToNativeDocumentForVarietyDramaAndMovie() throws Exception {
        String endpoint = InstrumentationRegistry.getArguments().getString("aiLiveEndpoint", "");
        Assume.assumeTrue(!endpoint.isEmpty());
        // The optional local relay keeps the real provider credential out of APKs/device files.
        for (String[] spec : List.of(
                new String[]{"现在就出发第四季【4K】", "2026", "综艺", "第1期上", "r4102opm7x3"},
                new String[]{"现在就出发第四季", "2026", "综艺", "EP01 Part B", "z41023tinp7"},
                new String[]{"现在就出发第四季", "2026", "综艺", "2026年10月10日上", "r4102opm7x3"},
                new String[]{"现在就出发第四季", "2026", "综艺", "第1期加更", "r4102w56q2o"},
                new String[]{"披荆斩棘2026", "2026", "综艺", "第十期上", "24719651"},
                new String[]{"披荆斩棘2026", "2026", "综艺", "会员加更第1期", "24579459"},
                new String[]{"哪吒2", "2025", "电影", "正片", "y4101qnn3jo"},
                new String[]{"【剧版】喜剧之王（2026）更新至14集4K", "2026", "电视剧", "第14集", ""})) {
            Result result = new Result(); long start = android.os.SystemClock.elapsedRealtime();
            DanmakuAiMatcher.start(new DanmakuMatchContext(spec[0], spec[1], spec[2], spec[3]),
                    new DanmakuAiClient(endpoint, "deepseek-v4.1-flash", "local-relay"), NativeDanmaku.sources(), List.of(), result);
            result.await(85);
            android.util.Log.i("Danmaku584QA", spec[0] + " " + spec[3] + " query=" + result.query + " error=" + result.error
                    + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start) + " source=" + (result.item == null ? "" : result.item.getUrl()));
            assertNotNull(spec[0] + ": " + result.error, result.item);
            assertTrue(result.item.getUrl(), result.item.getUrl().endsWith(spec[4]));
            DanmakuAiMatcher.cancel();
        }
    }

    @Test public void livePlayerFallbackUsesConfiguredAiAndPreservesRawEpisode() throws Exception {
        String endpoint = InstrumentationRegistry.getArguments().getString("aiLiveEndpoint", "");
        Assume.assumeTrue(!endpoint.isEmpty());
        var prefs = App.get().getSharedPreferences("danmaku_ai_private", android.content.Context.MODE_PRIVATE);
        Map<String, ?> previous = prefs.getAll();
        boolean auto = com.fongmi.android.tv.setting.DanmakuSetting.isAuto();
        boolean load = com.fongmi.android.tv.setting.DanmakuSetting.isLoad();
        boolean show = com.fongmi.android.tv.setting.DanmakuSetting.isShow();
        var player = new com.fongmi.android.tv.player.PlayerManager[1];
        try {
            com.fongmi.android.tv.setting.DanmakuAiSetting.save("https://example.invalid/v1", "deepseek-v4.1-flash", "local-relay", true);
            // Test-only loopback relay; production form continues to require HTTPS.
            prefs.edit().putString("base_url", endpoint).commit();
            com.fongmi.android.tv.setting.DanmakuSetting.putAuto(true);
            com.fongmi.android.tv.setting.DanmakuSetting.putLoad(true);
            String title = "【测试专线】现在就出发第四季", episode = "EP01 Part B";
            var selected = new CountDownLatch(1);
            var stages = new CopyOnWriteArrayList<DanmakuStatus.Stage>();
            var source = new AtomicReference<android.net.Uri>();
            var history = new com.fongmi.android.tv.bean.History(); history.setKey("584-ai-fallback"); history.setVodName(title);
            long start = android.os.SystemClock.elapsedRealtime();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player[0] = new com.fongmi.android.tv.player.PlayerManager((com.fongmi.android.tv.player.PlayerManager.Callback)
                        java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{com.fongmi.android.tv.player.PlayerManager.Callback.class}, (proxy, method, args) -> {
                            if (method.getName().equals("onDanmakuSourceChanged") && args[0] != null) { source.set((android.net.Uri) args[0]); selected.countDown(); }
                            if (method.getName().equals("onDanmakuStatusChanged")) stages.add(((DanmakuStatus) args[0]).stage());
                            return null;
                        }));
                try {
                    var spec = com.fongmi.android.tv.player.PlayerManager.class.getDeclaredField("spec"); spec.setAccessible(true);
                    spec.set(player[0], com.fongmi.android.tv.player.media.PlaySpec.from("fixture", "https://example.invalid/test.mp4", null,
                            new androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(episode).build()));
                } catch (Exception error) { throw new AssertionError(error); }
                VodPlaybackMedia.searchDanmaku(new com.fongmi.android.tv.bean.Result(), history,
                        com.fongmi.android.tv.bean.Episode.create(episode, "fixture"), 3, "2026", "综艺", player[0]);
            });
            assertTrue("Automatic player fallback deadline", selected.await(100, TimeUnit.SECONDS));
            assertTrue(stages.toString(), stages.contains(DanmakuStatus.Stage.AI_MATCHING));
            assertTrue(source.get().toString(), source.get().toString().endsWith("z41023tinp7"));
            var saved = DanmakuManualMatchStore.get().find(new DanmakuMatchContext(title, "2026", "综艺", episode));
            assertNotNull(saved); assertEquals("现在就出发", saved.query());
            android.util.Log.i("Danmaku584QA", "player automatic AI fallback elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start) + " stages=" + stages + " source=" + source.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (player[0] != null) { VodPlaybackMedia.invalidate(player[0]); player[0].release(); } });
            com.fongmi.android.tv.setting.DanmakuSetting.putAuto(auto); com.fongmi.android.tv.setting.DanmakuSetting.putLoad(load);
            com.fongmi.android.tv.setting.DanmakuSetting.putShow(show);
            var restore = prefs.edit().clear();
            for (var entry : previous.entrySet()) {
                if (entry.getValue() instanceof String value) restore.putString(entry.getKey(), value);
                if (entry.getValue() instanceof Boolean value) restore.putBoolean(entry.getKey(), value);
            }
            restore.commit();
        }
    }
}
