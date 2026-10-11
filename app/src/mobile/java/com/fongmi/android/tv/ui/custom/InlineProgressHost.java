package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.fongmi.android.tv.utils.Util;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Keeps provider-added passive progress panels in reserved page space, outside app controls. */
public final class InlineProgressHost implements ViewTreeObserver.OnPreDrawListener {
    private final Activity activity;
    private final FrameLayout content;
    private final View page;
    private final int originalBottom;
    private final Map<View, Boolean> candidates = new IdentityHashMap<>();

    public static void install(Activity activity, View page) {
        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content != null && page.getLayoutParams() instanceof FrameLayout.LayoutParams)
            content.getViewTreeObserver().addOnPreDrawListener(new InlineProgressHost(activity, content, page));
    }

    private InlineProgressHost(Activity activity, FrameLayout content, View page) {
        this.activity = activity; this.content = content; this.page = page;
        originalBottom = ((FrameLayout.LayoutParams) page.getLayoutParams()).bottomMargin;
    }

    @Override public boolean onPreDraw() {
        if (!page.isAttachedToWindow()) return true;
        boolean immersive = Util.isFullscreen(activity) || (android.os.Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode());
        var insets = ViewCompat.getRootWindowInsets(content);
        int bottom = insets == null ? 0 : insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()).bottom;
        int gap = Math.round(8 * page.getResources().getDisplayMetrics().density);
        int occupied = 0;
        boolean changed = false;
        Iterator<View> removed = candidates.keySet().iterator();
        while (removed.hasNext()) if (removed.next().getParent() != content) removed.remove();
        for (int i = 0; i < content.getChildCount(); i++) {
            View panel = content.getChildAt(i);
            if (panel == page) continue;
            boolean passive = candidates.computeIfAbsent(panel, InlineProgressHost::isProgressPanel);
            if (!passive || !(panel.getLayoutParams() instanceof FrameLayout.LayoutParams params)) continue;
            // Keep the same parent and View instance: providers retain and remove these themselves.
            panel.setAlpha(immersive ? 0 : 1);
            panel.setImportantForAccessibility(immersive ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (immersive || panel.getVisibility() != View.VISIBLE) continue;
            int margin = bottom + gap + occupied;
            if (params.gravity != (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) || params.bottomMargin != margin || params.topMargin != 0) {
                params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                params.bottomMargin = margin; params.topMargin = 0;
                panel.setLayoutParams(params); changed = true;
            }
            occupied += panel.getMeasuredHeight() + gap;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) page.getLayoutParams();
        if (params.bottomMargin != originalBottom + occupied) {
            params.bottomMargin = originalBottom + occupied; page.setLayoutParams(params); changed = true;
        }
        // Apply the reserved geometry before drawing, so the initial provider frame cannot cover UI.
        return !changed;
    }

    private static boolean isProgressPanel(View view) {
        return has(view, ProgressBar.class) && has(view, TextView.class) && !hasAction(view);
    }
    private static boolean has(View view, Class<?> type) {
        if (type.isInstance(view)) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (has(group.getChildAt(i), type)) return true;
        return false;
    }
    private static boolean hasAction(View view) {
        if (view.isClickable() || view instanceof android.widget.EditText || view instanceof android.widget.Button) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (hasAction(group.getChildAt(i))) return true;
        return false;
    }
}
