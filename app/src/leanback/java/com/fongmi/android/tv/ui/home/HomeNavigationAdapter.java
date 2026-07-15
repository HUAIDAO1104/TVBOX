package com.fongmi.android.tv.ui.home;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterHomeNavBinding;

import java.util.ArrayList;
import java.util.List;

public class HomeNavigationAdapter extends RecyclerView.Adapter<HomeNavigationAdapter.ViewHolder> {

    public interface Listener {
        void onNavClick(HomeNavItem item);
    }

    private final List<HomeNavItem> items = new ArrayList<>();
    private final Listener listener;
    private String selectedId = HomeState.HOME_ID;

    public HomeNavigationAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<HomeNavItem> next) {
        List<HomeNavItem> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).id().equals(next.get(newItemPosition).id());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).title().equals(next.get(newItemPosition).title());
            }
        });
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    public void select(String id) {
        String previous = selectedId;
        selectedId = id == null ? HomeState.HOME_ID : id;
        int oldPosition = indexOf(previous);
        int newPosition = indexOf(selectedId);
        if (oldPosition >= 0) notifyItemChanged(oldPosition);
        if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition);
    }

    public int indexOf(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) return i;
        return -1;
    }

    public HomeNavItem get(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).stableId();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterHomeNavBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HomeNavItem item = items.get(position);
        holder.binding.text.setText(item.title());
        boolean selected = item.id().equals(selectedId);
        holder.binding.text.setSelected(selected);
        holder.binding.text.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        holder.binding.text.setContentDescription(item.title());
        holder.binding.getRoot().setOnClickListener(view -> listener.onNavClick(item));
        holder.binding.getRoot().setOnFocusChangeListener((view, focused) -> {
            view.setTranslationZ(focused ? 6f : 0f);
            float scale = focused ? 1.02f : 1f;
            view.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final AdapterHomeNavBinding binding;

        public ViewHolder(AdapterHomeNavBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
