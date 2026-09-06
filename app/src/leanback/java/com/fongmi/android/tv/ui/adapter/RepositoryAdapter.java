package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.databinding.AdapterRepositoryBinding;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.repository.RepositoryStatus;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.github.catvod.utils.SecretRedactor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class RepositoryAdapter extends RecyclerView.Adapter<RepositoryAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(Repository repository);

        void onEdit(Repository repository);

        void onToggle(Repository repository);

        void onSync(Repository repository);

        void onMove(Repository repository, int delta);

        void onDelete(Repository repository);
    }

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final RepositoryManager manager = RepositoryManager.get();
    private java.util.Map<Long, String> content = java.util.Map.of();
    private java.util.Map<Long, Integer> counts = java.util.Map.of();
    private final List<Repository> items = new ArrayList<>();
    private final Listener listener;

    public RepositoryAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<Repository> repositories, java.util.Map<Long, Integer> counts) {
        java.util.List<Repository> previous = java.util.List.copyOf(items);
        java.util.Map<Long, String> oldContent = content;
        this.counts = counts;
        java.util.Map<Long, String> nextContent = new java.util.HashMap<>();
        for (Repository item : repositories) nextContent.put(item.getId(), item.getName() + item.getUrl() + meta(item) + item.isEnabled());
        androidx.recyclerview.widget.DiffUtil.DiffResult diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return repositories.size(); }
            @Override public boolean areItemsTheSame(int old, int next) { return previous.get(old).getId() == repositories.get(next).getId(); }
            @Override public boolean areContentsTheSame(int old, int next) { return java.util.Objects.equals(oldContent.get(previous.get(old).getId()), nextContent.get(repositories.get(next).getId())); }
        });
        items.clear();
        items.addAll(repositories);
        content = nextContent;
        diff.dispatchUpdatesTo(this);
    }

    @Override public long getItemId(int position) { return items.get(position).getId(); }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterRepositoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Repository item = items.get(position);
        holder.binding.name.setText(SearchDisplayName.removeEmoji(item.getName()));
        holder.binding.url.setText(SecretRedactor.redact(item.getUrl()));
        holder.binding.meta.setText(meta(item));
        holder.binding.enable.setText(item.isEnabled() ? R.string.repository_disable : R.string.repository_enable);
        holder.binding.info.setOnClickListener(v -> listener.onOpen(item));
        holder.binding.edit.setOnClickListener(v -> listener.onEdit(item));
        holder.binding.enable.setOnClickListener(v -> listener.onToggle(item));
        holder.binding.refresh.setOnClickListener(v -> listener.onSync(item));
        holder.binding.up.setOnClickListener(v -> listener.onMove(item, -1));
        holder.binding.down.setOnClickListener(v -> listener.onMove(item, 1));
        holder.binding.delete.setOnClickListener(v -> listener.onDelete(item));
    }

    private String meta(Repository item) {
        int count = counts.getOrDefault(item.getId(), 0);
        int statusRes = switch (item.getStatus()) {
            case RepositoryStatus.SYNCING -> R.string.repository_status_syncing;
            case RepositoryStatus.SUCCESS -> R.string.repository_status_success;
            case RepositoryStatus.FAILED -> R.string.repository_status_failed;
            case RepositoryStatus.DISABLED -> R.string.repository_status_disabled;
            default -> R.string.repository_status_idle;
        };
        String status = com.fongmi.android.tv.App.get().getString(statusRes);
        String time = item.getLastSuccessAt() == 0 ? com.fongmi.android.tv.App.get().getString(R.string.repository_never) : timeFormatter.format(Instant.ofEpochMilli(item.getLastSuccessAt()));
        return com.fongmi.android.tv.App.get().getString(R.string.repository_list_meta, status, count, time,
                item.isBuiltIn() ? com.fongmi.android.tv.App.get().getString(R.string.repository_builtin_suffix) : "");
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterRepositoryBinding binding;

        ViewHolder(AdapterRepositoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
