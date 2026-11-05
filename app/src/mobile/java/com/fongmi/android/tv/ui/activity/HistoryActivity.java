package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.HistoryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.SyncDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.fongmi.android.tv.ui.dialog.DialogGlass;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HistoryActivity extends BaseActivity implements HistoryAdapter.OnClickListener {

    private ActivityHistoryBinding mBinding;
    private HistoryAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        getHistory();
        mBinding.search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){getHistory();}
            public void afterTextChanged(android.text.Editable e){}
        });
    }

    private void setRecyclerView() {
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, getResources().getConfiguration().screenWidthDp / getResources().getConfiguration().fontScale >= 700 ? 2 : 1));
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
        mAdapter.setSize(Product.getSpec(this));
    }

    private void getHistory() {
        String query=mBinding.search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        var all=History.get();
        var visible=all.stream().filter(item -> (item.getVodName()+" "+item.getSiteName()).toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
        mAdapter.setItems(visible, () -> {
            mBinding.progressLayout.showContent();
            mBinding.empty.setVisibility(visible.isEmpty()?android.view.View.VISIBLE:android.view.View.GONE);
            mBinding.empty.setText(query.isEmpty()?"还没有观看记录\n播放影片后，会在这里保留上次看到的位置。":"没有找到匹配记录\n试试其他片名或来源名称。");
            mBinding.summary.setText("共 "+all.size()+" 部"+(query.isEmpty()?" · 点击继续观看，长按管理":" · 找到 "+visible.size()+" 部"));
        });
    }

    private void onSync() {
        SyncDialog.create().history().show(this);
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            DialogGlass.show(new MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(R.string.dialog_delete_history).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> { mAdapter.clear(); getHistory(); }));
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.HISTORY)) getHistory();
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        new MaterialAlertDialogBuilder(this).setTitle("移除这条历史？").setMessage(item.getVodName())
            .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{item.delete();getHistory();}).show();
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(!mAdapter.isDelete());
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_history, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.sync) onSync();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }
}
