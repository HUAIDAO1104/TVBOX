package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;

public class EpisodeGridHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeGridBinding binding;

    public EpisodeGridHolder(@NonNull AdapterEpisodeGridBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    @Override
    public void initView(Episode item) {
        binding.text.setSelected(item.isSelected());
        binding.text.setText(EpisodeAdapter.displayName(item));
        binding.text.setContentDescription(item.getDesc().concat(item.getName()) + (item.isSelected() ? "，正在播放" : ""));
        if(listener.isWatched(item))binding.text.append(" ✓");
        binding.text.setContentDescription(item.getName()+(listener.isWatched(item)?"，已看":"，未看")+(item.isSelected()?"，正在播放":""));
        binding.text.setOnLongClickListener(v->listener.onItemLongClick(item));
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
    }
}
