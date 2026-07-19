package com.fongmi.android.tv.ui.home;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterHomeHistoryBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.PosterResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class HomeHistoryAdapter extends RecyclerView.Adapter<HomeHistoryAdapter.ViewHolder> {

    public interface Listener {
        void onHistoryClick(History item);

        void onHistoryDelete(History item);

        boolean onHistoryLongClick();
    }

    private final List<History> items = new ArrayList<>();
    private final Listener listener;
    private boolean deleteMode;

    public HomeHistoryAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<History> next) {
        List<History> safe = next == null ? List.of() : next.stream().filter(Objects::nonNull).toList();
        for (History item : safe) PosterResolver.remember(item.getVodName(), item.getVodPic());
        List<History> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return safe.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return Objects.equals(old.get(oldItemPosition).getKey(), safe.get(newItemPosition).getKey());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).isSameContent(safe.get(newItemPosition))
                        && old.get(oldItemPosition).getPosition() == safe.get(newItemPosition).getPosition()
                        && old.get(oldItemPosition).getDuration() == safe.get(newItemPosition).getDuration();
            }
        });
        items.clear();
        items.addAll(safe);
        diff.dispatchUpdatesTo(this);
    }

    /** Rebind after the remote home shelf has supplied posters for existing history titles. */
    public void refreshPosters() {
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setDeleteMode(boolean deleteMode) {
        if (this.deleteMode == deleteMode) return;
        this.deleteMode = deleteMode;
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isDeleteMode() {
        return deleteMode;
    }

    @Override
    public long getItemId(int position) {
        return Objects.hashCode(items.get(position).getKey());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterHomeHistoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        History item = items.get(position);
        String displayName = SearchDisplayName.removeEmoji(item.getVodName());
        holder.binding.name.setText(displayName);
        int progress = progress(item);
        String remark = historyRemark(holder.binding.getRoot().getContext(), item, progress);
        holder.binding.remark.setText(remark);
        holder.binding.remark.setVisibility(remark.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.progress.setProgress(progress);
        holder.binding.progress.setVisibility(progress > 0 ? View.VISIBLE : View.INVISIBLE);
        ImgUtil.loadPoster(item.getVodName(), item.getVodPic(), holder.binding.poster);
        holder.binding.getRoot().setContentDescription(displayName + (remark.isEmpty() ? "" : ", " + remark));
        holder.binding.getRoot().setOnClickListener(view -> {
            if (deleteMode) listener.onHistoryDelete(item);
            else listener.onHistoryClick(item);
        });
        holder.binding.getRoot().setOnLongClickListener(view -> listener.onHistoryLongClick());
        holder.binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> animateFocus(view, hasFocus));
    }

    private int progress(History item) {
        if (item.getPosition() <= 0 || item.getDuration() <= 0) return 0;
        return (int) Math.max(0, Math.min(100, item.getPosition() * 100L / item.getDuration()));
    }

    private String historyRemark(Context context, History item, int progress) {
        if (deleteMode) return context.getString(R.string.home_delete_history);
        String episode = item.getVodRemarks().trim();
        if (!episode.isEmpty()) {
            if (episode.startsWith("看到") || episode.startsWith("已看") || episode.startsWith("Watched")) return episode;
            return context.getString(R.string.home_watched_to, episode);
        }
        if (item.getPosition() > 0 && item.getDuration() > item.getPosition()) {
            long minutes = (item.getDuration() - item.getPosition()) / 60000L;
            if (minutes > 0) return context.getString(R.string.home_remaining_minutes, minutes);
        }
        return progress > 0 ? context.getString(R.string.home_watched_percent, progress) : "";
    }

    private void animateFocus(View view, boolean focused) {
        float scale = focused ? 1.035f : 1f;
        view.setTranslationZ(focused ? 8f : 0f);
        view.animate().scaleX(scale).scaleY(scale).setDuration(view.getResources().getInteger(R.integer.tv_focus_animation_duration)).start();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.poster).clear(holder.binding.poster);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final AdapterHomeHistoryBinding binding;

        public ViewHolder(AdapterHomeHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
