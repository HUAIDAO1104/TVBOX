package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.*;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaItem;
import androidx.recyclerview.widget.*;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.databinding.DialogOfflineBatchBinding;
import com.fongmi.android.tv.offline.*;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.playback.vod.*;
import com.fongmi.android.tv.ui.activity.OfflineDownloadsActivity;
import com.fongmi.android.tv.utils.ResUtil;
import java.util.*;

public final class OfflineBatchDialog extends MobileSheetDialog {
    public interface Host {
        String offlineSite(); String offlineVideo(); String offlineTitle();
        Flag offlineFlag(); PlayerManager offlinePlayer();
    }
    private DialogOfflineBatchBinding binding;
    private final Set<String> selected = new LinkedHashSet<>(), existing = new HashSet<>();
    private final List<Episode> episodes = new ArrayList<>();
    private Host host; private Flag flag; private String site, video, title;
    private final Adapter adapter = new Adapter();
    public static void show(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().isStateSaved() || activity.getSupportFragmentManager().findFragmentByTag("offline_batch") != null) return;
        new OfflineBatchDialog().show(activity.getSupportFragmentManager(), "offline_batch");
    }
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent) { return binding = DialogOfflineBatchBinding.inflate(inflater,parent,false); }
    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state); if (state != null) selected.addAll(state.getStringArrayList("selected") == null ? Collections.emptyList() : state.getStringArrayList("selected"));
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) { out.putStringArrayList("selected",new ArrayList<>(selected)); super.onSaveInstanceState(out); }
    @Override protected void initView() {
        if (!(requireActivity() instanceof Host)) { dismissAllowingStateLoss(); return; }
        host = (Host) requireActivity(); flag = host.offlineFlag(); site = host.offlineSite(); video = host.offlineVideo(); title = host.offlineTitle();
        if (flag == null) { dismissAllowingStateLoss(); return; }
        episodes.clear(); existing.clear(); episodes.addAll(flag.getEpisodes()); episodes.removeIf(Episode::isBlockedPlaybackEntry);
        episodes.sort(Comparator.comparingInt(Episode::getIndex));
        for (androidx.media3.exoplayer.offline.Download d : OfflineStore.get(requireContext()).downloads()) {
            OfflineMetadata m = OfflineMetadata.decode(d.request.data); if (m.sourceId != null) existing.add(m.sourceId);
        }
        for (OfflineBatchQueue.Job job : OfflineBatchQueue.get(requireContext()).jobs()) existing.add(job.id);
        // Keep the user's explicit selection over rotation; otherwise preselect the playing episode.
        if (getArguments() == null && selected.isEmpty()) for (Episode e : episodes) if (e.isSelected() && !existing.contains(id(e))) selected.add(id(e));
        setArguments(new Bundle());
        binding.title.setText(title);
        binding.subtitle.setText(flag.getShow() + " · " + episodes.size() + " 集 · 已加入的剧集会自动跳过");
        binding.list.setLayoutManager(new GridLayoutManager(requireContext(),Math.max(2,getResources().getConfiguration().screenWidthDp / 110)));
        binding.list.setAdapter(adapter); applySize(); update();
    }
    @Override public void onConfigurationChanged(@NonNull android.content.res.Configuration config) {
        super.onConfigurationChanged(config); applySize();
    }
    private void applySize() {
        if (binding == null) return;
        android.content.res.Configuration config = getResources().getConfiguration();
        ((GridLayoutManager) binding.list.getLayoutManager()).setSpanCount(Math.max(2, (int) (config.screenWidthDp / (110 * Math.max(1, config.fontScale)))));
        binding.hint.setVisibility(config.screenHeightDp < 480 ? View.GONE : View.VISIBLE);
        binding.subtitle.setVisibility(config.screenHeightDp < 480 ? View.GONE : View.VISIBLE);
    }
    @Override protected void initEvent() {
        binding.close.setOnClickListener(v -> dismiss());
        binding.all.setOnClickListener(v -> {
            if (available().size() == selected.size()) selected.clear(); else selected.addAll(available()); update();
        });
        binding.next.setOnClickListener(v -> {
            selected.clear(); int start=0;
            for(int i=0;i<episodes.size();i++) if(episodes.get(i).isSelected()) start=i;
            for(int i=start;i<Math.min(start+5,episodes.size());i++) if(!existing.contains(id(episodes.get(i)))) selected.add(id(episodes.get(i)));
            update();
        });
        binding.manager.setOnClickListener(v -> { OfflineDownloadsActivity.start(requireContext()); dismiss(); });
        binding.download.setOnClickListener(v -> submit());
    }
    private String id(Episode e) { return OfflineBatchQueue.identity(site,video,flag.getFlag(),e.getUrl()); }
    private Set<String> available() { Set<String> ids=new LinkedHashSet<>(); for(Episode e:episodes) if(!existing.contains(id(e))) ids.add(id(e)); return ids; }
    private void update() {
        selected.retainAll(available()); binding.download.setEnabled(!selected.isEmpty());
        binding.download.setText(selected.isEmpty()?"请选择要下载的剧集":"下载所选 "+selected.size()+" 集");
        binding.all.setText(!selected.isEmpty()&&selected.size()==available().size()?"取消全选":"全选"); adapter.notifyDataSetChanged();
    }
    private void submit() {
        List<OfflineBatchQueue.Job> jobs=new ArrayList<>();
        PlayerManager player=host.offlinePlayer();
        DanmakuMatchContext context=VodPlaybackMedia.contextOf(player);
        DanmakuManualMatchStore.Selection match=DanmakuManualMatchStore.get().find(context);
        for(Episode e:episodes) if(selected.contains(id(e))) {
            OfflineBatchQueue.Job job=new OfflineBatchQueue.Job(); job.id=id(e); job.site=site; job.flag=flag.getFlag(); job.sourceUrl=e.getUrl();
            job.meta.title=title; job.meta.episode=e.getName(); job.meta.sourceId=job.id;
            if(match!=null) {
                String query=e.getNumber()>0?String.valueOf(e.getNumber()):e.getName();
                Danmaku comments=match.episode(query);
                if(comments!=null && comments.getUri()!=null) job.meta.danmaku=comments.getUri().toString();
            }
            MediaItem current=player.getCurrentMediaItem();
            // Reuse the current quality/auth only for the actual playing episode and source.
            if(e.isSelected() && current!=null && current.localConfiguration!=null
                    && title.equals(String.valueOf(current.mediaMetadata.title))
                    && e.getName().equals(String.valueOf(current.mediaMetadata.artist))
                    && !player.getPlayer().isCurrentMediaItemLive()) {
                if(current.localConfiguration.drmConfiguration!=null) continue;
                job.meta=OfflineActions.metadata(player); job.meta.title=title; job.meta.episode=e.getName(); job.meta.sourceId=job.id;
                job.resolvedUrl=current.localConfiguration.uri.toString(); job.mime=current.localConfiguration.mimeType;
            }
            jobs.add(job);
        }
        OfflineActions.requestNotification(requireActivity());
        int count=OfflineBatchQueue.get(requireContext()).add(jobs,existing);
        OfflineBatchService.kick(requireContext());
        Toast.makeText(requireContext(), count>0?"已加入 "+count+" 集，正在准备下载":"所选剧集已加入下载或暂不支持离线保存",Toast.LENGTH_LONG).show();
        OfflineDownloadsActivity.start(requireContext()); dismiss();
    }
    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup p,int type) {
            TextView view=new TextView(p.getContext());
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,ResUtil.dp2px(70)); lp.setMargins(ResUtil.dp2px(4),ResUtil.dp2px(4),ResUtil.dp2px(4),ResUtil.dp2px(4)); view.setLayoutParams(lp);
            view.setPadding(8,8,8,8); view.setGravity(Gravity.CENTER); view.setTextSize(13); view.setTextColor(ResUtil.getColor(R.color.mobile_text));
            view.setBackgroundResource(R.drawable.mobile_choice); view.setFocusable(true); return new Holder(view);
        }
        public void onBindViewHolder(@NonNull Holder holder,int position) {
            Episode e=episodes.get(position); String id=id(e); boolean added=existing.contains(id);
            holder.text.setText(e.getName()+(added?"\n已加入":e.isSelected()?"\n正在播放":""));
            holder.text.setSelected(selected.contains(id)); holder.text.setAlpha(added?0.4f:1f);
            holder.text.setEnabled(!added); holder.text.setContentDescription(e.getName()+(added?"，已加入下载":selected.contains(id)?"，已选择":"，未选择"));
            holder.text.setOnClickListener(v -> { if(!selected.add(id)) selected.remove(id); update(); });
        }
        public int getItemCount(){return episodes.size();}
    }
    private static final class Holder extends RecyclerView.ViewHolder { final TextView text; Holder(TextView text){super(text);this.text=text;} }
    @Override public void onDestroyView(){super.onDestroyView();binding=null;host=null;}
}
