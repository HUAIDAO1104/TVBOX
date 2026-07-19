package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingDanmakuBinding;
import com.fongmi.android.tv.impl.DanmakuListener;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.FocusSafeSettingsActivity;
import com.fongmi.android.tv.ui.dialog.DanmakuApiDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuSettingDialog;
import com.fongmi.android.tv.utils.Notify;

public class SettingDanmakuActivity extends FocusSafeSettingsActivity implements DanmakuListener {

    private ActivitySettingDanmakuBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingDanmakuActivity.class));
    }

    private String getApiStatus() {
        return getString(TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl()) ? R.string.none : R.string.yes);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingDanmakuBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.danmakuApiText.setText(getApiStatus());
        mBinding.danmakuAutoText.setText(Setting.getSwitch(DanmakuSetting.isAuto()));
        mBinding.danmakuLoadText.setText(Setting.getSwitch(DanmakuSetting.isLoad()));
        mBinding.danmakuSpiderText.setText(Setting.getSwitch(DanmakuSetting.isSpiderFirst()));
        updateApiVisibility();
        initSettingsFocus(savedInstanceState, R.id.danmakuLoad);
    }

    @Override
    protected void initEvent() {
        mBinding.danmakuApi.setOnClickListener(this::onDanmakuApi);
        mBinding.danmakuDetail.setOnClickListener(view -> DanmakuSettingDialog.create().show(this));
        mBinding.danmakuSearch.setOnClickListener(this::onDanmakuSearch);
        mBinding.danmakuAuto.setOnClickListener(this::setDanmakuAuto);
        mBinding.danmakuLoad.setOnClickListener(this::setDanmakuLoad);
        mBinding.danmakuSpider.setOnClickListener(this::setDanmakuSpider);
    }

    private void setDanmakuLoad(View view) {
        DanmakuSetting.putLoad(!DanmakuSetting.isLoad());
        mBinding.danmakuLoadText.setText(Setting.getSwitch(DanmakuSetting.isLoad()));
        updateApiVisibility();
    }

    private void updateApiVisibility() {
        boolean load = DanmakuSetting.isLoad();
        mBinding.danmakuApi.setVisibility(load ? View.VISIBLE : View.GONE);
        // Manual matching is a recovery tool, so it must remain discoverable even when automatic
        // loading or the API is not configured yet. Clicking it guides the user to the missing
        // prerequisite instead of silently removing the entry.
        mBinding.danmakuSearch.setVisibility(View.VISIBLE);
        mBinding.danmakuSearch.setAlpha(load ? 1f : 0.62f);
        updateAutoVisibility();
    }

    private void onDanmakuSearch(View view) {
        if (!DanmakuSetting.isLoad()) {
            Notify.show(R.string.danmaku_search_enable_first);
            return;
        }
        if (TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl())) {
            DanmakuApiDialog.show(this);
            return;
        }
        var service = Server.get().getService();
        if (service == null || service.player() == null || service.player().getMetadata() == null) {
            Notify.show(R.string.danmaku_search_no_playback);
            return;
        }
        DanmakuSettingDialog.create().player(service.player()).search(true).show(this);
    }

    private void updateAutoVisibility() {
        boolean show = DanmakuSetting.isLoad() && !TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl());
        mBinding.danmakuAuto.setVisibility(show ? View.VISIBLE : View.GONE);
        updateSpiderVisibility();
    }

    private void updateSpiderVisibility() {
        boolean show = DanmakuSetting.isLoad() && !TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl()) && DanmakuSetting.isAuto();
        mBinding.danmakuSpider.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void onDanmakuApi(View view) {
        DanmakuApiDialog.show(this);
    }

    @Override
    public void setDanmakuApi(String url) {
        DanmakuSetting.putApiUrl(url);
        mBinding.danmakuApiText.setText(getApiStatus());
        updateAutoVisibility();
    }

    private void setDanmakuAuto(View view) {
        DanmakuSetting.putAuto(!DanmakuSetting.isAuto());
        mBinding.danmakuAutoText.setText(Setting.getSwitch(DanmakuSetting.isAuto()));
        updateSpiderVisibility();
    }

    private void setDanmakuSpider(View view) {
        DanmakuSetting.putSpiderFirst(!DanmakuSetting.isSpiderFirst());
        mBinding.danmakuSpiderText.setText(Setting.getSwitch(DanmakuSetting.isSpiderFirst()));
    }
}
