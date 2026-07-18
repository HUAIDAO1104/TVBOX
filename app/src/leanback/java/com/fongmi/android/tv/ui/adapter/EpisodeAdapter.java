package com.fongmi.android.tv.ui.adapter;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeBinding;
import com.fongmi.android.tv.ui.detail.EpisodeDisplayName;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Episode> mItems;
    private int selectedPosition = RecyclerView.NO_POSITION;
    private int nextFocusUp = View.NO_ID;
    private int nextFocusDown = View.NO_ID;

    public EpisodeAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        setHasStableIds(true);
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        selectedPosition = findSelectedPosition();
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        selectedPosition = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    public int getPosition() {
        int position = findSelectedPosition();
        return position == RecyclerView.NO_POSITION ? 0 : position;
    }

    public void refreshSelection() {
        int previous = selectedPosition;
        selectedPosition = findSelectedPosition();
        if (previous >= 0 && previous < getItemCount()) notifyItemChanged(previous);
        if (selectedPosition >= 0 && selectedPosition < getItemCount() && selectedPosition != previous) {
            notifyItemChanged(selectedPosition);
        }
    }

    public void setFocusBounds(int nextFocusUp, int nextFocusDown) {
        int safeUp = nextFocusUp > 0 ? nextFocusUp : View.NO_ID;
        int safeDown = nextFocusDown > 0 ? nextFocusDown : View.NO_ID;
        if (this.nextFocusUp == safeUp && this.nextFocusDown == safeDown) return;
        this.nextFocusUp = safeUp;
        this.nextFocusDown = safeDown;
        if (!mItems.isEmpty()) notifyItemRangeChanged(0, mItems.size());
    }

    private int findSelectedPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
    }

    public Episode getActivated() {
        return mItems.isEmpty() ? new Episode() : mItems.get(getPosition());
    }

    public Episode getNext() {
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public long getItemId(int position) {
        Episode item = mItems.get(position);
        String key = item.getName() + '\u0000' + item.getUrl();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) hash = (hash ^ key.charAt(i)) * 0x100000001b3L;
        return hash;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode item = mItems.get(position);
        // Interior movement remains geometric. Only the first and last visual rows have explicit
        // exits, so DPAD never skips an episode row or escapes into the fixed left detail pane.
        boolean firstRow = position < 2;
        boolean lastRow = position / 2 == (getItemCount() - 1) / 2;
        holder.binding.text.setNextFocusUpId(firstRow ? nextFocusUp : View.NO_ID);
        holder.binding.text.setNextFocusDownId(lastRow ? nextFocusDown : View.NO_ID);
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setTypeface(item.isSelected() ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        holder.binding.text.setCompoundDrawablesRelativeWithIntrinsicBounds(item.isSelected() ? R.drawable.detail_v2_ic_playing : 0, 0, 0, 0);
        holder.binding.text.animate().cancel();
        applyFocusState(holder.binding.text, holder.binding.text.isFocused(), false);
        holder.binding.text.setOnFocusChangeListener((view, hasFocus) -> applyFocusState(view, hasFocus, true));
        String rawName = SearchDisplayName.removeEmoji(item.getDesc().concat(item.getName()));
        String displayName = EpisodeDisplayName.format(rawName);
        holder.binding.text.setText(displayName);
        holder.binding.text.setContentDescription(displayName);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    private void applyFocusState(View view, boolean focused, boolean animate) {
        float scale = focused ? 1.025f : 1.0f;
        float elevation = focused ? view.getResources().getDisplayMetrics().density * 5f : 0f;
        if (animate) {
            view.animate().scaleX(scale).scaleY(scale).translationZ(elevation).setDuration(140L).start();
        } else {
            view.setScaleX(scale);
            view.setScaleY(scale);
            view.setTranslationZ(elevation);
        }
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpisodeBinding binding;

        ViewHolder(@NonNull AdapterEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
