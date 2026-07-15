package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSearchVodBinding;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.ui.holder.SearchVodHolder;

public class SearchVodPresenter extends Presenter {

    private final OnClickListener listener;
    private final int[] size;

    public SearchVodPresenter(OnClickListener listener) {
        this.listener = listener;
        this.size = Product.getSpec(Style.rect());
    }

    public interface OnClickListener {
        void onItemClick(Vod item);

        boolean onLongClick(Vod item);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterSearchVodBinding binding = AdapterSearchVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new SearchVodHolder(binding, listener).size(size);
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        ((BaseVodHolder) viewHolder).initView((Vod) item);
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        ((BaseVodHolder) viewHolder).unbind();
    }
}
