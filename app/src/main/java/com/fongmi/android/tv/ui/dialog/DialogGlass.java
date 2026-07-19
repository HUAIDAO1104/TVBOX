package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

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
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(102, 28, 36, 50), Color.argb(82, 9, 14, 22)});
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), Color.argb(58, 255, 255, 255));
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
