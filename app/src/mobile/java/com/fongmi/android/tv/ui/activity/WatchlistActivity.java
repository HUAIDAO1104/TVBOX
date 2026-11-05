package com.fongmi.android.tv.ui.activity;

import android.content.*;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;
import androidx.recyclerview.widget.RecyclerView;
import com.fongmi.android.tv.databinding.*;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.watch.*;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.utils.ImgUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import java.util.*;

public final class WatchlistActivity extends BaseActivity {
    private ActivityWatchlistBinding binding;private WatchlistStore store;private List<WatchlistStore.Entry> rows=List.of();private int filter;private final Adapter adapter=new Adapter();
    public static void start(Context context){context.startActivity(new Intent(context,WatchlistActivity.class));}
    @Override protected ViewBinding getBinding(){return binding=ActivityWatchlistBinding.inflate(getLayoutInflater());}
    @Override protected void initView(Bundle state){
        store=WatchlistStore.get(this);binding.list.setAdapter(adapter);binding.list.setItemAnimator(null);binding.reminders.setChecked(store.reminders());
        for(String tab:new String[]{"全部","未看完","有更新","已看完"})binding.tabs.addTab(binding.tabs.newTab().setText(tab));
        if(state!=null){filter=state.getInt("filter");binding.search.setText(state.getString("search",""));binding.tabs.selectTab(binding.tabs.getTabAt(filter));}
        binding.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){public void onTabSelected(TabLayout.Tab tab){filter=tab.getPosition();reload();}public void onTabUnselected(TabLayout.Tab tab){}public void onTabReselected(TabLayout.Tab tab){}});
    }
    @Override protected void initEvent(){
        binding.checkUpdates.setOnClickListener(v->check(true));
        binding.reminders.setOnCheckedChangeListener((v,on)->{store.reminders(on);if(on)com.fongmi.android.tv.offline.OfflineActions.requestNotification(this);WatchlistJob.schedule(this);reload();});
        binding.search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){reload();}public void afterTextChanged(android.text.Editable e){}});
    }
    @Override protected void onResume(){super.onResume();reload();check(false);}
    private void check(boolean force){binding.checkUpdates.setEnabled(false);binding.checkUpdates.setText("检查中…");WatchlistChecker.check(this,force,()->{if(!isDestroyed()){binding.checkUpdates.setEnabled(true);binding.checkUpdates.setText("检查更新");reload();}});}
    private void reload(){
        String query=binding.search.getText().toString().trim().toLowerCase(Locale.ROOT);List<WatchlistStore.Entry> all=store.followed();
        rows=all.stream().filter(e->(filter!=1||!e.finished())&&(filter!=2||!e.fresh.isEmpty())&&(filter!=3||e.finished())).filter(e->e.title.toLowerCase(Locale.ROOT).contains(query)).toList();
        binding.empty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);binding.empty.setText(all.isEmpty()?"追更，从一部喜欢的剧开始\n在影片详情点击「追剧」，即可记录已看集数。":"当前分类暂无匹配影片\n切换分类或试试其他片名。");
        binding.status.setText(all.size()+" 部追剧 · "+all.stream().filter(e->!e.fresh.isEmpty()).count()+" 部有更新\n仅检查当前订阅；后台约每 6 小时由系统安排，省电模式或片源不可用时可能延迟。"+(store.reminders()&&!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()?"\n系统通知未允许，更新仍会显示在列表中。":""));
        adapter.notifyDataSetChanged();
    }
    private void open(WatchlistStore.Entry entry){
        Config config=Config.find(entry.cid);store.opened(entry.id);
        if(config==null){SearchActivity.start(this,entry.title);return;}
        if(entry.cid==VodConfig.getCid())VideoActivity.start(this,entry.site,entry.video,entry.title,entry.poster);
        else VodConfig.load(config,new com.fongmi.android.tv.impl.Callback(){@Override public void success(){if(!isDestroyed())VideoActivity.start(WatchlistActivity.this,entry.site,entry.video,entry.title,entry.poster);}});
    }
    private void manage(WatchlistStore.Entry entry){
        new MaterialAlertDialogBuilder(this).setTitle(entry.title).setItems(new String[]{"管理已看 / 未看","取消追剧"},(d,which)->{
            if(which==1)new MaterialAlertDialogBuilder(this).setTitle("取消追剧？").setMessage("保留已看记录，停止此片的新剧集提醒。").setNegativeButton("取消",null).setPositiveButton("取消追剧",(dialog,w)->{store.follow(entry.id,false);reload();}).show();
            else {boolean[] checked=new boolean[entry.episodes.size()];for(int i=0;i<checked.length;i++)checked[i]=entry.watched.contains(entry.episodes.get(i));
                new MaterialAlertDialogBuilder(this).setTitle("勾选已看剧集").setMultiChoiceItems(entry.episodes.toArray(new String[0]),checked,(dialog,i,value)->checked[i]=value)
                    .setNegativeButton("取消",null).setPositiveButton("保存",(dialog,w)->{for(int i=0;i<checked.length;i++)store.watched(entry.id,entry.episodes.get(i),checked[i]);reload();}).show();}
        }).show();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("filter",filter);state.putString("search",binding.search.getText().toString());super.onSaveInstanceState(state);}
    private final class Adapter extends RecyclerView.Adapter<Holder>{
        @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type){return new Holder(AdapterLibraryBinding.inflate(getLayoutInflater(),parent,false));}
        public int getItemCount(){return rows.size();}
        public void onBindViewHolder(@NonNull Holder h,int position){WatchlistStore.Entry e=rows.get(position);h.v.name.setText(e.title);h.v.site.setText(e.source);
            h.v.remark.setText((e.fresh.isEmpty()?"":"更新 "+e.fresh.size()+" 集 · ")+"已看 "+e.watchedCount()+" / "+e.episodes.size()+" 集"+(e.error.isEmpty()?"":"\n"+e.error)+(e.cid==VodConfig.getCid()?"":"\n切换至此片源后可检查更新"));
            h.v.progress.setMax(100);h.v.progress.setProgress(e.episodes.isEmpty()?0:(int)(e.watchedCount()*100/e.episodes.size()));
            h.v.delete.setVisibility(View.VISIBLE);h.v.delete.setText("管理剧集与追更 ›");h.v.delete.setOnClickListener(v->manage(e));
            ImgUtil.load(e.title,e.poster,h.v.image);h.v.getRoot().setOnClickListener(v->open(e));h.v.getRoot().setOnLongClickListener(v->{manage(e);return true;});
        }
    }
    private static final class Holder extends RecyclerView.ViewHolder{final AdapterLibraryBinding v;Holder(AdapterLibraryBinding v){super(v.getRoot());this.v=v;}}
}
