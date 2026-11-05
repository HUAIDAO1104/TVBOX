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
