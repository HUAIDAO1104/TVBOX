package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSearchBinding;
import com.fongmi.android.tv.utils.ImgUtil;

public class SearchAdapter extends BaseDiffAdapter<com.fongmi.android.tv.ui.search.SearchGroup, SearchAdapter.ViewHolder> {

    private final OnClickListener listener;

    public SearchAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Vod item);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        com.fongmi.android.tv.ui.search.SearchGroup group = getItem(position);
        Vod item = group.first();
        boolean large=holder.itemView.getResources().getConfiguration().fontScale>=1.4f;
        holder.binding.resultActions.setOrientation(large?android.widget.LinearLayout.VERTICAL:android.widget.LinearLayout.HORIZONTAL);
        holder.binding.site.setLayoutParams(new android.widget.LinearLayout.LayoutParams(large?ViewGroup.LayoutParams.MATCH_PARENT:0,ViewGroup.LayoutParams.WRAP_CONTENT,large?0:1));
        holder.binding.name.setText(item.getName());
        holder.binding.site.setText(com.fongmi.android.tv.ui.search.SearchDisplayName.clean(item.getSiteName()));
        String metadata = java.util.stream.Stream.of(item.getYear(), item.getArea(), item.getTypeName()).filter(v -> v != null && !v.isEmpty()).collect(java.util.stream.Collectors.joining(" · "));
        holder.binding.metadata.setText(metadata);
        holder.binding.metadata.setVisibility(metadata.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.watch.setText(group.sources.size()>1 ? "选择来源" : item.isFolder() ? "打开" : "播放");
        holder.binding.remark.setText(item.getRemarks());
        holder.binding.site.setVisibility(item.getSiteVisible());
        if(group.sources.size()>1){holder.binding.site.setVisibility(View.VISIBLE);holder.binding.site.setText(group.sources.size()+" 个播放选项");}
        holder.binding.remark.setVisibility(item.getRemarkVisible());
        holder.binding.getRoot().setContentDescription(item.getName()+"，"+metadata+"，"+holder.binding.site.getText()+"，"+holder.binding.watch.getText());
        holder.binding.getRoot().setOnClickListener(v -> {
            if(group.sources.size()==1){listener.onItemClick(item);return;}
            String[] labels=group.sources.stream().map(source->com.fongmi.android.tv.ui.search.SearchDisplayName.clean(source.getSiteName())+" · "+source.getName()+"\n"+source.getYear()+" "+source.getRemarks()).toArray(String[]::new);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(v.getContext()).setTitle(item.getName()+" · 选择来源")
                .setItems(labels,(dialog,which)->listener.onItemClick(group.sources.get(which))).setNegativeButton("取消",null).show();
        });
        ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.image).clear(holder.binding.image);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchBinding binding;

        ViewHolder(@NonNull AdapterSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
