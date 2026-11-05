package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeHoriBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ResUtil;

public class EpisodeHoriHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeHoriBinding binding;
    private final int maxWidth;

    public EpisodeHoriHolder(@NonNull AdapterEpisodeHoriBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        this.maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(32);
    }

    @Override
    public void initView(Episode item) {
        binding.text.setMaxWidth(maxWidth);
        binding.text.setSelected(item.isSelected());
        binding.text.setText(item.getDesc().concat(item.getName()));
        if(listener.isWatched(item))binding.text.append(" ✓");
        binding.text.setContentDescription(item.getName()+(listener.isWatched(item)?"，已看":"，未看")+(item.isSelected()?"，正在播放":""));
        binding.text.setOnLongClickListener(v->listener.onItemLongClick(item));
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
    }
}
