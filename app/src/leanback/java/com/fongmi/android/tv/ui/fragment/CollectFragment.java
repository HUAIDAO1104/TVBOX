package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.SearchVodPresenter;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class CollectFragment extends BaseFragment implements CustomScroller.Callback, SearchVodPresenter.OnClickListener {

    private static final String ARG_KEYWORD = "keyword";
    private static final String ARG_COLLECT = "collect";
    private static final String STATE_ROW = "search_v2_row";
    private static final String STATE_COLUMN = "search_v2_column";

    private FragmentCollectBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private Collect mCollect;
    private String mKeyword;

    public static CollectFragment newInstance(String keyword, Collect collect) {
        Bundle args = new Bundle();
        args.putString(ARG_KEYWORD, keyword);
        args.putParcelable(ARG_COLLECT, collect);
        CollectFragment fragment = new CollectFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return mKeyword = mKeyword == null ? requireArguments().getString(ARG_KEYWORD, "") : mKeyword;
    }

    private Collect getCollect() {
        if (mCollect == null) mCollect = requireArguments().getParcelable(ARG_COLLECT);
        return mCollect;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentCollectBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        setViewModel();
        addVideo(getCollect());
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), SearchVodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.addOnScrollListener(mScroller = new CustomScroller(this));
        mBinding.recycler.setHeader(getActivity(), R.id.searchHeader, R.id.recycler);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            if (result == null) return;
            mScroller.endLoading(result);
            addVideo(result.getList());
        });
    }

    private boolean checkLastSize(List<Vod> items) {
        if (mLast == null || items.isEmpty()) return false;
        int size = Product.getColumn() - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addVideo(items.subList(size, items.size()));
        return true;
    }

    private void addVideo(Collect collect) {
        if (collect != null) addVideo(collect.getList());
    }

    public void addVideo(List<Vod> items) {
        if (checkLastSize(items) || getActivity() == null || getActivity().isFinishing()) return;
        List<ListRow> rows = new ArrayList<>();
        SearchVodPresenter presenter = new SearchVodPresenter(this);
        for (List<Vod> part : Lists.partition(items, Product.getColumn())) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    @Override
    public void onItemClick(Vod item) {
        requireActivity().setResult(Activity.RESULT_OK);
        if (item.isFolder()) VodActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else VideoActivity.collect(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public boolean onLoadMore(String page) {
        Collect collect = getCollect();
        if ("all".equals(collect.getSite().getKey())) return false;
        mViewModel.searchContent(collect.getSite(), getKeyword(), false, page);
        return true;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        int row = Math.max(0, mBinding.recycler.getSelectedPosition());
        outState.putInt(STATE_ROW, row);
        RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(row);
        if (holder instanceof ItemBridgeAdapter.ViewHolder bridge
                && bridge.getViewHolder() instanceof ListRowPresenter.ViewHolder rowHolder) {
            outState.putInt(STATE_COLUMN, Math.max(0, rowHolder.getGridView().getSelectedPosition()));
        }
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (savedInstanceState == null || mAdapter.size() == 0) return;
        int row = Math.clamp(savedInstanceState.getInt(STATE_ROW), 0, mAdapter.size() - 1);
        int column = Math.max(0, savedInstanceState.getInt(STATE_COLUMN));
        mBinding.recycler.setSelectedPosition(row);
        mBinding.recycler.post(() -> restoreColumn(row, column));
    }

    private void restoreColumn(int row, int column) {
        RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(row);
        if (!(holder instanceof ItemBridgeAdapter.ViewHolder bridge)) return;
        if (!(bridge.getViewHolder() instanceof ListRowPresenter.ViewHolder rowHolder)) return;
        RecyclerView.Adapter<?> adapter = rowHolder.getGridView().getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return;
        rowHolder.getGridView().setSelectedPosition(Math.clamp(column, 0, adapter.getItemCount() - 1));
    }
}
