package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterRepositoryItemBinding;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

public class RepositoryItemAdapter extends RecyclerView.Adapter<RepositoryItemAdapter.ViewHolder> {

    public interface Listener {
        void onClick(RepositoryItem item);
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Listener listener;
    private boolean glass;

    public RepositoryItemAdapter(Listener listener) {
        this.listener = listener;
    }

    public RepositoryItemAdapter glass() {
        glass = true;
        return this;
    }

    public void load() {
        replace(loadEntries());
    }

    public void loadAsync(Runnable onLoaded) {
        Task.execute(() -> {
            List<Entry> loaded = loadEntries();
            App.post(() -> {
                replace(loaded);
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    private List<Entry> loadEntries() {
        List<Entry> loaded = new ArrayList<>();
        RepositoryManager manager = RepositoryManager.get();
        for (Repository repository : manager.getEnabled()) {
            for (RepositoryItem item : manager.getItems(repository.getId())) {
                loaded.add(new Entry(repository.getName(), item));
            }
        }
        return loaded;
    }

    private void replace(List<Entry> loaded) {
        entries.clear();
        entries.addAll(loaded);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterRepositoryItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (glass) holder.binding.getRoot().setBackgroundResource(R.drawable.selector_dialog_glass_card);
        holder.binding.name.setText(SearchDisplayName.removeEmoji(entry.item.getName()));
        holder.binding.repository.setText(SearchDisplayName.removeEmoji(entry.repository));
        holder.binding.getRoot().setOnClickListener(v -> listener.onClick(entry.item));
    }

    private record Entry(String repository, RepositoryItem item) {
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterRepositoryItemBinding binding;

        ViewHolder(AdapterRepositoryItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
