package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.AdapterFlagBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;

import java.util.ArrayList;
import java.util.List;

public class FlagAdapter extends RecyclerView.Adapter<FlagAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Flag> mItems;
    private int nextFocusDown;

    public FlagAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        nextFocusDown = R.id.episode;
    }

    public void addAll(List<Flag> items) {
        mItems.clear();
        for (Flag item : items) {
            if (item != null && !item.isBlockedPlaybackSource()) mItems.add(item);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public Flag get(int position) {
        return mItems.get(position);
    }

    public int indexOf(Flag item) {
        return mItems.indexOf(item);
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public void setNextFocusDown(int nextFocusDown) {
        this.nextFocusDown = nextFocusDown;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFlagBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Flag item = mItems.get(position);
        holder.binding.text.setText(displayName(holder, item, position));
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setNextFocusDownId(nextFocusDown);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    private String displayName(ViewHolder holder, Flag item, int position) {
        String clean = SearchDisplayName.clean(item.getShow().replaceAll("(?i)[|┃].*$", ""));
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("gzh") || lower.contains("公众号") || lower.contains("免费分享") || lower.contains("扫码")) clean = "";
        return clean.isEmpty() ? holder.itemView.getContext().getString(R.string.detail_v2_source_fallback, position + 1) : clean;
    }

    public interface OnClickListener {

        void onItemClick(Flag item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFlagBinding binding;

        ViewHolder(@NonNull AdapterFlagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
