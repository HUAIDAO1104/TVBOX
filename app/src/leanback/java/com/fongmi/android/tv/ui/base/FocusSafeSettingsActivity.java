package com.fongmi.android.tv.ui.base;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.widget.NestedScrollView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.setting.SettingsFocusPolicy;

import java.util.Map;
import java.util.WeakHashMap;

/** Shared TV settings focus behavior: unclipped scale, safe scrolling and state restore. */
public abstract class FocusSafeSettingsActivity extends BaseActivity {

    private static final String STATE_SCROLL_Y = "settings_scroll_y";
    private static final String STATE_FOCUSED_ID = "settings_focused_id";
    private static final int SAFE_INSET_DP = 24;
    private static final int FOCUSED_ELEVATION_DP = 4;
    private static final int FOCUSED_TRANSLATION_Z_DP = 8;

    private final Map<View, float[]> originalZ = new WeakHashMap<>();
    private NestedScrollView settingsScroll;
    private int defaultFocusId = View.NO_ID;
    private int lastFocusedId = View.NO_ID;
    private int restoredScrollY;

    protected final void initSettingsFocus(Bundle savedInstanceState, int defaultFocusId) {
        this.defaultFocusId = defaultFocusId;
        this.settingsScroll = findViewById(R.id.settingsScroll);
        this.restoredScrollY = savedInstanceState == null ? 0 : SettingsFocusPolicy.sanitizeScrollY(savedInstanceState.getInt(STATE_SCROLL_Y));
        this.lastFocusedId = savedInstanceState == null ? View.NO_ID : savedInstanceState.getInt(STATE_FOCUSED_ID, View.NO_ID);
        View root = findViewById(android.R.id.content);
        if (root instanceof ViewGroup group) {
            disableAncestorClipping(group);
            bindFocusableChildren(group);
        }
        root.post(this::restoreFocusState);
    }

    private void disableAncestorClipping(ViewGroup group) {
        group.setClipChildren(false);
        group.setClipToPadding(false);
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof ViewGroup childGroup) disableAncestorClipping(childGroup);
        }
    }

    private void bindFocusableChildren(ViewGroup group) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child.isFocusable()) bindFocus(child);
            if (child instanceof ViewGroup childGroup) bindFocusableChildren(childGroup);
        }
    }

    private void bindFocus(View view) {
        originalZ.put(view, new float[]{view.getElevation(), view.getTranslationZ()});
        view.setOnFocusChangeListener((target, focused) -> {
            float[] original = originalZ.get(target);
            float elevation = original == null ? 0f : original[0];
            float translationZ = original == null ? 0f : original[1];
            target.setElevation(elevation + (focused ? dp(FOCUSED_ELEVATION_DP) : 0));
            target.setTranslationZ(translationZ + (focused ? dp(FOCUSED_TRANSLATION_Z_DP) : 0));
            if (!focused) return;
            if (target.getId() != View.NO_ID) lastFocusedId = target.getId();
            target.post(() -> ensureFocusVisible(target));
        });
    }

    private void restoreFocusState() {
        if (settingsScroll != null) settingsScroll.scrollTo(0, restoredScrollY);
        int focusId = SettingsFocusPolicy.resolveFocusId(lastFocusedId, defaultFocusId);
        View target = findViewById(focusId);
        if (!canReceiveFocus(target)) target = findViewById(defaultFocusId);
        if (!canReceiveFocus(target)) return;
        target.requestFocus();
        View focused = target;
        target.post(() -> ensureFocusVisible(focused));
    }

    private boolean canReceiveFocus(View view) {
        return view != null && view.getVisibility() == View.VISIBLE && view.isEnabled() && view.isFocusable();
    }

    private void ensureFocusVisible(View focused) {
        if (settingsScroll == null || focused == null || !focused.isShown()) return;
        Rect bounds = new Rect();
        focused.getDrawingRect(bounds);
        settingsScroll.offsetDescendantRectToMyCoords(focused, bounds);
        int viewportTop = settingsScroll.getScrollY();
        int viewportBottom = viewportTop + settingsScroll.getHeight();
        int delta = SettingsFocusPolicy.requiredScrollDelta(viewportTop, viewportBottom, bounds.top, bounds.bottom, dp(SAFE_INSET_DP));
        if (delta != 0) settingsScroll.smoothScrollBy(0, delta);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        View focused = getCurrentFocus();
        if (focused != null) focused.post(() -> ensureFocusVisible(focused));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (settingsScroll != null) outState.putInt(STATE_SCROLL_Y, SettingsFocusPolicy.sanitizeScrollY(settingsScroll.getScrollY()));
        View focused = getCurrentFocus();
        if (focused != null && focused.getId() != View.NO_ID) lastFocusedId = focused.getId();
        outState.putInt(STATE_FOCUSED_ID, lastFocusedId);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        originalZ.clear();
        super.onDestroy();
    }
}
