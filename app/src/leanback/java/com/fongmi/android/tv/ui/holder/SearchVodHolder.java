package com.fongmi.android.tv.ui.holder;

import android.view.View;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSearchVodBinding;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.ui.presenter.SearchVodPresenter;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class SearchVodHolder extends BaseVodHolder {

    private final AdapterSearchVodBinding binding;
    private final SearchVodPresenter.OnClickListener listener;

    public SearchVodHolder(@NonNull AdapterSearchVodBinding binding, SearchVodPresenter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    public SearchVodHolder size(int[] size) {
        binding.posterContainer.getLayoutParams().height = size[1];
        binding.getRoot().getLayoutParams().width = size[0];
        return this;
    }

    @Override
    public void initView(Vod item) {
        String displayName = SearchDisplayName.clean(item.getName());
        String meta = join(item.getYear(), item.getTypeName());
        String source = join(SearchDisplayName.clean(item.getSiteName()), SearchDisplayName.clean(item.getRemarks()));
        binding.name.setText(displayName.isEmpty() ? item.getName() : displayName);
        binding.meta.setText(meta);
        binding.source.setText(source);
        binding.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        binding.source.setVisibility(source.isEmpty() ? View.GONE : View.VISIBLE);
        binding.getRoot().setContentDescription(join(binding.name.getText().toString(), meta, source));
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        binding.getRoot().setOnLongClickListener(v -> listener.onLongClick(item));
        ImgUtil.load(item.getName(), item.getPic(), binding.posterBackdrop);
        ImgUtil.loadPoster(item.getName(), item.getPic(), binding.poster);
    }

    private String join(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) parts.add(value.trim());
        return String.join(" · ", parts);
    }

    @Override
    public void unbind() {
        Glide.with(binding.poster).clear(binding.poster);
        Glide.with(binding.posterBackdrop).clear(binding.posterBackdrop);
    }
}
