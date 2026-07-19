package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.StateSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Shared translucent surface and background blur for TV dialogs and sheets. */
public final class DialogGlass {

    private DialogGlass() {
    }

    public static void apply(Dialog dialog) {
        applyBehind(dialog);
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(background(dialog.getContext(), 18));
        dialog.getWindow().getDecorView().post(() -> {
            if (dialog.getWindow() == null) return;
            android.view.View panel = dialog.findViewById(androidx.appcompat.R.id.parentPanel);
            if (panel != null) panel.setBackground(background(dialog.getContext(), 18));
            View content = dialog.findViewById(android.R.id.content);
            if (content != null) applyCards(content);
        });
    }

    public static void applySheet(Dialog dialog) {
        applyBehind(dialog);
        if (dialog == null || dialog.getWindow() == null) return;
        // Material sheets paint an opaque colorSurface on their window before the actual sheet
        // container is attached. Clear that layer so the 32–40% glass surface is visible.
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    public static AlertDialog show(MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        apply(dialog);
        return dialog;
    }

    public static void applyBehind(Dialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.34f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            params.setBlurBehindRadius(dp(dialog.getContext(), 28));
        }
        window.setAttributes(params);
    }

    public static Drawable background(Context context, int radiusDp) {
        return background(context, radiusDp, 102, 82);
    }

    /** Creates a glass surface whose two gradient stops use the same, explicit opacity. */
    public static Drawable background(Context context, int radiusDp, int alpha) {
        return background(context, radiusDp, alpha, alpha);
    }

    private static Drawable background(Context context, int radiusDp, int startAlpha, int endAlpha) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(startAlpha, 28, 36, 50), Color.argb(endAlpha, 9, 14, 22)});
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), Color.argb(58, 255, 255, 255));
        return drawable;
    }

    /**
     * Applies the same 35–40% glass fill to focusable text actions inside every dialog. This
     * catches native alert buttons and dynamically inflated option rows which cannot reliably be
     * styled from a single XML theme on older TV firmware.
     */
    public static void applyCards(View root) {
        if (root == null) return;
        // MaterialButton owns a ShapeAppearanceModel that MaterialButtonToggleGroup reads again
        // during layout. Replacing that background makes getShapeAppearanceModel() throw and
        // crashes the danmaku settings sheet as soon as its tab group is measured.
        if (root instanceof TextView && !(root instanceof CompoundButton)
                && !(root instanceof MaterialButton)
                && root.isFocusable() && root.getBackground() != null) {
            int left = root.getPaddingLeft();
            int top = root.getPaddingTop();
            int right = root.getPaddingRight();
            int bottom = root.getPaddingBottom();
            root.setBackground(cardSelector(root.getContext()));
            root.setPadding(left, top, right, bottom);
        }
        if (!(root instanceof ViewGroup group)) return;
        for (int i = 0; i < group.getChildCount(); i++) applyCards(group.getChildAt(i));
    }

    private static Drawable cardSelector(Context context) {
        StateListDrawable selector = new StateListDrawable();
        Drawable focused = card(context, 102, true); // 40%
        selector.addState(new int[]{android.R.attr.state_focused}, focused);
        selector.addState(new int[]{android.R.attr.state_pressed}, focused);
        selector.addState(new int[]{android.R.attr.state_checked}, card(context, 89, true));
        selector.addState(new int[]{android.R.attr.state_selected}, card(context, 89, true));
        selector.addState(StateSet.WILD_CARD, card(context, 89, false)); // 35%
        return selector;
    }

    private static Drawable card(Context context, int alpha, boolean accent) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(alpha, accent ? 53 : 21, accent ? 26 : 27, accent ? 34 : 36));
        drawable.setCornerRadius(dp(context, 14));
        drawable.setStroke(dp(context, accent ? 2 : 1),
                accent ? Color.rgb(255, 98, 107) : Color.argb(50, 255, 255, 255));
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
