package com.fongmi.android.tv.ui.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;

/** Compact, remote-friendly dots centered below the home hero poster. */
public final class HomeCarouselIndicatorView extends View {

    public interface Listener {
        void onMove(int direction);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float inactiveRadius;
    private final float activeRadius;
    private final float gap;
    private int count;
    private int selected;
    private Listener listener;

    public HomeCarouselIndicatorView(Context context) {
        this(context, null);
    }

    public HomeCarouselIndicatorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomeCarouselIndicatorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        inactiveRadius = 2.5f * density;
        activeRadius = 3.7f * density;
        gap = 9f * density;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setContentDescription(getResources().getString(R.string.home_featured_switcher));
        setOnClickListener(view -> move(1));
        setOnFocusChangeListener((view, focused) -> view.animate()
                .alpha(focused ? 1f : 0.78f)
                .scaleX(focused ? 1.04f : 1f)
                .scaleY(focused ? 1.04f : 1f)
                .setDuration(160)
                .start());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setState(int count, int selected) {
        this.count = Math.max(0, count);
        this.selected = this.count == 0 ? 0 : Math.max(0, Math.min(selected, this.count - 1));
        setVisibility(this.count > 1 ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            move(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            move(1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void move(int direction) {
        if (count < 2 || listener == null) return;
        listener.onMove(direction < 0 ? -1 : 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (count < 2) return;
        float total = activeRadius * 2f + (count - 1) * inactiveRadius * 2f + (count - 1) * gap;
        float left = (getWidth() - total) / 2f;
        float centerY = getHeight() / 2f;
        for (int index = 0; index < count; index++) {
            float radius = index == selected ? activeRadius : inactiveRadius;
            paint.setColor(index == selected ? Color.rgb(255, 78, 78) : Color.argb(104, 255, 255, 255));
            canvas.drawCircle(left + radius, centerY, radius, paint);
            left += radius * 2f + gap;
        }
    }
}
