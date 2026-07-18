package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeFeaturedBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.home.HomeFeaturedPolicy;
import com.fongmi.android.tv.ui.home.HomePosterAdapter;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/** Full list behind the independent "More" entry on the six-card home shelf. */
public class HomeFeaturedActivity extends BaseActivity implements HomePosterAdapter.Listener {

    private static final String EXTRA_ITEMS = "home_featured_items";
    private static final String STATE_POSITION = "home_featured_position";

    private ActivityHomeFeaturedBinding binding;
    private HomePosterAdapter adapter;
    private SiteViewModel viewModel;
    private int selectedPosition;

    public static void start(Activity activity, List<Vod> items) {
        if (items == null || items.isEmpty()) return;
        Intent intent = new Intent(activity, HomeFeaturedActivity.class);
        intent.putParcelableArrayListExtra(EXTRA_ITEMS, new ArrayList<>(items));
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityHomeFeaturedBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        selectedPosition = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_POSITION);
        binding.recycler.setNumColumns(HomeFeaturedPolicy.PREVIEW_LIMIT);
        binding.recycler.setHorizontalSpacing(ResUtil.dp2px(12));
        binding.recycler.setVerticalSpacing(ResUtil.dp2px(14));
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter = new HomePosterAdapter(this));
        List<Vod> items = getIntent().getParcelableArrayListExtra(EXTRA_ITEMS);
        adapter.submit(items == null ? List.of() : items);
        binding.empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        binding.recycler.post(this::updateGridLayout);
        if (adapter.getItemCount() > 0) {
            selectedPosition = Math.min(selectedPosition, adapter.getItemCount() - 1);
            binding.recycler.setSelectedPosition(selectedPosition);
            binding.recycler.requestFocus();
        }
        viewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        viewModel.getAction().observe(this, result -> {
            if (result != null && result.hasMsg()) Notify.show(result.getMsg());
        });
    }

    @Override
    protected void initEvent() {
    }

    private void updateGridLayout() {
        int columns = HomeFeaturedPolicy.PREVIEW_LIMIT;
        int available = binding.recycler.getWidth() - binding.recycler.getPaddingLeft() - binding.recycler.getPaddingRight();
        if (available <= 0) return;
        int minimumSpacing = getResources().getDimensionPixelSize(R.dimen.home_poster_min_spacing);
        int preferredWidth = getResources().getDimensionPixelSize(R.dimen.home_poster_card_width);
        int width = Math.min(preferredWidth, Math.max(1, (available - minimumSpacing * (columns - 1)) / columns));
        int height = Math.round(width * 4f / 3f);
        int spacing = Math.max(minimumSpacing, (available - width * columns) / (columns - 1));
        adapter.setCardSize(width, height);
        binding.recycler.setColumnWidth(width);
        binding.recycler.setHorizontalSpacing(spacing);
    }

    @Override
    public void onPosterClick(Vod item) {
        if (item == null) return;
        String key = item.getSiteKey().isEmpty() ? VodConfig.get().getHome().getKey() : item.getSiteKey();
        if (item.isAction()) viewModel.action(key, item.getAction());
        else if (VodConfig.get().getHome().isIndex()) CollectActivity.start(this, item.getName());
        else VideoActivity.detail(this, key, item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onPosterLongClick(Vod item) {
        if (item == null || item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onPosterFocused(Vod item, int position) {
        selectedPosition = Math.max(0, position);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_POSITION, selectedPosition);
        super.onSaveInstanceState(outState);
    }
}
