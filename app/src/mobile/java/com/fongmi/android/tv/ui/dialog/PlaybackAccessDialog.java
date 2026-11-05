package com.fongmi.android.tv.ui.dialog;

import android.os.Handler;
import android.os.Looper;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.*;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.databinding.DialogPlaybackAccessBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import com.fongmi.android.tv.setting.DanmakuSetting;
import java.util.*;

/** Mobile-only hub. All source selection continues through the existing playback pipeline. */
public final class PlaybackAccessDialog extends MobileSheetDialog {
    private DialogPlaybackAccessBinding binding;
    private PlayerManager player;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,500);}};
    public static void show(FragmentActivity activity){
        if(activity.getSupportFragmentManager().findFragmentByTag("playback-access")==null)new PlaybackAccessDialog().show(activity.getSupportFragmentManager(),"playback-access");
    }
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater,@Nullable ViewGroup container){return binding=DialogPlaybackAccessBinding.inflate(inflater,container,false);}
    @Override protected void initView(){binding.close.setOnClickListener(v->dismiss());}
    private boolean ready(){
        if(requireActivity() instanceof ControlDialog.Host host)player=host.playbackPlayer();
        return player!=null&&!player.isReleased();
    }
    @Override protected void initEvent(){
        binding.subtitleChoose.setOnClickListener(v->open(()->TrackDialog.create().player(player).type(C.TRACK_TYPE_TEXT).show(requireActivity())));
        binding.danmakuChoose.setOnClickListener(v->open(()->DanmakuDialog.create().player(player).show(requireActivity())));
        binding.danmakuSearch.setOnClickListener(v->open(()->DanmakuSettingDialog.create().player(player).search(true).show(requireActivity())));
        binding.danmakuStyle.setOnClickListener(v->open(()->DanmakuSettingDialog.create().player(player).show(requireActivity())));
        binding.subtitleEarlier.setOnClickListener(v->subtitle(-500,false));binding.subtitleLater.setOnClickListener(v->subtitle(500,false));binding.subtitleReset.setOnClickListener(v->subtitle(0,true));
        binding.danmakuEarlier.setOnClickListener(v->offset(-500,false));binding.danmakuLater.setOnClickListener(v->offset(500,false));binding.danmakuReset.setOnClickListener(v->offset(0,true));
        binding.danmakuToggle.setOnClickListener(v->{if(ready()){boolean enabled=!player.isDanmakuEnabled();DanmakuSetting.putShow(enabled);player.setDanmakuEnabled(enabled);render();}});
    }
    private void open(Runnable action){if(ready()){action.run();dismiss();}}
    private void subtitle(long delta,boolean reset){if(ready()){player.setTextOffsetMs(reset?0:Math.max(-600000,Math.min(600000,player.getTextOffsetMs()+delta)));render();}}
    private void offset(long delta,boolean reset){if(ready()){DanmakuSetting.putTimeOffsetMs(reset?0:Math.max(-600000,Math.min(600000,DanmakuSetting.getTimeOffsetMs()+delta)));player.setDanmakuConfig(DanmakuSetting.getConfig());render();}}
    public static String statusText(DanmakuStatus status){return switch(status.stage()){
        case MATCHING -> "正在匹配影片与集数";case AI_MATCHING -> "正在智能匹配";case RESTORING -> "正在恢复已选弹幕";
        case DOWNLOADING -> "正在加载弹幕"+(status.progress()>=0?" · "+status.progress()+"%":"");
        case READY -> "已加载 "+status.itemCount()+" 条弹幕";
        case FAILED -> switch(status.failure()){
            case DISABLED -> "自动加载已关闭，可手动选择来源";case NO_MATCH -> "未找到匹配结果，请核对影片和集数";
            case NETWORK, AI_NETWORK -> "网络连接失败，可重试匹配或导入本地弹幕";
            case ACCESS_DENIED, AI_ACCESS_DENIED -> "弹幕服务拒绝访问，请检查服务设置";
            case EMPTY_DOCUMENT -> "来源返回空弹幕，可更换来源";
            case INVALID_RESPONSE, AI_INVALID_RESPONSE -> "返回内容无法识别，可更换来源";
            default -> "弹幕加载失败，可重新选择来源或导入本地 XML";
        };
        default -> "尚未加载弹幕，可匹配影片或导入本地 XML";
    };}
    private void render(){
        if(binding==null||!isAdded()||!ready())return;
        int count=0;List<String> selected=new ArrayList<>();
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_TEXT){
            count+=group.length;
            for(int i=0;i<group.length;i++)if(group.isTrackSelected(i)){Format f=group.getTrackFormat(i);selected.add(f.label!=null?f.label:f.language!=null?f.language:"字幕 "+(i+1));}
        }
        MediaItem item=player.getCurrentMediaItem();int external=item==null||item.localConfiguration==null?0:item.localConfiguration.subtitleConfigurations.size();
        binding.subtitleStatus.setText(count==0?"当前播放器未检测到字幕轨。片源可能未附字幕，或此内核未能识别。可导入字幕或尝试其他播放内核。":
                "检测到 "+count+" 条字幕轨"+(external>0?" · 含 "+external+" 条外挂字幕":" · 视频文件内的字幕")+"\n"+(selected.isEmpty()?"字幕已关闭，点击下方选择":"当前："+String.join("、",selected)));
        boolean canOffset=player.getPlayer().isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET);
        binding.subtitleEarlier.setEnabled(canOffset&&count>0);binding.subtitleLater.setEnabled(canOffset&&count>0);binding.subtitleReset.setEnabled(canOffset&&count>0);
        binding.subtitleEarlier.setVisibility(count>0&&canOffset?View.VISIBLE:View.GONE);binding.subtitleLater.setVisibility(count>0&&canOffset?View.VISIBLE:View.GONE);binding.subtitleReset.setVisibility(count>0&&canOffset?View.VISIBLE:View.GONE);
        binding.subtitleOffset.setVisibility(count>0?View.VISIBLE:View.GONE);
        binding.subtitleOffset.setText(canOffset?"字幕同步："+seconds(player.getTextOffsetMs()):"当前内核不支持字幕同步调整");
        String source=player.getSelectedDanmaku().getName();
        if(source.contains("://")||source.startsWith("/"))source=player.getSelectedDanmakuUri()!=null&&"file".equals(player.getSelectedDanmakuUri().getScheme())?"本地 XML":"已选弹幕来源";
        binding.danmakuStatus.setText(statusText(player.getDanmakuStatus())+(source.isEmpty()?"":"\n来源："+source));
        binding.danmakuToggle.setText(player.isDanmakuEnabled()?"弹幕已开启 · 点击隐藏":"弹幕已隐藏 · 点击显示");
        binding.danmakuOffset.setText("弹幕同步："+seconds(DanmakuSetting.getTimeOffsetMs()));
    }
    private String seconds(long value){return String.format(Locale.ROOT,"%+.1f 秒",value/1000f);}
    @Override public void onStart(){super.onStart();handler.post(refresh);}
    @Override public void onStop(){handler.removeCallbacks(refresh);super.onStop();}
    @Override public void onDestroyView(){handler.removeCallbacks(refresh);binding=null;player=null;super.onDestroyView();}
}
