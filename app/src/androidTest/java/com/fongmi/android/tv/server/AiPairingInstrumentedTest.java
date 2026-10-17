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
        String address=Server.get().getAddress(true);
        Request.Builder request=new Request.Builder().url(address+"/ai-setup/"+path).header("X-Pairing-Token",token);
        if(body!=null)request.header("Origin",origin==null?address:origin).post(RequestBody.create(body,MediaType.get("application/json")));
        return http.newCall(request.build()).execute();
    }
    private String payload(String base, String key) { var o=new JsonObject();o.addProperty("baseUrl",base);o.addProperty("model","fixture-model");o.addProperty("key",key);o.addProperty("enabled",true);return o.toString(); }
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
        try(ActivityScenario<HomeActivity> scenario=ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(a->{var dialog=new DanmakuAiDialog();dialog.showNow(a.getSupportFragmentManager(),"ai-pairing-test");dialog.requireDialog().findViewById(R.id.scan).performClick();dialog.getChildFragmentManager().executePendingTransactions();
                var qr=(androidx.fragment.app.DialogFragment)dialog.getChildFragmentManager().findFragmentByTag("ai_pairing");token.set(decode(qr));oldToken.set(token.get());
                ((androidx.appcompat.app.AlertDialog)qr.requireDialog()).getButton(-1).performClick();token.set(decode(qr));assertNotEquals(oldToken.get(),token.get());});
            try(var r=request(oldToken.get(),"config",null,null)){assertEquals(410,r.code());}
            android.os.SystemClock.sleep(400);
            Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            try(var out=new java.io.FileOutputStream(new File(App.get().getExternalFilesDir(null),"577-ai-qr.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            if("true".equals(InstrumentationRegistry.getArguments().getString("capturePairing"))) {
                Files.writeString(new File(App.get().getExternalFilesDir(null),"577-pairing-token.txt").toPath(),token.get());
                long deadline=android.os.SystemClock.elapsedRealtime()+300000;
                while(!DanmakuAiSetting.hasKey()&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(200);
                assertTrue("browser submitted settings",DanmakuAiSetting.hasKey());
            } else try(var r=request(token.get(),"save",payload("https://example.invalid/v1","fixture-secret"),null)){assertEquals(200,r.code());}
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
            String response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(response, response.startsWith("HTTP/1.1 200"));
            assertEquals("fixture-slow-secret", DanmakuAiSetting.key());
        } finally { pairing.close(); }
    }
    private String decode(androidx.fragment.app.DialogFragment dialog) {
        try {Bitmap bitmap=((BitmapDrawable)((android.widget.ImageView)dialog.requireDialog().findViewById(R.id.code)).getDrawable()).getBitmap();int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
            String url=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(),bitmap.getHeight(),pixels)))).getText();assertTrue(url.contains("/ai-setup#"));return url.substring(url.indexOf('#')+1);
        }catch(Exception e){throw new AssertionError(e);}
    }
}
