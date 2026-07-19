package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterQuickBinding;
import com.fongmi.android.tv.security.PromotionFilter;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.PosterResolver;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class QuickAdapter extends RecyclerView.Adapter<QuickAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Vod> mItems;
    private final int width;

    public QuickAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        int space = ResUtil.dp2px(24) + ResUtil.dp2px(32);
        width = (ResUtil.getScreenWidth() - space) / 4;
    }

    public void addAll(List<Vod> items) {
        int start = mItems.size();
        for (Vod item : items) {
            if (item != null) PosterResolver.remember(item.getName(), item.getPic());
        }
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public void remove(int position) {
        mItems.remove(position);
        notifyItemRemoved(position);
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public Vod get(int position) {
        return mItems.get(position);
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterQuickBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Vod item = mItems.get(position);
        String site = SearchDisplayName.clean(item.getSiteName());
        holder.binding.name.setText(SearchDisplayName.removeEmoji(PromotionFilter.sanitizeDisplayText(item.getName())));
        holder.binding.site.setText(site);
        holder.binding.site.setVisibility(site.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.binding.remark.setText(PromotionFilter.sanitizeDisplayText(item.getRemarks()));
        ImgUtil.loadPoster(item.getName(), item.getPic(), holder.binding.poster);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.poster).clear(holder.binding.poster);
        super.onViewRecycled(holder);
    }

    public interface OnClickListener {

        void onItemClick(Vod item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterQuickBinding binding;

        ViewHolder(@NonNull AdapterQuickBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
