package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/** Shared translucent surface and background blur for TV dialogs and sheets. */
public final class DialogGlass {

    private DialogGlass() {
    }

    public static void apply(Dialog dialog) {
        applyBehind(dialog);
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(background(dialog.getContext(), 18));
    }

    public static void applyBehind(Dialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.30f;
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
