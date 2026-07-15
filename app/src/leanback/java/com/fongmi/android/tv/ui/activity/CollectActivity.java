package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SearchProgress;
import com.fongmi.android.tv.model.SearchSnapshot;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.playback.vod.DetailSourceFallbackPolicy;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.SearchSourcePanelAdapter;
import com.fongmi.android.tv.ui.adapter.SearchWorkAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.search.SearchAggregator;
import com.fongmi.android.tv.ui.search.SearchRelevance;
import com.fongmi.android.tv.ui.search.SearchSource;
import com.fongmi.android.tv.ui.search.SearchSourcePreference;
import com.fongmi.android.tv.ui.search.SearchSourceHealthStore;
import com.fongmi.android.tv.ui.search.SearchWork;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.gson.reflect.TypeToken;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CollectActivity extends BaseActivity implements SearchWorkAdapter.Listener, SearchSourcePanelAdapter.Listener {

    // Five columns keep 3:4 posters readable at TV distance while retaining two visible rows.
    private static final int COLUMN_COUNT = 5;
    private static final String STATE_WORK_ID = "aggregate_search_work_id";
    private static final String STATE_SOURCE_ID = "aggregate_search_source_id";
    private static final String STATE_LAYOUT = "aggregate_search_layout";
    private static final Pattern EPISODE_COUNT = Pattern.compile("(?:全|更新至|至)?\\s*(\\d{1,4})\\s*(?:集|期)");

    private ActivityCollectBinding binding;
    private SiteViewModel viewModel;
    private SearchWorkAdapter workAdapter;
    private SearchSourcePanelAdapter sourceAdapter;
    private SearchAggregator aggregator;
    private SearchProgress progress = SearchProgress.idle();
    private final Map<String, Vod> vodBySource = new LinkedHashMap<>();
    private String selectedWorkId;
    private String selectedSourceId;
    private String panelWorkId;
    private String pendingRestoreWorkId;
    private String pendingRestoreSourceId;
    private Parcelable pendingLayoutState;

    private enum SearchUiState {
        IDLE, SEARCHING, PARTIAL_SUCCESS, SUCCESS, EMPTY, CANCELLED, ERROR
    }

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        String keyword = getIntent().getStringExtra("keyword");
        return keyword == null ? "" : keyword.trim();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        pendingRestoreWorkId = savedInstanceState == null ? null : savedInstanceState.getString(STATE_WORK_ID);
        pendingRestoreSourceId = savedInstanceState == null ? null : savedInstanceState.getString(STATE_SOURCE_ID);
        pendingLayoutState = savedInstanceState == null ? null : savedInstanceState.getParcelable(STATE_LAYOUT);
        setupLists();
        setupViewModel();
        saveKeyword();
        resetAndSearch(false);
        binding.back.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.back.setOnClickListener(view -> finish());
        binding.stop.setOnClickListener(view -> {
            if (progress.running()) viewModel.stopSearch();
            else resetAndSearch(true);
        });
        binding.retry.setOnClickListener(view -> resetAndSearch(true));
        binding.sourceClose.setOnClickListener(view -> closeSourcePanel());
        binding.sourceFilter.setOnClickListener(view -> showSourceFilter());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        viewModel.stopSearch();
        setIntent(intent);
        saveKeyword();
        pendingRestoreWorkId = null;
        pendingRestoreSourceId = null;
        pendingLayoutState = null;
        resetAndSearch(false);
        binding.back.requestFocus();
    }

    private void setupLists() {
        ViewGroup.LayoutParams sourcePanelParams = binding.sourcePanel.getLayoutParams();
        sourcePanelParams.width = Math.min(ResUtil.dp2px(360), Math.round(ResUtil.getScreenWidth(this) * 0.30f));
        binding.sourcePanel.setLayoutParams(sourcePanelParams);
        binding.resultRecycler.setHasFixedSize(true);
        binding.resultRecycler.setPreserveFocusAfterLayout(true);
        binding.resultRecycler.setItemAnimator(null);
        binding.resultRecycler.setLayoutManager(new GridLayoutManager(this, COLUMN_COUNT));
        binding.resultRecycler.addItemDecoration(new SpaceItemDecoration(COLUMN_COUNT, 12));
        binding.resultRecycler.setItemViewCacheSize(COLUMN_COUNT * 2);
        binding.resultRecycler.setAdapter(workAdapter = new SearchWorkAdapter(this, COLUMN_COUNT));

        binding.sourceRecycler.setItemAnimator(null);
        binding.sourceRecycler.setPreserveFocusAfterLayout(true);
        binding.sourceRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.sourceRecycler.setAdapter(sourceAdapter = new SearchSourcePanelAdapter(this));
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        viewModel.getAggregateSearch().observe(this, this::applySearchSnapshot);
        viewModel.getSearchProgress().observe(this, value -> {
            if (value == null) return;
            progress = value;
            renderState(resolveState(value));
            restoreFocusIfPossible();
        });
    }

    private void resetAndSearch(boolean focusStop) {
        viewModel.stopSearch();
        closeSourcePanel(false);
        aggregator = new SearchAggregator(getKeyword());
        vodBySource.clear();
        selectedWorkId = null;
        selectedSourceId = null;
        workAdapter.submit(List.of());
        progress = SearchProgress.idle();
        binding.result.setText(getString(R.string.collect_result, getKeyword()));
        updateSourceFilterLabel();
        renderState(SearchUiState.IDLE);
        Set<String> selectedSources = SearchSourcePreference.parse(Setting.getSearchSources());
        viewModel.searchAllContent(getKeyword(), false,
                site -> SearchSourcePreference.isEnabled(site, selectedSources));
        if (focusStop) binding.stop.post(binding.stop::requestFocus);
    }

    private void showSourceFilter() {
        List<String> choices = SearchSourcePreference.choices(VodConfig.get().getSites());
        String[] displayChoices = choices.stream().map(source -> {
            if (SearchSourcePreference.ALL_OTHER_SOURCES.equals(source)) {
                return getString(R.string.search_v2_source_filter_other);
            }
            return SearchSourcePreference.isFourKDefault(source) ? source + " · 4K" : source;
        }).toArray(String[]::new);
        Set<String> pending = new LinkedHashSet<>(SearchSourcePreference.parse(Setting.getSearchSources()));
        boolean[] checked = new boolean[choices.size()];
        for (int index = 0; index < choices.size(); index++) checked[index] = pending.contains(choices.get(index));
        AlertDialog alert = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.search_v2_source_filter_title)
                .setMultiChoiceItems(displayChoices, checked, (dialog, which, enabled) -> {
                    if (enabled) pending.add(choices.get(which));
                    else pending.remove(choices.get(which));
                })
                .setNeutralButton(R.string.search_v2_source_filter_default, (dialog, which) -> {
                    Setting.putSearchSources(SearchSourcePreference.serialize(
                            new LinkedHashSet<>(SearchSourcePreference.DEFAULT_SOURCES)));
                    resetAndSearch(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.search_v2_source_filter_apply, (dialog, which) -> {
                    Setting.putSearchSources(SearchSourcePreference.serialize(pending));
                    resetAndSearch(true);
                })
                .create();
        alert.setOnShowListener(ignored -> compactSourceFilterRows(alert.getListView()));
        alert.show();
    }

    private void compactSourceFilterRows(ListView listView) {
        if (listView == null) return;
        listView.setDividerHeight(0);
        // Android TV's dialog theme expands choice rows to 48dp; six rows then
        // hide the non-default entry behind scrolling on 1080p. A compact
        // 38dp focus row keeps all five defaults plus "other" discoverable.
        int rowHeight = ResUtil.dp2px(38);
        listView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                child.setMinimumHeight(rowHeight);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null) {
                    params.height = rowHeight;
                    child.setLayoutParams(params);
                }
                if (child instanceof TextView) {
                    ((TextView) child).setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        listView.post(() -> {
            for (int index = 0; index < listView.getChildCount(); index++) {
                View child = listView.getChildAt(index);
                child.setMinimumHeight(rowHeight);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null) {
                    params.height = rowHeight;
                    child.setLayoutParams(params);
                }
                if (child instanceof TextView) {
                    ((TextView) child).setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                }
            }
        });
    }

    private void updateSourceFilterLabel() {
        int count = SearchSourcePreference.parse(Setting.getSearchSources()).size();
        binding.sourceFilter.setText(getString(R.string.search_v2_source_filter_count, count));
    }

    private void applySearchSnapshot(SearchSnapshot snapshot) {
        if (snapshot == null || aggregator == null) return;
        SearchAggregator rebuilt = new SearchAggregator(getKeyword());
        Map<String, Vod> rebuiltSources = new LinkedHashMap<>();
        SearchRelevance relevance = new SearchRelevance();
        for (Result result : snapshot.results()) {
            List<Vod> items = new ArrayList<>(result.getList());
            items.sort(Comparator.comparingInt((Vod vod) -> relevance.score(getKeyword(), vod.getName())).reversed());
            for (Vod vod : items) {
                SearchSource source = sourceFrom(vod);
                SearchAggregator.Update update = rebuilt.add(source);
                if (update.changed()) rebuiltSources.put(source.stableId(), vod);
            }
        }
        aggregator = rebuilt;
        vodBySource.clear();
        vodBySource.putAll(rebuiltSources);
        submitWorks();
    }

    private SearchSource sourceFrom(Vod vod) {
        Site site = vod.getSite() == null ? new Site() : vod.getSite();
        SearchSourceHealthStore.Snapshot health = SearchSourceHealthStore.get().snapshot(site.getKey());
        String scope = site.getRepositoryId() == 0
                ? "current:" + site.getConfigUrl()
                : String.valueOf(site.getRepositoryId());
        String config = site.getConfigName().isEmpty() ? getString(R.string.search_v2_current_repository) : site.getConfigName();
        return SearchSource.builder()
                .repositoryId(scope)
                .repositoryName(site.getRepositoryName())
                .configId(config)
                .siteKey(site.getKey())
                .siteName(site.getName())
                .vodId(vod.getId())
                .title(vod.getName())
                .posterUrl(vod.getPic())
                .year(vod.getYear())
                .area(vod.getArea())
                .type(vod.getTypeName())
                .actors(vod.getActor())
                .remarks(vod.getRemarks())
                .episodeCount(parseEpisodeCount(vod.getRemarks()))
                .availability(health.availability())
                .detailsAvailable(!vod.getId().isEmpty())
                .deviceCompatible(true)
                .requiresLogin(requiresLogin(site))
                .lastSuccessAtMillis(health.lastSuccessAtMillis())
                .responseTimeMillis(health.responseTimeMillis())
                .recentFailureCount(health.recentFailureCount())
                .repositoryPriority(site.getRepositoryPriority())
                .build();
    }

    private int parseEpisodeCount(String remarks) {
        if (remarks == null || remarks.isBlank()) return 0;
        Matcher matcher = EPISODE_COUNT.matcher(remarks);
        int result = 0;
        while (matcher.find()) result = Math.max(result, Integer.parseInt(matcher.group(1)));
        return result;
    }

    private boolean requiresLogin(Site site) {
        String value = (site.getName() + " " + site.getConfigName()).toLowerCase();
        return value.contains("网盘") || value.contains("云盘") || value.contains("夸克")
                || value.contains("uc") || value.contains("阿里") || value.contains("迅雷");
    }

    private void submitWorks() {
        View currentFocus = getCurrentFocus();
        RecyclerView.ViewHolder focusedHolder = currentFocus == null
                ? null : binding.resultRecycler.findContainingViewHolder(currentFocus);
        boolean resultHadFocus = focusedHolder != null
                && focusedHolder.getBindingAdapterPosition() != RecyclerView.NO_POSITION;
        String focused = focusedWorkId();
        String focusedSource = focusedWorkSourceId();
        workAdapter.submit(aggregator.snapshot());
        binding.resultSummary.setText(getString(R.string.search_v2_result_summary,
                aggregator.workCount(), aggregator.sourceCount()));
        if (focused != null) selectedWorkId = focused;
        if (focusedSource != null) selectedSourceId = focusedSource;
        updateOpenPanel();
        restoreFocusIfPossible();
        if (resultHadFocus && workAdapter.positionOf(focused) < 0
                && workAdapter.positionContainingSource(focusedSource) >= 0) {
            focusWork(focused, focusedSource);
        }
    }

    private void updateOpenPanel() {
        if (!isSourcePanelOpen() || panelWorkId == null) return;
        SearchWork work = aggregator.findWork(panelWorkId);
        String focusedSource = focusedSourceId();
        if (focusedSource == null) focusedSource = selectedSourceId;
        if (work == null) work = findWorkBySource(focusedSource);
        if (work == null) {
            closeSourcePanel();
            return;
        }
        panelWorkId = work.stableId();
        selectedWorkId = work.stableId();
        binding.sourceTitle.setText(getString(R.string.search_v2_source_title, work.displayTitle()));
        boolean changed = sourceAdapter.submit(work.rankedSources(), work.recommendedSource());
        if (changed) restoreSourceFocus(focusedSource);
    }

    private SearchWork findWorkBySource(String sourceStableId) {
        if (sourceStableId == null) return null;
        for (SearchWork work : aggregator.snapshot()) {
            for (SearchSource source : work.sources()) {
                if (sourceStableId.equals(source.stableId())) return work;
            }
        }
        return null;
    }

    private void saveKeyword() {
        if (getKeyword().isEmpty()) return;
        List<String> items;
        try {
            items = Setting.getKeyword().isEmpty()
                    ? new ArrayList<>()
                    : App.gson().fromJson(Setting.getKeyword(), TypeToken.getParameterized(List.class, String.class).getType());
        } catch (Exception ignored) {
            items = new ArrayList<>();
        }
        if (items == null) items = new ArrayList<>();
        items.remove(getKeyword());
        items.add(0, getKeyword());
        if (items.size() > 9) items = new ArrayList<>(items.subList(0, 9));
        Setting.putKeyword(App.gson().toJson(items));
    }

    private SearchUiState resolveState(SearchProgress value) {
        if (value.cancelled()) return SearchUiState.CANCELLED;
        if (value.running()) return aggregator.workCount() > 0 ? SearchUiState.PARTIAL_SUCCESS : SearchUiState.SEARCHING;
        if (aggregator.workCount() > 0) {
            return value.failed() + value.timedOut() > 0 ? SearchUiState.PARTIAL_SUCCESS : SearchUiState.SUCCESS;
        }
        if (value.session() == 0) return SearchUiState.IDLE;
        if (value.failed() + value.timedOut() > 0) return SearchUiState.ERROR;
        return SearchUiState.EMPTY;
    }

    private void renderState(SearchUiState state) {
        View previousFocus = getCurrentFocus();
        binding.stateBadge.setText(getStateLabel(state));
        binding.stateBadge.setTextColor(ContextCompat.getColor(this, getStateColor(state)));
        binding.resultSummary.setText(getString(R.string.search_v2_result_summary,
                aggregator == null ? 0 : aggregator.workCount(), aggregator == null ? 0 : aggregator.sourceCount()));
        binding.status.setText(getString(R.string.search_v2_source_progress, progress.total(),
                progress.completed(), progress.pending(), progress.timedOut(), progress.failed()));
        binding.progress.setMax(Math.max(1, progress.total()));
        binding.progress.setProgress(progress.completed());
        boolean running = progress.running();
        boolean hasResults = aggregator != null && aggregator.workCount() > 0;
        boolean canRestartAtTop = !running && hasResults
                && (state == SearchUiState.CANCELLED || state == SearchUiState.PARTIAL_SUCCESS);
        binding.stop.setText(running ? R.string.search_v2_stop : R.string.search_v2_retry);
        binding.stop.setVisibility(running || canRestartAtTop ? View.VISIBLE : View.GONE);
        binding.resultRecycler.setVisibility(hasResults ? View.VISIBLE : View.INVISIBLE);
        boolean showPanel = !hasResults && state != SearchUiState.IDLE;
        binding.statePanel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        boolean loading = state == SearchUiState.SEARCHING;
        binding.stateProgress.setVisibility(showPanel && loading ? View.VISIBLE : View.GONE);
        binding.retry.setVisibility(showPanel && !loading ? View.VISIBLE : View.GONE);
        if (showPanel) {
            if (loading) {
                binding.stateTitle.setText(R.string.search_v2_loading_title);
                binding.stateMessage.setText(R.string.search_v2_loading_message);
            } else if (state == SearchUiState.EMPTY) {
                binding.stateTitle.setText(R.string.search_v2_empty_title);
                binding.stateMessage.setText(progress.total() == 0 ? R.string.search_v2_no_source_message : R.string.search_v2_empty_message);
            } else if (state == SearchUiState.CANCELLED) {
                binding.stateTitle.setText(R.string.search_v2_cancelled_title);
                binding.stateMessage.setText(R.string.search_v2_cancelled_message);
            } else {
                binding.stateTitle.setText(isNetworkConnected() ? R.string.search_v2_error_title : R.string.search_v2_network_title);
                binding.stateMessage.setText(isNetworkConnected() ? R.string.search_v2_error_message : R.string.search_v2_network_message);
            }
        }
        if ((previousFocus == binding.stop && binding.stop.getVisibility() != View.VISIBLE)
                || (previousFocus != null && !hasResults
                && binding.resultRecycler.findContainingViewHolder(previousFocus) != null)) {
            binding.body.post(this::focusPrimaryContent);
        }
    }

    private int getStateLabel(SearchUiState state) {
        return switch (state) {
            case SEARCHING -> R.string.search_v2_state_searching;
            case PARTIAL_SUCCESS -> R.string.search_v2_state_partial;
            case SUCCESS -> R.string.search_v2_state_success;
            case EMPTY -> R.string.search_v2_state_empty;
            case CANCELLED -> R.string.search_v2_state_cancelled;
            case ERROR -> R.string.search_v2_state_error;
            default -> R.string.search_v2_state_idle;
        };
    }

    private int getStateColor(SearchUiState state) {
        return switch (state) {
            case SUCCESS -> R.color.tv_success;
            case PARTIAL_SUCCESS, CANCELLED -> R.color.tv_warning;
            case ERROR -> R.color.tv_error;
            default -> R.color.tv_text_secondary;
        };
    }

    private boolean isNetworkConnected() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || manager.getActiveNetwork() == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    public void onOpen(SearchWork work) {
        if (work == null || work.recommendedSource() == null) return;
        selectedWorkId = work.stableId();
        selectedSourceId = work.recommendedSource().stableId();
        openSource(work.recommendedSource());
    }

    @Override
    public void onShowSources(SearchWork work) {
        if (work == null || work.sources().isEmpty()) return;
        selectedWorkId = work.stableId();
        selectedSourceId = sourceAnchor(work);
        panelWorkId = work.stableId();
        binding.sourceTitle.setText(getString(R.string.search_v2_source_title, work.displayTitle()));
        sourceAdapter.submit(work.rankedSources(), work.recommendedSource());
        binding.sourceScrim.setVisibility(View.VISIBLE);
        binding.sourcePanel.setVisibility(View.VISIBLE);
        binding.sourcePanel.bringToFront();
        binding.sourceRecycler.post(() -> {
            RecyclerView.ViewHolder holder = binding.sourceRecycler.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else binding.sourceRecycler.requestFocus();
        });
    }

    @Override
    public void onOpen(SearchSource source) {
        if (source != null) selectedSourceId = source.stableId();
        openSource(source);
    }

    private void openSource(SearchSource source) {
        if (source == null) return;
        Vod selected = vodBySource.get(source.stableId());
        if (selected == null || selected.getSite() == null) return;
        SearchWork work = findWorkBySource(source.stableId());
        List<Vod> ranked = new ArrayList<>();
        if (work != null) {
            for (SearchSource candidate : work.rankedSources()) {
                Vod vod = vodBySource.get(candidate.stableId());
                if (vod != null && vod.getSite() != null) ranked.add(vod);
            }
        }
        ArrayList<Vod> candidates = DetailSourceFallbackPolicy.prioritize(selected, ranked,
                vod -> vod.getSiteKey() + '\u0000' + vod.getId());
        VideoActivity.collect(this, candidates);
    }

    @Override
    public void onClose() {
        closeSourcePanel();
    }

    private boolean isSourcePanelOpen() {
        return binding.sourcePanel.getVisibility() == View.VISIBLE;
    }

    private void closeSourcePanel() {
        closeSourcePanel(true);
    }

    private void closeSourcePanel(boolean restoreFocus) {
        if (!isSourcePanelOpen()) return;
        String focusedSource = focusedSourceId();
        if (focusedSource != null) selectedSourceId = focusedSource;
        binding.sourcePanel.setVisibility(View.GONE);
        binding.sourceScrim.setVisibility(View.GONE);
        panelWorkId = null;
        if (restoreFocus) focusWork(selectedWorkId, selectedSourceId);
    }

    private String focusedWorkId() {
        View focus = getCurrentFocus();
        if (focus == null) return selectedWorkId;
        RecyclerView.ViewHolder holder = binding.resultRecycler.findContainingViewHolder(focus);
        if (holder == null || holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return selectedWorkId;
        return workAdapter.get(holder.getBindingAdapterPosition()).stableId();
    }

    private String focusedWorkSourceId() {
        View focus = getCurrentFocus();
        RecyclerView.ViewHolder holder = focus == null ? null : binding.resultRecycler.findContainingViewHolder(focus);
        SearchWork work = null;
        if (holder != null && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
            work = workAdapter.get(holder.getBindingAdapterPosition());
        } else {
            int position = workAdapter.positionOf(selectedWorkId);
            if (position < 0) position = workAdapter.positionContainingSource(selectedSourceId);
            if (position >= 0) work = workAdapter.get(position);
        }
        return work == null ? selectedSourceId : sourceAnchor(work);
    }

    private String sourceAnchor(SearchWork work) {
        if (work == null || work.sources().isEmpty()) return null;
        if (selectedSourceId != null) {
            for (SearchSource source : work.sources()) {
                if (selectedSourceId.equals(source.stableId())) return selectedSourceId;
            }
        }
        return work.sources().get(0).stableId();
    }

    private String focusedSourceId() {
        View focus = getCurrentFocus();
        if (focus == null) return null;
        RecyclerView.ViewHolder holder = binding.sourceRecycler.findContainingViewHolder(focus);
        if (holder == null || holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return null;
        return sourceAdapter.get(holder.getBindingAdapterPosition()).stableId();
    }

    private void restoreSourceFocus(String stableId) {
        if (stableId == null) return;
        for (int position = 0; position < sourceAdapter.getItemCount(); position++) {
            if (!stableId.equals(sourceAdapter.get(position).stableId())) continue;
            int target = position;
            binding.sourceRecycler.scrollToPosition(target);
            binding.sourceRecycler.post(() -> {
                RecyclerView.ViewHolder holder = binding.sourceRecycler.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
            });
            return;
        }
    }

    private void restoreFocusIfPossible() {
        if (pendingRestoreWorkId != null || pendingRestoreSourceId != null) {
            int position = workAdapter.positionOf(pendingRestoreWorkId);
            if (position < 0) position = workAdapter.positionContainingSource(pendingRestoreSourceId);
            if (position >= 0) {
                String id = workAdapter.get(position).stableId();
                String sourceId = pendingRestoreSourceId;
                pendingRestoreWorkId = null;
                pendingRestoreSourceId = null;
                pendingLayoutState = null;
                focusWork(id, sourceId);
                return;
            }
        }
        if (pendingLayoutState != null && workAdapter.getItemCount() > 0 && !progress.running()) {
            binding.resultRecycler.getLayoutManager().onRestoreInstanceState(pendingLayoutState);
            pendingLayoutState = null;
        }
    }

    private void focusWork(String stableId) {
        focusWork(stableId, selectedSourceId);
    }

    private void focusWork(String stableId, String sourceStableId) {
        int position = workAdapter.positionOf(stableId);
        if (position < 0) position = workAdapter.positionContainingSource(sourceStableId);
        if (position < 0 && workAdapter.getItemCount() > 0) position = 0;
        if (position < 0) {
            binding.back.requestFocus();
            return;
        }
        SearchWork targetWork = workAdapter.get(position);
        selectedWorkId = targetWork.stableId();
        if (workAdapter.positionContainingSource(sourceStableId) == position) selectedSourceId = sourceStableId;
        else selectedSourceId = sourceAnchor(targetWork);
        int target = position;
        binding.resultRecycler.scrollToPosition(target);
        binding.resultRecycler.post(() -> {
            RecyclerView.ViewHolder holder = binding.resultRecycler.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void focusPrimaryContent() {
        if (workAdapter.getItemCount() > 0) {
            focusWork(selectedWorkId, selectedSourceId);
        } else if (binding.retry.getVisibility() == View.VISIBLE) {
            binding.retry.requestFocus();
        } else if (binding.stop.getVisibility() == View.VISIBLE) {
            binding.stop.requestFocus();
        } else {
            binding.back.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK && isSourcePanelOpen()) {
                closeSourcePanel();
                return true;
            }
            if (isSourcePanelOpen() && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                closeSourcePanel();
                return true;
            }
            View focus = getCurrentFocus();
            if ((focus == binding.back || focus == binding.stop || focus == binding.sourceFilter)
                    && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusPrimaryContent();
                return true;
            }
            RecyclerView.ViewHolder holder = focus == null ? null : binding.resultRecycler.findContainingViewHolder(focus);
            if (holder != null && holder.getBindingAdapterPosition() < COLUMN_COUNT && code == KeyEvent.KEYCODE_DPAD_UP) {
                binding.back.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        String workId = focusedWorkId();
        if (workId != null) outState.putString(STATE_WORK_ID, workId);
        String sourceId = focusedWorkSourceId();
        if (sourceId != null) outState.putString(STATE_SOURCE_ID, sourceId);
        RecyclerView.LayoutManager manager = binding.resultRecycler.getLayoutManager();
        if (manager != null) outState.putParcelable(STATE_LAYOUT, manager.onSaveInstanceState());
    }

    @Override
    protected void onBackInvoked() {
        if (isSourcePanelOpen()) {
            closeSourcePanel();
            return;
        }
        if (progress.running()) viewModel.stopSearch();
        super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) viewModel.stopSearch();
        super.onDestroy();
    }
}
