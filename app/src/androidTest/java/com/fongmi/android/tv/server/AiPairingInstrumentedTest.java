package com.fongmi.android.tv.server;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.DanmakuAiSetting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.dialog.DanmakuAiDialog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AiPairingInstrumentedTest {
    private Map<String, ?> old;
    private android.content.SharedPreferences prefs;
    private final OkHttpClient http = new OkHttpClient();
    @Before public void setup() { prefs=App.get().getSharedPreferences("danmaku_ai_private", Context.MODE_PRIVATE);old=prefs.getAll();prefs.edit().clear().commit();Server.get().start(); }
    @After public void cleanup() { var edit=prefs.edit().clear();for(var e:old.entrySet()){if(e.getValue() instanceof String s)edit.putString(e.getKey(),s);if(e.getValue() instanceof Boolean b)edit.putBoolean(e.getKey(),b);}edit.commit(); }
    private Response request(String token, String path, String body, String origin) throws Exception {
        return requestAt(Server.get().getAddress(true), token, path, body, origin);
    }
    private Response requestAt(String address, String token, String path, String body, String origin) throws Exception {
        Request.Builder request=new Request.Builder().url(address+"/ai-setup/"+path).header("X-Pairing-Token",token);
        if(body!=null)request.header("Origin",origin==null?address:origin).post(RequestBody.create(body,MediaType.get("application/json")));
        return http.newCall(request.build()).execute();
    }
    private String payload(String base, String key) { var o=new JsonObject();o.addProperty("baseUrl",base);o.addProperty("model","fixture-model");o.addProperty("key",key);o.addProperty("enabled",true);return o.toString(); }
    private String pairBody(String code) { var data = new JsonObject(); data.addProperty("code", code); return data.toString(); }
    @Test public void modelRefreshRequiresPairingOriginAndNewKeyForNewHost() throws Exception {
        DanmakuAiSetting.save("https://example.invalid/v1", "saved-model", "fixture-private-key", true);
        var pairing = AiPairingSession.open(() -> {});
        try {
            String body = "{\"baseUrl\":\"https://other.invalid/v1\",\"key\":\"\"}";
            try (var r = request("wrong", "models", body, null)) { assertEquals(410, r.code()); }
            try (var r = request(pairing.token(), "models", body, "https://other.invalid")) { assertEquals(403, r.code()); }
            try (var r = request(pairing.token(), "models", body, null)) {
                assertEquals(400, r.code()); assertEquals("key", JsonParser.parseString(r.body().string()).getAsJsonObject().get("code").getAsString());
            }
        } finally { pairing.close(); }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test public void phoneModelDropdownRefreshFailureStaleResponseAndSave() throws Exception {
        var pairing = AiPairingSession.open(() -> {});
        var web = new AtomicReference<android.webkit.WebView>();
        try (ActivityScenario<AiPasteTestActivity> scenario = ActivityScenario.launch(AiPasteTestActivity.class)) {
            scenario.onActivity(a -> {
                var view = new android.webkit.WebView(a); view.getSettings().setJavaScriptEnabled(true);
                a.setContentView(view); view.setFocusableInTouchMode(true); view.requestFocus(); web.set(view);
                view.loadUrl(Server.get().getAddress(true) + "/ai-setup#" + pairing.token());
            });
            awaitJs(web.get(), "!!document.getElementById('model') && document.getElementById('model').options.length>1");
            assertEquals("\"SELECT\"", js(web.get(), "document.getElementById('model').tagName"));
            assertEquals("\"deepseek-v4.1-flash\"", js(web.get(), "document.getElementById('model').value"));
            js(web.get(), "window.realFetch=window.fetch;window.replyMode='slow';window.fetch=(url,opts)=>String(url).endsWith('/models')?new Promise(resolve=>{window.pendingModelReply=resolve;if(window.replyMode==='fail')resolve(new Response(JSON.stringify({code:'models_auth'}),{status:500}));if(window.replyMode==='ok')resolve(new Response(JSON.stringify({models:[{id:'deepseek-v4.1-flash',label:'DeepSeek V4.1 Flash',recommended:true},{id:'fixture-choice',label:'Fixture model'}]}),{status:200}));}):window.realFetch(url,opts)");
            js(web.get(), "document.getElementById('key').value='fixture-model-key';document.getElementById('refreshModels').click()");
            awaitJs(web.get(), "document.getElementById('refreshModels').disabled");
            js(web.get(), "document.getElementById('model').dispatchEvent(new Event('change',{bubbles:true}))");
            assertEquals("true", js(web.get(), "!document.getElementById('refreshModels').disabled && document.getElementById('modelHelp').textContent===t.modelHelp && !document.getElementById('model').hasAttribute('aria-busy')"));
            js(web.get(), "document.getElementById('refreshModels').click()");
            awaitJs(web.get(), "document.getElementById('refreshModels').disabled");
            js(web.get(), "document.getElementById('base').value='https://other.invalid/v1';document.getElementById('base').dispatchEvent(new Event('input',{bubbles:true}));window.pendingModelReply(new Response(JSON.stringify({models:[{id:'stale-id',label:'stale'}]}),{status:200}))");
            android.os.SystemClock.sleep(150);
            assertEquals("\"\"", js(web.get(), "document.getElementById('model').value"));
            assertEquals("false", js(web.get(), "Array.from(document.getElementById('model').options).some(o=>o.value==='stale-id')"));
            js(web.get(), "document.getElementById('base').value='https://llm-api.mcisaas.com/v1';document.getElementById('base').dispatchEvent(new Event('input',{bubbles:true}));window.replyMode='fail';document.getElementById('refreshModels').click()");
            awaitJs(web.get(), "!document.getElementById('refreshModels').disabled && document.getElementById('modelHelp').textContent===t.modelsAuth");
            assertEquals("\"deepseek-v4.1-flash\"", js(web.get(), "document.getElementById('model').value"));
            js(web.get(), "window.replyMode='ok';document.getElementById('refreshModels').click()");
            awaitJs(web.get(), "Array.from(document.getElementById('model').options).some(o=>o.value==='fixture-choice')");
            tapField(web.get(), "model");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            android.os.SystemClock.sleep(400);
            capture("583-web-model-popup.png");
            var choice = new AtomicReference<android.graphics.Rect>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (var window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    var rect = modelChoiceBounds(window, "Fixture model"); if (rect != null) choice.set(rect);
                }
            });
            assertNotNull("Web model picker is open", choice.get());
            long tap = android.os.SystemClock.uptimeMillis();
            for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                var event = android.view.MotionEvent.obtain(tap, android.os.SystemClock.uptimeMillis(), action, choice.get().centerX(), choice.get().centerY(), 0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                InstrumentationRegistry.getInstrumentation().sendPointerSync(event); event.recycle(); android.os.SystemClock.sleep(60);
            }
            awaitJs(web.get(), "document.getElementById('model').value==='fixture-choice'");
            js(web.get(), "document.getElementById('form').requestSubmit()");
            awaitJs(web.get(), "document.getElementById('form').hidden");
            assertEquals("fixture-choice", DanmakuAiSetting.model());
        } finally {
            pairing.close(); InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (web.get() != null) web.get().destroy(); });
        }
    }
    @Test public void setupAssetsSurviveSharedPluginContextBeingCleared() throws Exception {
        var context = com.github.catvod.Init.context();
        String address = Server.get().getAddress(true);
        try {
            com.github.catvod.Init.set(null);
            for (String path : new String[]{"/ai-setup", "/css/ui.css"}) {
                try (var r = http.newCall(new Request.Builder().url(address + path).build()).execute()) {
                    assertEquals("App-owned resource must not depend on a plugin context: " + path, 200, r.code());
                    assertTrue(r.body().string().length() > 100);
                }
            }
        } finally { com.github.catvod.Init.set(context); }
    }
    @Test public void qrPageSurvivesSharedPlaybackServerStopping() throws Exception {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR_mode.equals("leanback"));
        var address = new AtomicReference<String>();
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(a -> {
                var dialog = new DanmakuAiDialog(); dialog.showNow(a.getSupportFragmentManager(), "ai-host-isolation-test");
                dialog.requireDialog().findViewById(R.id.scan).performClick();
            });
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            do {
                scenario.onActivity(a -> {
                    var dialog = (DanmakuAiDialog) a.getSupportFragmentManager().findFragmentByTag("ai-host-isolation-test");
                    dialog.getChildFragmentManager().executePendingTransactions();
                    var qr = (androidx.fragment.app.DialogFragment) dialog.getChildFragmentManager().findFragmentByTag("ai_pairing");
                    if (qr != null) address.set(((android.widget.TextView) qr.requireDialog().findViewById(R.id.address)).getText().toString());
                });
                if (address.get() != null && address.get().startsWith("http://")) break;
                android.os.SystemClock.sleep(100);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            assertNotNull(address.get()); assertTrue(address.get().startsWith("http://"));
            Server.get().stop(Runnable::run);
            try (var r = http.newCall(new Request.Builder().url(address.get()).build()).execute()) {
                assertEquals("An open pairing window must survive playback server cleanup", 200, r.code());
            }
        } finally { Server.get().start(); }
    }
    @Test public void browserCodeIsBoundedRevokedAndDoesNotLockQr() throws Exception {
        var pairing = AiPairingSession.open(() -> {});
        try {
            assertTrue(pairing.code().matches("[0-9]{8}"));
            assertFalse(pairing.connected());
            try (var r = request("", "pair", pairBody(pairing.code()), "https://other.invalid")) { assertEquals(403, r.code()); }
            try (var r = request("", "pair", pairBody(pairing.code()), null)) {
                assertEquals(200, r.code()); assertEquals(pairing.token(), JsonParser.parseString(r.body().string()).getAsJsonObject().get("token").getAsString());
                assertEquals("no-store", r.header("Cache-Control"));
            }
            String wrong = pairing.code().equals("00000000") ? "11111111" : "00000000";
            for (int i = 0; i < 5; i++) try (var r = request("", "pair", pairBody(wrong), null)) { assertEquals(i == 4 ? 429 : 400, r.code()); }
            try (var r = request("", "pair", pairBody(pairing.code()), null)) { assertEquals(429, r.code()); }
            try (var r = request(pairing.token(), "config", null, null)) { assertEquals(200, r.code()); }
            assertTrue(pairing.connected());
            pairing.close();
            try (var r = request("", "pair", pairBody(pairing.code()), null)) { assertEquals(410, r.code()); }
            pairing = AiPairingSession.open(() -> {});
            try (var r = request("", "pair", pairBody(pairing.code()), null)) { assertEquals(200, r.code()); }
            try (var r = request(pairing.token(), "save", payload("https://example.invalid/v1", "fixture-code-key"), null)) { assertEquals(200, r.code()); }
            try (var r = request("", "pair", pairBody(pairing.code()), null)) { assertEquals(410, r.code()); }
        } finally { pairing.close(); }
    }
    @Test public void browserAddressWithoutQrCanPairValidateAndSave() throws Exception {
        var pairing = AiPairingSession.open(() -> {});
        var web = new AtomicReference<android.webkit.WebView>();
        try (var host = new AiPairingHost(); ActivityScenario<AiPasteTestActivity> scenario = ActivityScenario.launch(AiPasteTestActivity.class)) {
            String address = ready(host).address();
            scenario.onActivity(a -> {
                var view = new android.webkit.WebView(a); view.getSettings().setJavaScriptEnabled(true);
                a.setContentView(view); view.setFocusableInTouchMode(true); view.requestFocus(); web.set(view);
                view.loadUrl(address);
            });
            awaitJs(web.get(), "!!document.getElementById('pairForm') && !document.getElementById('pairForm').hidden");
            assertEquals("true", js(web.get(), "document.getElementById('form').hidden"));
            capture("582-browser-code.png");
            js(web.get(), "document.getElementById('pairCode').value='123';document.getElementById('pairForm').requestSubmit()");
            awaitJs(web.get(), "document.getElementById('pairCode').getAttribute('aria-invalid')==='true'");
            String code = pairing.code();
            String wrong = code.equals("00000000") ? "11111111" : "00000000";
            js(web.get(), "document.getElementById('pairCode').value='" + wrong + "';document.getElementById('pairForm').requestSubmit()");
            awaitJs(web.get(), "!document.getElementById('pairSubmit').disabled && document.getElementById('pairCode').getAttribute('aria-invalid')==='true'");
            js(web.get(), "document.getElementById('pairCode').value='" + code.substring(0, 4) + " " + code.substring(4) + "';document.getElementById('pairForm').requestSubmit()");
            awaitJs(web.get(), "!document.getElementById('form').hidden && document.getElementById('pairForm').hidden");
            assertTrue(pairing.connected());
            assertEquals("\"\"", js(web.get(), "location.hash"));
            assertEquals("true", js(web.get(), "document.documentElement.scrollWidth<=innerWidth"));
            js(web.get(), "document.activeElement.blur()");
            capture("582-browser-form.png");
            js(web.get(), "document.getElementById('base').value='https://example.invalid/v1';document.getElementById('model').value='deepseek-v4.1-flash';document.getElementById('key').value='fixture-browser-key';document.getElementById('form').requestSubmit()");
            awaitJs(web.get(), "document.getElementById('form').hidden && document.getElementById('key').value===''");
            assertEquals("fixture-browser-key", DanmakuAiSetting.key());
        } finally {
            pairing.close();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (web.get() != null) web.get().destroy(); });
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

    private void capture(String name) throws Exception {
        android.os.SystemClock.sleep(250);
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (var out = new java.io.FileOutputStream(new File(App.get().getExternalFilesDir(null), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
    }
    private AiPairingHost.State ready(AiPairingHost host) throws Exception {
        var done = new java.util.concurrent.CountDownLatch(1);
        var value = new AtomicReference<AiPairingHost.State>();
        host.check(state -> { value.set(state); done.countDown(); });
        assertTrue("host check completes", done.await(8, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(AiPairingHost.Status.READY, value.get().status());
        return value.get();
    }
    @Test public void dedicatedHostRestartsDeadListenerAndClosesItsPort() throws Exception {
        org.junit.Assume.assumeFalse("A Wi-Fi/Ethernet network is required", Server.get().getAddress().isEmpty());
        String address;
        var host = new AiPairingHost();
        try {
            address = ready(host).address();
            assertNotEquals(Server.get().getAddress() + "/ai-setup", address);
            var field = AiPairingHost.class.getDeclaredField("listener"); field.setAccessible(true);
            ((fi.iki.elonen.NanoHTTPD) field.get(host)).stop();
            address = ready(host).address();
            try (var r = http.newCall(new Request.Builder().url(address).build()).execute()) { assertEquals(200, r.code()); }
            try (var r = http.newCall(new Request.Builder().url(address.replace("/ai-setup", "/device")).build()).execute()) { assertEquals(404, r.code()); }
        } finally { host.close(); }
        var url = new java.net.URL(address);
        try (var socket = new java.net.Socket()) {
            try { socket.connect(new java.net.InetSocketAddress(url.getHost(), url.getPort()), 700); fail("Closed pairing port must not accept connections"); }
            catch (java.io.IOException expected) { }
        }
    }
    @Test public void advertisedLanAddressServesPageAndRestartCannotBeStoppedByOldTask() throws Exception {
        var pending = new java.util.ArrayList<Runnable>();
        try {
            Server.get().stop(pending::add);
            Server.get().start();
            String address = Server.get().getAddress();
            assertFalse("Connected emulator must advertise its actual LAN address", address.isEmpty());
            assertFalse(address.contains("127.0.0.1"));
            for (var task : pending) task.run();
            pending.clear();
            assertEquals(address, Server.get().getAddress());
            try (var response = http.newCall(new Request.Builder().url(address + "/ai-setup").build()).execute()) {
                assertEquals(200, response.code());
                assertTrue(response.body().string().contains("pasteKey"));
            }
            var session = AiPairingSession.open(() -> {});
            try (var response = http.newCall(new Request.Builder().url(address + "/ai-setup/config")
                    .header("X-Pairing-Token", session.token()).build()).execute()) { assertEquals(200, response.code()); }
            finally { session.close(); }
        } finally { for (var task : pending) task.run(); }
    }
    @Test public void sessionRejectsStaleAndCrossOriginRequestsAndNeverReturnsSecret() throws Exception {
        var pairing=AiPairingSession.open(()->{});
        try {
            try(var r=request("wrong","config",null,null)){assertEquals(410,r.code());}
            try(var r=request(pairing.token(),"config",null,null)){assertEquals(200,r.code());assertFalse(r.body().string().contains("credential"));assertEquals("no-store",r.header("Cache-Control"));}
            try(var r=request(pairing.token(),"save",payload("https://example.invalid/v1","fixture-secret"),"https://other.invalid")){assertEquals(403,r.code());}
            try(var r=request(pairing.token(),"save",payload("http://example.invalid/v1","fixture-secret"),null)){assertEquals(400,r.code());}
            try(var r=request(pairing.token(),"save",payload("https://example.invalid/v1",""),null)){assertEquals(400,r.code());}
            assertFalse(DanmakuAiSetting.hasKey());
            try(var r=request(pairing.token(),"save",payload("https://example.invalid/v1","fixture-secret"),null)){assertEquals(200,r.code());assertFalse(r.body().string().contains("fixture-secret"));}
            assertEquals("fixture-secret",DanmakuAiSetting.key());assertTrue(DanmakuAiSetting.enabled());
            assertFalse(prefs.getString("credential","").contains("fixture-secret"));
            try(var r=request(pairing.token(),"save",payload("https://example.invalid/v1","replacement"),null)){assertEquals(410,r.code());}
            var next=AiPairingSession.open(()->{});
            try(var r=request(next.token(),"config",null,null)){String body=r.body().string();assertEquals(body,200,r.code());assertTrue(JsonParser.parseString(body).getAsJsonObject().get("hasKey").getAsBoolean());assertFalse(body.contains("fixture-secret"));}
            next.close();try(var r=request(next.token(),"config",null,null)){assertEquals(410,r.code());}
            var expired=AiPairingSession.open(()->{});var field=AiPairingSession.class.getDeclaredField("expires");field.setAccessible(true);field.setLong(expired,0);
            try(var r=request(expired.token(),"config",null,null)){assertEquals(410,r.code());}expired.close();
        } finally {pairing.close();}
    }
    @Test public void tvQrCanBeDecodedRefreshRevokesOldAndSaveUpdatesParent() throws Exception {
        Assume.assumeTrue("leanback".equals(BuildConfig.FLAVOR_mode));
        var token=new AtomicReference<String>();var oldToken=new AtomicReference<String>();
        var address = new AtomicReference<String>();
        try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(a->{var dialog=new DanmakuAiDialog();dialog.showNow(a.getSupportFragmentManager(),"ai-pairing-test");dialog.requireDialog().findViewById(R.id.scan).performClick();});
            String oldUrl = awaitQr(scenario);
            oldToken.set(oldUrl.substring(oldUrl.indexOf('#') + 1));
            address.set(oldUrl.substring(0, oldUrl.indexOf("/ai-setup")));
            scenario.onActivity(a -> {
                var dialog = (DanmakuAiDialog) a.getSupportFragmentManager().findFragmentByTag("ai-pairing-test");
                var qr = (androidx.fragment.app.DialogFragment) dialog.getChildFragmentManager().findFragmentByTag("ai_pairing");
                ((androidx.appcompat.app.AlertDialog)qr.requireDialog()).getButton(-1).performClick();
            });
            String url = awaitQr(scenario);
            token.set(url.substring(url.indexOf('#') + 1)); assertNotEquals(oldToken.get(), token.get());
            address.set(url.substring(0, url.indexOf("/ai-setup")));
            try(var r=requestAt(address.get(),oldToken.get(),"config",null,null)){assertEquals(410,r.code());}
            try(var r=requestAt(address.get(),token.get(),"config",null,null)){assertEquals(200,r.code());}
            android.os.SystemClock.sleep(2200);
            scenario.onActivity(a -> {
                var dialog = (DanmakuAiDialog) a.getSupportFragmentManager().findFragmentByTag("ai-pairing-test");
                var qr = (androidx.fragment.app.DialogFragment) dialog.getChildFragmentManager().findFragmentByTag("ai_pairing");
                assertEquals(a.getString(R.string.danmaku_ai_phone_connected), ((android.widget.TextView) qr.requireDialog().findViewById(R.id.connection)).getText().toString());
            });
            Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            try(var out=new java.io.FileOutputStream(new File(App.get().getExternalFilesDir(null),"577-ai-qr.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            if("true".equals(InstrumentationRegistry.getArguments().getString("capturePairing"))) {
                Files.writeString(new File(App.get().getExternalFilesDir(null),"577-pairing-token.txt").toPath(),token.get());
                long deadline=android.os.SystemClock.elapsedRealtime()+300000;
                while(!DanmakuAiSetting.hasKey()&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(200);
                assertTrue("browser submitted settings",DanmakuAiSetting.hasKey());
            } else try(var r=requestAt(address.get(),token.get(),"save",payload("https://example.invalid/v1","fixture-secret"),null)) {
                assertEquals(200,r.code());
                assertEquals("saved", JsonParser.parseString(r.body().string()).getAsJsonObject().get("code").getAsString());
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{var dialog=(DanmakuAiDialog)a.getSupportFragmentManager().findFragmentByTag("ai-pairing-test");dialog.getChildFragmentManager().executePendingTransactions();assertNull(dialog.getChildFragmentManager().findFragmentByTag("ai_pairing"));assertEquals("",((android.widget.EditText)dialog.requireDialog().findViewById(R.id.key)).getText().toString());assertTrue(((android.widget.TextView)dialog.requireDialog().findViewById(R.id.status)).getText().length()>0);
                assertEquals(a.getString(R.string.danmaku_ai_key_stored_placeholder), ((com.google.android.material.textfield.TextInputLayout)dialog.requireDialog().findViewById(R.id.keyLayout)).getPlaceholderText().toString());
                assertTrue(((android.widget.CompoundButton)dialog.requireDialog().findViewById(R.id.enabled)).isChecked());
                dialog.dismissNow();
                var reopened = new DanmakuAiDialog(); reopened.showNow(a.getSupportFragmentManager(), "ai-reopened");
                assertEquals(a.getString(R.string.danmaku_ai_saved_enabled), ((android.widget.TextView)reopened.requireDialog().findViewById(R.id.status)).getText().toString());
                reopened.dismissNow();});
        }
    }
    private String awaitQr(ActivityScenario<HomeActivity> scenario) throws Exception {
        var token = new AtomicReference<String>();
        long end = android.os.SystemClock.elapsedRealtime() + 15000;
        while (token.get() == null && android.os.SystemClock.elapsedRealtime() < end) {
            scenario.onActivity(a -> {
                var dialog = (DanmakuAiDialog) a.getSupportFragmentManager().findFragmentByTag("ai-pairing-test");
                dialog.getChildFragmentManager().executePendingTransactions();
                var qr = (androidx.fragment.app.DialogFragment) dialog.getChildFragmentManager().findFragmentByTag("ai_pairing");
                if (qr != null && ((android.widget.ImageView) qr.requireDialog().findViewById(R.id.code)).getDrawable() != null) token.set(decode(qr));
            });
            if (token.get() == null) android.os.SystemClock.sleep(100);
        }
        assertNotNull("A self-checked pairing QR must appear", token.get()); return token.get();
    }
    @Test public void slowPhoneUploadStillCommitsOnce() throws Exception {
        var pairing = AiPairingSession.open(() -> {});
        try (var socket = new java.net.Socket("127.0.0.1", com.github.catvod.Proxy.getPort())) {
            socket.setSoTimeout(15000);
            byte[] body = payload("https://example.invalid/v1", "fixture-slow-secret").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var out = socket.getOutputStream();
            String host = "127.0.0.1:" + com.github.catvod.Proxy.getPort();
            out.write(("POST /ai-setup/save HTTP/1.1\r\nHost: " + host + "\r\nOrigin: http://" + host
                    + "\r\nX-Pairing-Token: " + pairing.token() + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(body, 0, 12); out.flush();
            android.os.SystemClock.sleep(900);
            out.write(body, 12, body.length - 12); out.flush();
            String response = new String(com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(socket.getInputStream(), 8 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(response, response.startsWith("HTTP/1.1 200"));
            assertEquals("fixture-slow-secret", DanmakuAiSetting.key());
        } finally { pairing.close(); }
    }
    @Test public void phonePasteUsesNormalImeThenMasksAndSavesToTv() throws Exception {
        var pairing=AiPairingSession.open(()->{});
        var web=new AtomicReference<android.webkit.WebView>();
        try(ActivityScenario<AiPasteTestActivity> scenario=ActivityScenario.launch(AiPasteTestActivity.class)) {
            scenario.onActivity(a->{
                var view=new android.webkit.WebView(a);view.getSettings().setJavaScriptEnabled(true);
                a.setContentView(view);view.setFocusableInTouchMode(true);view.requestFocus();web.set(view);
                view.loadUrl(Server.get().getAddress(true)+"/ai-setup#"+pairing.token());
            });
            awaitJs(web.get(),"!!document.getElementById('form') && !document.getElementById('form').hidden");
            awaitJs(web.get(),"document.hasFocus()");
            assertEquals("\"password\"",js(web.get(),"document.getElementById('key').type"));
            tapKey(web.get());
            awaitJs(web.get(),"document.activeElement.id==='key' && document.getElementById('key').type==='text'");
            var editor=new android.view.inputmethod.EditorInfo();
            var connection=new AtomicReference<android.view.inputmethod.InputConnection>();
            long inputDeadline=android.os.SystemClock.elapsedRealtime()+5000;
            do {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->connection.set(web.get().onCreateInputConnection(editor)));
                if(connection.get()!=null)break;
                android.os.SystemClock.sleep(100);
            } while(android.os.SystemClock.elapsedRealtime()<inputDeadline);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                ((android.content.ClipboardManager)web.get().getContext().getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(android.content.ClipData.newPlainText("test key","fixture-native-paste-579"));
            });
            assertNotNull(connection.get());
            int variation=editor.inputType & android.text.InputType.TYPE_MASK_VARIATION;
            assertNotEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,variation);
            assertNotEquals(android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,variation);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertTrue(connection.get().performContextMenuAction(android.R.id.paste)));
            awaitJs(web.get(),"document.getElementById('key').value==='fixture-native-paste-579' && document.getElementById('key').type==='password'");
            assertEquals("false",js(web.get(),"document.activeElement.id==='key'"));
            android.os.SystemClock.sleep(300);
            Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            try(var output=new java.io.FileOutputStream(new File(App.get().getExternalFilesDir(null),"579-native-paste.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,output);}shot.recycle();
            // Leaving normal input without a paste must also restore masking.
            js(web.get(),"document.getElementById('pasteKey').click()");
            awaitJs(web.get(),"document.activeElement.id==='key' && document.getElementById('key').type==='text'");
            js(web.get(),"document.getElementById('model').focus()");
            awaitJs(web.get(),"document.getElementById('key').type==='password'");
            // A validation failure keeps the pasted value for correction.
            js(web.get(),"document.getElementById('base').value='http://invalid';document.getElementById('form').requestSubmit()");
            awaitJs(web.get(),"document.getElementById('base').getAttribute('aria-invalid')==='true'");
            assertEquals("\"fixture-native-paste-579\"",js(web.get(),"document.getElementById('key').value"));
            js(web.get(),"document.getElementById('base').value='https://example.invalid/v1';document.getElementById('model').value='deepseek-v4.1-flash';document.getElementById('form').requestSubmit()");
            awaitJs(web.get(),"document.getElementById('form').hidden && document.getElementById('key').value===''");
            assertEquals("fixture-native-paste-579",DanmakuAiSetting.key());
            assertTrue(DanmakuAiSetting.enabled());
            assertFalse(prefs.getString("credential","").contains("fixture-native-paste-579"));
        } finally {
            pairing.close();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(web.get()!=null)web.get().destroy();});
        }
    }
    private void tapKey(android.webkit.WebView web) throws Exception {
        tapField(web, "key");
    }
    private void tapField(android.webkit.WebView web, String id) throws Exception {
        js(web,"document.getElementById('" + id + "').scrollIntoView({block:'center'})");
        android.os.SystemClock.sleep(200);
        var point=JsonParser.parseString(js(web,"(()=>{const r=document.getElementById('" + id + "').getBoundingClientRect();return [(r.left+r.width/2)*devicePixelRatio,(r.top+r.height/2)*devicePixelRatio]})()")).getAsJsonArray();
        int[] location=new int[2];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web.getLocationOnScreen(location));
        float x=location[0]+point.get(0).getAsFloat(),y=location[1]+point.get(1).getAsFloat();
        long now=android.os.SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){
            var event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,x,y,0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle();android.os.SystemClock.sleep(60);
        }
    }
    private String js(android.webkit.WebView web,String script) throws Exception {
        var done=new java.util.concurrent.CountDownLatch(1);var value=new AtomicReference<String>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web.evaluateJavascript(script,result->{value.set(result);done.countDown();}));
        assertTrue("WebView script completed",done.await(10,java.util.concurrent.TimeUnit.SECONDS));return value.get();
    }
    private void awaitJs(android.webkit.WebView web,String script) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+15000;
        while(!"true".equals(js(web,script))){if(android.os.SystemClock.elapsedRealtime()>=deadline)fail("WebView condition: "+script+"; state="+js(web,"JSON.stringify({active:document.activeElement.id,focused:document.hasFocus(),type:document.getElementById('key')?.type,pasteMode:typeof pasteMode==='undefined'?null:pasteMode})"));android.os.SystemClock.sleep(100);}
    }
    private String decode(androidx.fragment.app.DialogFragment dialog) {
        try {Bitmap bitmap=((BitmapDrawable)((android.widget.ImageView)dialog.requireDialog().findViewById(R.id.code)).getDrawable()).getBitmap();int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
            String url=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(),bitmap.getHeight(),pixels)))).getText();assertTrue(url.contains("/ai-setup#"));return url;
        }catch(Exception e){throw new AssertionError(e);}
    }
}
