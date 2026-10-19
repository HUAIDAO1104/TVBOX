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
                        return model("{\"id\":\"" + selected + "\",\"episode\":\"第1期下\"}");
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
                        return input.contains("candidates") ? model("{\"id\":\"0\",\"episode\":\"第1期下\"}")
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

    @Test public void unavailableFirstDocumentStillResolvesThroughBackupCatalogue() throws Exception {
        assertAlternativeCatalogue(true);
    }

    @Test public void unavailableFirstDocumentKeepsSlowerDirectCatalogueAlive() throws Exception {
        assertAlternativeCatalogue(false);
    }

    private void assertAlternativeCatalogue(boolean useBackup) throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            AtomicInteger wrongDownloads = new AtomicInteger();
            String name = "回退电视剧第二季(2026)【电视剧】from fixture - 第14集";
            direct.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("/chat/completions"))
                        return model("{\"query\":\"回退电视剧\",\"episode\":\"第14集\"}");
                    if (request.getPath().startsWith("/search"))
                        return new MockResponse().setBody(App.gson().toJson(List.of(row(direct, name, "expired"))));
                    return new MockResponse().setResponseCode(404);
                }
            });
            backup.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().startsWith("/search"))
                        return new MockResponse().setBody(App.gson().toJson(List.of(row(backup, name, "valid"),
                                row(backup, name.replace("第14集", "第13集"), "wrong")))).setBodyDelay(400, TimeUnit.MILLISECONDS);
                    if (request.getPath().contains("/wrong?")) wrongDownloads.incrementAndGet();
                    return new MockResponse().setBody("<i><d p=\"1,1,25,16777215,0,0,0,1\">备用目录第十四集弹幕</d></i>");
                }
            });
            Result result = new Result();
            DanmakuAiMatcher.start(new DanmakuMatchContext("回退电视剧第二季", "2026", "电视剧", "第14集"),
                    new DanmakuAiClient(direct.url("/v1").toString(), "fixture", "fixture-key"),
                    useBackup ? List.of(endpoint(direct)) : List.of(endpoint(direct), endpoint(backup)),
                    useBackup ? List.of(endpoint(backup)) : List.of(), result);
            result.await(8);
            assertNotNull("failure=" + result.error, result.item);
            assertTrue(result.item.getUrl(), result.item.getUrl().contains("/valid?"));
            assertEquals(0, wrongDownloads.get());
        }
    }

    @Test public void changingEpisodeCancelsPendingAlternativeCatalogues() throws Exception {
        try (var direct = new MockWebServer(); var backup = new MockWebServer()) {
            direct.start(); backup.start();
            CountDownLatch backupStarted = new CountDownLatch(1);
            AtomicInteger downloads = new AtomicInteger();
            String name = "取消回退电视剧(2026)【电视剧】from fixture - 第14集";
            direct.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("/chat/completions")) return model("{\"query\":\"取消回退电视剧\"}");
                    if (request.getPath().startsWith("/search"))
                        return new MockResponse().setBody(App.gson().toJson(List.of(row(direct, name, "expired"))));
                    return new MockResponse().setResponseCode(404);
                }
            });
            backup.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().startsWith("/search")) {
                        backupStarted.countDown();
                        return new MockResponse().setBody(App.gson().toJson(List.of(row(backup, name, "cancelled"))))
                                .setBodyDelay(600, TimeUnit.MILLISECONDS);
                    }
                    downloads.incrementAndGet(); return new MockResponse().setResponseCode(500);
                }
            });
            Result result = new Result();
            DanmakuAiMatcher.start(new DanmakuMatchContext("取消回退电视剧", "2026", "电视剧", "14"),
                    new DanmakuAiClient(direct.url("/v1").toString(), "fixture", "fixture-key"),
                    List.of(endpoint(direct)), List.of(endpoint(backup)), result);
            assertTrue(backupStarted.await(5, TimeUnit.SECONDS));
            DanmakuAiMatcher.cancel();
            assertFalse("Cancelled work emitted a terminal callback", result.done.await(900, TimeUnit.MILLISECONDS));
            assertEquals(0, downloads.get());
        }
    }

    @Test public void invalidModelNormalizationStillLoadsVerifiedDramaEpisode() throws Exception {
        for (String response : List.of("", "not JSON", "{\"query\":\"目录电视剧\",\"confidence\":0.85}",
                "说明：```json\n{\"query\":\"目录电视剧\",\"confidence\":\"0.95\"}\n```",
                "{\"query\":\"无关节目\",\"episode\":\"第13集\",\"confidence\":1}")) {
            try (var server = new MockWebServer()) {
                server.start(); AtomicInteger modelCalls = new AtomicInteger();
                var items = List.of(row(server, "目录电视剧第二季(2026)【电视剧】from fixture - 第14集", "correct14"),
                        row(server, "目录电视剧第二季(2026)【电视剧】from fixture - 第13集", "wrong13"),
                        row(server, "目录电视剧第一季(2025)【电视剧】from fixture - 第14集", "wrongSeason"));
                server.setDispatcher(new Dispatcher() {
                    @Override public MockResponse dispatch(RecordedRequest request) {
                        if (request.getPath().contains("/chat/completions")) { modelCalls.incrementAndGet(); return model(response); }
                        if (request.getPath().startsWith("/search")) {
                            assertEquals("目录电视剧", request.getRequestUrl().queryParameter("name"));
                            assertEquals("", request.getRequestUrl().queryParameter("episode"));
                            return new MockResponse().setBody(App.gson().toJson(items));
                        }
                        assertTrue(request.getPath(), request.getPath().contains("/correct14?"));
                        return new MockResponse().setBody("<i><d p=\"1,1,25,16777215,0,0,0,1\">第十四集弹幕</d></i>");
                    }
                });
                Result result = new Result();
                DanmakuAiMatcher.start(new DanmakuMatchContext("目录电视剧第二季【4K】", "2026", "电视剧", "14(全38集)", "14"),
                        new DanmakuAiClient(server.url("/v1").toString(), "fixture", "fixture-key"), List.of(endpoint(server)), List.of(), result);
                result.await(6); assertNotNull("failure="+result.error, result.item);
                assertTrue(result.item.getUrl().contains("/correct14?")); assertEquals(1,modelCalls.get());
                DanmakuAiMatcher.cancel();
            }
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
                    if (length.find()) com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(input, Integer.parseInt(length.group(1)));
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
        livePlayerFallback("【测试专线】现在就出发第四季", "EP01 Part B", 3, "2026", "综艺", "z41023tinp7", "现在就出发", true);
    }

    @Test public void livePlayerFallbackKeepsResolvedDramaEpisode() throws Exception {
        livePlayerFallback("【测试专线】喜剧之王", "14(全24集)", 14, "2026", "电视剧", "2586684050154100", "喜剧之王", true);
    }

    @Test public void livePlayerAutomaticallyLoadsDramaWithoutManualSearch() throws Exception {
        for (String[] spec : List.of(new String[]{"喜剧之王", "2026", "第14集", "14", "2586684050154100"},
                new String[]{"狂飙", "2023", "14(全39集)", "14", "2856211773028900"},
                new String[]{"庆余年第二季", "2024", "第14集", "14", "b41007m5t4f"},
                new String[]{"长相思第二季", "2024", "蓝光", "5", "j41000ms26r"},
                new String[]{"罚罪2", "2025", "第2集", "2", "2329990978126400"}))
            livePlayerFallback(spec[0], spec[2], Integer.parseInt(spec[3]), spec[1], "电视剧", spec[4], spec[0], null);
    }

    private void livePlayerFallback(String title, String episode, int episodeIndex, String year, String type,
                                    String expectedId, String expectedQuery, Boolean expectedAi) throws Exception {
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
            var selected = new CountDownLatch(1);
            var stages = new CopyOnWriteArrayList<DanmakuStatus.Stage>();
            var source = new AtomicReference<android.net.Uri>();
            var history = new com.fongmi.android.tv.bean.History(); history.setKey("585-ai-fallback-" + title); history.setVodName(title);
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
                        com.fongmi.android.tv.bean.Episode.create(episode, "fixture"), episodeIndex, year, type, player[0]);
            });
            assertTrue("Automatic player fallback deadline", selected.await(100, TimeUnit.SECONDS));
            // Public catalogues can temporarily fail. Automatic recovery through AI is
            // valid here; forced-AI cases still require that stage and all cases verify ID.
            if (expectedAi != null) assertEquals(stages.toString(), expectedAi.booleanValue(), stages.contains(DanmakuStatus.Stage.AI_MATCHING));
            assertTrue(source.get().toString(), source.get().toString().endsWith(expectedId));
            var saved = DanmakuManualMatchStore.get().find(new DanmakuMatchContext(title, year, type, episode));
            assertNotNull(saved); assertEquals(expectedQuery, saved.query());
            android.util.Log.i("Danmaku584QA", "player automatic " + title + " " + episode + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start) + " stages=" + stages + " source=" + source.get());
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
