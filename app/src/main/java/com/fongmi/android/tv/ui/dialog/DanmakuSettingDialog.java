package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogDanmakuSettingBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

public final class DanmakuSettingDialog {

    private PlayerManager player;

    public static DanmakuSettingDialog create() {
        return new DanmakuSettingDialog();
    }

    public DanmakuSettingDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        for (Fragment fragment : manager.getFragments()) if (fragment instanceof BottomSheet || fragment instanceof SideSheet) return;
        if (Util.isFullscreenLand(activity) || Util.isLeanback()) new SideSheet(player).show(manager, null);
        else new BottomSheet(player).show(manager, null);
    }

    private static DialogDanmakuSettingBinding inflate(LayoutInflater inflater, ViewGroup container) {
        return DialogDanmakuSettingBinding.inflate(inflater, container, false);
    }

    private static void openSearch(FragmentActivity activity, PlayerManager preferred, Runnable dismiss) {
        PlayerManager target = preferred;
        var service = Server.get().getService();
        if (target == null && service != null) target = service.player();
        if (target == null || target.getMetadata() == null) {
            Notify.show(com.fongmi.android.tv.R.string.danmaku_search_no_playback);
            return;
        }
        if (TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl())) {
            Notify.show(com.fongmi.android.tv.R.string.danmaku_search_configure_api);
            return;
        }
        dismiss.run();
        DanmakuSearchDialog.create().player(target).show(activity);
    }

    public static final class BottomSheet extends BaseBottomSheetDialog {

        private DialogDanmakuSettingBinding binding;
        private final PlayerManager player;

        BottomSheet(PlayerManager player) {
            this.player = player;
        }

        @Override
        protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
            return binding = DanmakuSettingDialog.inflate(inflater, container);
        }

        @Override
        protected void initView() {
            new DanmakuSettingPanel(binding, player).bind();
            binding.search.setOnClickListener(view -> openSearch(requireActivity(), player, this::dismissNow));
            if (Util.isLeanback()) binding.search.requestFocus();
        }
    }

    public static final class SideSheet extends BaseSideSheetDialog {

        private DialogDanmakuSettingBinding binding;
        private final PlayerManager player;

        SideSheet(PlayerManager player) {
            this.player = player;
        }

        @Override
        protected int getWidth() {
            return Math.min(ResUtil.dp2px(420), ResUtil.getScreenWidth() / 2);
        }

        @Override
        protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
            return binding = DanmakuSettingDialog.inflate(inflater, container);
        }

        @Override
        protected void initView() {
            new DanmakuSettingPanel(binding, player).bind();
            binding.search.setOnClickListener(view -> openSearch(requireActivity(), player, this::dismissNow));
            if (Util.isLeanback()) binding.search.requestFocus();
        }
    }
}
