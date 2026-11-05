package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.text.InputType;
import android.widget.*;
import androidx.media3.common.Player;
import com.fongmi.android.tv.player.preview.ScrubPreview;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class PreciseSeekDialog {
    public static long parse(String text,long duration){
        String[] parts=text.trim().split(":",-1);if(parts.length<1||parts.length>3)throw new IllegalArgumentException();
        long seconds=0;for(int i=0;i<parts.length;i++){if(!parts[i].matches("[0-9]{1,8}"))throw new IllegalArgumentException();long number=Long.parseLong(parts[i]);if(i>0&&number>=60)throw new IllegalArgumentException();seconds=seconds*60+number;}
        long result=seconds*1000;if(duration<=0||result<0||result>duration)throw new IllegalArgumentException();return result;
    }
    public static void show(Activity activity,Player player){
        if(player==null||player.getDuration()<=0||!player.isCurrentMediaItemSeekable()){Toast.makeText(activity,"当前视频暂不支持定位",Toast.LENGTH_SHORT).show();return;}
        long duration=player.getDuration();androidx.media3.common.MediaItem item=player.getCurrentMediaItem();
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);int pad=(int)(24*activity.getResources().getDisplayMetrics().density);content.setPadding(pad,0,pad,0);
        TextView hint=new TextView(activity);hint.setText("输入 分:秒 或 时:分:秒，最长 "+ScrubPreview.time(duration));hint.setTextSize(14);content.addView(hint);
        EditText input=new EditText(activity);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_TIME);input.setText(ScrubPreview.time(player.getCurrentPosition()));input.setContentDescription("跳转时间");content.addView(input);
        LinearLayout steps=new LinearLayout(activity);for(int delta:new int[]{-10,-1,1,10}){com.google.android.material.button.MaterialButton b=new com.google.android.material.button.MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);b.setText((delta>0?"+":"")+delta+"秒");b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);steps.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{try{long value=parse(input.getText().toString(),duration);input.setText(ScrubPreview.time(Math.max(0,Math.min(duration,value+delta*1000L))));input.setError(null);}catch(IllegalArgumentException e){input.setError("请输入有效时间");}});}content.addView(steps);
        var dialog=new MaterialAlertDialogBuilder(activity).setTitle("精确定位").setView(content).setNegativeButton("取消",null).setPositiveButton("跳转",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{long value=parse(input.getText().toString(),duration);if(!java.util.Objects.equals(item,player.getCurrentMediaItem())){dialog.dismiss();return;}player.seekTo(value);dialog.dismiss();}catch(IllegalArgumentException e){input.setError("时间须在 00:00 到 "+ScrubPreview.time(duration)+" 之间");}}));dialog.show();
    }
}
