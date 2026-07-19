package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
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
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

public final class DanmakuSettingDialog {

    private PlayerManager player;
    private boolean searchInitially;

    public static DanmakuSettingDialog create() {
        return new DanmakuSettingDialog();
    }

    public DanmakuSettingDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public DanmakuSettingDialog search(boolean value) {
        this.searchInitially = value;
        return this;
    }

    public void show(FragmentActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        for (Fragment fragment : manager.getFragments()) if (fragment instanceof BottomSheet || fragment instanceof SideSheet) return;
        PlayerManager target = resolvePlayer(player);
        if (Util.isFullscreenLand(activity) || Util.isLeanback()) new SideSheet(target, searchInitially).show(manager, null);
        else new BottomSheet(target, searchInitially).show(manager, null);
    }

    private static DialogDanmakuSettingBinding inflate(LayoutInflater inflater, ViewGroup container) {
        return DialogDanmakuSettingBinding.inflate(inflater, container, false);
    }

    private static PlayerManager resolvePlayer(PlayerManager preferred) {
        PlayerManager target = preferred;
        var service = Server.get().getService();
        if (target == null && service != null) target = service.player();
        return target;
    }

    public static final class BottomSheet extends BaseBottomSheetDialog {

        private DialogDanmakuSettingBinding binding;
        private final PlayerManager player;
        private final boolean searchInitially;
        private DanmakuSearchPanel searchPanel;

        BottomSheet(PlayerManager player, boolean searchInitially) {
            this.player = player;
            this.searchInitially = searchInitially;
        }

        @Override
        protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
            return binding = DanmakuSettingDialog.inflate(inflater, container);
        }

        @Override
        protected void initView() {
            new DanmakuSettingPanel(binding, player).bind();
            if (player != null && player.getMetadata() != null) {
                searchPanel = new DanmakuSearchPanel(binding.searchPane, player);
                searchPanel.bind();
            }
            binding.search.setOnClickListener(view -> showSearch());
            if (searchInitially) showSearch();
            else if (Util.isLeanback()) binding.search.requestFocus();
        }

        private void showSearch() {
            if (searchPanel == null) {
                Notify.show(com.fongmi.android.tv.R.string.danmaku_search_no_playback);
                return;
            }
            // Portrait phones keep the same dialog instance and dedicate its surface to search.
            binding.settingsPane.setVisibility(View.GONE);
            binding.searchDivider.setVisibility(View.GONE);
            searchPanel.show(true);
        }

        @Override
        public void onDestroyView() {
            if (searchPanel != null) searchPanel.destroy();
            super.onDestroyView();
        }
    }

    public static final class SideSheet extends BaseSideSheetDialog {

        private DialogDanmakuSettingBinding binding;
        private final PlayerManager player;
        private final boolean searchInitially;
        private DanmakuSearchPanel searchPanel;

        SideSheet(PlayerManager player, boolean searchInitially) {
            this.player = player;
            this.searchInitially = searchInitially;
        }

        @Override
        protected int getWidth() {
            return Math.min(ResUtil.dp2px(1180), Math.round(ResUtil.getScreenWidth() * 0.88f));
        }

        @Override
        protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
            return binding = DanmakuSettingDialog.inflate(inflater, container);
        }

        @Override
        protected void initView() {
            new DanmakuSettingPanel(binding, player).bind();
            if (player != null && player.getMetadata() != null) {
                searchPanel = new DanmakuSearchPanel(binding.searchPane, player);
                searchPanel.bind();
            }
            binding.search.setOnClickListener(view -> showSearch());
            if (searchInitially) showSearch();
            else if (Util.isLeanback()) binding.search.requestFocus();
        }

        private void showSearch() {
            if (searchPanel == null) {
                Notify.show(com.fongmi.android.tv.R.string.danmaku_search_no_playback);
                return;
            }
            binding.searchDivider.setVisibility(View.VISIBLE);
            searchPanel.show(true);
        }

        @Override
        public void onDestroyView() {
            if (searchPanel != null) searchPanel.destroy();
            super.onDestroyView();
        }
    }
}
