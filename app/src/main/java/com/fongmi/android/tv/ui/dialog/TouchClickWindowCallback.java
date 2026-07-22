package com.fongmi.android.tv.ui.dialog;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsSeekBar;
import android.widget.EditText;

import androidx.appcompat.view.WindowCallbackWrapper;

import com.fongmi.android.tv.utils.Util;

/** Makes TV-oriented dialog widgets behave as direct, single-tap controls on phones. */
@SuppressLint("RestrictedApi")
final class TouchClickWindowCallback extends WindowCallbackWrapper {

    private final Window window;
    private final int touchSlop;
    private View target;
    private float downX;
    private float downY;
    private long downAt;
    private boolean moved;

    static void install(Window window) {
        if (!Util.isMobile() || window == null || window.getCallback() instanceof TouchClickWindowCallback) return;
        window.setCallback(new TouchClickWindowCallback(window));
    }

    private TouchClickWindowCallback(Window window) {
        super(window.getCallback());
        this.window = window;
        this.touchSlop = ViewConfiguration.get(window.getContext()).getScaledTouchSlop();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                downAt = event.getEventTime();
                moved = false;
                target = findClickableAt(window.getDecorView(), downX, downY);
                return super.dispatchTouchEvent(event);
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getRawX() - downX) > touchSlop || Math.abs(event.getRawY() - downY) > touchSlop) moved = true;
                return super.dispatchTouchEvent(event);
            case MotionEvent.ACTION_UP:
                if (shouldClick(event)) {
                    View clickTarget = target;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    reset();
                    clickTarget.performClick();
                    return true;
                }
                boolean handled = super.dispatchTouchEvent(event);
                reset();
                return handled;
            case MotionEvent.ACTION_CANCEL:
                reset();
                return super.dispatchTouchEvent(event);
            default:
                return super.dispatchTouchEvent(event);
        }
    }

    private boolean shouldClick(MotionEvent event) {
        if (target == null || moved) return false;
        if (event.getEventTime() - downAt >= ViewConfiguration.getLongPressTimeout()) return false;
        if (!target.isAttachedToWindow() || !target.isEnabled() || !target.isShown()) return false;
        Rect bounds = new Rect();
        return target.getGlobalVisibleRect(bounds) && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    private View findClickableAt(View view, float rawX, float rawY) {
        if (view == null || !view.isShown() || !view.isEnabled()) return null;
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(Math.round(rawX), Math.round(rawY))) return null;
        if (view instanceof ViewGroup group) {
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View child = findClickableAt(group.getChildAt(index), rawX, rawY);
                if (child != null) return child;
            }
        }
        if (!view.isClickable() || !view.hasOnClickListeners() || view instanceof EditText || view instanceof AbsSeekBar) return null;
        return view.getClass().getName().contains("Slider") ? null : view;
    }

    private void reset() {
        target = null;
        moved = false;
        downAt = 0L;
    }
}
