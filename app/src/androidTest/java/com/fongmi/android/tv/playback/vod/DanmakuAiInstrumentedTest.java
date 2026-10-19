package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.setting.DanmakuAiSetting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.dialog.DanmakuAiDialog;
import com.google.gson.JsonParser;
import com.google.android.material.textfield.TextInputEditText;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DanmakuAiInstrumentedTest {
    private Map<String, ?> oldPreferences;
    private android.content.SharedPreferences prefs;

    @Before public void before() {
        prefs = App.get().getSharedPreferences("danmaku_ai_private", Context.MODE_PRIVATE);
        oldPreferences = prefs.getAll();
        prefs.edit().clear().commit();
    }

    @After public void after() {
        DanmakuAiMatcher.cancel();
        var editor = prefs.edit().clear();
        for (var entry : oldPreferences.entrySet()) {
            if (entry.getValue() instanceof String value) editor.putString(entry.getKey(), value);
            if (entry.getValue() instanceof Boolean value) editor.putBoolean(entry.getKey(), value);
        }
        editor.commit();
    }

    @Test public void encryptsKeyAndRequiresNewKeyForAnotherEndpoint() {
        DanmakuAiSetting.save("https://example.invalid/v1/", "fixture-model", "fixture-secret", true);
        assertTrue(DanmakuAiSetting.enabled());
        assertEquals("fixture-secret", DanmakuAiSetting.key());
        assertFalse(prefs.getString("credential", "").contains("fixture-secret"));
        assertFalse(com.github.catvod.utils.Prefers.getPrefers().getAll().containsKey("credential"));
        try { DanmakuAiSetting.save("https://other.invalid/v1", "fixture-model", "", true); fail("Reused key"); }
        catch (IllegalArgumentException expected) { }
        try { DanmakuAiSetting.normalizeBaseUrl("http://example.invalid/v1"); fail("Insecure endpoint"); }
        catch (IllegalArgumentException expected) { }
        DanmakuAiSetting.save(DanmakuAiSetting.baseUrl(), "fixture-model", "", false);
        assertFalse(DanmakuAiSetting.enabled());
        assertEquals("fixture-secret", DanmakuAiSetting.key());
        DanmakuAiSetting.clear();
        assertFalse(DanmakuAiSetting.hasKey());
    }

    @Test public void sendsBoundedChatContractAndDoesNotFollowRedirects() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer destination = new MockWebServer()) {
            server.start(); destination.start();
            server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"query\\\":\\\"喜剧之王\\\",\\\"confidence\\\":1}\"}}]}"));
            var client = new DanmakuAiClient(server.url("/v1").toString(), "deepseek-v4.1-flash", "fixture-secret");
            var context = new DanmakuMatchContext("喜剧之王4K", "2026", "电视剧", "第14集");
            assertEquals("喜剧之王", DanmakuAiProtocol.query(client.complete(DanmakuAiProtocol.context("clean", context)), context));
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("/v1/chat/completions", request.getPath());
            assertEquals("Bearer fixture-secret", request.getHeader("Authorization"));
            var body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
            assertEquals(180, body.get("max_tokens").getAsInt());
            assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
            assertFalse(body.has("enable_thinking"));
            assertEquals(2, body.getAsJsonArray("messages").size());
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", destination.url("/stolen")));
            try { client.complete(DanmakuAiProtocol.context("clean", context)); fail("Followed redirect"); }
            catch (java.io.IOException expected) { assertEquals("http_302", expected.getMessage()); }
            assertEquals(0, destination.getRequestCount());
            for (int status : new int[]{401, 429, 500}) {
                server.enqueue(new MockResponse().setResponseCode(status));
                try { client.complete(DanmakuAiProtocol.context("clean", context)); fail("Accepted " + status); }
                catch (java.io.IOException expected) { assertEquals("http_" + status, expected.getMessage()); }
            }
            client.cancel();
            try { client.complete(DanmakuAiProtocol.context("clean", context)); fail("Ran after cancellation"); }
            catch (java.io.IOException expected) { assertEquals("cancelled", expected.getMessage()); }
        }
    }

    @Test public void fetchesAuthenticatedModelsAndCancelsSlowRequests() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer destination = new MockWebServer()) {
            server.start(); destination.start();
            var client = new DanmakuAiClient(server.url("/v1").toString(), "", "fixture-model-key");
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"deepseek-v4.1-flash\"},{\"id\":\"qwen3.5-flash\"}]}"));
            assertEquals(2, client.models().size());
            var request = server.takeRequest();
            assertEquals("GET", request.getMethod()); assertEquals("/v1/models", request.getPath());
            assertEquals("Bearer fixture-model-key", request.getHeader("Authorization"));
            for (var response : List.of(new MockResponse().setResponseCode(401), new MockResponse().setBody("{\"data\":[]}"),
                    new MockResponse().setBody("x".repeat(524289)), new MockResponse().setResponseCode(302).addHeader("Location", destination.url("/models")))) {
                server.enqueue(response);
                try { client.models(); fail("Accepted invalid model response"); } catch (java.io.IOException expected) { }
                server.takeRequest();
            }
            assertEquals(0, destination.getRequestCount());
            server.enqueue(new MockResponse().setHeadersDelay(10, TimeUnit.SECONDS).setBody("{\"data\":[]}"));
            var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> { try { client.models(); return false; } catch (java.io.IOException expected) { return true; } });
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); client.cancel();
                assertTrue(result.get(2, TimeUnit.SECONDS));
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void liveGatewayTlsWorksWithoutPuttingARealKeyOnTheDevice() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("danmakuLive")));
        // Real model tests use a host relay to keep the provider key off the QA device.
        // Also exercise Android's own HTTPS/trust store. The invalid token normally gets
        // an HTTP rejection; a public model list is also valid. DNS/TLS failures are not.
        var client = new DanmakuAiClient(DanmakuAiSetting.DEFAULT_BASE_URL, "", "invalid-transport-probe");
        try {
            assertFalse(client.models().isEmpty());
        } catch (java.io.IOException error) {
            assertTrue(error.toString(), "http_401".equals(error.getMessage()) || "http_403".equals(error.getMessage()));
        } finally { client.cancel(); }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test public void modelDropdownSelectsWithoutTypingAndSavesTheApiId() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                var dialog = new DanmakuAiDialog(); dialog.showNow(activity.getSupportFragmentManager(), "model-choice-test");
                var window = (androidx.appcompat.app.AlertDialog) dialog.requireDialog();
                com.google.android.material.textfield.MaterialAutoCompleteTextView model = window.findViewById(R.id.model);
                assertNull("No model-name keyboard", model.getKeyListener());
                assertTrue(model.getText().toString().contains("DeepSeek V4.1 Flash"));
                model.requestFocus();
            });
            var instrumentation = InstrumentationRegistry.getInstrumentation();
            instrumentation.waitForIdleSync();
            var ready = new java.util.concurrent.atomic.AtomicBoolean();
            long focusDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
            do {
                scenario.onActivity(activity -> {
                    var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("model-choice-test");
                    var model = dialog.requireDialog().findViewById(R.id.model);
                    ready.set(model.hasWindowFocus() && model.hasFocus() && model.isLaidOut());
                });
                if (!ready.get()) android.os.SystemClock.sleep(50);
            } while (!ready.get() && android.os.SystemClock.elapsedRealtime() < focusDeadline);
            assertTrue("Model field ready for actual input", ready.get());
            if (com.fongmi.android.tv.utils.Util.isMobile()) {
                var point = new int[2];
                scenario.onActivity(activity -> {
                    var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("model-choice-test");
                    var model = dialog.requireDialog().findViewById(R.id.model); model.getLocationOnScreen(point);
                    point[0] += model.getWidth() / 2; point[1] += model.getHeight() / 2;
                });
                long down = android.os.SystemClock.uptimeMillis();
                for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                    var event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, point[0], point[1], 0);
                    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); instrumentation.sendPointerSync(event); event.recycle(); android.os.SystemClock.sleep(60);
                }
            } else instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(300);
            var image = instrumentation.getUiAutomation().takeScreenshot();
            try (var output = new java.io.FileOutputStream(new File(App.get().getExternalFilesDir(null), "583-native-model-popup.png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            } finally { image.recycle(); }
            if (com.fongmi.android.tv.utils.Util.isMobile()) {
                var bounds = new AtomicReference<android.graphics.Rect>();
                instrumentation.runOnMainSync(() -> {
                    for (var window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                        var rectangle = modelChoiceBounds(window, "Qwen 3.5 Flash");
                        if (rectangle != null) bounds.set(rectangle);
                    }
                });
                assertNotNull("Qwen visible in dropdown", bounds.get());
                var rect = bounds.get();
                long now = android.os.SystemClock.uptimeMillis();
                for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                    var event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, rect.centerX(), rect.centerY(), 0);
                    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); instrumentation.sendPointerSync(event); event.recycle(); android.os.SystemClock.sleep(60);
                }
            } else {
                boolean selected = false;
                for (int i = 0; i < 5; i++) {
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                    instrumentation.waitForIdleSync();
                    var index = new java.util.concurrent.atomic.AtomicInteger(-1);
                    scenario.onActivity(activity -> {
                        var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("model-choice-test");
                        index.set(((android.widget.AutoCompleteTextView) dialog.requireDialog().findViewById(R.id.model)).getListSelection());
                    });
                    if (index.get() == 2) { selected = true; break; }
                }
                assertTrue("TV direction keys reach Qwen", selected);
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            }
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("model-choice-test");
                var window = (androidx.appcompat.app.AlertDialog) dialog.requireDialog();
                var model = (android.widget.TextView) window.findViewById(R.id.model);
                assertEquals("Qwen 3.5 Flash", model.getText().toString());
                ((android.widget.EditText) window.findViewById(R.id.key)).setText("fixture-dropdown-key");
                window.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            });
            long until = android.os.SystemClock.elapsedRealtime() + 5000;
            while (!DanmakuAiSetting.hasKey() && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(50);
            assertTrue(DanmakuAiSetting.hasKey()); assertEquals("qwen3.5-flash", DanmakuAiSetting.model());
            scenario.onActivity(activity -> {
                var dialog = new DanmakuAiDialog(); dialog.showNow(activity.getSupportFragmentManager(), "model-choice-reopened");
                var model = (android.widget.TextView) dialog.requireDialog().findViewById(R.id.model);
                assertEquals("Qwen 3.5 Flash", model.getText().toString()); dialog.dismissNow();
            });
        }
    }

    private android.graphics.Rect modelChoiceBounds(android.view.View view, String label) {
        if (view instanceof android.widget.TextView text && label.contentEquals(text.getText()) && view.isShown()) {
            var position = new int[2]; view.getLocationOnScreen(position);
            return new android.graphics.Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
        }
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            var rect = modelChoiceBounds(group.getChildAt(i), label); if (rect != null) return rect;
        }
        return null;
    }

    @Test public void unavailableModelChoiceFallsBackToEquivalentWorkingDocument() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    return request.getPath().contains("/bad") ? new MockResponse().setResponseCode(500)
                            : new MockResponse().setBody("<i><d p=\"1,1,25,16777215,0,0,0,1\">可用弹幕</d></i>");
                }
            });
            server.start();
            Danmaku bad = Danmaku.from(server.url("/api/v2/comment/bad?format=xml").toString());
            Danmaku good = Danmaku.from(server.url("/api/v2/comment/good?format=xml").toString());
            bad.setName("测试片 第14集"); good.setName("测试片 第14集");
            var finished = new CountDownLatch(1);
            var selected = new AtomicReference<Danmaku>();
            var constructor = Class.forName(DanmakuAiMatcher.class.getName() + "$Job").getDeclaredConstructor(DanmakuAiMatcher.Callback.class);
            constructor.setAccessible(true);
            Object job = constructor.newInstance(new DanmakuAiMatcher.Callback() {
                @Override public void onFound(String query, Danmaku item, List<Danmaku> catalogue) { selected.set(item); finished.countDown(); }
                @Override public void onFailure() { finished.countDown(); }
                @Override public void onProgress(int percent) { }
            });
            var verify = job.getClass().getDeclaredMethod("verify", String.class, List.class, List.class); verify.setAccessible(true);
            verify.invoke(job, "测试片", List.of(bad, good), List.of(bad, good));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertSame(good, selected.get());
        }
    }

    @Test public void settingsValidationKeepsFormAndSavedKeyIsNeverPrefilled() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                DanmakuAiDialog dialog = new DanmakuAiDialog();
                dialog.showNow(activity.getSupportFragmentManager(), "ai-test");
                var window = (androidx.appcompat.app.AlertDialog) dialog.requireDialog();
                ((com.google.android.material.materialswitch.MaterialSwitch) window.findViewById(R.id.enabled)).setChecked(true);
                window.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
                assertNotNull(((com.google.android.material.textfield.TextInputLayout) window.findViewById(R.id.keyLayout)).getError());
                assertTrue(dialog.isAdded());
                dialog.dismissNow();
            });
            DanmakuAiSetting.save(DanmakuAiSetting.DEFAULT_BASE_URL, DanmakuAiSetting.DEFAULT_MODEL, "fixture-secret", true);
            scenario.onActivity(activity -> {
                DanmakuAiDialog dialog = new DanmakuAiDialog();
                dialog.showNow(activity.getSupportFragmentManager(), "ai-test");
                TextInputEditText key = dialog.requireDialog().findViewById(R.id.key);
                assertEquals("", key.getText().toString());
                dialog.requireDialog().findViewById(R.id.clear).performClick();
                assertFalse(DanmakuAiSetting.hasKey());
                dialog.dismissNow();
            });
            if ("true".equals(InstrumentationRegistry.getArguments().getString("capture_ai"))) {
                scenario.onActivity(activity -> new DanmakuAiDialog().showNow(activity.getSupportFragmentManager(), "ai-capture"));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("ai-capture");
                    android.view.View test = dialog.requireDialog().findViewById(R.id.test);
                    test.requestFocus();
                    test.requestRectangleOnScreen(new android.graphics.Rect(0, 0, test.getWidth(), test.getHeight()), true);
                });
                android.os.SystemClock.sleep(400);
                scenario.onActivity(activity -> {
                    var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("ai-capture");
                    android.view.View test = dialog.requireDialog().findViewById(R.id.test);
                    var rectangle = new android.graphics.Rect();
                    assertTrue("Test action reachable", test.getGlobalVisibleRect(rectangle));
                    assertEquals("Test action not clipped", test.getHeight(), rectangle.height());
                });
                var image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                try (var output = new java.io.FileOutputStream(new File(App.get().getExternalFilesDir(null), "danmaku-ai-ui.png"))) {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                }
                image.recycle();
                if (com.fongmi.android.tv.utils.Util.isMobile()) {
                    scenario.onActivity(activity -> {
                        var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("ai-capture");
                        android.view.View key = dialog.requireDialog().findViewById(R.id.key);
                        key.requestFocus();
                        ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                                .showSoftInput(key, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    });
                    android.os.SystemClock.sleep(700);
                    scenario.onActivity(activity -> {
                        var dialog = (DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("ai-capture");
                        var rectangle = new android.graphics.Rect();
                        assertTrue("Key input visible with keyboard", dialog.requireDialog().findViewById(R.id.key).getGlobalVisibleRect(rectangle));
                        assertTrue("Save reachable with keyboard", ((androidx.appcompat.app.AlertDialog) dialog.requireDialog())
                                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).getGlobalVisibleRect(rectangle));
                    });
                    image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                    try (var output = new java.io.FileOutputStream(new File(App.get().getExternalFilesDir(null), "danmaku-ai-keyboard.png"))) {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                    }
                    image.recycle();
                }
                scenario.onActivity(activity -> ((DanmakuAiDialog) activity.getSupportFragmentManager().findFragmentByTag("ai-capture")).dismissNow());
            }
        }
    }

    /** Opt-in credential file is injected into the QA emulator, never into an APK or source. */
    @Test public void liveDrama14CleansSelectsAndLoadsRealDocument() throws Exception {
        File configFile = new File(App.get().getFilesDir(), "ai-live-probe.json");
        Assume.assumeTrue("Optional live credential absent", configFile.isFile());
        var config = JsonParser.parseString(Files.readString(configFile.toPath())).getAsJsonObject();
        DanmakuAiSetting.save(config.get("base_url").getAsString(), DanmakuAiSetting.DEFAULT_MODEL,
                config.get("api_key").getAsString(), true);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Danmaku> found = new AtomicReference<>();
        AtomicReference<String> clean = new AtomicReference<>();
        assertTrue(DanmakuAiMatcher.search(new DanmakuMatchContext("喜剧之王【更新至14集】4K", "2026", "电视剧", "第14集"), new DanmakuAiMatcher.Callback() {
            @Override public void onFound(String query, Danmaku item, List<Danmaku> catalogue) { clean.set(query); found.set(item); finished.countDown(); }
            @Override public void onFailure() { finished.countDown(); }
            @Override public void onProgress(int percent) { }
        }));
        assertTrue("AI fallback deadline", finished.await(48, TimeUnit.SECONDS));
        assertNotNull("Live AI must select and verify a real episode", found.get());
        assertEquals("喜剧之王", clean.get());
        assertTrue(DanmakuMatch.isReliable("喜剧之王", "2026", "电视剧", "第14集", found.get().getName()));
    }

    @Test public void liveAutomaticFallbackIsConditionalAndRemembersCleanQuery() throws Exception {
        File configFile = new File(App.get().getFilesDir(), "ai-live-probe.json");
        Assume.assumeTrue("Optional live credential absent", configFile.isFile());
        var config = JsonParser.parseString(Files.readString(configFile.toPath())).getAsJsonObject();
        DanmakuAiSetting.save(config.get("base_url").getAsString(), DanmakuAiSetting.DEFAULT_MODEL,
                config.get("api_key").getAsString(), true);
        boolean auto = com.fongmi.android.tv.setting.DanmakuSetting.isAuto();
        boolean load = com.fongmi.android.tv.setting.DanmakuSetting.isLoad();
        boolean show = com.fongmi.android.tv.setting.DanmakuSetting.isShow();
        com.fongmi.android.tv.setting.DanmakuSetting.putLoad(true);
        com.fongmi.android.tv.setting.DanmakuSetting.putAuto(true);
        try {
            for (String title : List.of("喜剧之王", "【专线" + java.util.UUID.randomUUID().toString().replaceAll("[^a-z]", "") + "】喜剧之王")) {
                var stages = new java.util.concurrent.CopyOnWriteArrayList<com.fongmi.android.tv.player.danmaku.DanmakuStatus.Stage>();
                var selected = new CountDownLatch(1);
                com.fongmi.android.tv.player.PlayerManager[] player = new com.fongmi.android.tv.player.PlayerManager[1];
                var history = new com.fongmi.android.tv.bean.History(); history.setKey("ai-test"); history.setVodName(title);
                var episode = com.fongmi.android.tv.bean.Episode.create("第14集", "fixture");
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    player[0] = new com.fongmi.android.tv.player.PlayerManager((com.fongmi.android.tv.player.PlayerManager.Callback)
                            java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class[]{com.fongmi.android.tv.player.PlayerManager.Callback.class}, (proxy, method, args) -> {
                        if (method.getName().equals("onDanmakuSourceChanged") && args[0] != null) selected.countDown();
                        if (method.getName().equals("onDanmakuStatusChanged")) stages.add(((com.fongmi.android.tv.player.danmaku.DanmakuStatus) args[0]).stage());
                        return null;
                    }));
                    try {
                        var spec = com.fongmi.android.tv.player.PlayerManager.class.getDeclaredField("spec"); spec.setAccessible(true);
                        spec.set(player[0], com.fongmi.android.tv.player.media.PlaySpec.from("fixture", "https://example.invalid/test.mp4", null,
                                new androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist("第14集").build()));
                    } catch (Exception error) { throw new AssertionError(error); }
                    VodPlaybackMedia.searchDanmaku(new com.fongmi.android.tv.bean.Result(), history, episode, 14, "2026", "电视剧", player[0]);
                });
                try {
                    assertTrue("Automatic path must finish", selected.await(100, TimeUnit.SECONDS));
                    assertEquals(title.startsWith("【"), stages.contains(com.fongmi.android.tv.player.danmaku.DanmakuStatus.Stage.AI_MATCHING));
                    var saved = DanmakuManualMatchStore.get().find(new DanmakuMatchContext(title, "2026", "电视剧", "14"));
                    assertNotNull(saved); assertEquals("喜剧之王", saved.query());
                    assertNotNull(saved.episode("15"));
                } finally {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { VodPlaybackMedia.invalidate(player[0]); player[0].release(); });
                }
            }
        } finally {
            com.fongmi.android.tv.setting.DanmakuSetting.putAuto(auto);
            com.fongmi.android.tv.setting.DanmakuSetting.putLoad(load);
            com.fongmi.android.tv.setting.DanmakuSetting.putShow(show);
        }
    }
}
