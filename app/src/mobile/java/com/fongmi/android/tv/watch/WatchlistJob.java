package com.fongmi.android.tv.watch;

import android.app.job.*;
import android.content.*;

/** System-managed best-effort checks. Only the currently loaded subscription is queried. */
public final class WatchlistJob extends JobService {
    private static final int ID=59207;
    private volatile int generation;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    public static void schedule(Context context){
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(!WatchlistStore.get(context).reminders()){scheduler.cancel(ID);return;}
        if(android.os.Build.VERSION.SDK_INT>=24 && scheduler.getPendingJob(ID)!=null)return;
        scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(context,WatchlistJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6*60*60*1000L).setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters params){
        if(!WatchlistStore.get(this).reminders())return false;
        int token=++generation;
        Runnable finish=()->{if(token==generation){generation++;handler.removeCallbacksAndMessages(null);jobFinished(params,false);}};
        handler.postDelayed(finish,120000);
        com.fongmi.android.tv.utils.Task.execute(()->{
            // Reuse the saved current subscription after process death; never switch to another one.
            if(com.fongmi.android.tv.api.config.VodConfig.get().getSites().isEmpty())com.fongmi.android.tv.api.config.VodConfig.get().ensureLoaded();
            handler.post(()->{if(token==generation)WatchlistChecker.check(this,false,()->token==generation,finish);});
        });return true;
    }
    @Override public boolean onStopJob(JobParameters params){generation++;handler.removeCallbacksAndMessages(null);return true;}
    @Override public void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
