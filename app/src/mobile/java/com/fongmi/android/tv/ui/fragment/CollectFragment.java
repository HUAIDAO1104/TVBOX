package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.os.Parcelable;
import android.view.*;
import androidx.annotation.*;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.*;
import com.fongmi.android.tv.databinding.FragmentCollectBinding;
import com.fongmi.android.tv.model.*;
import com.fongmi.android.tv.ui.activity.*;
import com.fongmi.android.tv.ui.adapter.SearchAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.MobileSearchSources;
import com.fongmi.android.tv.ui.search.SearchSourcePreference;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.google.android.material.tabs.TabLayout;
import java.util.*;

public class CollectFragment extends BaseFragment implements SearchAdapter.OnClickListener {
    private FragmentCollectBinding binding;
    private SiteViewModel model;
    private SearchRowsViewModel resultsModel;
    private int tabsGeneration;
    private SearchAdapter adapter;
    private List<Site> sites = List.of();
    private final Map<String, List<Vod>> rows = new LinkedHashMap<>();
    private final Map<String, Parcelable> positions = new HashMap<>();
    private String active = "";
    private boolean updatingTabs;
    private boolean opening;
    private SearchProgress progress = SearchProgress.idle();
    private int renderGeneration;
    public static CollectFragment newInstance(String keyword) {
        CollectFragment f = new CollectFragment(); Bundle b = new Bundle(); b.putString("keyword", keyword); f.setArguments(b); return f;
    }
    private String keyword() { return requireArguments().getString("keyword", ""); }
    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state); if (state != null) active = state.getString("active", "");
    }
    @Override public void onSaveInstanceState(@NonNull Bundle state) { super.onSaveInstanceState(state); state.putString("active", active); }
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) { return binding = FragmentCollectBinding.inflate(inflater, container, false); }
    @Override protected void initView() {
        binding.editQuery.setText(keyword());
        binding.editQuery.setContentDescription("修改搜索词：" + keyword());
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        binding.toolbar.setNavigationContentDescription(R.string.mobile_back);
        binding.toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        binding.editQuery.setOnClickListener(v -> {
            Bundle result = new Bundle(); result.putBoolean("edit", true); getParentFragmentManager().setFragmentResult("result", result);
            getParentFragmentManager().popBackStack();
        });
        adapter = new SearchAdapter(this);
        adapter.setStateRestorationPolicy(androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), getResources().getConfiguration().screenWidthDp >= 600 ? 2 : 1));
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) {
                if (updatingTabs) return;
                positions.put(active, binding.recycler.getLayoutManager().onSaveInstanceState());
                active = Objects.toString(tab.getTag(), "");
                model.prioritizeSearch(site -> site.getKey().equals(active));
                renderRows(true);
            }
            public void onTabUnselected(TabLayout.Tab tab) { }
            public void onTabReselected(TabLayout.Tab tab) { }
        });
        model = new ViewModelProvider(this).get(SiteViewModel.class);
        binding.chooseSource.setOnClickListener(v -> showResultSources());
        binding.sources.setOnClickListener(v -> MobileSearchSources.show(this, () -> startSearch(true)));
        binding.stop.setOnClickListener(v -> model.stopSearch());
        binding.retry.setOnClickListener(v -> model.requestMore(site -> (active.isEmpty() || active.equals(site.getKey())) && model.sourceState(site) != null && model.sourceState(site).failed(), false));
        binding.more.setOnClickListener(v -> model.requestMore(site -> site.getKey().equals(active), true));
        resultsModel = new ViewModelProvider(this).get(SearchRowsViewModel.class);
        resultsModel.rows().observe(getViewLifecycleOwner(), value -> {
            if (value.session() != model.currentSearchSession() || binding == null) return;
            rows.clear(); rows.putAll(value.sources()); updateTabs(); renderRows(false);
        });
        model.getAggregateSearch().observe(getViewLifecycleOwner(), resultsModel::submit);
        // Let navigation paint before building source tabs and starting the search session.
        android.view.View root = binding.getRoot();
        root.post(() -> { if (binding != null && binding.getRoot() == root) startSearch(false); });
        model.getSearchProgress().observe(getViewLifecycleOwner(), value -> { if (value.session() != model.currentSearchSession()) return; progress = value; renderStatus(); });
        model.getSourceProgress().observe(getViewLifecycleOwner(), value -> { updateTabs(); renderStatus(); });
    }
    private void showResultSources() {
        String[] names = new String[sites.size() + 1];
        names[0] = "全部来源 · " + rows.getOrDefault("", List.of()).size() + " 条";
        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            SiteViewModel.SourceState state = model.sourceState(site);
            names[i + 1] = SearchDisplayName.clean(site.getName()) + " · " + rows.getOrDefault(site.getKey(), List.of()).size() + " 条"
                    + (state != null && state.busy() ? " · 搜索中" : state != null && state.failed() ? " · 待重试" : "");
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("切换结果来源")
                .setSingleChoiceItems(names, binding.tabs.getSelectedTabPosition(), (dialog, which) -> {
                    if (binding != null) {
                        TabLayout.Tab tab = binding.tabs.getTabAt(which);
                        if (tab != null) binding.tabs.selectTab(tab);
                        else {
                            positions.put(active, binding.recycler.getLayoutManager().onSaveInstanceState());
                            active = sites.get(which - 1).getKey();
                            model.prioritizeSearch(site -> site.getKey().equals(active)); renderRows(true);
                        }
                    }
                    dialog.dismiss();
                }).setNegativeButton("关闭", null).show();
    }
    private void startSearch(boolean force) {
        Set<String> selected = MobileSearchSources.selected();
        String selection = SearchSourcePreference.serialize(selected);
        sites = VodConfig.get().getSites().stream().filter(Site::isSearchable).filter(site -> SearchSourcePreference.isEnabled(site, selected)).toList();
        if (force) { active = ""; positions.clear(); }
        if (!active.isEmpty() && sites.stream().noneMatch(s -> s.getKey().equals(active))) active = "";
        updatingTabs = true;
        binding.tabs.removeAllTabs();
        binding.tabs.addTab(binding.tabs.newTab().setTag("").setText("全部"), active.isEmpty());
        updatingTabs = false;
        int generation = ++tabsGeneration;
        addSourceTabs(generation, 0);
        if (force || !model.hasSearch(keyword(), selection)) {
            if (!force && model.restoreCachedSearch(keyword(), selected)) { model.rememberSelection(selection); }
            else { model.searchCurrentContent(keyword(), false, s -> SearchSourcePreference.isEnabled(s, selected)); model.rememberSelection(selection); }
        }
        renderStatus();
    }
    private void addSourceTabs(int generation, int from) {
        if (binding == null || generation != tabsGeneration) return;
        updatingTabs = true;
        int end = Math.min(sites.size(), from + 8);
        for (int i = from; i < end; i++) {
            Site site = sites.get(i);
            binding.tabs.addTab(binding.tabs.newTab().setTag(site.getKey()).setText(SearchDisplayName.clean(site.getName())), active.equals(site.getKey()));
        }
        updatingTabs = false;
        updateTabs();
        if (end < sites.size()) binding.tabs.postOnAnimation(() -> addSourceTabs(generation, end));
    }
    private void updateTabs() {
        if (binding == null) return;
        for (int i = 0; i < binding.tabs.getTabCount(); i++) {
            TabLayout.Tab tab = binding.tabs.getTabAt(i);
            String key = Objects.toString(tab.getTag(), "");
            int count = rows.getOrDefault(key, List.of()).size();
            String label = i == 0 ? "全部" : SearchDisplayName.clean(sites.get(i - 1).getName());
            SiteViewModel.SourceState state = i == 0 ? null : model.sourceState(sites.get(i - 1));
            String suffix = state != null && state.busy() ? " · 搜索中" : state != null && state.failed() ? " · 待重试" : "";
            String text = label + " " + count, description = label + "，" + count + " 条结果" + suffix;
            if (!android.text.TextUtils.equals(tab.getText(), text)) tab.setText(text);
            if (!android.text.TextUtils.equals(tab.getContentDescription(), description)) tab.setContentDescription(description);
        }
    }
    private void renderRows(boolean restore) {
        if (binding == null) return;
        String key = active; int generation = ++renderGeneration;
        adapter.setItems(new ArrayList<>(rows.getOrDefault(key, List.of())), () -> {
            if (binding == null || generation != renderGeneration || !active.equals(key)) return;
            if (restore) {
                Parcelable position = positions.get(key);
                if (position != null) binding.recycler.getLayoutManager().onRestoreInstanceState(position);
                else binding.recycler.scrollToPosition(0);
            }
            renderStatus();
        });
    }
    private void renderStatus() {
        if (binding == null || model == null) return;
        int count = rows.getOrDefault("", List.of()).size();
        binding.status.setText(getString(R.string.mobile_results_progress, progress.completed(), progress.total(), count));
        binding.progress.setVisibility(progress.running() ? View.VISIBLE : View.INVISIBLE);
        binding.stop.setVisibility(progress.running() ? View.VISIBLE : View.GONE);
        boolean failed = sites.stream().anyMatch(s -> (active.isEmpty() || active.equals(s.getKey())) && model.sourceState(s) != null && model.sourceState(s).failed());
        boolean busy = sites.stream().anyMatch(s -> (active.isEmpty() || active.equals(s.getKey())) && model.sourceState(s) != null && model.sourceState(s).busy());
        boolean more = !active.isEmpty() && sites.stream().anyMatch(s -> active.equals(s.getKey()) && model.sourceState(s) != null && model.sourceState(s).hasMore() && !model.sourceState(s).busy() && !model.sourceState(s).failed());
        binding.retry.setVisibility(failed ? View.VISIBLE : View.GONE);
        binding.more.setVisibility(more ? View.VISIBLE : View.GONE);
        binding.empty.setVisibility(rows.getOrDefault(active, List.of()).isEmpty() ? View.VISIBLE : View.GONE);
        binding.empty.setText(sites.isEmpty() ? R.string.mobile_no_sources : busy ? R.string.mobile_searching : progress.cancelled() ? R.string.mobile_search_cancelled : failed ? R.string.mobile_results_failed : R.string.mobile_results_empty);
    }
    @Override public void onItemClick(Vod item) {
        if (opening) return;
        opening = true;
        if (item.isFolder()) FolderActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), item.getPic());
    }
    @Override public void onResume() { super.onResume(); opening = false; }
    @Override public void onDestroyView() {
        super.onDestroyView();
        // Rotation retains the ViewModel/session. Leaving the result route cancels its work.
        if (!requireActivity().isChangingConfigurations()) model.stopSearch();
        tabsGeneration++;
        binding = null;
    }
}
