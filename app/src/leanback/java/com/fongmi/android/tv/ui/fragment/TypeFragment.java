package com.fongmi.android.tv.ui.fragment;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.databinding.FragmentTypeTouchBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomVerticalGridView;
import com.fongmi.android.tv.ui.custom.ProgressLayout;
import com.fongmi.android.tv.ui.presenter.FilterPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PosterResolver;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private static final String STATE_POSITION = "state_position";
    private static final int EMBEDDED_COLUMN_COUNT = 6;

    public interface Host {
        void openCategoryFolder(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder);

        void onCategoryError(String message);
    }

    private HashMap<String, String> mExtends;
    private ViewBinding mBinding;
    private SwipeRefreshLayout mSwipeLayout;
    private ProgressLayout mProgressLayout;
    private RecyclerView mRecycler;
    private CustomVerticalGridView mLeanbackRecycler;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private final Runnable filterRefresh = this::onRefresh;
    private boolean refreshingFirstPage;
    private SiteViewModel mViewModel;
    private List<Filter> mFilters;
    private boolean headerVisible;
    private boolean filterVisible;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        return newInstance(key, typeId, style, extend, folder, false);
    }

    public static TypeFragment newEmbeddedInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        return newInstance(key, typeId, style, extend, folder, true);
    }

    private static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, boolean embedded) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putBoolean("embedded", embedded);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        TypeFragment fragment = new TypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private boolean isEmbedded() {
        return getArguments().getBoolean("embedded");
    }

    private Style getStyle() {
        if (isEmbedded()) return Style.rect();
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private List<Filter> getFilter() {
        return Cache.copy(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return ((FolderFragment) getParentFragment());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        if (Util.isMobile()) {
            FragmentTypeTouchBinding binding = FragmentTypeTouchBinding.inflate(inflater, container, false);
            mBinding = binding;
            mSwipeLayout = binding.swipeLayout;
            mProgressLayout = binding.progressLayout;
            mRecycler = binding.recycler;
        } else {
            FragmentTypeBinding binding = FragmentTypeBinding.inflate(inflater, container, false);
            mBinding = binding;
            mSwipeLayout = binding.swipeLayout;
            mProgressLayout = binding.progressLayout;
            mRecycler = mLeanbackRecycler = binding.recycler;
        }
        return mBinding;
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        mFilters = getFilter();
        setRecyclerView();
        setViewModel();
        setFilters();
        if (isEmbedded() && !mFilters.isEmpty()) {
            filterVisible = true;
            showFilter();
        }
        getVideo();
    }

    @Override
    protected void initEvent() {
        mSwipeLayout.setOnRefreshListener(this);
        // TV remote users refresh through normal navigation. Disabling the pull container for
        // embedded Leanback categories prevents touch overscroll from dragging the whole page.
        mSwipeLayout.setEnabled(false);
        mRecycler.addOnScrollListener(mScroller);
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(8, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED), FilterPresenter.class);
        if (Util.isMobile()) {
            // Category pages on phones must use a genuine touch layout manager. Leanback's
            // GridLayoutManager always retains a selected row for DPAD and can realign it after
            // pagination or fragment reuse, which is the long-standing snap-back-to-top bug.
            mRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
            mRecycler.setItemAnimator(null);
            mRecycler.setHasFixedSize(false);
            mRecycler.addItemDecoration(new VerticalSpacingDecoration(ResUtil.dp2px(16)));
        } else {
            if (!isEmbedded()) mLeanbackRecycler.setHeader(getActivity(), R.id.recycler);
            mLeanbackRecycler.setVerticalSpacing(ResUtil.dp2px(16));
        }
        mRecycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
        mViewModel.getError().observe(getViewLifecycleOwner(), message -> {
            if (message == null || message.isEmpty()) return;
            if (isEmbedded() && getActivity() instanceof Host host) host.onCategoryError(message);
        });
    }

    private void setFilters() {
        for (Filter filter : mFilters) {
            if (mExtends.containsKey(filter.getKey())) {
                filter.setSelected(mExtends.get(filter.getKey()));
            }
        }
    }

    private void setClick(ArrayObjectAdapter adapter, String key, Value item) {
        for (int i = 0; i < adapter.size(); i++) ((Value) adapter.get(i)).setSelected(item);
        adapter.notifyArrayItemRangeChanged(0, adapter.size());
        if (item.isSelected()) mExtends.put(key, item.getV());
        else mExtends.remove(key);
        App.post(filterRefresh, 220);
    }

    private void getVideo() {
        mLast = null;
        checkFilter();
        refreshingFirstPage = true;
        mScroller.beginRefresh();
        getVideo(getTypeId(), "1");
    }

    private void getVideo(String typeId, String page) {
        mViewModel.categoryContent(getKey(), typeId, page, true, new HashMap<>(mExtends));
    }

    private void setAdapter(Result result) {
        if (result == null) return;
        if (result.hasMsg()) {
            if (refreshingFirstPage) mScroller.cancelRefresh();
            refreshingFirstPage = false;
            mSwipeLayout.setRefreshing(false);
            if (mAdapter.size() > (filterVisible ? mFilters.size() : 0)) mProgressLayout.showContent();
            return;
        }
        if (refreshingFirstPage) {
            int filters = filterVisible ? mFilters.size() : 0;
            if (mAdapter.size() > filters) mAdapter.removeItems(filters, mAdapter.size() - filters);
            refreshingFirstPage = false;
        }
        boolean first = mScroller.first();
        boolean flag = mExtends.isEmpty();
        int size = result.getList().size();
        mProgressLayout.showContent(first & flag, size);
        mSwipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        rememberPosters(result.getList());
        Style style = isEmbedded() ? Style.rect() : result.getStyle(getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
        checkMore();
    }

    private void rememberPosters(List<Vod> items) {
        if (items == null) return;
        for (Vod item : items) {
            if (item != null) PosterResolver.remember(item.getName(), item.getPic());
        }
    }

    private void checkMore() {
        if (mScroller.isDisable() || mAdapter.size() >= 5) return;
        mScroller.checkMore();
    }

    private boolean checkLastSize(List<Vod> items, Style style) {
        if (mLast == null || items.isEmpty()) return false;
        int size = getColumn(style) - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addGrid(items.subList(size, items.size()), style);
        return true;
    }

    private void addGrid(List<Vod> items, Style style) {
        if (checkLastSize(items, style)) return;
        List<ListRow> rows = new ArrayList<>();
        int columns = getColumn(style);
        VodPresenter presenter = new VodPresenter(this, style, isEmbedded(), columns);
        for (List<Vod> part : Lists.partition(items, columns)) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private int getColumn(Style style) {
        return isEmbedded() ? EMBEDDED_COLUMN_COUNT : Product.getColumn(style);
    }

    private ListRow getRow(Filter filter) {
        FilterPresenter presenter = new FilterPresenter(filter.getKey());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        presenter.setOnClickListener((key, item) -> setClick(adapter, key, item));
        adapter.setItems(filter.getValue(), null);
        return new ListRow(adapter);
    }

    private void showFilter() {
        List<ListRow> rows = new ArrayList<>();
        for (Filter filter : mFilters) rows.add(getRow(filter));
        // On TV the filter row should immediately become the DPAD anchor. On phones this
        // delayed jump can arrive after the user has already started a fling and pull the page
        // back to the beginning, so native touch scrolling keeps its current viewport.
        if (!Util.isMobile()) mRecycler.postDelayed(() -> mRecycler.scrollToPosition(0), 48);
        mAdapter.addAll(0, rows);
    }

    private void hideFilter() {
        mAdapter.removeItems(0, mFilters.size());
    }

    public void toggleFilter(boolean visible) {
        if (mFilters.isEmpty()) return;
        this.filterVisible = visible;
        if (visible) showFilter();
        else hideFilter();
    }

    private void checkFilter() {
        int adapterSize = mAdapter.size();
        int filterSize = filterVisible ? mFilters.size() : 0;
        if (adapterSize == 0) mProgressLayout.showProgress();
        else mSwipeLayout.setRefreshing(true);
    }

    public void onRefresh() {
        App.removeCallbacks(filterRefresh);
        getVideo();
    }

    @Override public void onDestroyView() {
        App.removeCallbacks(filterRefresh);
        super.onDestroyView();
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            if (isEmbedded() && getActivity() instanceof Host host) host.openCategoryFolder(getKey(), item.getId(), getStyle(), new HashMap<>(mExtends), isFolder());
            else getParent().openFolder(item.getId(), mExtends);
            headerVisible = mLeanbackRecycler != null && mLeanbackRecycler.isHeaderVisible();
        } else {
            if (getSite().isIndex()) CollectActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        CollectActivity.start(requireActivity(), item.getName());
        return true;
    }

    @Override
    public boolean onLoadMore(String page) {
        getVideo(getTypeId(), page);
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // Phone pages are driven by touch scroll and direct tab taps. Restoring TV focus here
        // makes Leanback align the previously selected row and undoes the user's scroll.
        if (Util.isMobile()) return;
        if (isEmbedded()) {
            if (!hidden && !mBinding.getRoot().isInTouchMode()) mLeanbackRecycler.requestFocus();
            return;
        }
        if (hidden) {
            mLeanbackRecycler.showHeader();
        } else {
            if (headerVisible) mLeanbackRecycler.showHeader();
            else mLeanbackRecycler.hideHeader();
            if (!mBinding.getRoot().isInTouchMode()) mLeanbackRecycler.requestFocus();
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (!Util.isMobile() && mBinding != null && !isEmbedded() && !mBinding.getRoot().isInTouchMode()) mLeanbackRecycler.moveToTop();
    }

    public int getSelectedPosition() {
        return mLeanbackRecycler == null ? 0 : Math.max(0, mLeanbackRecycler.getSelectedPosition());
    }

    public void restorePosition(int position) {
        if (!Util.isMobile() && mLeanbackRecycler != null) mLeanbackRecycler.setSelectedPosition(Math.max(0, position));
    }

    public boolean requestContentFocus() {
        return !Util.isMobile() && mLeanbackRecycler != null && mLeanbackRecycler.requestFocus();
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (savedInstanceState != null) restorePosition(savedInstanceState.getInt(STATE_POSITION));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_POSITION, getSelectedPosition());
        super.onSaveInstanceState(outState);
    }

    private static final class VerticalSpacingDecoration extends RecyclerView.ItemDecoration {

        private final int spacing;

        private VerticalSpacingDecoration(int spacing) {
            this.spacing = spacing;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            outRect.bottom = spacing;
        }
    }
}
