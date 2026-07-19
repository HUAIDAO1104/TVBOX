package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.PosterResolver;

import java.util.List;
import java.util.Objects;

/** Six-column full history browser. Long-press enters explicit delete mode. */
public final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(History item);
        void onDelete(History item);
        void onDeleteMode();
    }

    private static final int COLUMN_COUNT = 6;
    private final Listener listener;
    private List<History> items = List.of();
    private boolean deleteMode;

    public HistoryAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<History> next) {
        List<History> safe = next == null ? List.of() : List.copyOf(next);
        for (History item : safe) PosterResolver.remember(item.getVodName(), item.getVodPic());
        List<History> old = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return safe.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return Objects.equals(old.get(oldPosition).getKey(), safe.get(newPosition).getKey());
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                History previous = old.get(oldPosition);
                History current = safe.get(newPosition);
                return previous.isSameContent(current)
                        && previous.getPosition() == current.getPosition()
                        && previous.getDuration() == current.getDuration()
                        && previous.getVodRemarks().equals(current.getVodRemarks());
            }
        }, false);
        items = safe;
        diff.dispatchUpdatesTo(this);
    }

    public boolean isDeleteMode() {
        return deleteMode;
    }

    public void setDeleteMode(boolean value) {
        if (deleteMode == value) return;
        deleteMode = value;
        notifyItemRangeChanged(0, getItemCount());
    }

    @Override public long getItemId(int position) { return Objects.hashCode(items.get(position).getKey()); }
    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        AdapterVodBinding binding = AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        int[] size = Product.getSpec(Style.rect(), COLUMN_COUNT);
        binding.getRoot().getLayoutParams().width = size[0];
        binding.image.getLayoutParams().height = size[1];
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        History item = items.get(position);
        String name = SearchDisplayName.removeEmoji(item.getVodName());
        String remark = SearchDisplayName.removeEmoji(item.getVodRemarks());
        holder.binding.name.setText(name);
        holder.binding.remark.setText(remark);
        holder.binding.remark.setVisibility(remark.isEmpty() || deleteMode ? View.GONE : View.VISIBLE);
        holder.binding.site.setText(SearchDisplayName.removeEmoji(item.getSiteName()));
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.delete.setVisibility(deleteMode ? View.VISIBLE : View.GONE);
        holder.itemView.setContentDescription(name + (remark.isEmpty() ? "" : ", " + remark));
        holder.itemView.setOnClickListener(view -> {
            if (deleteMode) listener.onDelete(item);
            else listener.onOpen(item);
        });
        holder.itemView.setOnLongClickListener(view -> {
            listener.onDeleteMode();
            return true;
        });
        ImgUtil.loadPoster(item.getVodName(), item.getVodPic(), holder.binding.image);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.image).clear(holder.binding.image);
        super.onViewRecycled(holder);
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final AdapterVodBinding binding;

        ViewHolder(AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                view.setTranslationZ(focused ? 10f : 0f);
                view.animate().scaleX(focused ? 1.045f : 1f).scaleY(focused ? 1.045f : 1f)
                        .setDuration(focused ? 150 : 100).start();
            });
        }
    }
}
