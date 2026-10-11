package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.databinding.DialogControlBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.ui.adapter.ParseAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Timer;
import com.google.android.material.slider.Slider;

import java.util.Arrays;
import java.util.List;

public class ControlDialog extends MobileSheetDialog implements ParseAdapter.OnClickListener {

    private final String[] scale;
    private DialogControlBinding binding;
    private ActivityVideoBinding parent;
    private List<TextView> scales;
    private PlayerManager player;
    private History history;
    private String siteKey = "";
    private boolean parse;
    private int restoreAttempts;

    public ControlDialog() {
        this.scale = ResUtil.getStringArray(R.array.select_scale);
    }

    public static ControlDialog create() {
        return new ControlDialog();
    }

    public ControlDialog parent(ActivityVideoBinding parent) {
        this.parent = parent;
        return this;
    }

    public ControlDialog history(History history) {
        this.history = history;
        return this;
    }

    public ControlDialog parse(boolean parse) {
        this.parse = parse;
        return this;
    }

    public ControlDialog siteKey(String siteKey) {
        this.siteKey = siteKey == null ? "" : siteKey;
        return this;
    }

    public ControlDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public ControlDialog show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof ControlDialog) return this;
        show(activity.getSupportFragmentManager(), null);
        return this;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        binding = DialogControlBinding.inflate(inflater, container, false);
        scales = Arrays.asList(binding.scale0, binding.scale1, binding.scale2, binding.scale3, binding.scale4);
        return binding;
    }

    public interface Host {
        ActivityVideoBinding playbackBinding();
        History playbackHistory();
        PlayerManager playbackPlayer();
        String playbackSiteKey();
        boolean playbackUsesParse();
    }

    @Override
    protected void initView() {
        // DialogFragment can be restored without builder fields after Activity recreation.
        if (requireActivity() instanceof Host host) {
            parent = host.playbackBinding(); history = host.playbackHistory(); player = host.playbackPlayer();
            siteKey = host.playbackSiteKey(); parse = host.playbackUsesParse();
        }
        if (player == null || history == null) {
            if (++restoreAttempts > 40) { dismissAllowingStateLoss(); return; }
            binding.close.setOnClickListener(v -> dismiss());
            binding.getRoot().postDelayed(() -> { if (binding != null && isAdded()) { initView(); if (player != null && history != null) initEvent(); } }, 150);
            return;
        }
        binding.speedPresets.removeAllViews();
        for (float speed : new float[]{0.75f, 1f, 1.25f, 1.5f, 2f}) {
            com.google.android.material.button.MaterialButton preset = new com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            preset.setText(formatSpeed(speed)); preset.setTag(speed); preset.setMinHeight(com.fongmi.android.tv.utils.ResUtil.dp2px(48));
            preset.setMinWidth(0); preset.setMinimumWidth(0); preset.setTextSize(14);
            preset.setPadding(8, 0, 8, 0); preset.setCornerRadius(com.fongmi.android.tv.utils.ResUtil.dp2px(10));
            float fontScale = getResources().getConfiguration().fontScale;
            int width = Math.round(Math.max(64 * fontScale, (getResources().getConfiguration().screenWidthDp - 40) / 5f - 8));
            com.google.android.flexbox.FlexboxLayout.LayoutParams params = new com.google.android.flexbox.FlexboxLayout.LayoutParams(com.fongmi.android.tv.utils.ResUtil.dp2px(width), com.fongmi.android.tv.utils.ResUtil.dp2px(52));
            params.setMargins(0, 0, com.fongmi.android.tv.utils.ResUtil.dp2px(8), 0); preset.setLayoutParams(params);
            preset.setOnClickListener(v -> binding.speed.setValue(speed));
            binding.speedPresets.addView(preset);
        }

        binding.decode.setText(parent.control.action.decode.getText());
        binding.ending.setText("片尾 · " + parent.control.action.ending.getText());
        binding.opening.setText("片头 · " + parent.control.action.opening.getText());
        binding.repeat.setSelected(parent.control.action.repeat.isSelected());
        binding.timer.setSelected(Timer.get().isRunning());
        setMediaOptionVisible();
        setTrackVisible();
        setScaleText();
        setPlayer();
        setParse();
    }

    @Override
    protected void initEvent() {
        if (player == null || history == null) return;
        binding.close.setOnClickListener(v -> dismiss());
        binding.advancedToggle.setOnClickListener(v -> {
            boolean expanded = binding.advanced.getVisibility() != View.VISIBLE;
            binding.advanced.setVisibility(expanded ? View.VISIBLE : View.GONE);
            binding.advancedToggle.setText(expanded ? "收起高级选项" : "高级播放选项");
        });
        binding.resetSkip.setOnClickListener(v -> {
            parent.control.action.opening.performLongClick(); parent.control.action.ending.performLongClick();
            binding.opening.setText("片头 · " + parent.control.action.opening.getText()); binding.ending.setText("片尾 · " + parent.control.action.ending.getText());
        });
        binding.resetPlayback.setOnClickListener(v -> dismiss(parent.control.action.reset));
        binding.replay.setOnClickListener(v -> dismiss(parent.control.action.replay));
        binding.timer.setOnClickListener(this::onTimer);
        binding.speed.addOnChangeListener(this::setSpeed);
        for (TextView view : scales) view.setOnClickListener(this::setScale);
        binding.text.setOnClickListener(v -> dismiss(parent.control.action.text));
        binding.audio.setOnClickListener(v -> dismiss(parent.control.action.audio));
        binding.video.setOnClickListener(v -> dismiss(parent.control.action.video));
        binding.player.setOnClickListener(v -> dismiss(parent.control.action.player));
        binding.danmaku.setOnClickListener(v -> dismiss(parent.control.action.danmaku));
        binding.edition.setOnClickListener(v -> dismiss(parent.control.action.edition));
        binding.chapter.setOnClickListener(v -> dismiss(parent.control.action.chapter));
        binding.repeat.setOnClickListener(v -> active(binding.repeat, parent.control.action.repeat));
        binding.decode.setOnClickListener(v -> click(binding.decode, parent.control.action.decode));
        binding.ending.setOnClickListener(v -> click(binding.ending, parent.control.action.ending));
        binding.opening.setOnClickListener(v -> click(binding.opening, parent.control.action.opening));
        binding.player.setOnLongClickListener(v -> longClick(binding.player, parent.control.action.player));
        binding.ending.setOnLongClickListener(v -> longClick(binding.ending, parent.control.action.ending));
        binding.opening.setOnLongClickListener(v -> longClick(binding.opening, parent.control.action.opening));
    }

    private void onTimer(View view) {
        TimerDialog.create().show(getActivity());
        dismiss();
    }

    private void setSpeed(@NonNull Slider slider, float value, boolean fromUser) {
        parent.control.action.speed.setText(player.setSpeed(value));
        if (history != null) history.setSpeed(player.getSpeed());
        updateSpeed(value);
    }

    private String formatSpeed(float value) { return new java.text.DecimalFormat("0.##").format(value) + "×"; }

    private void updateSpeed(float value) {
        binding.speedLabel.setText("播放速度 · " + formatSpeed(value));
        for (int i = 0; i < binding.speedPresets.getChildCount(); i++) {
            com.google.android.material.button.MaterialButton button = (com.google.android.material.button.MaterialButton) binding.speedPresets.getChildAt(i);
            boolean selected = Math.abs((float) button.getTag() - value) < 0.01f;
            button.setSelected(selected);
            button.setTextColor(com.fongmi.android.tv.utils.ResUtil.getColor(selected ? R.color.mobile_accent : R.color.mobile_text));
            button.setStrokeColor(android.content.res.ColorStateList.valueOf(com.fongmi.android.tv.utils.ResUtil.getColor(selected ? R.color.mobile_accent : R.color.mobile_outline)));
        }
    }

    private void setScaleText() {
        for (int i = 0; i < scales.size(); i++) {
            scales.get(i).setText(scale[i]);
            scales.get(i).setSelected(scales.get(i).getText().equals(parent.control.action.scale.getText()));
        }
    }

    private void setParse() {
        setParseVisible(parse);
        binding.parse.setHasFixedSize(true);
        binding.parse.setItemAnimator(null);
        binding.parse.addItemDecoration(new SpaceItemDecoration(8));
        binding.parse.setAdapter(new ParseAdapter(this, siteKey));
    }

    private void setScale(View view) {
        for (TextView textView : scales) textView.setSelected(false);
        ((Listener) requireActivity()).onScale(Integer.parseInt(view.getTag().toString()));
        view.setSelected(true);
    }

    private void active(View view, TextView target) {
        target.performClick();
        view.setSelected(target.isSelected());
    }

    private void click(TextView view, TextView target) {
        target.performClick();
        view.setText((view == binding.opening ? "片头 · " : view == binding.ending ? "片尾 · " : "") + target.getText());
    }

    private boolean longClick(TextView view, TextView target) {
        target.performLongClick();
        view.setText((view == binding.opening ? "片头 · " : view == binding.ending ? "片尾 · " : "") + target.getText());
        return true;
    }

    private void dismiss(View view) {
        view.post(view::performClick);
        dismiss();
    }

    public void setPlayer() {
        binding.speed.setValue(Math.max(0.5f, Math.min(5f, Math.round(player.getSpeed() * 4) / 4f)));
        updateSpeed(player.getSpeed());
        binding.player.setText(parent.control.action.player.getText());
        binding.decode.setVisibility(parent.control.action.decode.getVisibility());
        binding.danmaku.setVisibility(parent.control.action.danmaku.getVisibility());
    }

    public void setParseVisible(boolean visible) {
        binding.parse.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.parseText.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void setTrackVisible() {
        binding.text.setVisibility(parent.control.action.text.getVisibility());
        binding.audio.setVisibility(parent.control.action.audio.getVisibility());
        binding.video.setVisibility(parent.control.action.video.getVisibility());
        binding.track.setVisibility(binding.text.getVisibility() == View.GONE && binding.audio.getVisibility() == View.GONE && binding.video.getVisibility() == View.GONE ? View.GONE : View.VISIBLE);
    }

    public void setMediaOptionVisible() {
        binding.edition.setVisibility(parent.control.action.edition.getVisibility());
        binding.chapter.setVisibility(parent.control.action.chapter.getVisibility());
    }

    @Override
    public void onItemClick(Parse item) {
        ((Listener) requireActivity()).onParse(item);
        binding.parse.getAdapter().notifyItemRangeChanged(0, binding.parse.getAdapter().getItemCount());
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; parent = null; player = null; }

    public interface Listener {

        void onScale(int tag);

        void onParse(Parse item);
    }
}
