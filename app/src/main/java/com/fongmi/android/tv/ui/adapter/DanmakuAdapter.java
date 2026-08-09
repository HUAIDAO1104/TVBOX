package com.fongmi.android.tv.ui.adapter;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterDanmakuBinding;
import com.fongmi.android.tv.playback.vod.DanmakuMatch;
import com.fongmi.android.tv.playback.vod.DanmakuResultGrouper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DanmakuAdapter extends RecyclerView.Adapter<DanmakuAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Danmaku> mItems;
    private final List<Row> mRows;
    private final boolean grouped;
    private String selectedUrl;

    public DanmakuAdapter(OnClickListener listener) {
        this(listener, false);
    }

    public DanmakuAdapter(OnClickListener listener, boolean grouped) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.mRows = new ArrayList<>();
        this.grouped = grouped;
        this.selectedUrl = "";
        setHasStableIds(true);
    }

    public interface OnClickListener {

        void onItemClick(Danmaku item);

        default void onItemFocus(Danmaku item, int position, int total) {
        }
    }

    public void clear() {
        int size = mItems.size();
        mItems.clear();
        mRows.clear();
        notifyItemRangeRemoved(0, size);
    }

    public DanmakuAdapter addAll(List<Danmaku> items) {
        if (items == null) return this;
        int start = mItems.size();
        for (Danmaku item : items) {
            if (!isDisplayable(item)) continue;
            mItems.add(item);
        }
        rebuildRows();
        int count = mItems.size() - start;
        if (count > 0) notifyItemRangeInserted(start, count);
        return this;
    }

    public void setItems(List<Danmaku> items) {
        List<Row> oldRows = new ArrayList<>(mRows);
        List<Danmaku> nextItems = new ArrayList<>();
        if (items != null) {
            for (Danmaku item : items) {
                if (!isDisplayable(item)) continue;
                nextItems.add(item);
            }
        }
        List<Row> nextRows = rowsOf(nextItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffCallback(oldRows, nextRows), true);
        mItems.clear();
        mItems.addAll(nextItems);
        mRows.clear();
        mRows.addAll(nextRows);
        diff.dispatchUpdatesTo(this);
    }

    /** Updates only the rows whose selected state changed, preserving TV focus. */
    public void setSelected(Danmaku selected) {
        String next = selected == null ? "" : selected.getUrl();
        if (Objects.equals(selectedUrl, next)) {
            int position = indexOf(next);
            if (position >= 0) notifyItemChanged(position);
            return;
        }
        int previousPosition = indexOf(selectedUrl);
        selectedUrl = next;
        int nextPosition = indexOf(selectedUrl);
        if (previousPosition >= 0) notifyItemChanged(previousPosition);
        if (nextPosition >= 0 && nextPosition != previousPosition) notifyItemChanged(nextPosition);
    }

    private int indexOf(String url) {
        if (url == null || url.isEmpty()) return RecyclerView.NO_POSITION;
        for (int i = 0; i < mItems.size(); i++) {
            if (url.equals(mItems.get(i).getUrl())) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    public int indexOfUrl(String url) {
        return indexOf(url);
    }

    public Danmaku getItem(int position) {
        return position < 0 || position >= mItems.size() ? null : mItems.get(position);
    }

    private static boolean isDisplayable(Danmaku item) {
        return item != null && !item.isEmpty() && !item.isBlockedSource();
    }

    public int getSelected() {
        for (int i = 0; i < mItems.size(); i++) if (Objects.equals(selectedUrl, mItems.get(i).getUrl())) return i;
        return 0;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public long getItemId(int position) {
        Danmaku item = getItem(position);
        return item == null ? RecyclerView.NO_ID : stableId(item.getUrl());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDanmakuBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Danmaku item = mItems.get(position);
        Row row = mRows.get(position);
        Resources resources = holder.itemView.getResources();
        boolean selected = Objects.equals(selectedUrl, item.getUrl());
        holder.binding.group.setVisibility(row.showGroup ? View.VISIBLE : View.GONE);
        if (row.showGroup) holder.binding.group.setText(groupLabel(resources, item));
        holder.binding.text.setText(selected
                ? resources.getString(R.string.danmaku_result_selected, item.getName())
                : item.getName());
        holder.binding.text.setSelected(selected);
        if (!holder.itemView.hasFocus()) {
            holder.itemView.setScaleX(1.0f);
            holder.itemView.setScaleY(1.0f);
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AdapterDanmakuBinding binding;

        public ViewHolder(@NonNull AdapterDanmakuBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
            itemView.setOnFocusChangeListener((view, hasFocus) -> {
                view.animate().cancel();
                view.animate()
                        .scaleX(hasFocus ? 1.035f : 1.0f)
                        .scaleY(hasFocus ? 1.035f : 1.0f)
                        .setDuration(hasFocus ? 110L : 80L)
                        .start();
                if (!hasFocus) return;
                int position = getBindingAdapterPosition();
                Danmaku item = getItem(position);
                if (item != null) listener.onItemFocus(item, position, getItemCount());
            });
        }

        @Override
        public void onClick(View view) {
            int position = getBindingAdapterPosition();
            Danmaku item = getItem(position);
            if (item != null) listener.onItemClick(item);
        }
    }

    private void rebuildRows() {
        mRows.clear();
        mRows.addAll(rowsOf(mItems));
    }

    private List<Row> rowsOf(List<Danmaku> items) {
        List<Row> rows = new ArrayList<>(items.size());
        String previousGroup = null;
        for (Danmaku item : items) {
            String group = grouped ? DanmakuResultGrouper.groupKey(item) : "";
            boolean showGroup = grouped && !Objects.equals(previousGroup, group);
            rows.add(new Row(item, group, showGroup));
            previousGroup = group;
        }
        return rows;
    }

    private static String groupLabel(Resources resources, Danmaku item) {
        String name = item == null ? "" : item.getName();
        String provider = DanmakuMatch.resultProvider(name);
        String platform = DanmakuMatch.resultPlatform(name);
        String source = provider.isEmpty() ? platform : platform.isEmpty() ? provider : provider + " / " + platform;
        if (source.isEmpty()) source = resources.getString(R.string.danmaku_group_other_source);
        Integer season = DanmakuMatch.resultSeason(name);
        return season == null
                ? resources.getString(R.string.danmaku_group_unseasoned, source)
                : resources.getString(R.string.danmaku_group_season, season, source);
    }

    private static long stableId(String value) {
        long hash = 0xcbf29ce484222325L;
        String text = Objects.toString(value, "");
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private record Row(Danmaku item, String group, boolean showGroup) {
    }

    private static final class DiffCallback extends DiffUtil.Callback {

        private final List<Row> oldRows;
        private final List<Row> newRows;

        private DiffCallback(List<Row> oldRows, List<Row> newRows) {
            this.oldRows = oldRows;
            this.newRows = newRows;
        }

        @Override
        public int getOldListSize() {
            return oldRows.size();
        }

        @Override
        public int getNewListSize() {
            return newRows.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldRows.get(oldItemPosition).item.getUrl()
                    .equals(newRows.get(newItemPosition).item.getUrl());
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Row oldRow = oldRows.get(oldItemPosition);
            Row newRow = newRows.get(newItemPosition);
            return oldRow.showGroup == newRow.showGroup
                    && oldRow.group.equals(newRow.group)
                    && oldRow.item.getName().equals(newRow.item.getName());
        }
    }
}
