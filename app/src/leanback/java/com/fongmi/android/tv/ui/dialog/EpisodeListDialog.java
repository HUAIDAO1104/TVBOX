package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.DialogEpisodeListBinding;
import com.fongmi.android.tv.playback.vod.EpisodePickerPolicy;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.search.SearchDisplayName;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Collections;
import java.util.List;

/** Complete, playback-safe episode selector used from the fullscreen TV controller. */
public class EpisodeListDialog extends BaseSideSheetDialog implements FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener {

    private static final String TAG = "episode_picker";
    private DialogEpisodeListBinding binding;
    private EpisodeAdapter episodeAdapter;
    private FlagAdapter flagAdapter;
    private String title = "";

    public interface Listener {

        List<Flag> getEpisodePickerFlags();

        boolean isEpisodePickerReversed();

        void onEpisodePickerFlag(Flag item);

        void onEpisodePickerEpisode(Episode item);

        void onEpisodePickerReverse();

        void onEpisodePickerDismissed();
    }

    public static EpisodeListDialog create() {
        return new EpisodeListDialog();
    }

    public EpisodeListDialog title(String title) {
        this.title = title == null ? "" : title;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof EpisodeListDialog) return;
        }
        show(activity.getSupportFragmentManager(), TAG);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogEpisodeListBinding.inflate(inflater, container, false);
    }

    @Override
    protected int getWidth() {
        int screen = ResUtil.getScreenWidth();
        return Math.min(Math.max(Math.round(screen * 0.46f), ResUtil.dp2px(520)), ResUtil.dp2px(760));
    }

    @Override
    protected void initView() {
        binding.title.setText(title.isEmpty() ? getString(R.string.episode_picker_title) : SearchDisplayName.removeEmoji(title));
        binding.flag.setHorizontalSpacing(ResUtil.dp2px(6));
        binding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        binding.flag.setAdapter(flagAdapter = new FlagAdapter(this));
        binding.episode.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.episode.setHasFixedSize(true);
        binding.episode.setItemAnimator(null);
        binding.episode.setAdapter(episodeAdapter = new EpisodeAdapter(this));
        reload(true);
    }

    @Override
    protected void initEvent() {
        binding.reverse.setOnClickListener(view -> {
            listener().onEpisodePickerReverse();
            reload(true);
        });
    }

    @Override
    public void onItemClick(Flag item) {
        listener().onEpisodePickerFlag(item);
        reload(true);
    }

    @Override
    public void onItemClick(Episode item) {
        listener().onEpisodePickerEpisode(item);
        dismissAllowingStateLoss();
    }

    private void reload(boolean focusEpisode) {
        List<Flag> flags = safeFlags();
        flagAdapter.addAll(flags);
        Flag selected = selectedFlag(flags);
        List<Episode> episodes = selected == null ? Collections.emptyList() : selected.getEpisodes();
        episodeAdapter.addAll(episodes);
        binding.sourceSection.setVisibility(flags.size() > 1 ? View.VISIBLE : View.GONE);
        binding.reverse.setVisibility(episodes.size() > 1 ? View.VISIBLE : View.GONE);
        episodeAdapter.setFocusBounds(flags.size() > 1 ? R.id.flag : R.id.reverse, View.NO_ID);
        binding.reverse.setText(listener().isEpisodePickerReversed() ? R.string.play_reverse : R.string.episode_picker_forward);
        binding.current.setText(getString(R.string.episode_picker_current,
                selected == null ? getString(R.string.episode_picker_sources) : SearchDisplayName.removeEmoji(selected.getShow()),
                currentEpisodeName(episodes)));
        if (!focusEpisode || episodes.isEmpty()) return;
        int position = episodeAdapter.getPosition();
        focusEpisode(position, 2);
    }

    private void focusEpisode(int position, int remainingAttempts) {
        binding.episode.scrollToPosition(position);
        binding.episode.post(() -> {
            if (binding == null || !isAdded()) return;
            RecyclerView.ViewHolder holder = binding.episode.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.itemView.requestFocus();
            } else if (remainingAttempts > 0) {
                binding.episode.postDelayed(() -> focusEpisode(position, remainingAttempts - 1), 32L);
            } else {
                binding.reverse.requestFocus();
            }
        });
    }

    private List<Flag> safeFlags() {
        List<Flag> flags = listener().getEpisodePickerFlags();
        return flags == null ? Collections.emptyList() : flags;
    }

    private Flag selectedFlag(List<Flag> flags) {
        int position = EpisodePickerPolicy.selectedFlagPosition(flags);
        return position < 0 ? null : flags.get(position);
    }

    private String currentEpisodeName(List<Episode> episodes) {
        int position = EpisodePickerPolicy.selectedEpisodePosition(episodes);
        if (position < 0) return getString(R.string.episode_picker_title);
        Episode episode = episodes.get(position);
        return SearchDisplayName.removeEmoji(episode.getDesc() + episode.getName());
    }

    private Listener listener() {
        return (Listener) requireActivity();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (getActivity() instanceof Listener listener) listener.onEpisodePickerDismissed();
    }
}
