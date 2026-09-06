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
import com.fongmi.android.tv.databinding.AdapterSearchWorkBinding;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.ui.search.SearchSource;
import com.fongmi.android.tv.ui.search.SearchSourcePreference;
import com.fongmi.android.tv.ui.search.SearchWork;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SearchWorkAdapter extends RecyclerView.Adapter<SearchWorkAdapter.ViewHolder> {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern EXTRA_SPACE = Pattern.compile("\\s+");

    public interface Listener {
        void onOpen(SearchWork work);

        void onShowSources(SearchWork work);
    }

    private static final java.util.concurrent.ExecutorService diffs = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "search-list-diff"));
    private volatile int generation;
    private Map<String, Integer> sourceCounts = Map.of();
    private final Listener listener;
    private final int columns;
    private List<SearchWork> items = List.of();
    private Map<String, String> posterOverrides = Map.of();

    public SearchWorkAdapter(Listener listener, int columns) {
        this.listener = listener;
        this.columns = Math.max(1, columns);
        setHasStableIds(true);
    }

    public void submit(List<SearchWork> next) { submit(next, () -> {}); }

    /** A source switch is a complete, cached page replacement and should respond immediately. */
    public void replace(List<SearchWork> next) {
        generation++;
        items = List.copyOf(next);
        notifyDataSetChanged();
    }

    public void updateSourceCounts(Map<String, Integer> next) {
        if (sourceCounts.equals(next)) return;
        sourceCounts = Map.copyOf(next);
        if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "sources");
    }

    public void submit(List<SearchWork> next, Runnable committed) {
        int version = ++generation;
        List<SearchWork> safe = next == null ? List.of() : List.copyOf(next);
        List<SearchWork> previous = items;
        if (previous.equals(safe)) { committed.run(); return; }
        if (isPureAppend(previous, safe)) {
            items = safe;
            notifyItemRangeInserted(previous.size(), safe.size() - previous.size());
            committed.run();
            return;
        }
        diffs.execute(() -> {
        if (version != generation) return;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return safe.size(); }
            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return previous.get(oldItemPosition).stableId().equals(safe.get(newItemPosition).stableId());
            }
            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return previous.get(oldItemPosition).equals(safe.get(newItemPosition));
            }
        }, false);
        com.fongmi.android.tv.App.post(() -> {
            if (version != generation) return;
            items = safe;
            diff.dispatchUpdatesTo(this);
            committed.run();
        });
        });
    }

    private static boolean isPureAppend(List<SearchWork> previous, List<SearchWork> next) {
        if (next.size() <= previous.size()) return false;
        for (int index = 0; index < previous.size(); index++) {
            if (!previous.get(index).equals(next.get(index))) return false;
        }
        return true;
    }

    public SearchWork get(int position) {
        return items.get(position);
    }

    public void updatePosterOverrides(Map<String, String> next) {
        Map<String, String> safe = next == null || next.isEmpty() ? Map.of() : Map.copyOf(next);
        Map<String, String> previous = posterOverrides;
        if (previous.equals(safe)) return;
        posterOverrides = safe;
        for (int index = 0; index < items.size(); index++) {
            SearchWork work = items.get(index);
            if (!Objects.equals(resolvePoster(work, previous), resolvePoster(work, safe))) {
                notifyItemChanged(index, "poster");
            }
        }
    }

    public int positionOf(String stableId) {
        if (stableId == null) return -1;
        for (int index = 0; index < items.size(); index++) {
            if (stableId.equals(items.get(index).stableId())) return index;
        }
        return -1;
    }

    /** Finds a work through one of its durable source ids when aggregate metadata changed its id. */
    public int positionContainingSource(String sourceStableId) {
        if (sourceStableId == null) return -1;
        for (int index = 0; index < items.size(); index++) {
            for (SearchSource source : items.get(index).sources()) {
                if (sourceStableId.equals(source.stableId())) return index;
            }
        }
        return -1;
    }

    @Override
    public long getItemId(int position) {
        return stableLong(items.get(position).stableId());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchWorkBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SearchWork work = items.get(position);
        SearchSource recommended = work.recommendedSource();
        String sourceName = recommended == null ? "" : SearchDisplayName.clean(recommended.siteName());
        if (sourceName.isEmpty() && recommended != null) sourceName = recommended.siteName();
        if (sourceName.isEmpty()) sourceName = holder.itemView.getContext().getString(R.string.search_v2_source_unknown);
        String meta = join(work.year(), work.area(), work.type());
        String update = displayUpdate(work, holder);
        holder.binding.name.setText(work.displayTitle());
        holder.binding.meta.setText(meta);
        holder.binding.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.update.setText(update);
        holder.binding.update.setVisibility(update.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.source.setText(sourceName);
        int count = recommended == null ? work.sourceCount() : sourceCounts.getOrDefault(recommended.stableId(), work.sourceCount());
        holder.binding.sourceCount.setText(holder.itemView.getContext().getResources().getQuantityString(
                R.plurals.search_v2_card_source_count, count, count));
        holder.binding.sourceCount.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        holder.itemView.setOnFocusChangeListener((view, focused) -> holder.binding.name.setSelected(focused));
        holder.binding.getRoot().setContentDescription(join(work.displayTitle(), meta, update,
                holder.binding.source.getText().toString(), holder.binding.sourceCount.getText().toString()));
        holder.binding.getRoot().setOnClickListener(view -> listener.onOpen(work));
        holder.binding.getRoot().setOnLongClickListener(view -> {
            listener.onShowSources(work);
            return true;
        });
        holder.binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int adapterPosition = holder.getBindingAdapterPosition();
            boolean rightEdge = adapterPosition != RecyclerView.NO_POSITION
                    && ((adapterPosition + 1) % columns == 0 || adapterPosition == getItemCount() - 1);
            if (keyCode != KeyEvent.KEYCODE_MENU
                    && (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT || !rightEdge)) return false;
            listener.onShowSources(work);
            return true;
        });
        ImgUtil.loadPoster(work.displayTitle(), resolvePoster(work, posterOverrides), holder.binding.poster);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && !payloads.contains("sources")) {
            SearchWork work = items.get(position);
            ImgUtil.loadPoster(work.displayTitle(), resolvePoster(work, posterOverrides), holder.binding.poster);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    private static String resolvePoster(SearchWork work, Map<String, String> overrides) {
        SearchSource recommended = work.recommendedSource();
        String borrowed = "";
        for (SearchSource source : work.sources()) {
            String candidate = overrides.get(source.stableId());
            if (candidate != null && !candidate.isBlank()) {
                borrowed = candidate;
                break;
            }
        }
        if (!borrowed.isBlank() && (work.posterUrl() == null || work.posterUrl().isBlank()
                || recommended != null && recommended.requiresLogin())) return borrowed;
        return work.posterUrl();
    }

    private static String displayUpdate(SearchWork work, ViewHolder holder) {
        String remarks = work.remarks() == null ? "" : work.remarks().trim();
        remarks = EXTRA_SPACE.matcher(HTML_TAG.matcher(remarks).replaceAll(" ")).replaceAll(" ").trim();
        if (!remarks.isEmpty()) return remarks;
        if (work.episodeCount() <= 0) return "";
        return holder.itemView.getContext().getString(R.string.search_v2_card_episode_count, work.episodeCount());
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.poster).clear(holder.binding.poster);
        super.onViewRecycled(holder);
    }

    private static String join(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) parts.add(value.trim());
        return String.join(" · ", parts);
    }

    private static long stableLong(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public static final class ViewHolder extends RecyclerView.ViewHolder {
        final AdapterSearchWorkBinding binding;
        ViewHolder(AdapterSearchWorkBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
