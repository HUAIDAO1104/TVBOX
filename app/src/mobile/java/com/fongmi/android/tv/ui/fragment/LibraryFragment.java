package com.fongmi.android.tv.ui.fragment;

import android.view.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.*;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.databinding.FragmentLibraryBinding;
import com.fongmi.android.tv.ui.activity.*;
import com.fongmi.android.tv.ui.adapter.*;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.google.android.material.tabs.TabLayout;

public class LibraryFragment extends BaseFragment {
    private FragmentLibraryBinding binding;
    private HistoryAdapter history;
    private KeepAdapter keeps;
    private WatchlistAdapter following;
    private int selected = 2;
    private int generation;
    private boolean openingKeep;
    private final android.os.Parcelable[] positions = new android.os.Parcelable[3];
    @Override public void onCreate(@Nullable android.os.Bundle state) { super.onCreate(state); if (state != null) { selected = state.getInt("selected", 2); positions[2] = state.getParcelable("watchPosition"); positions[0] = state.getParcelable("historyPosition"); positions[1] = state.getParcelable("keepPosition"); } }
    @Override public void onSaveInstanceState(@NonNull android.os.Bundle state) { super.onSaveInstanceState(state); state.putInt("selected", selected); if (binding != null) positions[selected] = binding.recycler.getLayoutManager().onSaveInstanceState(); state.putParcelable("historyPosition", positions[0]); state.putParcelable("keepPosition", positions[1]); state.putParcelable("watchPosition", positions[2]); }
    public static LibraryFragment newInstance() { return new LibraryFragment(); }
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = FragmentLibraryBinding.inflate(inflater, container, false);
    }
    @Override protected void initView() {
        following = new WatchlistAdapter(e -> WatchlistActivity.open(requireActivity(), e),
                e -> WatchlistActivity.manage(requireActivity(), e, this::reload));
        history = new HistoryAdapter(new HistoryAdapter.OnClickListener() {
            public void onItemClick(History item) { VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic()); }
            public void onItemDelete(History item) { }
            public boolean onLongClick() { HistoryActivity.start(requireActivity()); return true; }
        });
        keeps = new KeepAdapter(new KeepAdapter.OnClickListener() {
            public void onItemClick(Keep item) { openKeep(item); }
            public void onItemDelete(Keep item) { }
            public boolean onLongClick() { KeepActivity.start(requireActivity()); return true; }
        });
        history.setStateRestorationPolicy(androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        keeps.setStateRestorationPolicy(androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        history.setSize(Product.getSpec(requireContext()));
        keeps.setSize(Product.getSpec(requireContext()));
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), getResources().getConfiguration().screenWidthDp / getResources().getConfiguration().fontScale >= 700 ? 2 : 1));
        binding.recycler.setItemAnimator(null);
        binding.tabs.addTab(binding.tabs.newTab().setText("在追").setTag(2));
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.mobile_history).setTag(0));
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.mobile_keep).setTag(1));
        binding.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) { int destination = (Integer) tab.getTag(); if (selected != destination) positions[selected] = binding.recycler.getLayoutManager().onSaveInstanceState(); selected = destination; reload(); binding.recycler.getLayoutManager().onRestoreInstanceState(positions[selected]); }
            public void onTabUnselected(TabLayout.Tab tab) { }
            public void onTabReselected(TabLayout.Tab tab) { }
        });
        for (int i = 0; i < binding.tabs.getTabCount(); i++) {
            var tab = binding.tabs.getTabAt(i);
            if (tab != null && Integer.valueOf(selected).equals(tab.getTag())) { tab.select(); break; }
        }
        binding.sync.setOnClickListener(v -> HistoryActivity.startSync(requireActivity()));
        com.fongmi.android.tv.watch.WatchlistJob.schedule(requireContext());
        binding.browse.setOnClickListener(v -> ((HomeActivity) requireActivity()).change(0));
        binding.manage.setOnClickListener(v -> { if (selected == 2) WatchlistActivity.start(requireContext()); else if (selected == 0) HistoryActivity.start(requireActivity()); else KeepActivity.start(requireActivity()); });
        reload();
        binding.recycler.getLayoutManager().onRestoreInstanceState(positions[selected]);
    }
    private void openKeep(Keep item) {
        if (openingKeep) return;
        Config config = Config.find(item.getCid());
        if (config == null) { SearchActivity.start(requireActivity(), item.getVodName()); return; }
        if (item.getCid() == com.fongmi.android.tv.api.config.VodConfig.getCid()) {
            VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            return;
        }
        openingKeep = true;
        com.fongmi.android.tv.api.config.VodConfig.load(config, new com.fongmi.android.tv.impl.Callback() {
            @Override public void success() {
                openingKeep = false;
                if (isAdded() && !isHidden()) VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }
            @Override public void error(String message) { openingKeep = false; com.fongmi.android.tv.utils.Notify.show(message); }
        });
    }
    private void reload() {
        if (binding == null || history == null) return;
        var adapter = selected == 2 ? following : selected == 0 ? history : keeps;
        if (binding.recycler.getAdapter() != adapter) binding.recycler.setAdapter(adapter);
        if (selected == 2) {
            generation++;
            var entries = com.fongmi.android.tv.watch.WatchlistStore.get(requireContext()).followed();
            following.setItems(entries);
            binding.empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            binding.emptyTitle.setText("还没有追剧");
            binding.hint.setText("在影片详情点「追剧」，这里就会显示已看集数和新集更新。");
            binding.manage.setVisibility(View.VISIBLE);
            return;
        }
        binding.emptyTitle.setText(R.string.mobile_library_empty);
        binding.hint.setText(selected == 0 ? R.string.mobile_history_hint : R.string.mobile_keep_hint);
        int tab = selected, request = ++generation;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> tab == 0 ? History.get() : Keep.getVod())
            .thenAccept(items -> App.post(() -> {
                if (binding == null || tab != selected || request != generation || !isAdded()) return;
                binding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                binding.manage.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                if (tab == 0) history.setItems((java.util.List<History>) (java.util.List<?>) items);
                else keeps.setItems((java.util.List<Keep>) (java.util.List<?>) items);
            }));
    }
    @Override public void onConfigurationChanged(@NonNull android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (binding == null) return;
        ((GridLayoutManager) binding.recycler.getLayoutManager()).setSpanCount(getResources().getConfiguration().screenWidthDp / getResources().getConfiguration().fontScale >= 700 ? 2 : 1);
        history.setSize(Product.getSpec(requireContext())); keeps.setSize(Product.getSpec(requireContext()));
        android.os.Parcelable position = binding.recycler.getLayoutManager().onSaveInstanceState();
        binding.recycler.setAdapter(selected == 2 ? following : selected == 0 ? history : keeps);
        binding.recycler.getLayoutManager().onRestoreInstanceState(position);
    }
    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void onHistoryChanged(com.fongmi.android.tv.event.RefreshEvent event) {
        if (!isHidden() && event.getType() == com.fongmi.android.tv.event.RefreshEvent.Type.HISTORY) reload();
    }
    @Override public void onStart() { super.onStart(); org.greenrobot.eventbus.EventBus.getDefault().register(this); }
    @Override public void onStop() { org.greenrobot.eventbus.EventBus.getDefault().unregister(this); super.onStop(); }
    @Override public void onResume() { super.onResume(); reload(); }
    @Override public void onHiddenChanged(boolean hidden) { super.onHiddenChanged(hidden); if (!hidden) reload(); }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
