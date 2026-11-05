package com.fongmi.android.tv.ui.dialog;

import android.view.View;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.*;
import androidx.media3.ui.PlayerView;
import androidx.media3.exoplayer.offline.Download;
import com.fongmi.android.tv.databinding.DialogPlaybackAccessBinding;
import com.fongmi.android.tv.offline.*;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.ui.activity.OfflineDownloadsActivity;
import com.google.android.material.bottomsheet.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** Same mobile panel for cache-only playback; matching never makes an offline network request. */
public final class OfflineAccessDialog {
    public static BottomSheetDialog show(FragmentActivity activity,Player player,PlayerView view,OfflineStore store,Download download,boolean enabled,java.util.function.Consumer<Boolean> toggle){
        var binding=DialogPlaybackAccessBinding.inflate(activity.getLayoutInflater());
        BottomSheetDialog dialog=new BottomSheetDialog(activity);dialog.setContentView(binding.getRoot());
        binding.close.setOnClickListener(v->dialog.dismiss());binding.danmakuSearch.setVisibility(View.GONE);binding.danmakuStyle.setVisibility(View.GONE);
        binding.danmakuStatus.setText(store.danmaku(download.request.id).isFile()?"来源：已保存的本地 XML\n断网时也可显示":"此下载尚无本地弹幕，可到下载管理导入 XML");
        binding.danmakuChoose.setText("管理离线弹幕与字幕");binding.danmakuChoose.setOnClickListener(v->{dialog.dismiss();OfflineDownloadsActivity.start(activity);});
        boolean[] visible={enabled};binding.danmakuToggle.setText(enabled?"弹幕已开启 · 点击隐藏":"弹幕已隐藏 · 点击显示");
        binding.danmakuToggle.setOnClickListener(v->{visible[0]=!visible[0];toggle.accept(visible[0]);binding.danmakuToggle.setText(visible[0]?"弹幕已开启 · 点击隐藏":"弹幕已隐藏 · 点击显示");});
        List<TrackSelectionOverride> overrides=new ArrayList<>();List<String> labels=new ArrayList<>();labels.add("关闭字幕");int selected=0;
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<group.length;i++){
            Format format=group.getTrackFormat(i);labels.add(format.label!=null?format.label:format.language!=null?format.language:"字幕 "+labels.size());
            overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(),i));if(group.isTrackSelected(i))selected=labels.size()-1;
        }
        binding.subtitleStatus.setText(overrides.isEmpty()?"本地视频未检测到字幕轨\n可到下载管理补存此片源提供的外挂字幕。":"本地字幕："+overrides.size()+" 条\n当前："+labels.get(selected));
        binding.subtitleChoose.setText("选择本地字幕轨");binding.subtitleChoose.setEnabled(!overrides.isEmpty());int selection=selected;
        binding.subtitleChoose.setOnClickListener(v->new MaterialAlertDialogBuilder(activity).setTitle("本地字幕").setSingleChoiceItems(labels.toArray(new String[0]),selection,(d,which)->{
            var parameters=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,which==0);
            if(which>0)parameters.setOverrideForType(overrides.get(which-1));player.setTrackSelectionParameters(parameters.build());binding.subtitleStatus.setText("本地字幕："+overrides.size()+" 条\n当前："+labels.get(which));d.dismiss();
        }).setNegativeButton("关闭",null).show());
        Runnable refresh=()->{binding.subtitleOffset.setText(String.format(Locale.ROOT,"字幕同步：%+.1f 秒",player.getTextOffsetMs()/1000f));binding.danmakuOffset.setText(String.format(Locale.ROOT,"弹幕同步：%+.1f 秒",DanmakuSetting.getTimeOffsetMs()/1000f));};
        boolean canText=!overrides.isEmpty()&&player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET);
        for(View button:List.of(binding.subtitleEarlier,binding.subtitleLater,binding.subtitleReset,binding.subtitleOffset))button.setVisibility(canText?View.VISIBLE:View.GONE);
        binding.subtitleEarlier.setOnClickListener(v->{player.setTextOffsetMs(Math.max(-600000,player.getTextOffsetMs()-500));refresh.run();});binding.subtitleLater.setOnClickListener(v->{player.setTextOffsetMs(Math.min(600000,player.getTextOffsetMs()+500));refresh.run();});binding.subtitleReset.setOnClickListener(v->{player.setTextOffsetMs(0);refresh.run();});
        binding.danmakuEarlier.setOnClickListener(v->{DanmakuSetting.putTimeOffsetMs(DanmakuSetting.getTimeOffsetMs()-500);view.setDanmakuConfig(DanmakuSetting.getConfig());refresh.run();});binding.danmakuLater.setOnClickListener(v->{DanmakuSetting.putTimeOffsetMs(DanmakuSetting.getTimeOffsetMs()+500);view.setDanmakuConfig(DanmakuSetting.getConfig());refresh.run();});binding.danmakuReset.setOnClickListener(v->{DanmakuSetting.putTimeOffsetMs(0);view.setDanmakuConfig(DanmakuSetting.getConfig());refresh.run();});refresh.run();
        dialog.setOnShowListener(d->{android.widget.FrameLayout sheet=dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);if(sheet!=null){sheet.getLayoutParams().height=(int)(activity.getResources().getDisplayMetrics().heightPixels*.82);sheet.requestLayout();BottomSheetBehavior.from(sheet).setState(BottomSheetBehavior.STATE_EXPANDED);}});dialog.show();return dialog;
    }
}
