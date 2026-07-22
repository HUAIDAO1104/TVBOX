package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityFileBinding;
import com.fongmi.android.tv.databinding.ActivityFileTouchBinding;
import com.fongmi.android.tv.ui.adapter.FileAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.ProgressLayout;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Path;

import java.io.File;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    private ViewBinding mBinding;
    private RecyclerView mRecycler;
    private androidx.leanback.widget.VerticalGridView mLeanbackRecycler;
    private ProgressLayout mProgressLayout;
    private FileAdapter mAdapter;
    private File dir;

    private boolean isRoot() {
        return Path.root().equals(dir);
    }

    @Override
    protected ViewBinding getBinding() {
        if (Util.isMobile()) {
            ActivityFileTouchBinding binding = ActivityFileTouchBinding.inflate(getLayoutInflater());
            mBinding = binding;
            mRecycler = binding.recycler;
            mProgressLayout = binding.progressLayout;
        } else {
            ActivityFileBinding binding = ActivityFileBinding.inflate(getLayoutInflater());
            mBinding = binding;
            mRecycler = mLeanbackRecycler = binding.recycler;
            mProgressLayout = binding.progressLayout;
        }
        return mBinding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        checkPermission();
    }

    private void setRecyclerView() {
        mRecycler.setHasFixedSize(true);
        if (Util.isMobile()) {
            mRecycler.setLayoutManager(new LinearLayoutManager(this));
            mRecycler.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            mRecycler.setFocusable(false);
            mRecycler.setFocusableInTouchMode(false);
            mRecycler.setPreserveFocusAfterLayout(false);
            mRecycler.addItemDecoration(new VerticalSpacingDecoration(ResUtil.dp2px(16)));
        } else {
            mLeanbackRecycler.setVerticalSpacing(ResUtil.dp2px(16));
        }
        mRecycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    private void checkPermission() {
        PermissionUtil.requestFile(this, allGranted -> update(Path.root()));
    }

    private void update(File dir) {
        if (mLeanbackRecycler == null) mRecycler.scrollToPosition(0);
        else mLeanbackRecycler.setSelectedPosition(0);
        mAdapter.addAll(Path.list(this.dir = dir));
        mProgressLayout.showContent(true, mAdapter.getItemCount());
    }

    @Override
    public void onItemClick(File file) {
        if (file.isDirectory()) {
            update(file);
        } else {
            setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
            finish();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (isRoot()) {
            super.onBackInvoked();
        } else {
            update(dir.getParentFile());
        }
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
