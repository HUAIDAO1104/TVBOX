package com.fongmi.android.tv.experience;

import android.content.Context;
import android.net.Uri;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.ui.search.SearchGroup;
import com.fongmi.android.tv.watch.WatchlistStore;
import com.fongmi.android.tv.player.preview.PreviewDataSource;
import com.fongmi.android.tv.ui.dialog.PreciseSeekDialog;
import com.fongmi.android.tv.ui.dialog.PlaybackAccessDialog;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import com.fongmi.android.tv.offline.OfflineMetadata;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import okhttp3.mockwebserver.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=35,application=com.fongmi.android.tv.offline.OfflineRuntimeTest.TestApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MobileLibraryTest {
    private Context context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("mobile-watchlist-v1",0).edit().clear().commit();ReflectionHelpers.setStaticField(WatchlistStore.class,"instance",null);}
    private Vod video(String name,String year,String site,String id){Vod v=new Vod();v.setName(name);v.setYear(year);v.setId(id);v.setSite(new com.google.gson.Gson().fromJson("{\"key\":\""+site+"\",\"name\":\""+site+"\"}",Site.class));return v;}
    private Vod series(String flag,String... names){Vod v=video("星河旅途","2026","a","show");StringJoiner urls=new StringJoiner("#");for(String name:names)urls.add(name+"$https://example.invalid/"+name);v.setFlags(new ArrayList<>(List.of(Flag.create(flag,urls.toString()))));return v;}
    @Test public void sameTitleMergesAcrossSourcesButPreservesRemakesAndSeasons(){
        var a=video("星河 旅途 第一季","2026","a","1");var b=video("星河旅途 第一季","2026","b","2");
        var groups=SearchGroup.of(List.of(a,b,video("星河旅途 第一季","2020","c","3"),video("星河旅途 第二季","2026","d","4")),true);
        assertEquals(3,groups.size());assertEquals(2,groups.get(0).sources.size());assertSame(b,groups.get(0).sources.get(1));
    }
    @Test public void unknownYearAndDifferentEditionsRemainSeparate(){assertEquals(3,SearchGroup.of(List.of(video("旅途","2026","a","1"),video("旅途","","b","2"),video("旅途 导演剪辑版","2026","c","3")),true).size());}
    @Test public void sourceTabDoesNotAggregateAndGroupChangeRebinds(){Vod a=video("旅途","2026","a","1"),b=video("旅途","2026","b","2");assertEquals(2,SearchGroup.of(List.of(a,b),false).size());assertFalse(SearchGroup.of(List.of(a),true).get(0).isSameContent(SearchGroup.of(List.of(a,b),true).get(0)));}
    @Test public void repeatedSourceResultsAreDeduplicated(){Vod a=video("旅途","2026","a","1");assertEquals(1,SearchGroup.of(List.of(a,a),true).get(0).sources.size());}
    @Test public void reorderingAndReappearingEpisodesDoNotCreateDuplicateUpdates(){
        WatchlistStore.Entry e=new WatchlistStore.Entry();e.followed=true;assertTrue(WatchlistStore.applyCatalog(e,series("HD","第1集","第2集")).isEmpty());
        assertTrue(WatchlistStore.applyCatalog(e,series("HD","第2集","第1集")).isEmpty());
        assertEquals(Set.of("第3集"),WatchlistStore.applyCatalog(e,series("HD","第1集","第2集","第3集")));
        WatchlistStore.applyCatalog(e,series("HD","第1集","第2集"));assertTrue(WatchlistStore.applyCatalog(e,series("HD","第1集","第2集","第3集")).isEmpty());
    }
    @Test public void replacingEpisodeLabelsOrSourceDoesNotNotifyEntireSeries(){WatchlistStore.Entry e=new WatchlistStore.Entry();e.followed=true;WatchlistStore.applyCatalog(e,series("HD","第1集","第2集"));assertTrue(WatchlistStore.applyCatalog(e,series("HD","EP01","EP02")).isEmpty());assertTrue(WatchlistStore.applyCatalog(e,series("4K","第1集","第2集","第3集")).isEmpty());}
    @Test public void followedWatchedAndUpdatesPersistIndependently(){
        WatchlistStore store=WatchlistStore.get(context);var e=store.observe(5,"a","show","来源",series("HD","1","2"));store.follow(e.id,true);store.watched(e.id,"1",true);assertEquals(1,store.refresh(e.id,series("HD","1","2","3")));
        ReflectionHelpers.setStaticField(WatchlistStore.class,"instance",null);store=WatchlistStore.get(context);e=store.find(e.id);assertTrue(e.followed);assertEquals(1,e.watchedCount());assertEquals(Set.of("3"),e.fresh);store.watched(e.id,"1",false);assertEquals(0,store.find(e.id).watchedCount());assertFalse(store.reminders());
    }
    @Test public void watchedStateUsesTheLineChosenForPlayback(){
        var store=WatchlistStore.get(context);var entry=store.observe(1,"a","1","source",series("HD","1","2"));
        store.useFlag(entry.id,Flag.create("4K","第1集$https://example.invalid/1#第2集$https://example.invalid/2"));
        store.watched(entry.id,"第1集",true);assertEquals(1,store.find(entry.id).watchedCount());assertEquals("4K",store.find(entry.id).flag);
    }
    @Test public void identitySeparatesSubscriptionsAndSources(){assertNotEquals(WatchlistStore.id(1,"a","1"),WatchlistStore.id(2,"a","1"));assertNotEquals(WatchlistStore.id(1,"a","1"),WatchlistStore.id(1,"b","1"));}
    @Test public void corruptStoredWatchlistDoesNotCrash(){context.getSharedPreferences("mobile-watchlist-v1",0).edit().putString("entries","[{\"id\":null}]").commit();assertTrue(WatchlistStore.get(context).followed().isEmpty());}
    @Test public void oldDownloadMetadataHasSafeGroupingFallback(){OfflineMetadata m=OfflineMetadata.decode("{\"title\":\"旅途\",\"episode\":\"1\"}".getBytes());assertEquals("legacy:旅途",m.groupKey());assertEquals("",m.poster);m.showId="source-id";assertEquals("source-id",OfflineMetadata.decode(m.encode()).groupKey());}
    @Test public void preciseSeekParsesHoursAndRejectsInvalidOrPastEnd(){assertEquals(3723000,PreciseSeekDialog.parse("1:02:03",4000000));assertEquals(123000,PreciseSeekDialog.parse("02:03",200000));for(String value:List.of("1:60","-1","1:2:3:4","999999999999","abc","100:00"))assertThrows(IllegalArgumentException.class,()->PreciseSeekDialog.parse(value,300000));}
    @Test public void missingDanmakuExplainsFailureRatherThanPretendingReady(){assertTrue(PlaybackAccessDialog.statusText(DanmakuStatus.failed(DanmakuStatus.Failure.NO_MATCH)).contains("核对"));assertTrue(PlaybackAccessDialog.statusText(DanmakuStatus.failed(DanmakuStatus.Failure.EMPTY_DOCUMENT)).contains("空弹幕"));assertTrue(PlaybackAccessDialog.statusText(DanmakuStatus.ready(12)).contains("12"));}
    @Test public void previewRequiresRangeAndPreservesHeaders()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 131072-131075/131076").setBody("test"));server.start();
            try(PreviewDataSource data=new PreviewDataSource(Uri.parse(server.url("/video.mp4").toString()),Map.of("Authorization","test-only","Range","bytes=0-"),null,-1)){
                byte[] out=new byte[4];assertEquals(4,data.readAt(131072,out,0,4));assertEquals("test",new String(out));assertEquals(131076,data.getSize());
                var request=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("test-only",request.getHeader("Authorization"));assertEquals("bytes=131072-262143",request.getHeader("Range"));
            }
        }
    }
    @Test public void previewRejectsServerIgnoringRangeWithoutDownloadingMovie()throws Exception{
        try(MockWebServer server=new MockWebServer()){server.enqueue(new MockResponse().setBody("ignored"));server.start();try(PreviewDataSource data=new PreviewDataSource(Uri.parse(server.url("/movie").toString()),Map.of(),null,2000000)){assertThrows(java.io.IOException.class,()->data.readAt(1000000,new byte[100],0,100));}}
    }
    @Test public void updateJobsOnlyRunAfterOptIn(){
        var scheduler=context.getSystemService(android.app.job.JobScheduler.class);scheduler.cancelAll();
        com.fongmi.android.tv.watch.WatchlistJob.schedule(context);assertTrue(scheduler.getAllPendingJobs().isEmpty());
        WatchlistStore.get(context).reminders(true);com.fongmi.android.tv.watch.WatchlistJob.schedule(context);assertEquals(1,scheduler.getAllPendingJobs().size());assertEquals(21600000,scheduler.getAllPendingJobs().get(0).getIntervalMillis());
        WatchlistStore.get(context).reminders(false);com.fongmi.android.tv.watch.WatchlistJob.schedule(context);assertTrue(scheduler.getAllPendingJobs().isEmpty());
    }
    @Test public void cancelledPreviewMakesNoRequest()throws Exception{try(PreviewDataSource data=new PreviewDataSource(Uri.parse("https://example.invalid/video"),Map.of(),null,-1)){data.cancel();assertThrows(java.io.IOException.class,()->data.readAt(0,new byte[1],0,1));}}
}
