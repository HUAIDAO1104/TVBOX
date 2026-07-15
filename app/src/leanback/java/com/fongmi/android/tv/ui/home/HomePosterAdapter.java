package com.fongmi.android.tv.ui.home;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterHomePosterBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class HomePosterAdapter extends RecyclerView.Adapter<HomePosterAdapter.ViewHolder> {

    public interface Listener {
        void onPosterClick(Vod item);

        boolean onPosterLongClick(Vod item);

        void onPosterFocused(Vod item, int position);
    }

    private final List<Vod> items = new ArrayList<>();
    private final Listener listener;
    private int cardWidth;
    private int cardHeight;

    public HomePosterAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<Vod> next) {
        List<Vod> submitted = next == null ? List.of() : new ArrayList<>(next);
        List<Vod> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return submitted.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return stableKey(old.get(oldItemPosition)).equals(stableKey(submitted.get(newItemPosition)));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).isSameContent(submitted.get(newItemPosition));
            }
        });
        items.clear();
        items.addAll(submitted);
        diff.dispatchUpdatesTo(this);
    }

    public void setCardSize(int width, int height) {
        if (width <= 0 || height <= 0 || width == cardWidth && height == cardHeight) return;
        cardWidth = width;
        cardHeight = height;
        notifyItemRangeChanged(0, getItemCount());
    }

    public Vod get(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    public String stableKeyAt(int position) {
        Vod item = get(position);
        return item == null ? "" : stableKey(item);
    }

    public List<String> stableKeys() {
        List<String> keys = new ArrayList<>(items.size());
        for (Vod item : items) keys.add(stableKey(item));
        return keys;
    }

    @Override
    public long getItemId(int position) {
        return HomeFeaturedPolicy.stableId(stableKey(items.get(position)));
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterHomePosterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Vod item = items.get(position);
        applyCardSize(holder.binding.getRoot());
        holder.binding.name.setText(item.getName());
        String metadata = metadata(item);
        holder.binding.remark.setText(metadata);
        holder.binding.remark.setVisibility(metadata.isEmpty() ? View.GONE : View.VISIBLE);
        ImgUtil.load(item.getName(), item.getPic(), holder.binding.poster, true);
        holder.binding.getRoot().setContentDescription(item.getName() + (metadata.isEmpty() ? "" : ", " + metadata));
        holder.binding.getRoot().setOnClickListener(view -> listener.onPosterClick(item));
        holder.binding.getRoot().setOnLongClickListener(view -> listener.onPosterLongClick(item));
        holder.binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> {
            animateFocus(view, hasFocus);
            int adapterPosition = holder.getBindingAdapterPosition();
            if (hasFocus && adapterPosition != RecyclerView.NO_POSITION) listener.onPosterFocused(get(adapterPosition), adapterPosition);
        });
    }

    private void applyCardSize(View view) {
        if (cardWidth <= 0 || cardHeight <= 0) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null || params.width == cardWidth && params.height == cardHeight) return;
        params.width = cardWidth;
        params.height = cardHeight;
        view.setLayoutParams(params);
    }

    private static String stableKey(Vod item) {
        String itemId = item.getId().isEmpty() ? item.getName() : item.getId();
        return HomeFeaturedPolicy.stableKey(item.getSiteKey(), itemId);
    }

    private String metadata(Vod item) {
        if (!item.getYear().isEmpty() && !item.getRemarks().isEmpty()) return TextUtils.join(" · ", List.of(item.getYear(), item.getRemarks()));
        if (!item.getYear().isEmpty()) return item.getYear();
        return item.getRemarks();
    }

    private void animateFocus(View view, boolean focused) {
        float scale = focused ? 1.035f : 1f;
        view.setTranslationZ(focused ? 8f : 0f);
        view.animate().cancel();
        view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationY(focused ? -2f : 0f)
                .setDuration(focused ? 180 : 145)
                .withLayer()
                .start();
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
        private final AdapterHomePosterBinding binding;

        public ViewHolder(AdapterHomePosterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
