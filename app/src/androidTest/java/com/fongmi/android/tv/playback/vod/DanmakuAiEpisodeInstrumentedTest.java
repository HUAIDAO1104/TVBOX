package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import com.fongmi.android.tv.setting.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DanmakuAiEpisodeInstrumentedTest {
    private MockWebServer server;
    private android.content.SharedPreferences preferences;
    private Map<String,?> previous;
    private String oldApi, title;
    private boolean oldAuto, oldLoad;
    private PlayerManager player;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final CountDownLatch modelStarted = new CountDownLatch(1);
    private final AtomicReference<String> selected = new AtomicReference<>();
    private final AtomicReference<DanmakuStatus> status = new AtomicReference<>();
    private volatile boolean slow, invalid, hang;

    @Before public void setup() throws Exception {
        preferences=App.get().getSharedPreferences("danmaku_ai_private",Context.MODE_PRIVATE);
        previous=preferences.getAll(); oldApi=DanmakuSetting.getApiUrl(); oldAuto=DanmakuSetting.isAuto(); oldLoad=DanmakuSetting.isLoad();
        title="前置识别验收"+System.nanoTime();
        server=new MockWebServer(); server.start();
        server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) {
            if(request.getPath().contains("/chat/completions")) {
                events.add("model"); modelCalls.incrementAndGet();modelStarted.countDown();
                JsonObject body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
                JsonObject input=JsonParser.parseString(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                if(!"episode".equals(input.get("task").getAsString()))return new MockResponse().setResponseCode(400);
                String label=input.get("episode").getAsString(); int number=invalid?73:label.contains(" 23 ")?23:22;
                JsonObject answer=new JsonObject();answer.addProperty("episode","第"+number+"集");answer.addProperty("evidence",String.valueOf(number));
                JsonObject message=new JsonObject();message.addProperty("content",answer.toString());
                JsonObject choice=new JsonObject();choice.add("message",message);JsonArray choices=new JsonArray();choices.add(choice);
                JsonObject reply=new JsonObject();reply.add("choices",choices);
                if(hang)return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                return new MockResponse().setBody(reply.toString()).setBodyDelay(slow?2:0,TimeUnit.SECONDS);
            }
            if(request.getPath().startsWith("/search")) {
                events.add("search"); return new MockResponse().setBody(App.gson().toJson(List.of(row(1),row(22),row(23),row(73))));
            }
            events.add("document:"+request.getPath());
            return new MockResponse().setBody("<i><d p=\"0,1,25,16777215,0,0,0,1\">当前集专属弹幕</d></i>");
        }});
        DanmakuAiSetting.save("https://example.invalid/v1",DanmakuAiModels.DEFAULT,"test-only",true);
        preferences.edit().putString("base_url",server.url("/v1").toString()).commit();
        DanmakuSetting.putApiUrl(server.url("/search?name={name}&episode={episode}").toString().replace("%7B","{").replace("%7D","}"));
        DanmakuSetting.putAuto(true);DanmakuSetting.putLoad(true);
        main(() -> player=new PlayerManager((PlayerManager.Callback)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new java.lang.Class[]{PlayerManager.Callback.class},(proxy,method,args)->{
                    if(method.getName().equals("onDanmakuSourceChanged")&&args[0]!=null)selected.set(args[0].toString());
                    if(method.getName().equals("onDanmakuStatusChanged"))status.set((DanmakuStatus)args[0]);
                    return null;
                })));
    }
    @After public void cleanup() throws Exception {
        main(() -> { if(player!=null){VodPlaybackMedia.invalidate(player);player.release();} });
        DanmakuSetting.putApiUrl(oldApi);DanmakuSetting.putAuto(oldAuto);DanmakuSetting.putLoad(oldLoad);
        var edit=preferences.edit().clear();
        for(var entry:previous.entrySet()) {
            if(entry.getValue() instanceof String v)edit.putString(entry.getKey(),v);
            if(entry.getValue() instanceof Boolean v)edit.putBoolean(entry.getKey(),v);
        }
        edit.commit();server.shutdown();
    }
    private Danmaku row(int episode) {
        Danmaku item=Danmaku.from(server.url("/api/v2/comment/episode-"+episode+"?format=xml").toString());
        item.setName(title+"(2020)【动漫】from fixture - 第"+episode+"集");return item;
    }
    private String label(int number) {return "序号01【4K】"+title+" "+number+" 1-73集";}
    private void play(String label) {
        selected.set(null);status.set(null);
        main(() -> {
            try {
                var spec=PlayerManager.class.getDeclaredField("spec");spec.setAccessible(true);
                spec.set(player,com.fongmi.android.tv.player.media.PlaySpec.from("fixture","https://example.invalid/"+label.hashCode()+".mp4",null,
                        new androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(label).build()));
            }catch(Exception e){throw new AssertionError(e);}
            History history=new History();history.setKey(title);history.setVodName(title);
            long start=SystemClock.elapsedRealtime();
            VodPlaybackMedia.searchDanmaku(new Result(),history,Episode.create(label,"fixture-"+label),1,"2020","动漫",player);
            assertTrue("AI work blocked main thread",SystemClock.elapsedRealtime()-start<150);
        });
    }
    private void loaded(int episode) {
        await(() -> selected.get()!=null,12000);
        assertTrue(selected.get(),selected.get().contains("/episode-"+episode+"?"));
    }
    @Test public void complexNameUsesAiBeforeSearchingAndNeverRestoresTheFirstEpisode() {
        var wrong=row(1);
        DanmakuManualMatchStore.get().rememberAutomatic(new DanmakuMatchContext(title,"2020","动漫",label(22),"1"),title,wrong,List.of(wrong));
        play(label(22));loaded(22);
        assertEquals("model",events.get(0));assertEquals(1,modelCalls.get());
        assertEquals("第22集",VodPlaybackMedia.contextOf(player).getResolvedEpisode());
        assertFalse(events.toString(),events.stream().anyMatch(e->e.contains("/episode-1?")));
        var saved=DanmakuManualMatchStore.get().find(VodPlaybackMedia.contextOf(player));
        assertNotNull(saved.episode("22"));assertFalse(saved.episode("1").getUrl().equals(saved.episode("22").getUrl()));
    }
    @Test public void simpleNameSkipsAiEvenWhenConfigured() {
        play("第23集");loaded(23);assertEquals(0,modelCalls.get());
    }
    @Test public void repeatedFilenameUsesAiCacheAndNextEpisodeGetsItsOwnIdentity() {
        play(label(22));loaded(22);play(label(23));loaded(23);play(label(22));loaded(22);
        assertEquals(2,modelCalls.get());
    }
    @Test public void explicitManualCorrectionOverridesAiAndSurvivesSerialization() {
        play(label(22));loaded(22);selected.set(null);
        main(() -> VodPlaybackMedia.applyManualMatch(player,title,row(23),List.of(row(22),row(23)),item->{},()->fail("manual correction")));
        loaded(23);
        var store=DanmakuManualMatchStore.get();var saved=store.find(VodPlaybackMedia.contextOf(player));
        String json=DanmakuManualMatchStore.encode(Map.of("work",saved));
        assertEquals("第23集",DanmakuManualMatchStore.decode(json).get("work").correctedEpisode(label(22)));
        play(label(22));loaded(23);assertEquals(1,modelCalls.get());
    }
    @Test public void invalidAiCannotChooseTheCollectionTailOrInventFirstEpisode() {
        invalid=true;play(label(22));
        await(() -> status.get()!=null && status.get().stage()==DanmakuStatus.Stage.FAILED,10000);
        assertNull(selected.get());assertEquals(List.of("model"),events);
        play("【4K】"+title+" 22 1-73集");loaded(22);
        assertFalse(events.toString(),events.stream().anyMatch(e->e.contains("/episode-73?")));
    }
    @Test public void lateAiResponseCannotOverwriteAnEpisodeChangeOrManualSelection() throws Exception {
        slow=true;play(label(22));assertTrue(modelStarted.await(3,TimeUnit.SECONDS));
        play("第23集");loaded(23);SystemClock.sleep(2400);
        assertTrue(selected.get(),selected.get().contains("/episode-23?"));
        assertFalse(events.toString(),events.stream().anyMatch(e->e.contains("/episode-22?")));
        play(label(22));main(() -> VodPlaybackMedia.applyExternalDanmaku(player,row(73)));
        SystemClock.sleep(2400);assertTrue(selected.get(),selected.get().contains("/episode-73?"));
    }
    @Test public void timeoutFallsBackOnlyToEvidencedEpisodeWithinBoundedTime() {
        // Isolate the model deadline from unrelated public-provider search deadlines.
        DanmakuManualMatchStore.get().remember(new DanmakuMatchContext(title,"2020","动漫","第22集"),title,row(22),List.of(row(22)));
        hang=true;long start=SystemClock.elapsedRealtime();play("【4K】"+title+" 22 1-73集");loaded(22);
        assertTrue(SystemClock.elapsedRealtime()-start<12000);
        assertEquals(1,modelCalls.get());
    }
    @Test public void previousDefaultMigratesWithoutChangingOtherModelsOrProviders() {
        DanmakuAiSetting.clear();
        DanmakuAiSetting.save(DanmakuAiSetting.DEFAULT_BASE_URL,"deepseek-v4.1-flash","test-only",true);
        assertEquals("deepseek-v4.1-flash-1017",DanmakuAiSetting.model());
        DanmakuAiSetting.save(DanmakuAiSetting.DEFAULT_BASE_URL,"qwen3.5-flash","",true);
        assertEquals("qwen3.5-flash",DanmakuAiSetting.model());
        DanmakuAiSetting.save("https://example.invalid/v1","deepseek-v4.1-flash","test-only",true);
        assertEquals("deepseek-v4.1-flash",DanmakuAiSetting.model());
    }
    private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private static void await(java.util.function.BooleanSupplier test,long millis) {
        long end=SystemClock.elapsedRealtime()+millis;
        while(SystemClock.elapsedRealtime()<end){if(test.getAsBoolean())return;SystemClock.sleep(50);}fail("Timeout");
    }
}
