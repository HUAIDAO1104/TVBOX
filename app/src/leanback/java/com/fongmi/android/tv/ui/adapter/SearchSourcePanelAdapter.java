package com.fongmi.android.tv.ui.adapter;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterSearchSourcePanelBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.ui.search.SearchSource;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

public final class SearchSourcePanelAdapter extends RecyclerView.Adapter<SearchSourcePanelAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(SearchSource source);

        void onClose();
    }

    private final Listener listener;
    private List<SearchSource> items = List.of();
    private String recommendedId = "";
    private String posterTitle = "";

    public SearchSourcePanelAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    /** @return true only when rows or the recommended marker actually changed. */
    public boolean submit(List<SearchSource> sources, SearchSource recommended, String workTitle) {
        List<SearchSource> safe = sources == null ? List.of() : List.copyOf(sources);
        String nextRecommendedId = recommended == null ? "" : recommended.stableId();
        String nextPosterTitle = workTitle == null ? "" : workTitle;
        List<SearchSource> previous = items;
        String previousRecommendedId = recommendedId;
        String previousPosterTitle = posterTitle;
        if (previous.equals(safe) && previousRecommendedId.equals(nextRecommendedId)
                && previousPosterTitle.equals(nextPosterTitle)) return false;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return safe.size(); }
            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return previous.get(oldItemPosition).stableId().equals(safe.get(newItemPosition).stableId());
            }
            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                SearchSource oldItem = previous.get(oldItemPosition);
                SearchSource newItem = safe.get(newItemPosition);
                return oldItem.equals(newItem)
                        && (previousRecommendedId.equals(oldItem.stableId())
                        == nextRecommendedId.equals(newItem.stableId()))
                        && previousPosterTitle.equals(nextPosterTitle);
            }
        }, false);
        items = safe;
        recommendedId = nextRecommendedId;
        posterTitle = nextPosterTitle;
        diff.dispatchUpdatesTo(this);
        return true;
    }

    public SearchSource get(int position) {
        return items.get(position);
    }

    @Override public int getItemCount() { return items.size(); }

    @Override
    public long getItemId(int position) {
        return stableLong(items.get(position).stableId());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchSourcePanelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SearchSource source = items.get(position);
        String site = SearchDisplayName.clean(source.siteName());
        if (site.isEmpty()) site = source.siteName();
        String repository = source.repositoryName().isEmpty()
                ? holder.itemView.getContext().getString(R.string.search_v2_current_repository)
                : source.repositoryName();
        String config = source.configId().isEmpty() ? site : source.configId();
        String update = source.remarks().isEmpty() && source.episodeCount() > 0
                ? source.episodeCount() + "集"
                : source.remarks();
        if (update.isEmpty()) update = "—";
        int statusId = switch (source.availability()) {
            case AVAILABLE -> R.string.search_v2_source_available;
            case UNAVAILABLE -> R.string.search_v2_source_unavailable;
            case UNKNOWN -> R.string.search_v2_source_unknown;
        };
        holder.binding.name.setText(site);
        holder.binding.repository.setText(holder.itemView.getContext().getString(
                R.string.search_v2_source_repository, repository, config));
        holder.binding.rawTitle.setText(holder.itemView.getContext().getString(
                R.string.search_v2_source_raw_title, source.title()));
        holder.binding.status.setText(holder.itemView.getContext().getString(
                R.string.search_v2_source_status, update, holder.itemView.getContext().getString(statusId)));
        holder.binding.recommended.setVisibility(recommendedId.equals(source.stableId()) ? View.VISIBLE : View.GONE);
        holder.binding.getRoot().setContentDescription(holder.binding.name.getText() + "，"
                + holder.binding.repository.getText() + "，" + holder.binding.status.getText());
        holder.binding.getRoot().setOnClickListener(view -> listener.onOpen(source));
        holder.binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            listener.onClose();
            return true;
        });
        ImgUtil.loadPoster(posterTitle.isEmpty() ? source.title() : posterTitle,
                source.posterUrl(), holder.binding.poster);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.poster).clear(holder.binding.poster);
        super.onViewRecycled(holder);
    }

    public static final class ViewHolder extends RecyclerView.ViewHolder {
        final AdapterSearchSourcePanelBinding binding;
        ViewHolder(AdapterSearchSourcePanelBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static long stableLong(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
