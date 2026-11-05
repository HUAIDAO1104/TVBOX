package com.fongmi.android.tv.watch;

import android.content.Context;
import androidx.annotation.Keep;
import com.fongmi.android.tv.bean.*;
import com.google.gson.Gson;
import java.util.*;

/** Local, source-scoped watch state. Signed/expiring episode URLs are deliberately not stored. */
public final class WatchlistStore {
    private static WatchlistStore instance;
    public static synchronized WatchlistStore get(Context context){if(instance==null)instance=new WatchlistStore(context.getApplicationContext());return instance;}
    @Keep public static final class Entry {
        public String id="",site="",video="",title="",poster="",source="",flag="",error="";
        public int cid; public boolean followed; public long checked, updated;
        public List<String> episodes=new ArrayList<>();
        public Set<String> known=new LinkedHashSet<>(),watched=new LinkedHashSet<>(),fresh=new LinkedHashSet<>();
        public long watchedCount(){return episodes.stream().filter(watched::contains).count();}
        public boolean finished(){return !episodes.isEmpty()&&watchedCount()==episodes.size();}
    }
    private final android.content.SharedPreferences prefs;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private WatchlistStore(Context context){
        prefs=context.getSharedPreferences("mobile-watchlist-v1",0);
        try{Entry[] saved=new Gson().fromJson(prefs.getString("entries","[]"),Entry[].class);
            if(saved!=null)for(Entry e:saved)if(e!=null&&e.id!=null&&e.site!=null&&e.video!=null&&e.title!=null&&e.episodes!=null&&e.known!=null&&e.watched!=null&&e.fresh!=null){
                if(e.flag==null)e.flag="";if(e.error==null)e.error="";if(e.poster==null)e.poster="";if(e.source==null)e.source="";entries.put(e.id,e);
            }
        }catch(RuntimeException ignored){}
    }
    public static String id(int cid,String site,String video){return com.fongmi.android.tv.offline.OfflineMetadata.id(cid+"\n"+site+"\n"+video,Map.of());}
    private Entry copy(Entry e){return e==null?null:new Gson().fromJson(new Gson().toJson(e),Entry.class);}
    private void save(){prefs.edit().putString("entries",new Gson().toJson(entries.values())).apply();}
    public synchronized Entry find(String id){return copy(entries.get(id));}
    public synchronized List<Entry> followed(){return entries.values().stream().filter(e->e.followed).sorted(Comparator.comparingLong((Entry e)->e.updated).reversed()).map(this::copy).toList();}
    public synchronized Entry observe(int cid,String site,String video,String source,Vod vod){
        String id=id(cid,site,video);Entry e=entries.get(id);
        if(e==null){e=new Entry();e.id=id;e.cid=cid;e.site=site;e.video=video;entries.put(id,e);}
        e.title=vod.getName();e.poster=vod.getPic();e.source=source;
        applyCatalog(e,vod);e.checked=System.currentTimeMillis();e.updated=Math.max(e.updated,e.checked);e.error="";
        // Keep followed records; evict old, unfollowed playback state first.
        if(entries.size()>500){Entry oldest=entries.values().stream().filter(v->!v.followed&&!v.id.equals(id)).min(Comparator.comparingLong(v->v.updated)).orElse(null);if(oldest!=null)entries.remove(oldest.id);}
        save();return copy(e);
    }
    public static Set<String> applyCatalog(Entry e,Vod vod){
        Flag chosen=null;
        for(Flag flag:vod.getFlags())if(!flag.isBlockedPlaybackSource()&&!flag.getEpisodes().isEmpty()){
            if(flag.getFlag().equals(e.flag)){chosen=flag;break;}
            if(chosen==null||flag.getEpisodes().size()>chosen.getEpisodes().size())chosen=flag;
        }
        if(chosen==null)return Set.of();
        List<String> names=chosen.getEpisodes().stream().filter(ep->!ep.isBlockedPlaybackEntry()).map(Episode::getName).filter(name->!name.isBlank()).distinct().toList();
        if(names.isEmpty())return Set.of();
        boolean sameFlag=e.flag.equals(chosen.getFlag());
        long overlap=names.stream().filter(e.known::contains).count();
        boolean baseline=e.known.isEmpty()||!sameFlag||(!e.episodes.isEmpty()&&overlap<Math.min(e.episodes.size(),names.size())/2.0);
        Set<String> added=new LinkedHashSet<>(names);added.removeAll(e.known);
        if(baseline){added.clear();e.known.clear();e.fresh.clear();}
        e.flag=chosen.getFlag();e.episodes=new ArrayList<>(names);e.known.addAll(names);e.fresh.retainAll(names);
        if(e.followed)e.fresh.addAll(added);else added.clear();
        return added;
    }
    public synchronized void useFlag(String id,Flag flag){
        Entry e=entries.get(id);if(e==null||flag==null||flag.getEpisodes().isEmpty()||e.flag.equals(flag.getFlag()))return;
        Vod vod=new Vod();vod.setFlags(new ArrayList<>(List.of(flag)));applyCatalog(e,vod);save();
    }
    public synchronized int refresh(String id,Vod vod){Entry e=entries.get(id);if(e==null||!e.followed)return 0;int added=applyCatalog(e,vod).size();e.checked=System.currentTimeMillis();e.error="";if(added>0)e.updated=e.checked;save();return added;}
    public synchronized void failed(String id,String reason){Entry e=entries.get(id);if(e!=null){e.checked=System.currentTimeMillis();e.error=reason;save();}}
    public synchronized void follow(String id,boolean value){Entry e=entries.get(id);if(e!=null){e.followed=value;e.fresh.clear();e.updated=System.currentTimeMillis();save();}}
    public synchronized void watched(String id,String episode,boolean value){Entry e=entries.get(id);if(e==null||!e.episodes.contains(episode))return;boolean changed=value?e.watched.add(episode):e.watched.remove(episode);if(value)e.fresh.remove(episode);if(changed){e.updated=System.currentTimeMillis();save();}}
    public synchronized void opened(String id){Entry e=entries.get(id);if(e!=null){e.fresh.clear();save();}}
    public boolean reminders(){return prefs.getBoolean("reminders",false);}
    public void reminders(boolean value){prefs.edit().putBoolean("reminders",value).apply();}
}
