package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * Prevents focus-driven TV layouts from pulling a touch scroll back to the previously focused
 * child. DPAD focus behavior is unchanged because suppression is active only during a real touch
 * drag and for a short settling window after the finger is released.
 */
public final class TouchSafeNestedScrollView extends NestedScrollView {

    private static final long SETTLE_WINDOW_MS = 320L;

    private final int touchSlop;
    private float downX;
    private float downY;
    private long suppressFocusScrollUntil;
    private boolean dragging;

    public TouchSafeNestedScrollView(@NonNull Context context) {
        this(context, null);
    }

    public TouchSafeNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, androidx.core.R.attr.nestedScrollViewStyle);
    }

    public TouchSafeNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        trackTouch(event);
        return super.dispatchTouchEvent(event);
    }

    private void trackTouch(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    dragging = dy > touchSlop && dy > dx;
                }
                if (dragging) suppressFocusScrollUntil = SystemClock.uptimeMillis() + SETTLE_WINDOW_MS;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) suppressFocusScrollUntil = SystemClock.uptimeMillis() + SETTLE_WINDOW_MS;
                dragging = false;
                break;
            default:
                break;
        }
    }

    private boolean suppressFocusScroll() {
        return dragging || SystemClock.uptimeMillis() < suppressFocusScrollUntil;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (suppressFocusScroll()) {
            if (getParent() != null) getParent().requestChildFocus(this, focused);
            return;
        }
        super.requestChildFocus(child, focused);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return !suppressFocusScroll() && super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }
}
