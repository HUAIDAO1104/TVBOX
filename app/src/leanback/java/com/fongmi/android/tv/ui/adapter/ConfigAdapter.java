package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;

import java.util.List;
import java.util.ArrayList;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> mItems;
    private boolean readOnly;
    private boolean glass;
    private String currentUrl = "";

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ConfigAdapter current(Config current) {
        currentUrl = current == null ? "" : current.getUrl();
        return this;
    }

    public ConfigAdapter glass() {
        glass = true;
        return this;
    }

    public ConfigAdapter addAll(int type) {
        setItems(Config.getAll(type));
        return this;
    }

    public void setItems(List<Config> items) {
        mItems = items == null ? new ArrayList<>() : new ArrayList<>(items);
        if (!mItems.isEmpty() && !readOnly) mItems.remove(0);
        notifyDataSetChanged();
    }

    public int remove(Config item) {
        int position = mItems.indexOf(item);
        if (position == -1) return -1;
        item.delete();
        mItems.remove(position);
        notifyItemRemoved(position);
        return getItemCount();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        if (glass) {
            holder.binding.text.setBackgroundResource(R.drawable.selector_dialog_glass_card);
            holder.binding.delete.setBackgroundResource(R.drawable.selector_dialog_glass_card);
        }
        boolean current = item.getUrl().equals(currentUrl);
        holder.binding.text.setText((current ? "●  " : "    ") + SearchDisplayName.removeEmoji(item.getDesc()));
        holder.binding.text.setSelected(current);
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        holder.binding.delete.setOnClickListener(v -> listener.onDeleteClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        public ViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
