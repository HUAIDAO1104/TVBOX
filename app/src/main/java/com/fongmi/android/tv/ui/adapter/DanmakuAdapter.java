package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.databinding.AdapterDanmakuBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DanmakuAdapter extends RecyclerView.Adapter<DanmakuAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Danmaku> mItems;
    private String selectedUrl;

    public DanmakuAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.selectedUrl = "";
    }

    public interface OnClickListener {

        void onItemClick(Danmaku item);
    }

    public void clear() {
        int size = mItems.size();
        mItems.clear();
        notifyItemRangeRemoved(0, size);
    }

    public DanmakuAdapter addAll(List<Danmaku> items) {
        if (items == null) return this;
        int start = mItems.size();
        for (Danmaku item : items) {
            if (!isDisplayable(item)) continue;
            item.setSelected(Objects.equals(selectedUrl, item.getUrl()));
            mItems.add(item);
        }
        int count = mItems.size() - start;
        if (count > 0) notifyItemRangeInserted(start, count);
        return this;
    }

    public void setItems(List<Danmaku> items) {
        mItems.clear();
        if (items != null) {
            for (Danmaku item : items) {
                if (!isDisplayable(item)) continue;
                item.setSelected(Objects.equals(selectedUrl, item.getUrl()));
                mItems.add(item);
            }
        }
        notifyDataSetChanged();
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
        for (Danmaku item : mItems) item.setSelected(Objects.equals(selectedUrl, item.getUrl()));
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

    private static boolean isDisplayable(Danmaku item) {
        return item != null && !item.isEmpty() && !item.isBlockedSource();
    }

    public int getSelected() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDanmakuBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Danmaku item = mItems.get(position);
        holder.binding.text.setText(item.getName());
        holder.binding.text.setSelected(item.isSelected());
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AdapterDanmakuBinding binding;

        public ViewHolder(@NonNull AdapterDanmakuBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            listener.onItemClick(mItems.get(getLayoutPosition()));
        }
    }
}
