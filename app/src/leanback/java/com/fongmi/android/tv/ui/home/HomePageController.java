package com.fongmi.android.tv.ui.home;

import android.view.View;

import com.fongmi.android.tv.databinding.ActivityHomeBinding;

public class HomePageController {

    private final ActivityHomeBinding binding;
    private final HomeState state;

    public HomePageController(ActivityHomeBinding binding, HomeState state) {
        this.binding = binding;
        this.state = state;
    }

    public void showLoading(boolean switchingConfig) {
        state.setPage(switchingConfig ? HomeState.Page.SWITCHING_CONFIG : HomeState.Page.LOADING);
        showOnly(binding.loadingState);
    }

    public void showHome() {
        state.setPage(HomeState.Page.HOME);
        showOnly(binding.homeScroll);
    }

    public void showCategory() {
        state.setPage(HomeState.Page.CATEGORY);
        showOnly(binding.categoryContainer);
    }

    public void showEmpty() {
        state.setPage(HomeState.Page.EMPTY);
        showOnly(binding.emptyState);
        binding.emptyConfig.requestFocus();
    }

    public void showError(CharSequence message) {
        state.setPage(HomeState.Page.ERROR);
        binding.errorMessage.setText(message);
        showOnly(binding.errorState);
        binding.retry.requestFocus();
    }

    private void showOnly(View target) {
        binding.homeScroll.setVisibility(target == binding.homeScroll ? View.VISIBLE : View.GONE);
        binding.categoryContainer.setVisibility(target == binding.categoryContainer ? View.VISIBLE : View.GONE);
        binding.loadingState.setVisibility(target == binding.loadingState ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(target == binding.emptyState ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(target == binding.errorState ? View.VISIBLE : View.GONE);
        target.animate().cancel();
        target.setAlpha(0.76f);
        target.animate().alpha(1f).setDuration(180).start();
    }
}
