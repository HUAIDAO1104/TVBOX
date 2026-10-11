package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.FragmentVodBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.HistoryActivity;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.FilterDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LinkDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigListener, SiteListener, FilterListener, TypeAdapter.OnClickListener {

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private TypeAdapter mAdapter;
    private Result mResult;
    private com.fongmi.android.tv.bean.History recentHistory;

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.title.setSelected(true);
        setRecyclerView();
        setViewModel();
        showProgress();
        setTitle();
        setLogo();
        if (!getHome().getKey().isEmpty()) homeContent();
        else showEmpty(false);
    }

    @Override
    protected void initEvent() {
        mBinding.continueEntry.setOnClickListener(v -> {
            if (recentHistory != null) com.fongmi.android.tv.ui.activity.VideoActivity.start(requireActivity(), recentHistory.getSiteKey(), recentHistory.getVodId(), recentHistory.getVodName(), recentHistory.getVodPic());
        });
        mBinding.searchEntry.setOnClickListener(v -> SearchActivity.start(requireActivity()));
        mBinding.liveEntry.setOnClickListener(v -> LiveActivity.start(requireActivity()));
        mBinding.configEntry.setOnClickListener(this::onSite);
        mBinding.linkEntry.setOnClickListener(this::onLink);
        mBinding.setup.setOnClickListener(v -> ConfigDialog.create().vod().show(this));
        mBinding.retry.setOnClickListener(v -> homeContent());
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.logo.setOnClickListener(this::onLogo);
        mBinding.link.setOnClickListener(this::onLink);
        mBinding.title.setOnClickListener(this::onSite);
        mBinding.sourceStatus.setOnClickListener(this::onSite);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.filter.setOnLongClickListener(this::onLink);
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setSelected(position);
                setFabVisible(position);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new TypeAdapter(this));
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        if (result == null) return;
        mBinding.emptyState.setVisibility(View.GONE);
        mAdapter.addAll(mResult = result);
        mBinding.pager.getAdapter().notifyDataSetChanged();
        setFabVisible(0);
        hideProgress();
        showContent();
        if (mAdapter.getItemCount() == 0) showEmpty(result.hasMsg());
    }

    private void setFabVisible(int position) {
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.show();
        } else if (position == 0 || mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.hide();
        }
    }

    private void setTitle() {
        mBinding.title.setText(R.string.app_name);
        String name = SearchDisplayName.clean(getHome().getName());
        mBinding.configEntry.setText("换源");
        mBinding.sourceStatus.setText(name);
        mBinding.title.setContentDescription(getString(R.string.app_name) + "，当前来源：" + name);
        mBinding.configEntry.setMaxLines(1);
        mBinding.configEntry.setEllipsize(TextUtils.TruncateAt.END);
        mBinding.configEntry.setContentDescription(getString(R.string.mobile_source) + "，" + name);
    }

    private void loadRecentHistory() {
        com.fongmi.android.tv.utils.Task.execute(() -> {
            List<com.fongmi.android.tv.bean.History> items = com.fongmi.android.tv.bean.History.get();
            com.fongmi.android.tv.App.post(() -> {
                if (mBinding == null || !isAdded()) return;
                recentHistory = items.isEmpty() ? null : items.get(0);
                mBinding.continueEntry.setVisibility(recentHistory == null ? View.GONE : View.VISIBLE);
                if (recentHistory != null) mBinding.continueEntry.setText(getString(R.string.mobile_history) + " · " + recentHistory.getVodName() + "  " + recentHistory.getVodRemarks());
            });
        });
    }

    @Override public void onResume() { super.onResume(); loadRecentHistory(); }
    @Override public void onHiddenChanged(boolean hidden) { super.onHiddenChanged(hidden); if (!hidden) loadRecentHistory(); }

    private void onTop(View view) {
        getFragment().scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
        else if (mBinding.link.getVisibility() == View.INVISIBLE) mBinding.link.show();
    }

    private boolean onLink(View view) {
        LinkDialog.show(this);
        return true;
    }

    private void onLogo(View view) {
        HistoryDialog.create().vod().readOnly().show(this);
    }

    private void onSite(View view) {
        if (VodConfig.get().getSites().isEmpty()) ConfigDialog.create().vod().show(this);
        else SiteDialog.create().show(this);
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.keep) KeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) SearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        return true;
    }

    private void showProgress() {
        mBinding.emptyState.setVisibility(View.GONE);
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void showEmpty(boolean failed) {
        hideProgress();
        mBinding.emptyMessage.setText(failed ? R.string.mobile_load_failed : R.string.mobile_home_empty_hint);
        mBinding.emptyState.setVisibility(View.VISIBLE);
        mBinding.retry.setVisibility(getHome().getKey().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        mBinding.type.setVisibility(View.VISIBLE);
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        showProgress();
        setFabVisible(0);
        mAdapter.clear();
        mViewModel.homeContent();
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                setTitle();
            case SIZE:
                homeContent();
                break;
            case CATEGORY:
                getFragment().onRefresh();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                showEmpty(false);
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                showContent();
                showEmpty(true);
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        mBinding.pager.setCurrentItem(position);
        mAdapter.setSelected(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        getFragment().setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        if (!getFragment().canBack()) return true;
        getFragment().goBack();
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
        mBinding = null;
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type, 4);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }


    }
}
