package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.databinding.ActivityRepositoryDetailBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.repository.RepositoryItemChecker;
import com.fongmi.android.tv.repository.RepositoryStatus;
import com.fongmi.android.tv.ui.adapter.RepositoryManageAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.SecretRedactor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class RepositoryDetailActivity extends BaseActivity implements RepositoryManageAdapter.Listener {

    private static final String EXTRA_ID = "repository_id";
    private final RepositoryManager manager = RepositoryManager.get();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private ActivityRepositoryDetailBinding binding;
    private RepositoryManageAdapter adapter;
    private Repository repository;

    public static void start(Activity activity, long repositoryId) {
        activity.startActivity(new Intent(activity, RepositoryDetailActivity.class).putExtra(EXTRA_ID, repositoryId));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityRepositoryDetailBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        repository = com.fongmi.android.tv.db.AppDatabase.get().getRepositoryDao().findById(getIntent().getLongExtra(EXTRA_ID, 0));
        if (repository == null) {
            finish();
            return;
        }
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 10));
        binding.recycler.setAdapter(adapter = new RepositoryManageAdapter(this));
        render();
        binding.recycler.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.refresh.setOnClickListener(v -> manager.sync(repository, new com.fongmi.android.tv.repository.RepositorySyncManager.Listener() {
            @Override public void onSuccess(Repository item, boolean cached) { repository = item; render(); }
            @Override public void onError(Repository item, String message, boolean hasCache) { repository = item; render(); }
        }));
    }

    private void render() {
        binding.title.setText(repository.getName());
        binding.url.setText(SecretRedactor.redact(repository.getUrl()));
        binding.type.setText(repository.getUrl().startsWith("http") ? (repository.getUrl().startsWith("https") ? "HTTPS" : "HTTP · " + getString(R.string.repository_insecure)) : getString(R.string.repository_local));
        binding.status.setText(switch (repository.getStatus()) {
            case RepositoryStatus.SYNCING -> R.string.repository_status_syncing;
            case RepositoryStatus.SUCCESS -> R.string.repository_status_success;
            case RepositoryStatus.FAILED -> R.string.repository_status_failed;
            case RepositoryStatus.DISABLED -> R.string.repository_status_disabled;
            default -> R.string.repository_status_idle;
        });
        binding.meta.setText(getString(R.string.repository_detail_meta,
                manager.getItemCount(repository.getId()),
                repository.getLastSuccessAt() == 0 ? getString(R.string.repository_never) : formatter.format(Instant.ofEpochMilli(repository.getLastSuccessAt())),
                repository.isBuiltIn() ? getString(R.string.repository_read_only) : getString(R.string.repository_user_owned)));
        binding.flags.setText(getString(R.string.repository_detail_flags,
                repository.isEnabled() ? getString(R.string.repository_flag_enabled) : getString(R.string.repository_flag_disabled),
                repository.isAutoSync() ? getString(R.string.repository_flag_auto) : getString(R.string.repository_flag_manual),
                manager.getMappingCount(repository.getId())));
        binding.syncInfo.setText(getString(R.string.repository_detail_sync,
                repository.getEtag().isEmpty() ? "—" : repository.getEtag(),
                repository.getLastModified().isEmpty() ? "—" : repository.getLastModified(),
                repository.getLastSyncAt() == 0 ? getString(R.string.repository_never) : formatter.format(Instant.ofEpochMilli(repository.getLastSyncAt())),
                repository.getLastFailureAt() == 0 ? getString(R.string.repository_never) : formatter.format(Instant.ofEpochMilli(repository.getLastFailureAt()))));
        binding.cache.setText(repository.getLastSuccessAt() == 0 ? R.string.repository_no_cache : R.string.repository_cache_available);
        binding.error.setText(repository.getErrorMessage());
        binding.error.setVisibility(repository.getErrorMessage().isEmpty() ? View.GONE : View.VISIBLE);
        adapter.submit(manager.getAllItems(repository.getId()), repository.isBuiltIn());
        binding.empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onToggle(RepositoryItem item) {
        manager.setItemEnabled(item, !item.isEnabled());
        render();
    }

    @Override
    public void onMove(RepositoryItem item, int delta) {
        manager.moveItem(item, delta);
        render();
    }

    @Override
    public void onUse(RepositoryItem item) {
        Config config = Config.find(item.getUrl(), item.getName(), item.getType());
        Config previous = switch (item.getType()) {
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> VodConfig.get().getConfig();
        };
        load(item.getType(), config, new Callback() {
            @Override public void success() { manager.updateItemCheck(item, true, ""); Notify.show(R.string.repository_item_selected); render(); }
            @Override public void error(String msg) {
                manager.updateItemCheck(item, false, msg);
                if (previous == null || previous.getUrl().equals(config.getUrl())) {
                    Notify.show(R.string.repository_item_failed);
                    render();
                    return;
                }
                load(item.getType(), previous, new Callback() {
                    @Override public void success() { Notify.show(R.string.repository_item_failed); render(); }
                    @Override public void error(String rollbackError) { Notify.show(R.string.repository_rollback_failed); render(); }
                });
            }
        });
    }

    private void load(int type, Config config, Callback callback) {
        if (type == 1) LiveConfig.load(config, callback);
        else if (type == 2) WallConfig.load(config, callback);
        else VodConfig.load(config, callback);
    }

    @Override
    public void onCheck(RepositoryItem item) {
        RepositoryItemChecker.check(item, new RepositoryItemChecker.Listener() {
            @Override public void onStart(RepositoryItem ignored) { render(); }
            @Override public void onComplete(RepositoryItem ignored, boolean success) { render(); }
        });
    }

    @Override
    public void onDelete(RepositoryItem item) {
        if (manager.deleteItem(repository, item)) render();
        else Notify.show(R.string.repository_read_only_notice);
    }
}
