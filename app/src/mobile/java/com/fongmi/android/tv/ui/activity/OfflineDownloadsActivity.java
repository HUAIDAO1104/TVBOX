package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.os.*;
import android.text.*;
import android.text.format.Formatter;
import android.view.*;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.*;
import com.fongmi.android.tv.offline.*;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

public final class OfflineDownloadsActivity extends BaseActivity {
    private ActivityOfflineDownloadsBinding binding;
    private OfflineStore store;
    private OfflineBatchQueue queue;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String,long[]> samples = new HashMap<>();
    private final Map<String,Long> rates = new HashMap<>();
    private final Adapter adapter = new Adapter();
    private int filter;
    private String importId;
    private boolean editing;
    private final Runnable tick = new Runnable() { public void run() { refresh(); handler.postDelayed(this, 1000); } };
    private final ActivityResultLauncher<String[]> importer = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null && importId != null && store.download(importId) != null) store.importDanmaku(importId, uri, error -> {
            toast(error == null ? "弹幕已保存，可离线播放" : error); refresh();
        });
    });
    private static final class Row {
        final Download download; final OfflineBatchQueue.Job job; final OfflineMetadata meta; final String id;
        Row(Download d) { download=d; job=null; meta=OfflineMetadata.decode(d.request.data); id="d:"+d.request.id; }
        Row(OfflineBatchQueue.Job j) { job=j; download=null; meta=j.meta; id="p:"+j.id; }
        boolean complete() { return download!=null && download.state==Download.STATE_COMPLETED; }
        boolean failed() { return download!=null?download.state==Download.STATE_FAILED:job.state==OfflineBatchQueue.FAILED; }
        boolean paused() { return download!=null?download.state==Download.STATE_STOPPED:job.state==OfflineBatchQueue.PAUSED; }
        boolean removing() { return download!=null && download.state==Download.STATE_REMOVING; }
    }
    public static void start(Context context) { context.startActivity(new Intent(context, OfflineDownloadsActivity.class)); }
    @Override protected ViewBinding getBinding() { return binding = ActivityOfflineDownloadsBinding.inflate(getLayoutInflater()); }
    @Override protected void initView(Bundle state) {
        store = OfflineStore.get(this); queue=OfflineBatchQueue.get(this);
        if (state != null) {
            importId=state.getString("importId"); filter=state.getInt("filter"); editing=state.getBoolean("editing");
            ArrayList<String> saved=state.getStringArrayList("selected"); if(saved!=null) selected.addAll(saved);
            binding.search.setText(state.getString("search", ""));
        }
        binding.list.setItemAnimator(null); binding.list.setAdapter(adapter); binding.wifi.setChecked(store.wifiOnly());
        DownloadService.start(this, OfflineDownloadService.class); OfflineBatchService.kick(this);
    }
    @Override protected void initEvent() {
        binding.wifi.setOnCheckedChangeListener((button, checked) -> { store.setWifiOnly(checked); OfflineBatchService.kick(this); });
        binding.all.setOnClickListener(v -> filter(0)); binding.active.setOnClickListener(v -> filter(1));
        binding.completed.setOnClickListener(v -> filter(2)); binding.failed.setOnClickListener(v -> filter(3));
        binding.manage.setOnClickListener(v -> { editing=!editing; selected.clear(); refresh(); });
        binding.selectAll.setOnClickListener(v -> {
            Set<String> visible=new LinkedHashSet<>(); for(Row r:items) if(!r.removing()) visible.add(r.id);
            if(selected.containsAll(visible)) selected.clear(); else selected.addAll(visible); refresh();
        });
        binding.pauseSelected.setOnClickListener(v -> batch(0));
        binding.resumeSelected.setOnClickListener(v -> batch(1));
        binding.deleteSelected.setOnClickListener(v -> batch(2));
        binding.search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { selected.clear(); refresh(); }
            public void afterTextChanged(Editable e) { }
        });
    }
    private void filter(int value) { filter=value; selected.clear(); refresh(); }
    @Override protected void onStart() { super.onStart(); handler.post(tick); }
    @Override protected void onStop() { handler.removeCallbacks(tick); super.onStop(); }
    @Override protected void onBackInvoked() { if(editing){editing=false;selected.clear();refresh();}else super.onBackInvoked(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("importId",importId);state.putInt("filter",filter);state.putBoolean("editing",editing);
        state.putStringArrayList("selected",new ArrayList<>(selected));state.putString("search",binding.search.getText().toString());super.onSaveInstanceState(state);
    }
    private void toast(String text) { if (!isDestroyed()) Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private String size(long value) { return Formatter.formatFileSize(this,Math.max(0,value)); }
    private void refresh() {
        if(store==null||isDestroyed())return;
        List<Row> all=new ArrayList<>();for(OfflineBatchQueue.Job j:queue.jobs())all.add(new Row(j));for(Download d:store.downloads())all.add(new Row(d));
        String query=binding.search.getText().toString().trim().toLowerCase(Locale.ROOT);
        items.clear();int completed=0,failed=0;long now=SystemClock.elapsedRealtime();Set<String> live=new HashSet<>();
        for(Row r:all){
            if(r.complete())completed++;if(r.failed())failed++;
            live.add(r.id);
            if(r.download!=null){
                long bytes=r.download.getBytesDownloaded();long[] last=samples.get(r.id);
                if(last==null||now-last[1]>=800){rates.put(r.id,last==null?0:Math.max(0,bytes-last[0])*1000/Math.max(1,now-last[1]));samples.put(r.id,new long[]{bytes,now});}
            }
            if(filter==1&&(r.complete()||r.failed())||filter==2&&!r.complete()||filter==3&&!r.failed())continue;
            if(!(r.meta.title+" "+r.meta.episode).toLowerCase(Locale.ROOT).contains(query))continue;
            items.add(r);
        }
        samples.keySet().retainAll(live);rates.keySet().retainAll(live);
        Set<String> visible=new HashSet<>();for(Row r:items)if(!r.removing())visible.add(r.id);selected.retainAll(visible);
        binding.all.setText("全部 "+all.size());binding.active.setText("进行中 "+(all.size()-completed-failed));binding.completed.setText("已完成 "+completed);binding.failed.setText("失败 "+failed);
        View[] tabs={binding.all,binding.active,binding.completed,binding.failed};for(int i=0;i<tabs.length;i++){
            tabs[i].setSelected(filter==i);((android.widget.TextView)tabs[i]).setTextColor(ResUtil.getColor(filter==i?R.color.mobile_accent:R.color.mobile_muted));
        }
        binding.empty.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);
        binding.empty.setText(!query.isEmpty()?"没有找到匹配的下载\n试试影片名称或集数":all.isEmpty()?"把喜欢的影片带在身边\n在播放页点击下载，选择想看的剧集。":filter==3?"暂时没有失败任务\n所有任务状态都会在这里更新。":"这个分类还没有视频\n切换分类查看其他下载。");
        long used=store.cache.getCacheSpace(),free=store.root.getUsableSpace(),total=store.root.getTotalSpace();
        binding.storage.setText(size(used)+"  已下载");binding.storageHint.setText(completed+" 集可离线观看 · 设备可用 "+size(free));
        binding.storageProgress.setProgress(total>0?(int)Math.min(100,used*100/total):0);
        binding.manage.setText(editing?"完成":"管理");binding.manage.setEnabled(editing||!all.isEmpty());
        binding.overview.setVisibility(editing?View.GONE:View.VISIBLE);binding.selectionBar.setVisibility(editing?View.VISIBLE:View.GONE);binding.batchBar.setVisibility(editing?View.VISIBLE:View.GONE);
        binding.selectionCount.setText("已选 "+selected.size()+" 项");binding.selectAll.setText(!visible.isEmpty()&&selected.containsAll(visible)?"取消全选":"全选当前列表");
        boolean pause=false,resume=false;for(Row r:items)if(selected.contains(r.id)){pause|=!r.complete()&&!r.paused()&&!r.failed()&&!r.removing();resume|=r.paused()||r.failed();}
        binding.pauseSelected.setEnabled(pause);binding.resumeSelected.setEnabled(resume);binding.deleteSelected.setEnabled(!selected.isEmpty());adapter.notifyDataSetChanged();
    }
    private String state(Row row) {
        if(row.job!=null){switch(row.job.state){case OfflineBatchQueue.PREPARING:return "正在获取下载信息";case OfflineBatchQueue.PAUSED:return "已暂停 · 尚未下载";case OfflineBatchQueue.FAILED:return row.job.error;default:return "等待准备下载";}}
        switch(row.download.state){case Download.STATE_COMPLETED:return "已完成 · 可离线播放";case Download.STATE_FAILED:return "下载失败 · 点击重试";case Download.STATE_STOPPED:return "已暂停";case Download.STATE_REMOVING:return "正在删除";case Download.STATE_DOWNLOADING:return "正在下载";default:return store.manager.isWaitingForRequirements()?"等待网络或可用存储空间":"排队中";}
    }
    private void pause(Row r){if(r.complete()||r.paused()||r.failed()||r.removing())return;if(r.job!=null)queue.pause(r.job.id);else DownloadService.sendSetStopReason(this,OfflineDownloadService.class,r.download.request.id,1,false);}
    private void resume(Row r){if(!r.paused()&&!r.failed())return;if(r.job!=null)queue.resume(r.job.id);else{DownloadService.sendAddDownload(this,OfflineDownloadService.class,r.download.request,0,false);DownloadService.sendResumeDownloads(this,OfflineDownloadService.class,false);}OfflineBatchService.kick(this);}
    private void remove(Row r){if(r.job!=null)queue.remove(r.job.id);else DownloadService.sendRemoveDownload(this,OfflineDownloadService.class,r.download.request.id,false);}
    private void action(Row r){if(r.removing())return;if(r.complete())OfflinePlayerActivity.start(this,r.download.request.id);else if(r.paused()||r.failed())resume(r);else pause(r);refresh();}
    private void batch(int action){
        List<Row> chosen=new ArrayList<>();for(Row r:items)if(selected.contains(r.id))chosen.add(r);
        if(action==2){new MaterialAlertDialogBuilder(this).setTitle("删除所选 "+chosen.size()+" 项？").setMessage("取消待下载任务，并删除已下载视频、弹幕及字幕。其他下载不受影响。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{for(Row r:chosen)remove(r);selected.clear();refresh();}).show();return;}
        for(Row r:chosen)if(action==0)pause(r);else resume(r);refresh();
    }
    private void manage(Row r){
        if(r.download!=null){manage(r.download);return;}
        if(r.removing())return;
        new MaterialAlertDialogBuilder(this).setTitle(r.meta.title+" · "+r.meta.episode).setItems(new String[]{r.paused()||r.failed()?"继续 / 重试准备":"暂停准备","删除待下载任务"},(d,which)->{
            if(which==0)action(r);else new MaterialAlertDialogBuilder(this).setTitle("删除待下载任务？").setMessage(r.meta.episode).setNegativeButton("取消",null).setPositiveButton("删除",(dd,w)->{remove(r);refresh();}).show();
        }).show();
    }
    private void manage(Download original) {
        Download item = store.download(original.request.id);
        if (item == null || item.state == Download.STATE_REMOVING) return;
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        if (item.state == Download.STATE_COMPLETED) {
            labels.add("离线播放"); actions.add(() -> OfflinePlayerActivity.start(this, item.request.id));
        } else if (item.state == Download.STATE_STOPPED || item.state == Download.STATE_FAILED) {
            labels.add(item.state == Download.STATE_FAILED ? "重试下载" : "继续下载");
            actions.add(() -> {
                DownloadService.sendAddDownload(this, OfflineDownloadService.class, item.request, 0, false);
                DownloadService.sendResumeDownloads(this, OfflineDownloadService.class, false);
            });
        } else {
            labels.add("暂停下载"); actions.add(() -> DownloadService.sendSetStopReason(this, OfflineDownloadService.class, item.request.id, 1, false));
        }
        if (item.state == Download.STATE_COMPLETED || item.state == Download.STATE_STOPPED || item.state == Download.STATE_FAILED) {
            labels.add("补存弹幕和外挂字幕"); actions.add(() -> { toast("正在补存，请稍候…"); store.retryExtras(item, error -> { toast(store.extrasStatus(item)); refresh(); }); });
            labels.add("导入本地弹幕 XML"); actions.add(() -> { importId = item.request.id; importer.launch(new String[]{"text/xml", "application/xml", "application/octet-stream"}); });
        }
        labels.add("删除视频、弹幕和字幕"); actions.add(() -> new MaterialAlertDialogBuilder(this).setTitle("删除此下载？")
                .setMessage("将删除本地视频及其弹幕、字幕，释放存储空间。").setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> DownloadService.sendRemoveDownload(this, OfflineDownloadService.class, item.request.id, false)).show());
        new MaterialAlertDialogBuilder(this).setTitle(OfflineMetadata.decode(item.request.data).title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).show();
    }
    private final class Adapter extends RecyclerView.Adapter<Holder> {
        Adapter(){setHasStableIds(true);}
        @Override public long getItemId(int p){Row r=items.get(p);return Long.parseLong(r.id.substring(2,17),16)+(r.job==null?0:Long.MIN_VALUE);}
        @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type){return new Holder(AdapterOfflineDownloadBinding.inflate(LayoutInflater.from(parent.getContext()),parent,false));}
        public void onBindViewHolder(@NonNull Holder h,int position){
            Row r=items.get(position);h.view.title.setText(r.meta.title);h.view.episode.setText(r.meta.episode);h.view.status.setText(state(r));
            h.view.status.setTextColor(r.failed()?ResUtil.getColor(R.color.mobile_accent):r.complete()?android.graphics.Color.parseColor("#71D5B0"):ResUtil.getColor(R.color.mobile_muted));
            h.view.cover.setText(r.meta.title.isEmpty()?"影片":r.meta.title.substring(0,r.meta.title.offsetByCodePoints(0,Math.min(2,r.meta.title.codePointCount(0,r.meta.title.length())))));
            h.view.check.setVisibility(editing?View.VISIBLE:View.GONE);h.view.check.setChecked(selected.contains(r.id));
            h.view.action.setVisibility(editing?View.GONE:View.VISIBLE);h.view.more.setVisibility(editing?View.GONE:View.VISIBLE);
            h.view.action.setImageResource(r.complete()?R.drawable.ic_mobile_play:r.paused()||r.failed()?R.drawable.ic_mobile_download:R.drawable.ic_offline_pause);
            h.view.action.setEnabled(!r.removing());h.view.action.setContentDescription(r.complete()?"离线播放":r.failed()?"重试下载":r.paused()?"继续下载":"暂停下载");
            float percent=r.download==null?-1:r.download.getPercentDownloaded();
            boolean indeterminate=(r.job!=null&&r.job.state==OfflineBatchQueue.PREPARING)||(r.download!=null&&percent<0&&r.download.state==Download.STATE_DOWNLOADING);
            h.view.progress.setVisibility(r.job!=null&&r.job.state!=OfflineBatchQueue.PREPARING?View.GONE:View.VISIBLE);
            h.view.progress.setIndeterminate(indeterminate);if(!indeterminate)h.view.progress.setProgress(r.complete()?100:(int)Math.max(0,percent));
            if(r.download==null){h.view.size.setText(r.paused()?"继续后将重新获取片源":r.failed()?"重试后将重新获取片源":"准备完成后将自动开始下载");h.view.extras.setText(r.meta.danmaku.isEmpty()?"弹幕未匹配 · 可在下载后导入":"将同时保存已匹配的弹幕");}
            else{
                Download d=r.download;long speed=d.state==Download.STATE_DOWNLOADING?rates.getOrDefault(r.id,0L):0;
                String bytes=size(d.getBytesDownloaded())+(d.contentLength>0?" / "+size(d.contentLength):"");
                String detail=speed>0?" · "+size(speed)+"/s":percent>=0&&!r.complete()?" · "+(int)percent+"%":"";
                h.view.size.setText(bytes+detail);h.view.extras.setText(store.extrasStatus(d));
            }
            h.view.getRoot().setContentDescription(r.meta.title+" "+r.meta.episode+"，"+state(r)+(editing?(selected.contains(r.id)?"，已选择":"，未选择"):""));
            h.view.getRoot().setOnClickListener(v->{if(editing){if(!selected.add(r.id))selected.remove(r.id);refresh();}else if(r.complete())action(r);else manage(r);});
            h.view.getRoot().setOnLongClickListener(v->{editing=true;selected.add(r.id);refresh();return true;});
            h.view.action.setOnClickListener(v->action(r));h.view.more.setOnClickListener(v->manage(r));
        }
        public int getItemCount(){return items.size();}
    }
    private static final class Holder extends RecyclerView.ViewHolder{final AdapterOfflineDownloadBinding view;Holder(AdapterOfflineDownloadBinding view){super(view.getRoot());this.view=view;}}
}
