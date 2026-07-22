package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class CustomVerticalGridView extends VerticalGridView {

    private List<View> views;
    private boolean pressDown;
    private boolean pressUp;
    private boolean moveTop;
    private final int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private boolean touchDragging;

    public CustomVerticalGridView(@NonNull Context context) {
        this(context, null);
    }

    public CustomVerticalGridView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomVerticalGridView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setMoveTop(true);
        if (Util.isMobile()) configureForTouch();
    }

    private void configureForTouch() {
        // A Leanback grid normally keeps one child selected so DPAD focus can always be
        // recovered.  That selected child is also used as a layout anchor after rows are added,
        // which fights a phone fling and snaps the viewport back to the old (usually first) row.
        // Posters remain clickable; only remote/focus navigation is disabled for mobile builds.
        setPreserveFocusAfterLayout(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    @Override
    protected void initAttributes(@NonNull Context context, @Nullable AttributeSet attrs) {
        super.initAttributes(context, attrs);
        setOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable ViewHolder child, int position, int subposition) {
                if (pressDown && position == 1) hideHeader();
                if (pressUp && position == 0) showHeader();
            }
        });
    }

    public void setHeader(FragmentActivity activity, int... layoutIds) {
        if (activity != null) views = Arrays.stream(layoutIds).mapToObj(id -> (View) activity.findViewById(id)).filter(Objects::nonNull).toList();
    }

    public void setMoveTop(boolean moveTop) {
        this.moveTop = moveTop;
    }

    public void hideHeader() {
        if (views != null) for (View view : views) view.setVisibility(View.GONE);
    }

    public void showHeader() {
        if (views != null) for (View view : views) view.setVisibility(View.VISIBLE);
    }

    public boolean isHeaderVisible() {
        if (views != null) for (View view : views) if (view.getId() == R.id.recycler && view.getVisibility() == View.VISIBLE) return true;
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (Util.isMobile()) return super.dispatchKeyEvent(event);
        if (!KeyUtil.isActionDown(event)) return super.dispatchKeyEvent(event);
        if (KeyUtil.isBackKey(event)) return moveTop && moveToTop();
        pressUp = KeyUtil.isUpKey(event);
        pressDown = KeyUtil.isDownKey(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    touchDragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!touchDragging) {
                        float dx = Math.abs(event.getX() - touchDownX);
                        float dy = Math.abs(event.getY() - touchDownY);
                        if (dy > touchSlop && dy > dx) {
                            touchDragging = true;
                            // Requesting focus here makes Leanback realign the selected child.
                            // On phones that manifests as a snap back to the first row. Let the
                            // RecyclerView own the drag; DPAD focus remains untouched.
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchDragging = false;
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    public boolean moveToTop() {
        if (Util.isMobile()) return false;
        if (views == null || getSelectedPosition() == 0 || getAdapter() == null || getAdapter().getItemCount() == 0) return false;
        for (View view : views) if (view.getId() == R.id.recycler) view.requestFocus();
        scrollToPosition(0);
        showHeader();
        return true;
    }
}
