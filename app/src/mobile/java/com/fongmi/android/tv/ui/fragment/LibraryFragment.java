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
    private int selected;
    private int generation;
    private boolean openingKeep;
    private final android.os.Parcelable[] positions = new android.os.Parcelable[2];
    @Override public void onCreate(@Nullable android.os.Bundle state) { super.onCreate(state); if (state != null) { selected = state.getInt("selected"); positions[0] = state.getParcelable("historyPosition"); positions[1] = state.getParcelable("keepPosition"); } }
    @Override public void onSaveInstanceState(@NonNull android.os.Bundle state) { super.onSaveInstanceState(state); state.putInt("selected", selected); if (binding != null) positions[selected] = binding.recycler.getLayoutManager().onSaveInstanceState(); state.putParcelable("historyPosition", positions[0]); state.putParcelable("keepPosition", positions[1]); }
    public static LibraryFragment newInstance() { return new LibraryFragment(); }
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = FragmentLibraryBinding.inflate(inflater, container, false);
    }
    @Override protected void initView() {
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
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), Product.getColumn(requireContext())));
        binding.recycler.setItemAnimator(null);
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.mobile_history));
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.mobile_keep));
        binding.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) { if (selected != tab.getPosition()) positions[selected] = binding.recycler.getLayoutManager().onSaveInstanceState(); selected = tab.getPosition(); reload(); binding.recycler.getLayoutManager().onRestoreInstanceState(positions[selected]); }
            public void onTabUnselected(TabLayout.Tab tab) { }
            public void onTabReselected(TabLayout.Tab tab) { }
        });
        binding.tabs.selectTab(binding.tabs.getTabAt(selected));
        binding.browse.setOnClickListener(v -> ((HomeActivity) requireActivity()).change(0));
        binding.manage.setOnClickListener(v -> { if (selected == 0) HistoryActivity.start(requireActivity()); else KeepActivity.start(requireActivity()); });
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
        if (binding.recycler.getAdapter() != (selected == 0 ? history : keeps)) binding.recycler.setAdapter(selected == 0 ? history : keeps);
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
        ((GridLayoutManager) binding.recycler.getLayoutManager()).setSpanCount(Product.getColumn(requireContext()));
        history.setSize(Product.getSpec(requireContext())); keeps.setSize(Product.getSpec(requireContext()));
        android.os.Parcelable position = binding.recycler.getLayoutManager().onSaveInstanceState();
        binding.recycler.setAdapter(selected == 0 ? history : keeps);
        binding.recycler.getLayoutManager().onRestoreInstanceState(position);
    }
    @Override public void onResume() { super.onResume(); reload(); }
    @Override public void onHiddenChanged(boolean hidden) { super.onHiddenChanged(hidden); if (!hidden) reload(); }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
