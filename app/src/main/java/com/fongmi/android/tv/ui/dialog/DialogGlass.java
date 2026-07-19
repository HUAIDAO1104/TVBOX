package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
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

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Shared translucent surface and background blur for TV dialogs and sheets. */
public final class DialogGlass {

    private static final int LEGACY_SAMPLE_WIDTH = 192;
    private static final Map<Dialog, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    private DialogGlass() {
    }

    public static void apply(Dialog dialog) {
        applyBehind(dialog);
        if (dialog == null || dialog.getWindow() == null) return;
        // Keep a single glass layer. Stacking a window fill and a panel fill makes the intended
        // 30–40% surface look nearly opaque on older firmware.
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().getDecorView().post(() -> {
            if (dialog.getWindow() == null) return;
            android.view.View panel = dialog.findViewById(androidx.appcompat.R.id.parentPanel);
            if (panel != null) panel.setBackground(surface(dialog, panel, 18));
            else dialog.getWindow().setBackgroundDrawable(
                    surface(dialog, dialog.getWindow().getDecorView(), 18));
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

    public static Drawable surface(Dialog dialog, View target, int radiusDp) {
        return surface(dialog, target, radiusDp, 102, 82);
    }

    public static Drawable surface(Dialog dialog, View target, int radiusDp, int alpha) {
        return surface(dialog, target, radiusDp, alpha, alpha);
    }

    private static Drawable surface(Dialog dialog, View target, int radiusDp,
                                    int startAlpha, int endAlpha) {
        Drawable overlay = background(dialog.getContext(), radiusDp, startAlpha, endAlpha);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return overlay;
        Snapshot snapshot = snapshot(dialog);
        return snapshot == null ? overlay : new FrostedDrawable(
                snapshot, target, overlay, dp(dialog.getContext(), radiusDp));
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
     * Android 9 has no window blur API. Capture the activity once at a very low resolution and
     * reuse that immutable sample for the whole lifetime of the dialog. Upscaling the pre-blurred
     * 192 px sample gives a convincing frosted surface without any per-frame capture or GPU blur.
     */
    private static Snapshot snapshot(Dialog dialog) {
        Snapshot cached = SNAPSHOTS.get(dialog);
        if (cached != null) return cached;
        Activity activity = findActivity(dialog.getContext());
        if (activity == null || activity.getWindow() == null) return null;
        View root = activity.getWindow().getDecorView();
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return null;
        int sampleWidth = Math.min(LEGACY_SAMPLE_WIDTH, Math.max(96, width / 8));
        int sampleHeight = Math.max(1, Math.round(height * sampleWidth / (float) width));
        try {
            Bitmap bitmap = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(sampleWidth / (float) width, sampleHeight / (float) height);
            root.draw(canvas);
            blur(bitmap, 2);
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            Snapshot snapshot = new Snapshot(bitmap, location[0], location[1], width, height);
            SNAPSHOTS.put(dialog, snapshot);
            return snapshot;
        } catch (OutOfMemoryError | RuntimeException ignored) {
            // The translucent gradient remains a safe fallback on unusually constrained devices.
            return null;
        }
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper wrapper) {
            if (wrapper instanceof Activity activity) return activity;
            Context base = wrapper.getBaseContext();
            if (base == context) break;
            context = base;
        }
        return context instanceof Activity activity ? activity : null;
    }

    private static void blur(Bitmap bitmap, int radius) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] input = new int[width * height];
        int[] horizontal = new int[input.length];
        int[] output = new int[input.length];
        bitmap.getPixels(input, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    int sx = Math.max(0, Math.min(width - 1, x + dx));
                    int color = input[row + sx];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                    count++;
                }
                horizontal[row + x] = Color.argb((int) (a / count), (int) (r / count),
                        (int) (g / count), (int) (b / count));
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int sy = Math.max(0, Math.min(height - 1, y + dy));
                    int color = horizontal[sy * width + x];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                    count++;
                }
                output[y * width + x] = Color.argb((int) (a / count), (int) (r / count),
                        (int) (g / count), (int) (b / count));
            }
        }
        bitmap.setPixels(output, 0, width, 0, 0, width, height);
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

    private record Snapshot(Bitmap bitmap, int left, int top, int width, int height) {
    }

    private static final class FrostedDrawable extends Drawable {

        private final WeakReference<View> target;
        private final Snapshot snapshot;
        private final Drawable overlay;
        private final Paint paint;
        private final Path path;
        private final Rect source;
        private final RectF destination;
        private final int[] location;
        private final float radius;

        private FrostedDrawable(Snapshot snapshot, View target, Drawable overlay, float radius) {
            this.target = new WeakReference<>(target);
            this.snapshot = snapshot;
            this.overlay = overlay;
            this.radius = radius;
            this.paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            this.path = new Path();
            this.source = new Rect();
            this.destination = new RectF();
            this.location = new int[2];
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            destination.set(bounds);
            path.reset();
            path.addRoundRect(destination, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(path);
            View view = target.get();
            if (view != null && source(view, bounds)) {
                canvas.drawBitmap(snapshot.bitmap(), source, destination, paint);
            }
            overlay.setBounds(bounds);
            overlay.draw(canvas);
            canvas.restoreToCount(save);
        }

        private boolean source(View view, Rect bounds) {
            view.getLocationOnScreen(location);
            float scaleX = snapshot.bitmap().getWidth() / (float) snapshot.width();
            float scaleY = snapshot.bitmap().getHeight() / (float) snapshot.height();
            int left = Math.round((location[0] - snapshot.left()) * scaleX);
            int top = Math.round((location[1] - snapshot.top()) * scaleY);
            int right = Math.round((location[0] - snapshot.left() + bounds.width()) * scaleX);
            int bottom = Math.round((location[1] - snapshot.top() + bounds.height()) * scaleY);
            left = Math.max(0, Math.min(snapshot.bitmap().getWidth() - 1, left));
            top = Math.max(0, Math.min(snapshot.bitmap().getHeight() - 1, top));
            right = Math.max(left + 1, Math.min(snapshot.bitmap().getWidth(), right));
            bottom = Math.max(top + 1, Math.min(snapshot.bitmap().getHeight(), bottom));
            source.set(left, top, right, bottom);
            return !source.isEmpty();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            overlay.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            overlay.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
