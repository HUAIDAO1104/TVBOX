package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingPlayerBinding;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.impl.UaListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.MpvConfDialog;
import com.fongmi.android.tv.ui.dialog.SpeedDialog;
import com.fongmi.android.tv.ui.dialog.DialogGlass;
import com.fongmi.android.tv.ui.dialog.UaDialog;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DecimalFormat;

public class SettingPlayerFragment extends BaseFragment implements UaListener, SpeedListener {

    private FragmentSettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] background;
    private String[] caption;
    private String[] render;
    private String[] scale;
    private String[] engine;

    public static SettingPlayerFragment newInstance() {
        return new SettingPlayerFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPlayerBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setVisible();
        setPlaybackModeText();
        format = new DecimalFormat("0.#");
        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()) + "×");
        mBinding.adblockText.setChecked(Setting.isAdblock());
        mBinding.mpvVulkanText.setChecked(PlayerSetting.isMpvVulkan());
        mBinding.mpvGpuNextText.setChecked(PlayerSetting.isMpvGpuNext());
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSetting.isCaption() ? 1 : 0]);
        mBinding.backgroundText.setText((background = ResUtil.getStringArray(R.array.select_background))[PlayerSetting.getBackground()]);
    }

    @Override
    protected void initEvent() {
        mBinding.playerToolbar.setNavigationOnClickListener(v -> ((HomeActivity) requireActivity()).change(1));
        mBinding.engine.setOnClickListener(this::setEngine);
        mBinding.mpvConf.setOnClickListener(this::onMpvConf);
        mBinding.mpvGpuNext.setOnClickListener(this::setMpvGpuNext);
        mBinding.mpvVulkan.setOnClickListener(this::setMpvVulkan);
        mBinding.render.setOnClickListener(this::setRender);
        mBinding.scale.setOnClickListener(this::onScale);
        mBinding.caption.setOnClickListener(this::setCaption);
        mBinding.caption.setOnLongClickListener(this::onCaption);
        mBinding.speed.setOnClickListener(this::onSpeed);
        mBinding.background.setOnClickListener(this::onBackground);
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.preload.setOnClickListener(this::onPreload);
        mBinding.decode.setOnClickListener(this::onDecode);
        mBinding.ua.setOnClickListener(this::onUa);
        mBinding.adblockText.setOnClickListener(this::setAdblock);
        mBinding.mpvVulkanText.setOnClickListener(this::setMpvVulkan);
        mBinding.mpvGpuNextText.setOnClickListener(this::setMpvGpuNext);
    }

    private void setVisible() {
        boolean exo = !PlayerSetting.isMpv();
        mBinding.mpvConf.setVisibility(exo ? View.GONE : View.VISIBLE);
        mBinding.mpvVulkan.setVisibility(exo ? View.GONE : View.VISIBLE);
        mBinding.mpvGpuNext.setVisibility(exo ? View.GONE : View.VISIBLE);
        mBinding.decode.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.adblock.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.caption.setVisibility(PlayerSetting.hasCaption() ? View.VISIBLE : View.GONE);
    }

    private void setEngine(View view) {
        DialogGlass.show(new MaterialAlertDialogBuilder(requireActivity()).setTitle("播放内核")
                .setSingleChoiceItems(engine, PlayerSetting.getEngine(), (dialog, which) -> {
                    PlayerSetting.putEngine(which); setPlaybackModeText(); setVisible(); dialog.dismiss();
                }).setNegativeButton("取消", null));
    }

    private void onMpvConf(View view) {
        MpvConfDialog.show(this);
    }

    private void setMpvGpuNext(View view) {
        PlayerSetting.putMpvGpuNext(!PlayerSetting.isMpvGpuNext());
        mBinding.mpvGpuNextText.setChecked(PlayerSetting.isMpvGpuNext());
    }

    private void setMpvVulkan(View view) {
        PlayerSetting.putMpvVulkan(!PlayerSetting.isMpvVulkan());
        mBinding.mpvVulkanText.setChecked(PlayerSetting.isMpvVulkan());
    }

    private void setRender(View view) {
        DialogGlass.show(new MaterialAlertDialogBuilder(requireActivity()).setTitle("画面渲染")
                .setSingleChoiceItems(render, PlayerSetting.getRender(), (dialog, which) -> {
                    PlayerSetting.putRender(which); setPlaybackModeText(); dialog.dismiss();
                }).setNegativeButton("取消", null));
    }

    private void setPlaybackModeText() {
        engine = ResUtil.getStringArray(R.array.select_engine);
        render = ResUtil.getStringArray(R.array.select_render);
        mBinding.engineText.setText(engine[PlayerSetting.getEngine()]);
        mBinding.renderText.setText(render[PlayerSetting.getRender()]);
    }

    private void onScale(View view) {
        DialogGlass.show(new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_scale).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(scale, PlayerSetting.getScale(), (dialog, which) -> {
            mBinding.scaleText.setText(scale[which]);
            PlayerSetting.putScale(which);
            dialog.dismiss();
        }));
    }

    private void setCaption(View view) {
        DialogGlass.show(new MaterialAlertDialogBuilder(requireActivity()).setTitle("字幕样式")
                .setSingleChoiceItems(caption, PlayerSetting.isCaption() ? 1 : 0, (dialog, which) -> {
                    PlayerSetting.putCaption(which == 1); mBinding.captionText.setText(caption[which]); dialog.dismiss();
                }).setNeutralButton("系统字幕设置", (dialog, which) -> startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS)))
                .setNegativeButton("取消", null));
    }

    private boolean onCaption(View view) {
        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSetting.isCaption();
    }

    private void onSpeed(View view) {
        SpeedDialog.show(this);
    }

    @Override
    public void setSpeed(float speed) {
        mBinding.speedText.setText(format.format(speed) + "×");
        PlayerSetting.putSpeed(speed);
    }

    private void onBackground(View view) {
        DialogGlass.show(new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_background).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(background, PlayerSetting.getBackground(), (dialog, which) -> {
            mBinding.backgroundText.setText(background[which]);
            PlayerSetting.putBackground(which);
            dialog.dismiss();
        }));
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setChecked(Setting.isAdblock());
    }

    private void onPreload(View view) {
        ((HomeActivity) requireActivity()).change(4);
    }

    private void onDecode(View view) {
        ((HomeActivity) requireActivity()).change(5);
    }

    private void onUa(View view) {
        UaDialog.show(this);
    }

    @Override
    public void setUa(String ua) {
        Setting.putUa(ua);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}
