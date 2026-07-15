package com.fongmi.android.tv.ui.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class HomePosterAuraView extends AppCompatImageView {

    private static final float VERTICAL_SCALE = 0.64f;

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private float centerX;
    private float centerY;

    public HomePosterAuraView(Context context) {
        this(context, null);
    }

    public HomePosterAuraView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomePosterAuraView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width == 0 || height == 0) return;
        centerX = width * 0.5f;
        centerY = height * 0.5f;
        float radius = width * 0.39f;
        maskPaint.setShader(new RadialGradient(
                centerX,
                centerY,
                radius,
                new int[]{Color.WHITE, 0xECFFFFFF, 0x72FFFFFF, Color.TRANSPARENT},
                new float[]{0f, 0.30f, 0.64f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int layer = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), null);
        super.onDraw(canvas);
        int mask = canvas.save();
        canvas.scale(1f, VERTICAL_SCALE, centerX, centerY);
        float top = centerY - centerY / VERTICAL_SCALE;
        float bottom = centerY + (getHeight() - centerY) / VERTICAL_SCALE;
        canvas.drawRect(0f, top, getWidth(), bottom, maskPaint);
        canvas.restoreToCount(mask);
        canvas.restoreToCount(layer);
    }
}
