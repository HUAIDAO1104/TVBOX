package com.fongmi.android.tv.ui.adapter;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterSearchSourceFamilyBinding;

import java.util.List;

/** Persistent source lane used to filter the result grid without starting a new search. */
public final class SearchSourceFamilyAdapter extends RecyclerView.Adapter<SearchSourceFamilyAdapter.ViewHolder> {

    public record Item(String id, String name, String status, boolean active) {
    }

    public interface Listener {
        void onSelect(Item item);
    }

    private final Listener listener;
    private List<Item> items = List.of();

    public SearchSourceFamilyAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<Item> next) {
        List<Item> safe = next == null ? List.of() : List.copyOf(next);
        List<Item> previous = items;
        if (previous.equals(safe)) return;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return safe.size(); }
            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return previous.get(oldItemPosition).id().equals(safe.get(newItemPosition).id());
            }
            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return previous.get(oldItemPosition).equals(safe.get(newItemPosition));
            }
        }, false);
        items = safe;
        diff.dispatchUpdatesTo(this);
    }

    public int positionOf(String id) {
        if (id == null) return -1;
        for (int index = 0; index < items.size(); index++) {
            if (id.equals(items.get(index).id())) return index;
        }
        return -1;
    }

    public Item get(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id().hashCode();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchSourceFamilyBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = items.get(position);
        holder.binding.getRoot().setActivated(item.active());
        holder.binding.activeIndicator.setVisibility(item.active() ? View.VISIBLE : View.INVISIBLE);
        holder.binding.name.setTypeface(null, item.active() ? Typeface.BOLD : Typeface.NORMAL);
        holder.binding.name.setText(item.name());
        holder.binding.status.setText(item.status());
        holder.binding.getRoot().setOnClickListener(view -> listener.onSelect(item));
        holder.binding.getRoot().setOnFocusChangeListener((view, focused) -> {
            view.animate().cancel();
            view.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f)
                    .setDuration(focused ? 150 : 100).start();
        });
        holder.binding.getRoot().setContentDescription(item.name() + " " + item.status());
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final AdapterSearchSourceFamilyBinding binding;

        ViewHolder(AdapterSearchSourceFamilyBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
