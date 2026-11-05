package com.fongmi.android.tv.watch;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.ui.activity.WatchlistActivity;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WatchlistChecker {
    private static final AtomicBoolean busy=new AtomicBoolean();
    private static final ExecutorService coordinator=Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor queries=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>());
    public static boolean running(){return busy.get();}
    private static final List<Runnable> waiting=new ArrayList<>();
    public static void check(Context context,boolean force,Runnable done){check(context,force,()->true,done);}
    public static void check(Context context,boolean force,java.util.function.BooleanSupplier active,Runnable done){
        Context app=context.getApplicationContext();Handler main=new Handler(Looper.getMainLooper());
        synchronized(waiting){waiting.add(done);if(!busy.compareAndSet(false,true))return;}
        coordinator.execute(()->{
            try{
                WatchlistStore store=WatchlistStore.get(app);int cid=VodConfig.getCid();long deadline=SystemClock.elapsedRealtime()+90000;
                List<WatchlistStore.Entry> records=new ArrayList<>(store.followed());records.sort(Comparator.comparingLong(e->e.checked));
                for(WatchlistStore.Entry e:records){
                    if(!active.getAsBoolean()||SystemClock.elapsedRealtime()>deadline||Thread.currentThread().isInterrupted())break;
                    if(e.cid!=cid||VodConfig.get().getSite(e.site).isEmpty())continue;
                    if(!force&&System.currentTimeMillis()-e.checked<6*60*60*1000L)continue;
                    Future<com.fongmi.android.tv.bean.Result> request=null;
                    try{
                        request=queries.submit(()->SiteApi.catalogContent(e.site,e.video));
                        var result=request.get(15,TimeUnit.SECONDS);
                        if(!active.getAsBoolean()||VodConfig.getCid()!=cid)break;
                        if(result.getVod().getFlags().isEmpty()){store.failed(e.id,"片源暂未返回剧集，已保留原记录");continue;}
                        int count=store.refresh(e.id,result.getVod());
                        if(count>0&&store.reminders())notifyUpdate(app,e,count);
                    }catch(Exception error){store.failed(e.id,"更新检查未完成，请稍后重试");}
                    finally{if(request!=null)request.cancel(true);}
                }
            }finally{List<Runnable> callbacks;synchronized(waiting){busy.set(false);callbacks=new ArrayList<>(waiting);waiting.clear();}for(Runnable callback:callbacks)main.post(callback);}
        });
    }
    private static void notifyUpdate(Context context,WatchlistStore.Entry entry,int count){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel("watch-updates","追剧更新",NotificationManager.IMPORTANCE_DEFAULT));
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled())return;
        PendingIntent intent=PendingIntent.getActivity(context,0,new Intent(context,WatchlistActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        try{NotificationManagerCompat.from(context).notify(entry.id.hashCode(),new NotificationCompat.Builder(context,"watch-updates").setSmallIcon(R.drawable.ic_mobile_play)
                .setContentTitle(entry.title+" 有新剧集").setContentText("新增 "+count+" 集，点击查看追剧列表").setContentIntent(intent).setAutoCancel(true).build());}catch(SecurityException ignored){}
    }
}
