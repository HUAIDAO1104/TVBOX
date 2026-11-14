package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.*;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fongmi.android.tv.api.BilibiliDanmaku;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.playback.vod.*;
import com.fongmi.android.tv.setting.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual playlist/controller -> service -> Exo -> parser. Deterministic episode bodies expose
 * cross-episode reuse even when every downloaded document parses successfully. */
@RunWith(AndroidJUnit4.class)
public class DanmakuEpisodeTransitionInstrumentedTest {
    private static final String TITLE = "连续匹配验收动画";

    @Test public void thousandEpisodeCachedSearchNeverRanksOnUiThread() throws Exception {
        List<Danmaku> rows = new ArrayList<>();
        for (int i=1;i<=1000;i++) {
            Danmaku row=Danmaku.from("bilibili-danmaku://video/"+(800000+i)+"/"+(900000+i)+"/1200");
            row.setName("缓存计算验收(2020)【动漫】from bilibili - 【bilibili】 第"+i+"集"); rows.add(row);
        }
        cacheCatalogue("598",rows);
        CountDownLatch ready=new CountDownLatch(1); AtomicBoolean onMain=new AtomicBoolean(); AtomicLong callMs=new AtomicLong();
        AtomicReference<com.fongmi.android.tv.api.NativeDanmaku.Job> job=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long start=SystemClock.elapsedRealtime();
            job.set(BilibiliDanmaku.search("缓存计算验收","缓存计算验收","2020","动漫","第500集",new com.fongmi.android.tv.api.IqiyiDanmaku.Callback<>() {
                public void onSuccess(List<Danmaku> values) { onMain.set(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());ready.countDown(); }
                public void onFailure(IOException e) { ready.countDown(); }
            }));
            callMs.set(SystemClock.elapsedRealtime()-start);
        });
        try {
            assertTrue(ready.await(8,TimeUnit.SECONDS));
            android.util.Log.i("Danmaku598QA","1000-episode cached search mainMs="+callMs.get()+" callbackOnMain="+onMain.get());
            assertFalse("Cached ranking blocks search and episode controls",onMain.get());
            assertTrue("Search entry blocked UI for "+callMs.get()+"ms",callMs.get()<150);
        } finally { job.get().cancel(); clearCatalogue("598"); }
    }

    @Test public void confirmedWrong73IsRepairedAcrossActualPlaylistAndReentry() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        boolean auto = DanmakuSetting.isAuto(), show = DanmakuSetting.isShow();
        int engine = PlayerSetting.getEngine();
        File video = new File(context.getFilesDir(), "episode-transition-" + System.nanoTime() + ".mp4");
        try (var in = instrumentation.getContext().getAssets().open("playback-timeline.mp4")) {
            Files.write(video.toPath(), com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(in, 8_000_000));
        }
        List<Danmaku> rows = seedCatalogue();
        for (Danmaku row : rows) seedDocument(row);
        Danmaku wrong = Danmaku.from(episode(rows, 73).getUrl());
        wrong.setName(episode(rows, 17).getName()); wrong.setSourceKey(BilibiliDanmaku.SOURCE);
        DanmakuManualMatchStore.get().remember(new DanmakuMatchContext(TITLE,"2020","动漫","第17集"), TITLE, wrong, List.of(wrong));
        DanmakuSetting.putAuto(false); DanmakuSetting.putShow(true); PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
        try (ActivityScenario<VideoActivity> scenario = ActivityScenario.launch(new Intent(context, VideoActivity.class)
                .putExtra("key","push_agent").putExtra("id",Uri.fromFile(video).toString()).putExtra("name",TITLE))) {
            await(scenario, a -> a.service() != null && a.player().isPlaying(), "initial playback");
            main(scenario, a -> {
                DanmakuSetting.putAuto(true);
                controller(a).onDetailResult(detail(video));
            });
            assertEpisode(scenario, rows, 17);
            // Explicit correction must survive next/previous and re-entry. Only the clicked row
            // is supplied, as happens when a user chooses before the directory finishes loading.
            CountDownLatch selected = new CountDownLatch(1);
            var saved = DanmakuManualMatchStore.class.getDeclaredField("entries"); saved.setAccessible(true);
            ((Map<?,?>)saved.get(DanmakuManualMatchStore.get())).remove(TITLE);
            main(scenario, a -> VodPlaybackMedia.applyManualMatch(a.player(),TITLE,episode(rows,17),List.of(episode(rows,17)),
                    item -> selected.countDown(), () -> fail("manual correction")));
            assertTrue(selected.await(5,TimeUnit.SECONDS));
            for (int number : new int[]{18,19,20,22,23,73}) {
                main(scenario, a -> {
                    long start = SystemClock.elapsedRealtime(); controller(a).nextEpisode(false);
                    long elapsed = SystemClock.elapsedRealtime()-start;
                    android.util.Log.i("Danmaku598QA","playlist next mainMs="+elapsed);
                    assertTrue("Switching must not rank/save a whole directory on UI: "+elapsed, elapsed<200);
                });
                assertEpisode(scenario,rows,number);
            }
            main(scenario, a -> controller(a).prevEpisode(false)); assertEpisode(scenario,rows,23);
            scenario.recreate();
            await(scenario,a -> a.service()!=null && a.player().isPlaying()
                    && a.player().getMetadata()!=null && "01".contentEquals(a.player().getMetadata().artist),"recreated local fixture");
            main(scenario,a -> {
                Result reopened=detail(video); controller(a).onDetailResult(reopened);
                controller(a).selectEpisode(reopened.getVod().getFlags().get(0).getEpisodes().get(5));
            });
            assertEpisode(scenario,rows,23);
            main(scenario,a -> controller(a).nextEpisode(false)); assertEpisode(scenario,rows,73);
        } finally {
            DanmakuSetting.putAuto(auto); DanmakuSetting.putShow(show); PlayerSetting.putEngine(engine); video.delete();
            clearCatalogue("28747");
            for (Danmaku row : rows) com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.invalidate(row.getUri());
        }
    }

    private static String label(int number) { return number+" 4k 连续匹配验收动画 1-73集"; }

    private static Result detail(File video) {
        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id",Uri.fromFile(video).toString()); vod.addProperty("vod_name",TITLE);
        vod.addProperty("vod_year","2020"); vod.addProperty("type_name","动漫"); vod.addProperty("vod_play_from","本地验收");
        List<String> playlist = new ArrayList<>();
        for (int n : new int[]{17,18,19,20,22,23,73}) playlist.add(label(n)+"$"+Uri.fromFile(video));
        vod.addProperty("vod_play_url",String.join("#",playlist));
        JsonArray list = new JsonArray(); list.add(vod); JsonObject result = new JsonObject(); result.add("list",list);
        Result parsed = Result.fromJson(result.toString()); parsed.getVod().setFlags(); return parsed;
    }

    private static VodPlaybackController controller(VideoActivity activity) {
        try { var f=VideoActivity.class.getDeclaredField("mVod"); f.setAccessible(true); return (VodPlaybackController)f.get(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static Danmaku episode(List<Danmaku> rows,int number) {
        return rows.stream().filter(row -> DanmakuMatch.isEpisodeCompatible("第"+number+"集",row.getName())).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static List<Danmaku> seedCatalogue() throws Exception {
        String json;
        try(var in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("danmaku/identity-episodes.json")) {
            json=new String(com.fongmi.android.tv.utils.InputStreamCompat.readUpTo(in,20000),StandardCharsets.UTF_8);
        }
        var parse=BilibiliDanmaku.class.getDeclaredMethod("catalogue",String.class,String.class); parse.setAccessible(true);
        List<Danmaku> rows=(List<Danmaku>)parse.invoke(null,json,"28747");
        cacheCatalogue("28747",rows);
        return rows;
    }
    @SuppressWarnings("unchecked") private static void cacheCatalogue(String id,List<Danmaku> rows) throws Exception {
        var field=BilibiliDanmaku.class.getDeclaredField("CATALOGUES");field.setAccessible(true);
        Map<String,Object> cached=(Map<String,Object>)field.get(null);
        Class<?> type=Class.forName(BilibiliDanmaku.class.getName()+"$CachedCatalogue");
        var constructor=type.getDeclaredConstructor(long.class,List.class);constructor.setAccessible(true);
        synchronized(cached) { cached.put(id,constructor.newInstance(System.nanoTime(),rows)); }
    }
    private static void clearCatalogue(String id) throws Exception {
        var field=BilibiliDanmaku.class.getDeclaredField("CATALOGUES");field.setAccessible(true);
        Map<?,?> cached=(Map<?,?>)field.get(null);
        synchronized(cached) { cached.remove(id); }
    }
    private static void seedDocument(Danmaku row) throws Exception {
        var cache=com.fongmi.android.tv.player.danmaku.DanmakuDocumentCache.class;
        var f=cache.getDeclaredMethod("fileFor",Uri.class);f.setAccessible(true);
        File file=(File)f.invoke(null,row.getUri());
        StringBuilder body=new StringBuilder("<i>");
        for(int i=0;i<60;i++) body.append("<d p=\"").append(i).append(",1,25,16777215,0,0,0,1\">第")
                .append(DanmakuMatch.episodeNumber(row.getName())).append("集专属内容</d>");
        Files.write(file.toPath(),body.append("</i>").toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void assertEpisode(ActivityScenario<VideoActivity> scenario,List<Danmaku> rows,int number) {
        String url=episode(rows,number).getUrl(), text="第"+number+"集专属内容";
        await(scenario,a -> a.player().getMetadata()!=null && label(number).contentEquals(a.player().getMetadata().artist)
                && url.equals(a.player().getSelectedDanmaku().getUrl()) && parsedText(a,text),"episode "+number);
        android.util.Log.i("Danmaku598QA","verified actual playlist, selected URL and parsed body episode="+number);
    }
    private static boolean parsedText(VideoActivity a,String text) {
        try {
            Object controller=a.getPlayerView().getDanmakuController();
            var f=controller.getClass().getDeclaredField("sortedItems");f.setAccessible(true);
            var rows=(androidx.media3.ui.danmaku.Danmaku[])f.get(controller);
            if(rows==null || rows.length==0)return false;
            return Arrays.stream(rows).allMatch(row -> text.contentEquals(row.text));
        } catch(Exception e) { throw new AssertionError(e); }
    }
    private interface Check { boolean test(VideoActivity activity); }
    private static void main(ActivityScenario<VideoActivity> s,ActivityScenario.ActivityAction<VideoActivity> action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> s.onActivity(action));
    }
    private static void await(ActivityScenario<VideoActivity> s,Check check,String label) {
        long deadline=SystemClock.elapsedRealtime()+15000; AtomicBoolean done=new AtomicBoolean();
        while(SystemClock.elapsedRealtime()<deadline) {
            main(s,a -> done.set(check.test(a))); if(done.get())return; SystemClock.sleep(50);
        }
        main(s,a -> android.util.Log.e("Danmaku598QA","timeout "+label+" metadata="+a.player().getMetadata().title+" / "+a.player().getMetadata().artist+" selected="+a.player().getSelectedDanmaku()));
        fail(label);
    }
}
