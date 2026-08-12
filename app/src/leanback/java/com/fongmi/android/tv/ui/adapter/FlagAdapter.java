package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.AdapterFlagBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;

import java.util.ArrayList;
import java.util.List;

public class FlagAdapter extends RecyclerView.Adapter<FlagAdapter.ViewHolder> {

    private static final String PAYLOAD_SELECTION = "selection";
    private static final String PAYLOAD_FOCUS_DOWN = "focus_down";
    private final OnClickListener mListener;
    private final List<Flag> mItems;
    private int selectedPosition;
    private int nextFocusDown;

    public FlagAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        selectedPosition = RecyclerView.NO_POSITION;
        nextFocusDown = R.id.episode;
        setHasStableIds(true);
    }

    public void addAll(List<Flag> items) {
        mItems.clear();
        for (Flag item : items) {
            if (item != null && !item.isBlockedPlaybackSource()) mItems.add(item);
        }
        selectedPosition = findSelectedPosition();
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        selectedPosition = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    public Flag get(int position) {
        return mItems.get(position);
    }

    public List<Flag> getItems() {
        return new ArrayList<>(mItems);
    }

    public int indexOf(Flag item) {
        return mItems.indexOf(item);
    }

    public int getPosition() {
        int position = findSelectedPosition();
        return position == RecyclerView.NO_POSITION ? 0 : position;
    }

    public void refreshSelection() {
        int previous = selectedPosition;
        selectedPosition = findSelectedPosition();
        if (previous >= 0 && previous < getItemCount()) notifyItemChanged(previous, PAYLOAD_SELECTION);
        if (selectedPosition >= 0 && selectedPosition < getItemCount() && selectedPosition != previous) {
            notifyItemChanged(selectedPosition, PAYLOAD_SELECTION);
        }
    }

    public void setNextFocusDown(int nextFocusDown) {
        if (this.nextFocusDown == nextFocusDown) return;
        this.nextFocusDown = nextFocusDown;
        if (!mItems.isEmpty()) notifyItemRangeChanged(0, mItems.size(), PAYLOAD_FOCUS_DOWN);
    }

    private int findSelectedPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public long getItemId(int position) {
        String key = mItems.get(position).getFlag();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) hash = (hash ^ key.charAt(i)) * 0x100000001b3L;
        return hash;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFlagBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Flag item = mItems.get(position);
        holder.binding.text.setText(displayName(holder, item, position));
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setNextFocusDownId(nextFocusDown);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        Flag item = mItems.get(position);
        for (Object payload : payloads) {
            if (PAYLOAD_SELECTION.equals(payload)) holder.binding.text.setSelected(item.isSelected());
            else if (PAYLOAD_FOCUS_DOWN.equals(payload)) holder.binding.text.setNextFocusDownId(nextFocusDown);
        }
    }

    private String displayName(ViewHolder holder, Flag item, int position) {
        String clean = SearchDisplayName.clean(item.getShow().replaceAll("(?i)[|┃].*$", ""));
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("gzh") || lower.contains("公众号") || lower.contains("免费分享") || lower.contains("扫码")) clean = "";
        return clean.isEmpty() ? holder.itemView.getContext().getString(R.string.detail_v2_source_fallback, position + 1) : clean;
    }

    public interface OnClickListener {

        void onItemClick(Flag item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFlagBinding binding;

        ViewHolder(@NonNull AdapterFlagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
