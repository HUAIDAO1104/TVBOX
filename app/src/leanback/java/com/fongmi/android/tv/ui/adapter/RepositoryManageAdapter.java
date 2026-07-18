package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.databinding.AdapterRepositoryManageItemBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.github.catvod.utils.SecretRedactor;

import java.util.ArrayList;
import java.util.List;

public class RepositoryManageAdapter extends RecyclerView.Adapter<RepositoryManageAdapter.ViewHolder> {

    public interface Listener {
        void onToggle(RepositoryItem item);
        void onMove(RepositoryItem item, int delta);
        void onUse(RepositoryItem item);
        void onCheck(RepositoryItem item);
        void onDelete(RepositoryItem item);
    }

    private final List<RepositoryItem> items = new ArrayList<>();
    private final Listener listener;
    private boolean readOnly;

    public RepositoryManageAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<RepositoryItem> values, boolean readOnly) {
        this.readOnly = readOnly;
        items.clear();
        items.addAll(values);
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterRepositoryManageItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RepositoryItem item = items.get(position);
        holder.binding.name.setText(SearchDisplayName.removeEmoji(item.getName()));
        holder.binding.type.setText(item.getItemId().startsWith("spider-") ? R.string.repository_dynamic_line : R.string.repository_static_config);
        holder.binding.url.setText(SecretRedactor.redact(item.getUrl()));
        String status = switch (item.getCheckStatus()) {
            case "CHECKING" -> holder.itemView.getContext().getString(R.string.repository_checking);
            case "AVAILABLE" -> holder.itemView.getContext().getString(R.string.repository_available);
            case "FAILED" -> holder.itemView.getContext().getString(R.string.repository_unavailable);
            default -> holder.itemView.getContext().getString(R.string.repository_unchecked);
        };
        if (item.getLastCheckedAt() > 0) status += " · " + DateFormat.format("MM-dd HH:mm", item.getLastCheckedAt());
        if (!item.getErrorMessage().isEmpty()) status += " · " + item.getErrorMessage();
        holder.binding.checkStatus.setText(status);
        holder.binding.enable.setText(item.isEnabled() ? R.string.repository_disable : R.string.repository_enable);
        holder.binding.delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        holder.binding.enable.setOnClickListener(v -> listener.onToggle(item));
        holder.binding.up.setOnClickListener(v -> listener.onMove(item, -1));
        holder.binding.down.setOnClickListener(v -> listener.onMove(item, 1));
        holder.binding.use.setOnClickListener(v -> listener.onUse(item));
        holder.binding.check.setOnClickListener(v -> listener.onCheck(item));
        holder.binding.delete.setOnClickListener(v -> listener.onDelete(item));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final AdapterRepositoryManageItemBinding binding;
        ViewHolder(AdapterRepositoryManageItemBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
