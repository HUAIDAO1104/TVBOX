package com.fongmi.android.tv.offline;

import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.MediaItem;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.ui.activity.*;
import com.fongmi.android.tv.ui.dialog.*;
import com.fongmi.android.tv.databinding.DialogControlBinding;
import com.google.gson.Gson;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Actual mobile screens, local fixture media and persistent batch tasks; no external video service. */
@RunWith(AndroidJUnit4.class)
public class MobileExperienceTest {
    private Context context;
    private MockWebServer server;
    private byte[] video;
    private OfflineStore store;
    private java.lang.reflect.Field sitesField;
    private Object oldSites;
    private ActivityScenario<?> screen;
    private boolean previousDanmaku;
    private float previousSpeed;
    private androidx.core.os.LocaleListCompat previousLocales;
    @Before public void setup() throws Exception {
        context=ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("mobile-watchlist-v1",0).edit().clear().commit();
        java.lang.reflect.Field watchInstance=com.fongmi.android.tv.watch.WatchlistStore.class.getDeclaredField("instance");watchInstance.setAccessible(true);watchInstance.set(null,null);
        previousDanmaku=com.fongmi.android.tv.setting.DanmakuSetting.isLoad();
        previousSpeed=com.fongmi.android.tv.setting.PlayerSetting.getSpeed();
        previousLocales=androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
        main(()->{androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("zh-CN"));com.fongmi.android.tv.setting.DanmakuSetting.putLoad(false);com.fongmi.android.tv.setting.PlayerSetting.putSpeed(1f);});
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("offline/clip.mp4")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);video=out.toByteArray();
        }
        server=new MockWebServer();server.setDispatcher(new Dispatcher(){
            public MockResponse dispatch(RecordedRequest req){
                if(req.getPath().startsWith("/api")){
                    StringBuilder episodes=new StringBuilder();for(int i=1;i<=24;i++){if(i>1)episodes.append('#');episodes.append("第 ").append(i).append(" 集$").append(server.url("/episode"+i+".mp4"));}
                    Map<String,Object> vod=new LinkedHashMap<>();vod.put("vod_id","mobile-ui-fixture");vod.put("vod_name","星河旅途");vod.put("vod_remarks","24 集全");vod.put("vod_content","在旅途中发现生活的另一种可能。此影片用于验证手机版界面与离线下载。");vod.put("vod_play_from","高清线路");vod.put("vod_play_url",episodes.toString());
                    return new MockResponse().setHeader("Content-Type","application/json").setBody(new Gson().toJson(Collections.singletonMap("list",java.util.List.of(vod))));
                }
                int start=0,end=video.length-1;String range=req.getHeader("Range");MockResponse response=new MockResponse().setHeader("Content-Type","video/mp4").setHeader("Accept-Ranges","bytes");
                if(range!=null){String[] parts=range.substring(6).split("-",-1);start=Integer.parseInt(parts[0]);if(parts.length>1&&!parts[1].isEmpty())end=Math.min(end,Integer.parseInt(parts[1]));if(start>=video.length)return response.setResponseCode(416);response.setResponseCode(206).setHeader("Content-Range","bytes "+start+"-"+end+"/"+video.length);}
                return response.setBody(new Buffer().write(video,start,end-start+1));
            }
        });server.start();
        sitesField=VodConfig.class.getDeclaredField("sites");sitesField.setAccessible(true);oldSites=sitesField.get(VodConfig.get());
        Site site=new Gson().fromJson("{\"key\":\"mobile-ui-fixture\",\"name\":\"本地测试片库\",\"type\":1,\"api\":\""+server.url("/api")+"\"}",Site.class);
        sitesField.set(VodConfig.get(),new ArrayList<>(java.util.List.of(site)));
        main(()->{store=OfflineStore.get(context);store.setWifiOnly(false);store.manager.removeAllDownloads();for(OfflineBatchQueue.Job j:OfflineBatchQueue.get(context).jobs())OfflineBatchQueue.get(context).remove(j.id);});
        waitFor(()->onMain(()->store.downloads().isEmpty()),15000);
    }
    @After public void cleanup() throws Exception {
        if(screen!=null)screen.close();
        main(()->{androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(previousLocales);com.fongmi.android.tv.setting.DanmakuSetting.putLoad(previousDanmaku);com.fongmi.android.tv.setting.PlayerSetting.putSpeed(previousSpeed);});
        main(()->{context.stopService(new Intent(context,OfflineBatchService.class));for(OfflineBatchQueue.Job j:OfflineBatchQueue.get(context).jobs())OfflineBatchQueue.get(context).remove(j.id);store.manager.removeAllDownloads();});
        if(sitesField!=null)sitesField.set(VodConfig.get(),oldSites);
        if(server!=null)server.shutdown();
    }
    private static void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private static <T>T onMain(Callable<T> action)throws Exception{AtomicReference<T> ref=new AtomicReference<>();AtomicReference<Exception> err=new AtomicReference<>();main(()->{try{ref.set(action.call());}catch(Exception e){err.set(e);}});if(err.get()!=null)throw err.get();return ref.get();}
    private static void waitFor(Callable<Boolean> condition,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.call())return;Thread.sleep(100);}fail("Timed out waiting for mobile screen state");}
    private void screenshot(String name)throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(400);
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File dir=new File(context.getExternalFilesDir(null),"mobile-experience");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    private String download(int episode)throws Exception{
        OfflineMetadata meta=new OfflineMetadata();meta.title="星河旅途";meta.episode="第 "+episode+" 集";
        String url=server.url("/episode"+episode+".mp4").toString();AtomicBoolean done=new AtomicBoolean();AtomicReference<String> error=new AtomicReference<>();
        main(()->store.enqueue(MediaItem.fromUri(url),meta,e->{error.set(e);done.set(true);}));waitFor(done::get,55000);assertNull(error.get());String id=OfflineMetadata.id(url,meta.headers);
        waitFor(()->onMain(()->store.download(id)!=null&&store.download(id).state==androidx.media3.exoplayer.offline.Download.STATE_COMPLETED),30000);return id;
    }
    @Test public void downloadsSupportVisibleSelectionAndBatchActions()throws Exception{
        download(1);download(2);
        main(()->{
            OfflineBatchQueue.Job waiting=new OfflineBatchQueue.Job();waiting.id=OfflineBatchQueue.identity("fixture","show","line","3");waiting.meta.title="星河旅途";waiting.meta.episode="第 3 集";waiting.sourceUrl=server.url("/episode3.mp4").toString();waiting.site="mobile-ui-fixture";waiting.flag="高清线路";waiting.state=OfflineBatchQueue.PAUSED;
            OfflineBatchQueue.Job failed=waiting.copy();failed.id=OfflineBatchQueue.identity("fixture","show","line","4");failed.meta.episode="第 4 集";failed.state=OfflineBatchQueue.FAILED;failed.error="此线路暂不可用 · 可重试";
            OfflineBatchQueue.get(context).add(java.util.List.of(waiting,failed),Collections.emptySet());
        });
        ActivityScenario<OfflineDownloadsActivity> activity=ActivityScenario.launch(OfflineDownloadsActivity.class);screen=activity;
        screenshot("downloads");
        activity.onActivity(a->{a.findViewById(R.id.manage).performClick();a.findViewById(R.id.selectAll).performClick();assertTrue(a.findViewById(R.id.deleteSelected).isEnabled());assertTrue(a.findViewById(R.id.resumeSelected).isEnabled());});
        screenshot("downloads-selection");
        activity.onActivity(a->{a.findViewById(R.id.completed).performClick();assertFalse(a.findViewById(R.id.deleteSelected).isEnabled());a.findViewById(R.id.selectAll).performClick();a.findViewById(R.id.deleteSelected).performClick();});
        screenshot("downloads-delete-confirmation");
    }
    @Test public void portraitControlsBatchPickerAndPlaybackSettingsAreReachable()throws Exception{
        Intent intent=new Intent(context,VideoActivity.class).putExtra("key","mobile-ui-fixture").putExtra("id","mobile-ui-fixture").putExtra("name","星河旅途");
        ActivityScenario<VideoActivity> activity=ActivityScenario.launch(intent);screen=activity;AtomicReference<VideoActivity> ref=new AtomicReference<>();activity.onActivity(ref::set);
        waitFor(()->onMain(()->ref.get().playbackPlayer()!=null&&!ref.get().playbackPlayer().isEmpty()&&ref.get().offlineFlag()!=null),30000);
        activity.onActivity(a->{a.playbackPlayer().pause();if(a.playbackBinding().control.getRoot().getVisibility()!=View.VISIBLE)a.onSingleTap();assertEquals(View.VISIBLE,a.playbackBinding().control.cast.getVisibility());assertEquals(View.VISIBLE,a.playbackBinding().control.setting.getVisibility());assertEquals(View.VISIBLE,a.playbackBinding().control.fullscreen.getVisibility());});
        screenshot("player-portrait");
        activity.onActivity(a->a.playbackBinding().control.setting.performClick());
        waitFor(()->onMain(()->ref.get().getSupportFragmentManager().getFragments().stream().anyMatch(f->f instanceof ControlDialog&&f.getView()!=null)),5000);
        screenshot("playback-settings");
        activity.onActivity(a->{for(androidx.fragment.app.Fragment f:a.getSupportFragmentManager().getFragments())if(f instanceof ControlDialog)((ControlDialog)f).dismiss();});
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        activity.onActivity(a->a.playbackBinding().control.download.performClick());
        waitFor(()->onMain(()->ref.get().getSupportFragmentManager().findFragmentByTag("offline_batch")!=null),5000);
        screenshot("batch-picker");
        activity.onActivity(a->{androidx.fragment.app.Fragment f=a.getSupportFragmentManager().findFragmentByTag("offline_batch");assertNotNull(f);f.requireView().findViewById(R.id.next).performClick();assertTrue(f.requireView().findViewById(R.id.download).isEnabled());((OfflineBatchDialog)f).dismiss();});
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        activity.onActivity(a->a.playbackBinding().control.fullscreen.performClick());Thread.sleep(1200);
        android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Got it"))node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        activity.onActivity(a->{if(a.playbackBinding().control.getRoot().getVisibility()!=View.VISIBLE)a.onSingleTap();});screenshot("player-landscape");
    }
    @Test public void realScrubPreviewAndUnifiedCaptionPanelWork()throws Exception{
        Intent intent=new Intent(context,VideoActivity.class).putExtra("key","mobile-ui-fixture").putExtra("id","mobile-ui-fixture").putExtra("name","星河旅途");
        ActivityScenario<VideoActivity> activity=ActivityScenario.launch(intent);screen=activity;AtomicReference<VideoActivity> ref=new AtomicReference<>();activity.onActivity(ref::set);
        waitFor(()->onMain(()->ref.get().playbackPlayer()!=null&&ref.get().playbackPlayer().getDuration()>0&&ref.get().offlineFlag()!=null),30000);
        activity.onActivity(a->{a.playbackPlayer().pause();if(a.playbackBinding().control.getRoot().getVisibility()!=View.VISIBLE)a.onSingleTap();a.onSeeking(2000);});
        waitFor(()->onMain(()->ref.get().playbackBinding().control.previewImage.getDrawable()!=null),12000);screenshot("scrub-real-frame");
        activity.onActivity(a->{a.onSeekEnd(2000);assertEquals(View.GONE,a.playbackBinding().control.scrubPreview.getVisibility());PreciseSeekDialog.show(a,a.playbackPlayer().getPlayer());});
        screenshot("precise-seek");InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(300);
        activity.onActivity(PlaybackAccessDialog::show);
        waitFor(()->onMain(()->ref.get().getSupportFragmentManager().findFragmentByTag("playback-access")!=null&&ref.get().getSupportFragmentManager().findFragmentByTag("playback-access").getView()!=null),5000);
        activity.onActivity(a->{View panel=a.getSupportFragmentManager().findFragmentByTag("playback-access").requireView();assertTrue(((android.widget.TextView)panel.findViewById(R.id.subtitleStatus)).getText().toString().contains("未检测到"));assertTrue(panel.findViewById(R.id.subtitleChoose).isEnabled());});
        screenshot("subtitles-and-danmaku");
    }
    @Test public void watchlistPersistsWatchedEpisodesAndShowsNewEpisodes()throws Exception{
        var watch=com.fongmi.android.tv.watch.WatchlistStore.get(context);String id=com.fongmi.android.tv.watch.WatchlistStore.id(VodConfig.getCid(),"mobile-ui-fixture","watch-fixture");
        var vod=new com.fongmi.android.tv.bean.Vod();vod.setName("星河旅途");vod.setFlags(new ArrayList<>(List.of(com.fongmi.android.tv.bean.Flag.create("高清线路","第1集$https://example.invalid/1#第2集$https://example.invalid/2"))));
        main(()->{watch.observe(VodConfig.getCid(),"mobile-ui-fixture","watch-fixture","本地测试片库",vod);watch.follow(id,true);watch.watched(id,"第1集",true);
            vod.setFlags(new ArrayList<>(List.of(com.fongmi.android.tv.bean.Flag.create("高清线路","第1集$https://example.invalid/1#第2集$https://example.invalid/2#第3集$https://example.invalid/3"))));assertEquals(1,watch.refresh(id,vod));});
        ActivityScenario<WatchlistActivity> activity=ActivityScenario.launch(WatchlistActivity.class);screen=activity;
        waitFor(()->onMain(()->!com.fongmi.android.tv.watch.WatchlistChecker.running()),20000);
        screenshot("watchlist-updates");activity.recreate();
        activity.onActivity(a->{assertEquals(1,watch.find(id).watchedCount());assertTrue(watch.find(id).fresh.contains("第3集"));assertTrue(a.findViewById(R.id.reminders).isShown());});
        main(()->watch.follow(id,false));
    }
    @Test public void groupedDownloadsKeepSelectionAcrossCollapseAndDoNotSelectOtherFilm()throws Exception{
        download(1);download(2);
        main(()->{OfflineBatchQueue.Job other=new OfflineBatchQueue.Job();other.id=OfflineBatchQueue.identity("fixture","other","line","1");other.meta.title="海边日记";other.meta.episode="第1集";other.site="mobile-ui-fixture";other.flag="高清线路";other.sourceUrl="https://example.invalid/other";other.state=OfflineBatchQueue.PAUSED;OfflineBatchQueue.get(context).add(List.of(other),Set.of());});
        ActivityScenario<OfflineDownloadsActivity> activity=ActivityScenario.launch(OfflineDownloadsActivity.class);screen=activity;Thread.sleep(500);
        activity.onActivity(a->{androidx.recyclerview.widget.RecyclerView list=a.findViewById(R.id.list);assertEquals(2,list.getAdapter().getItemCount());list.findViewHolderForAdapterPosition(0).itemView.performClick();});
        Thread.sleep(200);screenshot("downloads-group-expanded");
        activity.onActivity(a->{androidx.recyclerview.widget.RecyclerView list=a.findViewById(R.id.list);assertEquals(4,list.getAdapter().getItemCount());list.findViewHolderForAdapterPosition(0).itemView.performLongClick();assertEquals("已选 2 项",((android.widget.TextView)a.findViewById(R.id.selectionCount)).getText().toString());assertFalse(a.findViewById(R.id.resumeSelected).isEnabled());});
        screenshot("downloads-group-selection");
    }
    @Test public void libraryAndAggregatedSearchRemainUsableWithLargeText()throws Exception{
        var keep=new com.fongmi.android.tv.bean.Keep();keep.setKey("mobile-ui-fixture"+com.fongmi.android.tv.db.AppDatabase.SYMBOL+"large-font-fixture");keep.setVodName("星河旅途：一场漫长的远行");keep.setSiteName("本地测试片库");keep.setCid(VodConfig.getCid());
        String searchScope=MobileSearchSources.scope(),previousSources=com.fongmi.android.tv.setting.Setting.getSearchSources(searchScope);
        float previous=android.provider.Settings.System.getFloat(context.getContentResolver(),android.provider.Settings.System.FONT_SCALE,1f);
        try{
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("settings put system font_scale 1.8").close();Thread.sleep(800);
            main(keep::save);ActivityScenario<KeepActivity> library=ActivityScenario.launch(KeepActivity.class);screen=library;Thread.sleep(500);screenshot("library-large-text");
            library.onActivity(a->{assertTrue(a.getResources().getConfiguration().fontScale>1.7);((android.widget.EditText)a.findViewById(R.id.search)).setText("不存在的记录");});Thread.sleep(300);
            library.onActivity(a->assertEquals(View.VISIBLE,a.findViewById(R.id.empty).getVisibility()));screenshot("library-empty-large-text");library.close();screen=null;
            // Two real search providers return the same fixture; the actual results route must group them.
            Site second=new Gson().fromJson("{\"key\":\"mobile-ui-fixture-2\",\"name\":\"备用测试来源\",\"type\":1,\"api\":\""+server.url("/api")+"\"}",Site.class);
            var sources=new ArrayList<Site>((List<Site>)sitesField.get(VodConfig.get()));sources.add(second);sitesField.set(VodConfig.get(),sources);
            com.fongmi.android.tv.setting.Setting.putSearchSources(searchScope,com.fongmi.android.tv.ui.search.SearchSourcePreference.serialize(new LinkedHashSet<>(com.fongmi.android.tv.ui.search.SearchSourcePreference.choices(sources))));
            ActivityScenario<SearchActivity> search=ActivityScenario.launch(new Intent(context,SearchActivity.class).putExtra("keyword","星河旅途"));screen=search;AtomicReference<SearchActivity> ref=new AtomicReference<>();search.onActivity(ref::set);
            waitFor(()->onMain(()->{for(androidx.fragment.app.Fragment f:ref.get().getSupportFragmentManager().getFragments())if(f instanceof com.fongmi.android.tv.ui.fragment.CollectFragment&&f.getView()!=null){androidx.recyclerview.widget.RecyclerView list=f.requireView().findViewById(R.id.recycler);return list!=null&&list.getAdapter()!=null&&list.getAdapter().getItemCount()==1&&((com.fongmi.android.tv.ui.adapter.SearchAdapter)list.getAdapter()).getItem(0).sources.size()==2;}return false;}),30000);
            screenshot("search-merged-large-text");
            search.onActivity(a->{for(androidx.fragment.app.Fragment f:a.getSupportFragmentManager().getFragments())if(f instanceof com.fongmi.android.tv.ui.fragment.CollectFragment&&f.getView()!=null){androidx.recyclerview.widget.RecyclerView list=f.requireView().findViewById(R.id.recycler);list.findViewHolderForAdapterPosition(0).itemView.performClick();}});screenshot("search-source-choice");
        }finally{com.fongmi.android.tv.setting.Setting.putSearchSources(searchScope,previousSources);if(screen!=null){screen.close();screen=null;}main(keep::delete);InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("settings put system font_scale "+previous).close();Thread.sleep(500);}
    }
    @Test public void offlinePreviewAndCaptionPanelUseOnlyLocalFiles()throws Exception{
        String id=download(1);server.shutdown();server=null;
        ActivityScenario<OfflinePlayerActivity> activity=ActivityScenario.launch(new Intent(context,OfflinePlayerActivity.class).putExtra("id",id));screen=activity;AtomicReference<OfflinePlayerActivity> ref=new AtomicReference<>();activity.onActivity(ref::set);
        waitFor(()->onMain(()->{androidx.media3.ui.PlayerView view=ref.get().findViewById(R.id.player);return view.getPlayer()!=null&&view.getPlayer().getDuration()>0;}),20000);
        activity.onActivity(a->{try{androidx.media3.ui.PlayerView view=a.findViewById(R.id.player);view.getPlayer().pause();view.showController();var method=OfflinePlayerActivity.class.getDeclaredMethod("preview",long.class);method.setAccessible(true);method.invoke(a,2000L);}catch(Exception e){throw new AssertionError(e);}});
        waitFor(()->onMain(()->((android.widget.ImageView)ref.get().findViewById(R.id.previewImage)).getDrawable()!=null),10000);screenshot("offline-cached-preview");
        activity.onActivity(a->a.findViewById(R.id.access).performClick());Thread.sleep(300);screenshot("offline-subtitles-and-danmaku");
    }
    @Test public void batchServiceResolvesAndDownloadsEpisodesOnAndroid() throws Exception {
        ActivityScenario<OfflineDownloadsActivity> activity=ActivityScenario.launch(OfflineDownloadsActivity.class);screen=activity;
        main(()->{
            List<OfflineBatchQueue.Job> jobs=new ArrayList<>();
            for(int i=7;i<=8;i++){
                OfflineBatchQueue.Job job=new OfflineBatchQueue.Job();job.site="mobile-ui-fixture";job.flag="高清线路";job.sourceUrl=server.url("/episode"+i+".mp4").toString();
                job.id=OfflineBatchQueue.identity(job.site,"mobile-ui-fixture",job.flag,job.sourceUrl);job.meta.title="星河旅途";job.meta.episode="第 "+i+" 集";jobs.add(job);
            }
            OfflineBatchQueue.get(context).add(jobs,Collections.emptySet());OfflineBatchService.kick(context);
        });
        waitFor(()->onMain(()->store.downloads().size()==2&&store.downloads().stream().allMatch(d->d.state==androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)),45000);
        assertTrue(onMain(()->OfflineBatchQueue.get(context).jobs().isEmpty()));
        screenshot("batch-downloads-completed");
    }
    @Test public void globalPlaybackSettingsHaveGroupedChoices()throws Exception{
        ActivityScenario<HomeActivity> activity=ActivityScenario.launch(HomeActivity.class);screen=activity;Thread.sleep(1200);
        activity.onActivity(a->{a.change(1);a.change(2);});Thread.sleep(1500);screenshot("global-playback-settings");
    }
}
