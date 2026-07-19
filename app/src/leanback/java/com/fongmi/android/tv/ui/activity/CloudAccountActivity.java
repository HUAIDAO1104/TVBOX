package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.cloud.CloudAccountManager;
import com.fongmi.android.tv.cloud.CloudLoginRoute;
import com.fongmi.android.tv.cloud.CloudLoginRouteResolver;
import com.fongmi.android.tv.cloud.CloudProvider;
import com.fongmi.android.tv.databinding.ActivityCloudAccountBinding;
import com.fongmi.android.tv.ui.adapter.CloudAccountAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.CloudAccountEditDialog;
import com.fongmi.android.tv.ui.dialog.DialogGlass;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Future;

public class CloudAccountActivity extends BaseActivity implements CloudAccountAdapter.Listener,
        CloudAccountEditDialog.Listener {

    private static final String EXTRA_RETURN_ON_SAVE = "return_on_save";

    private ActivityCloudAccountBinding binding;
    private CloudAccountAdapter adapter;
    private Future<?> discoveryTask;
    private Future<?> loginTask;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, CloudAccountActivity.class));
    }

    public static void startForResult(Activity activity, int requestCode) {
        Intent intent = new Intent(activity, CloudAccountActivity.class);
        intent.putExtra(EXTRA_RETURN_ON_SAVE, true);
        activity.startActivityForResult(intent, requestCode);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityCloudAccountBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 12));
        binding.recycler.setAdapter(adapter = new CloudAccountAdapter(this));
        binding.refresh.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.refresh.setOnClickListener(v -> loadRepositoryLoginRoutes());
        loadRepositoryLoginRoutes();
    }

    private void loadRepositoryLoginRoutes() {
        if (isRunning(discoveryTask)) return;
        binding.refresh.setEnabled(false);
        binding.scanStatus.setText(R.string.cloud_scan_loading);
        discoveryTask = Task.submit(() -> {
            List<CloudLoginRoute> routes = new ArrayList<>();
            for (Site site : loginSites()) {
                try {
                    Result result = SiteApi.homeContent(site);
                    routes = CloudLoginRouteResolver.routes(site.getKey(), result.getTypes().stream()
                            .map(type -> new CloudLoginRouteResolver.CategoryDescriptor(type.getTypeId(), type.getTypeName()))
                            .toList());
                    if (!routes.isEmpty()) {
                        break;
                    }
                } catch (Throwable ignored) {
                    // A configuration center is optional.  Try the next candidate without exposing
                    // repository responses, which may contain credentials or internal URLs.
                }
            }
            List<CloudLoginRoute> resolved = routes;
            postUi(() -> {
                adapter.submitRoutes(resolved);
                if (!binding.recycler.hasFocus()) binding.recycler.post(() -> binding.recycler.scrollToPosition(0));
                binding.refresh.setEnabled(true);
                if (resolved.isEmpty()) {
                    binding.scanStatus.setText(R.string.cloud_scan_empty);
                } else {
                    binding.scanStatus.setText(getString(R.string.cloud_scan_ready, resolved.size()));
                }
            });
        });
    }

    private List<Site> loginSites() {
        List<Site> sites = new ArrayList<>(VodConfig.get().getSites());
        sites.removeIf(site -> CloudLoginRouteResolver.siteScore(descriptor(site)) < 6);
        sites.sort(Comparator.comparingInt((Site site) -> CloudLoginRouteResolver.siteScore(descriptor(site))).reversed());
        return sites;
    }

    private CloudLoginRouteResolver.SiteDescriptor descriptor(Site site) {
        return new CloudLoginRouteResolver.SiteDescriptor(site.getKey(), site.getName(), site.getApi());
    }

    @Override
    public void onRepositoryLogin(CloudLoginRoute route) {
        if (isRunning(loginTask)) return;
        binding.refresh.setEnabled(false);
        binding.scanStatus.setText(getString(R.string.cloud_scan_opening, route.title()));
        loginTask = Task.submit(() -> {
            try {
                Result category = SiteApi.categoryContent(route.siteKey(), route.typeId(), "1", true, new HashMap<>());
                CloudLoginRouteResolver.ActionDescriptor login = CloudLoginRouteResolver.findLoginAction(category.getList().stream()
                        .map(this::actionDescriptor)
                        .toList());
                if (login == null) {
                    postUi(() -> {
                        binding.refresh.setEnabled(true);
                        binding.scanStatus.setText(getString(R.string.cloud_scan_action_missing, route.title()));
                    });
                    return;
                }
                // The repository Spider already owns the provider-specific QR protocol.  Calling
                // its detail action here opens that verified dialog on this Activity, without
                // switching VodConfig.home or routing a settings action through VideoActivity.
                SiteApi.detailContent(route.siteKey(), login.id());
                postUi(() -> {
                    binding.refresh.setEnabled(true);
                    binding.scanStatus.setText(getString(R.string.cloud_scan_started, route.title()));
                });
            } catch (Throwable ignored) {
                postUi(() -> {
                    binding.refresh.setEnabled(true);
                    binding.scanStatus.setText(getString(R.string.cloud_scan_failed, route.title()));
                });
            }
        });
    }

    private CloudLoginRouteResolver.ActionDescriptor actionDescriptor(Vod vod) {
        return new CloudLoginRouteResolver.ActionDescriptor(vod.getId(), vod.getName(), vod.getRemarks());
    }

    private boolean isRunning(Future<?> task) {
        return task != null && !task.isDone() && !task.isCancelled();
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    @Override
    public void onEdit(CloudProvider provider) {
        CloudAccountEditDialog.create().provider(provider).listener(this).show(this);
    }

    @Override
    public void onValidate(CloudProvider provider) {
        boolean valid = CloudAccountManager.validateFormat(provider.id());
        adapter.notifyDataSetChanged();
        Notify.show(valid ? R.string.cloud_format_valid_notice : R.string.cloud_format_invalid_notice);
    }

    @Override
    public void onLogout(CloudProvider provider) {
        DialogGlass.apply(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cloud_logout_title)
                .setMessage(getString(R.string.cloud_logout_message, provider.name()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    CloudAccountManager.logout(provider.id());
                    adapter.notifyDataSetChanged();
                }).show());
    }

    @Override
    public void onSaved() {
        adapter.notifyDataSetChanged();
        if (getIntent().getBooleanExtra(EXTRA_RETURN_ON_SAVE, false)) {
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (discoveryTask != null) discoveryTask.cancel(true);
        if (loginTask != null) loginTask.cancel(true);
        super.onDestroy();
    }
}
