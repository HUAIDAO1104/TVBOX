package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class KeepActivity extends BaseActivity implements KeepAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private KeepAdapter mAdapter;
    private int loadGeneration;
    private boolean opening;
    private int openGeneration;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
    }

    @Override protected void onResume() {
        super.onResume();
        opening = false;
        getKeep();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new KeepAdapter(this));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
    }

    private void getKeep() {
        int generation = ++loadGeneration;
        com.fongmi.android.tv.utils.Task.execute(() -> {
            java.util.List<Keep> items = Keep.getVod();
            com.fongmi.android.tv.App.post(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                mAdapter.setItems(items, () -> mBinding.progressLayout.showContent(true, mAdapter.getItemCount()));
            });
        });
    }

    private void loadConfig(Config config, Keep item) {
        int generation = openGeneration;
        Notify.show("正在切换收藏所属配置…");
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                if (generation != openGeneration || !opening || isFinishing() || isDestroyed()) return;
                opening = false;
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                if (generation != openGeneration) return;
                opening = false;
                if (isFinishing() || isDestroyed()) return;
                Notify.show(msg);
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.KEEP) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        if (opening) return;
        opening = true;
        int generation = ++openGeneration;
        com.fongmi.android.tv.utils.Task.execute(() -> {
            Config config = Config.find(item.getCid());
            com.fongmi.android.tv.App.post(() -> {
                if (generation != openGeneration || !opening || isFinishing() || isDestroyed()) return;
                if (config == null) CollectActivity.start(this, item.getVodName());
                else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
                else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            });
        });
    }

    @Override
    public void onItemDelete(Keep item) {
        mAdapter.remove(item.delete(), () -> {
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
        });
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(true);
        return true;
    }

    @Override
    protected void onBackInvoked() {
        if (opening) { openGeneration++; opening = false; Notify.show("已取消打开收藏"); }
        else if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }
}
