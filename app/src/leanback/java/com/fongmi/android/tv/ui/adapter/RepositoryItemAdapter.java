package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.databinding.AdapterRepositoryItemBinding;
import com.fongmi.android.tv.repository.RepositoryManager;
import com.fongmi.android.tv.ui.search.SearchDisplayName;

import java.util.ArrayList;
import java.util.List;

public class RepositoryItemAdapter extends RecyclerView.Adapter<RepositoryItemAdapter.ViewHolder> {

    public interface Listener {
        void onClick(RepositoryItem item);
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Listener listener;

    public RepositoryItemAdapter(Listener listener) {
        this.listener = listener;
    }

    public void load() {
        entries.clear();
        RepositoryManager manager = RepositoryManager.get();
        for (Repository repository : manager.getEnabled()) {
            for (RepositoryItem item : manager.getItems(repository.getId())) {
                entries.add(new Entry(repository.getName(), item));
            }
        }
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
