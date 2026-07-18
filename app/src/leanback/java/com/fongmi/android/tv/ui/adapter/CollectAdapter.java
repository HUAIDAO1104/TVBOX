package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.databinding.AdapterSearchSourceBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;

import java.util.ArrayList;
import java.util.List;

public class CollectAdapter extends RecyclerView.Adapter<CollectAdapter.ViewHolder> {

    private final List<Collect> mItems;

    public CollectAdapter() {
        mItems = new ArrayList<>();
    }

    public void add(Collect item) {
        if (mItems.contains(item)) return;
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public Collect get(int position) {
        return mItems.get(position);
    }

    public int findPosition(String siteKey) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).getSite().getKey().equals(siteKey)) return i;
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchSourceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Collect item = mItems.get(position);
        holder.binding.getRoot().setOnClickListener(null);
        String name = SearchDisplayName.clean(item.getSite().getName());
        holder.binding.text.setText(name.isEmpty() ? SearchDisplayName.removeEmoji(item.getSite().getName()) : name);
        holder.binding.text.setContentDescription(holder.binding.text.getText());
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchSourceBinding binding;

        ViewHolder(@NonNull AdapterSearchSourceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
