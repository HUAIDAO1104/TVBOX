package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.databinding.AdapterParseBinding;

import java.util.List;

public class ParseAdapter extends RecyclerView.Adapter<ParseAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Parse> mItems;
    private final String mSiteKey;

    public ParseAdapter(OnClickListener listener) {
        this(listener, "");
    }

    public ParseAdapter(OnClickListener listener, String siteKey) {
        mListener = listener;
        mSiteKey = siteKey == null ? "" : siteKey;
        mItems = VodConfig.get().getPlaybackParses(mSiteKey);
    }

    public int getPosition() {
        Parse selected = VodConfig.get().getPlaybackParse(mSiteKey);
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).equals(selected)) return i;
        return 0;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterParseBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Parse item = mItems.get(position);
        holder.binding.text.setText(item.getName());
        holder.binding.text.setSelected(item.equals(VodConfig.get().getPlaybackParse(mSiteKey)));
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(Parse item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterParseBinding binding;

        ViewHolder(@NonNull AdapterParseBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
