package com.fongmi.android.tv.ui.home;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class HomeAtmosphereController {

    private final LruCache<String, Integer> colorCache = new LruCache<>(48);
    private final View baseTarget;
    private final View glowTarget;
    private CustomTarget<Bitmap> paletteTarget;
    private String activeKey = "";
    private boolean destroyed;

    public HomeAtmosphereController(View baseTarget, View glowTarget) {
        this.baseTarget = baseTarget;
        this.glowTarget = glowTarget;
        applyColor(defaultColor());
    }

    public void show(Vod item) {
        String key = key(item);
        activeKey = key;
        if (item == null || item.getPic().isEmpty()) {
            applyColor(defaultColor());
            return;
        }
        Integer cached = colorCache.get(key);
        if (cached != null) {
            applyColor(cached);
            return;
        }
        clearTarget();
        paletteTarget = new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                Palette.from(resource).generate(palette -> {
                    if (destroyed || palette == null || !activeKey.equals(key)) return;
                    int color = normalize(palette.getVibrantColor(palette.getMutedColor(defaultColor())));
                    colorCache.put(key, color);
                    applyColor(color);
                });
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (activeKey.equals(key)) applyColor(defaultColor());
            }
        };
        ImgUtil.load(item.getPic(), paletteTarget);
    }

    private String key(Vod item) {
        if (item == null) return "";
        String identity;
        if (!item.getId().isEmpty()) identity = item.getId();
        else if (!item.getPic().isEmpty()) identity = item.getPic();
        else identity = item.getName();
        return item.getSiteKey() + ':' + identity;
    }

    private int normalize(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0.34f, Math.min(hsv[1], 0.72f));
        hsv[2] = Math.max(0.28f, Math.min(hsv[2], 0.48f));
        return Color.HSVToColor(hsv);
    }

    private void applyColor(int color) {
        int background = ResUtil.getColor(R.color.tv_background);
        int middle = ColorUtils.blendARGB(background, color, 0.62f);
        int edge = ColorUtils.blendARGB(background, color, 0.38f);
        int tail = ColorUtils.blendARGB(background, color, 0.16f);
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{background, middle, edge, tail});
        int glowColor = ColorUtils.blendARGB(color, Color.WHITE, 0.09f);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.69f, 0.38f);
        glow.setGradientRadius(baseTarget.getResources().getDisplayMetrics().widthPixels * 0.62f);
        glow.setColors(new int[]{ColorUtils.setAlphaComponent(glowColor, 166), ColorUtils.setAlphaComponent(glowColor, 82), ColorUtils.setAlphaComponent(color, 26), Color.TRANSPARENT});
        animateBackground(baseTarget, base);
        animateBackground(glowTarget, glow);
    }

    private void animateBackground(View target, Drawable drawable) {
        target.animate().cancel();
        target.setAlpha(0.72f);
        target.setBackground(drawable);
        target.animate().alpha(1f).setDuration(220).start();
    }

    private int defaultColor() {
        return ResUtil.getColor(R.color.tv_background_secondary);
    }

    private void clearTarget() {
        if (paletteTarget == null) return;
        Glide.with(baseTarget).clear(paletteTarget);
        paletteTarget = null;
    }

    public void destroy() {
        destroyed = true;
        clearTarget();
        colorCache.evictAll();
    }
}
