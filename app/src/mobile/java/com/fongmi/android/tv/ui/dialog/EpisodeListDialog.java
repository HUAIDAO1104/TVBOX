package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.DialogEpisodeListBinding;
import com.fongmi.android.tv.playback.vod.EpisodePickerPolicy;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.google.android.material.tabs.TabLayout;
import java.util.Collections;
import java.util.List;

/** One source/episode picker for portrait and fullscreen; long series are paged in groups of 50. */
public class EpisodeListDialog extends MobileSheetDialog implements FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener {
    private static final String TAG = "episode_picker";
    private static final int PAGE_SIZE = 50;
    private DialogEpisodeListBinding binding;
    private EpisodeAdapter episodeAdapter;
    private FlagAdapter flagAdapter;
    private List<Episode> episodes = Collections.emptyList();
    private boolean updatingRanges;

    public interface Listener {
        List<Flag> getEpisodePickerFlags();
        boolean isEpisodePickerReversed();
        void onEpisodePickerFlag(Flag item);
        void onEpisodePickerEpisode(Episode item);
        void onEpisodePickerReverse();
        void onEpisodePickerDismissed();
    }
    public static EpisodeListDialog create() { return new EpisodeListDialog(); }
    public EpisodeListDialog title(String title) {
        Bundle args = new Bundle(); args.putString("title", title == null ? "" : title); setArguments(args); return this;
    }
    public void show(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof EpisodeListDialog) return;
        show(activity.getSupportFragmentManager(), TAG);
    }
    @Override protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogEpisodeListBinding.inflate(inflater, container, false);
    }
    @Override protected void setBehavior(com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        // This fully expanded picker owns one vertical scroller. A drag must scroll episodes,
        // never move the sheet or be routed to the horizontal source list.
        dialog.getBehavior().setDraggable(false);
        adaptLayout();
    }
    private void adaptLayout() {
        if (binding == null) return;
        boolean shortWindow = getResources().getConfiguration().screenHeightDp < 480;
        binding.body.setOrientation(shortWindow ? android.widget.LinearLayout.HORIZONTAL : android.widget.LinearLayout.VERTICAL);
        int sidebar = Math.round(236 * getResources().getDisplayMetrics().density);
        binding.options.setLayoutParams(new android.widget.LinearLayout.LayoutParams(shortWindow ? sidebar : -1, -2));
        binding.episodePane.setLayoutParams(new android.widget.LinearLayout.LayoutParams(shortWindow ? 0 : -1, shortWindow ? -1 : 0, 1));
        binding.title.setMaxLines(shortWindow ? 1 : 2);
        binding.count.setVisibility(shortWindow ? View.GONE : View.VISIBLE);
        binding.episode.post(() -> { if (binding != null) updateColumns(); });
    }
    private void updateColumns() {
        int longest = episodeAdapter.getItems().stream().mapToInt(e -> EpisodeAdapter.displayName(e).length()).max().orElse(2);
        float scale = getResources().getConfiguration().fontScale;
        float width = binding.episode.getWidth() / getResources().getDisplayMetrics().density;
        if (width <= 0) return;
        int columns = Math.max(1, Math.min(5, (int)(width / Math.max(72, Math.min(12, longest) * 14 * scale + 28))));
        ((GridLayoutManager)binding.episode.getLayoutManager()).setSpanCount(columns);
    }
    @Override protected void initView() {
        String title = getArguments() == null ? "" : getArguments().getString("title", "");
        binding.title.setText(title.isEmpty() ? "选择剧集" : title);
        binding.flag.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        binding.flag.setItemAnimator(null);
        binding.flag.setAdapter(flagAdapter = new FlagAdapter(this));
        binding.episode.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        binding.episode.setItemAnimator(null);
        binding.episode.setAdapter(episodeAdapter = new EpisodeAdapter(this, ViewType.GRID));
        reload();
    }
    @Override protected void initEvent() {
        binding.close.setOnClickListener(v -> dismiss());
        binding.locate.setOnClickListener(v -> locateCurrent());
        binding.reverse.setOnClickListener(v -> { listener().onEpisodePickerReverse(); reload(); });
        binding.ranges.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) { if (!updatingRanges) showPage(tab.getPosition(), false); }
            public void onTabUnselected(TabLayout.Tab tab) { }
            public void onTabReselected(TabLayout.Tab tab) { }
        });
    }
    @Override public void onItemClick(Flag item) {
        if (!item.isSelected()) listener().onEpisodePickerFlag(item);
        reload();
    }
    @Override public void onItemClick(Episode item) {
        if (!item.isSelected()) listener().onEpisodePickerEpisode(item);
        dismiss();
    }
    private void reload() {
        List<Flag> flags = listener().getEpisodePickerFlags();
        if (flags == null) flags = Collections.emptyList();
        flagAdapter.addAll(flags);
        int selected = EpisodePickerPolicy.selectedFlagPosition(flags);
        Flag flag = selected < 0 ? null : flags.get(selected);
        episodes = flag == null ? Collections.emptyList() : flag.getEpisodes();
        binding.sourceSection.setVisibility(flags.size() > 1 ? View.VISIBLE : View.GONE);
        if (selected >= 0) binding.flag.scrollToPosition(selected);
        binding.reverse.setVisibility(episodes.size() > 1 ? View.VISIBLE : View.GONE);
        binding.reverse.setText(listener().isEpisodePickerReversed() ? "倒序" : "正序");
        binding.count.setText("共 " + episodes.size() + " 集");
        int current = EpisodePickerPolicy.selectedEpisodePosition(episodes);
        binding.locate.setEnabled(current >= 0);
        binding.current.setText((flag == null ? "暂无线路" : flag.getShow()) + (current < 0 ? "" : " · " + episodes.get(current).getDesc() + episodes.get(current).getName()));
        binding.empty.setVisibility(episodes.isEmpty() ? View.VISIBLE : View.GONE);
        binding.ranges.setVisibility(episodes.size() > PAGE_SIZE ? View.VISIBLE : View.GONE);
        updatingRanges = true;
        binding.ranges.removeAllTabs();
        for (int start = 0; start < episodes.size(); start += PAGE_SIZE) {
            int end = Math.min(start + PAGE_SIZE, episodes.size());
            String label = listener().isEpisodePickerReversed() ? (episodes.size() - start) + "–" + (episodes.size() - end + 1) : (start + 1) + "–" + end;
            binding.ranges.addTab(binding.ranges.newTab().setText(label), false);
        }
        updatingRanges = false;
        locateCurrent();
    }
    public void refreshCurrentEpisode() {
        if (binding == null) return;
        List<Flag> flags = listener().getEpisodePickerFlags();
        int selected = EpisodePickerPolicy.selectedFlagPosition(flags);
        if (selected < 0) return;
        Flag flag = flags.get(selected);
        int current = EpisodePickerPolicy.selectedEpisodePosition(flag.getEpisodes());
        binding.current.setText(flag.getShow() + (current < 0 ? "" : " · " + flag.getEpisodes().get(current).getName()));
        binding.locate.setEnabled(current >= 0);
        // Selection may advance automatically while the picker is open. Keep the user's page/scroll.
        episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.getItemCount());
    }
    private void locateCurrent() {
        int page = Math.max(0, EpisodePickerPolicy.selectedEpisodePosition(episodes)) / PAGE_SIZE;
        updatingRanges = true;
        binding.ranges.selectTab(binding.ranges.getTabAt(page));
        updatingRanges = false;
        showPage(page, true);
    }
    private void showPage(int page, boolean locate) {
        int start = Math.min(page * PAGE_SIZE, episodes.size());
        List<Episode> items = episodes.subList(start, Math.min(start + PAGE_SIZE, episodes.size()));
        episodeAdapter.addAll(items);
        updateColumns();
        binding.episode.scrollToPosition(locate ? episodeAdapter.getPosition() : 0);
    }
    private Listener listener() { return (Listener) requireActivity(); }
    @Override public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (getActivity() instanceof Listener host) host.onEpisodePickerDismissed();
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
