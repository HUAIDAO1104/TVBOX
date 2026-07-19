package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

public class CustomViewPager extends ViewPager {

    public CustomViewPager(@NonNull Context context) {
        super(context);
    }

    public CustomViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setCurrentItem(int item) {
        super.setCurrentItem(item, false);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // Leanback pages are switched by the tab/remote. Horizontal touch dragging made the
        // complete TV canvas float under a finger and fought the vertical poster grid.
        if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false;
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false;
        return super.onTouchEvent(event);
    }
}
