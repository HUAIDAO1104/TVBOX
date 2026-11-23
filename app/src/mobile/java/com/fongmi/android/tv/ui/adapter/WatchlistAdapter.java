package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.databinding.AdapterLibraryBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.watch.WatchlistStore;
import java.util.List;
import java.util.function.Consumer;

public final class WatchlistAdapter extends RecyclerView.Adapter<WatchlistAdapter.Holder> {
    private final Consumer<WatchlistStore.Entry> open;
    private final Consumer<WatchlistStore.Entry> manage;
    private List<WatchlistStore.Entry> entries = List.of();

    public WatchlistAdapter(Consumer<WatchlistStore.Entry> open, Consumer<WatchlistStore.Entry> manage) {
        this.open = open;
        this.manage = manage;
    }

    public void setItems(List<WatchlistStore.Entry> items) {
        entries = items;
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return entries.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(AdapterLibraryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        var entry = entries.get(position);
        var card = holder.binding;
        card.name.setText(entry.title);
        card.site.setText(entry.source);
        String progress = "已看 " + entry.watchedCount() + " / " + entry.episodes.size() + " 集";
        if (!entry.fresh.isEmpty()) progress = "更新 " + entry.fresh.size() + " 集 · " + progress;
        if (!entry.error.isEmpty()) progress += "\n" + entry.error;
        if (entry.cid != VodConfig.getCid()) progress += "\n切换至此片源后可检查更新";
        card.remark.setText(progress);
        card.progress.setMax(100);
        card.progress.setProgress(entry.episodes.isEmpty() ? 0 : (int) (entry.watchedCount() * 100 / entry.episodes.size()));
        card.delete.setVisibility(View.VISIBLE);
        card.delete.setText("管理剧集与追更 ›");
        card.delete.setOnClickListener(v -> manage.accept(entry));
        ImgUtil.load(entry.title, entry.poster, card.image);
        card.getRoot().setOnClickListener(v -> open.accept(entry));
        card.getRoot().setOnLongClickListener(v -> { manage.accept(entry); return true; });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final AdapterLibraryBinding binding;
        Holder(AdapterLibraryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
