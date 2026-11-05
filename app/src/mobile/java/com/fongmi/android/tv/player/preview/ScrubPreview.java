package com.fongmi.android.tv.player.preview;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.media3.datasource.DataSource;
import java.util.*;
import java.util.concurrent.*;

/** One worker, one pending target; stale frames can never overwrite the latest seek position. */
public final class ScrubPreview implements AutoCloseable {
    private final View root;private final ImageView image;private final TextView label;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final android.util.LruCache<String,Bitmap> cache=new android.util.LruCache<>(8);
    private volatile PreviewDataSource active;private volatile int generation;private volatile boolean closed;private Runnable pending;
    public ScrubPreview(View root,ImageView image,TextView label){this.root=root;this.image=image;this.label=label;}
    public static boolean supported(Uri uri,String mime){String path=Objects.toString(uri.getPath(),"").toLowerCase(Locale.ROOT);String type=Objects.toString(mime,"").toLowerCase(Locale.ROOT);
        return !path.endsWith(".m3u8")&&!path.endsWith(".mpd")&&!type.contains("mpegurl")&&!type.contains("dash")&&List.of("http","https","file","content").contains(uri.getScheme());}
    public void show(Uri uri,Map<String,String> headers,String mime,long position,DataSource.Factory local,long size){
        if(closed)return;int token=++generation;cancelRequest();root.setVisibility(View.VISIBLE);image.setImageDrawable(null);image.setVisibility(View.GONE);
        long target=Math.max(0,position);String time=time(target);label.setText(time+" · 正在取预览画面");
        if(uri==null||!supported(uri,mime)){label.setText(time+" · 此片源仅支持时间定位");return;}
        String key=com.fongmi.android.tv.offline.OfflineMetadata.id(uri.toString(),headers)+":"+(target/5000);
        Bitmap cached=cache.get(key);if(cached!=null){display(cached,time);return;}
        Map<String,String> auth=new HashMap<>(headers);
        DataSource.Factory factory=local!=null?local:List.of("file","content").contains(uri.getScheme())?new androidx.media3.datasource.DefaultDataSource.Factory(root.getContext()):null;
        pending=()->worker.execute(()->{
            Bitmap frame=null;PreviewDataSource data=new PreviewDataSource(uri,auth,factory,size);active=data;
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try{
                if(closed||token!=generation)return;
                retriever.setDataSource(data);
                if(Build.VERSION.SDK_INT>=27)frame=retriever.getScaledFrameAtTime(target*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180);
                else {Bitmap full=retriever.getFrameAtTime(target*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(full!=null){frame=Bitmap.createScaledBitmap(full,320,Math.max(1,full.getHeight()*320/full.getWidth()),true);if(frame!=full)full.recycle();}}
            }catch(Exception|OutOfMemoryError ignored){}finally{try{retriever.release();}catch(Exception ignored){}data.close();if(active==data)active=null;}
            Bitmap result=frame;
            main.post(()->{if(closed||token!=generation){if(result!=null)result.recycle();return;}if(result!=null){cache.put(key,result);display(result,time);}else label.setText(time+" · 暂无预览，松手即可跳转");});
        });main.postDelayed(pending,250);
    }
    private void display(Bitmap bitmap,String time){image.setImageBitmap(bitmap);image.setVisibility(View.VISIBLE);label.setText(time+" · 附近关键帧");}
    public static String time(long ms){long sec=Math.max(0,ms)/1000;return sec>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",sec/3600,sec/60%60,sec%60):String.format(Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    private void cancelRequest(){if(pending!=null)main.removeCallbacks(pending);worker.getQueue().clear();PreviewDataSource current=active;if(current!=null)current.cancel();}
    public void hide(){generation++;cancelRequest();root.setVisibility(View.GONE);image.setImageDrawable(null);}
    @Override public void close(){closed=true;hide();worker.shutdownNow();cache.evictAll();}
}
