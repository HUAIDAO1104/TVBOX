package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsSeekBar;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.ui.custom.CustomWallView;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity {

    private int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private long touchDownAt;
    private boolean touchMoved;
    private boolean touchTargetWasFocused;
    private View touchTarget;

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (!customWall()) return;
        ((ViewGroup) findViewById(android.R.id.content)).addView(new CustomWallView(this, null), 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return false;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    /**
     * TV widgets are focus-first by design, which makes the first touchscreen tap only select an
     * item on some vendor ROMs. For a genuine touchscreen tap, cancel the focus-only delivery and
     * perform exactly one click in the same gesture. Key events and DPAD focus remain untouched.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getRawX();
                touchDownY = event.getRawY();
                touchDownAt = event.getEventTime();
                touchMoved = false;
                touchTarget = findClickableAt(getWindow().getDecorView(), touchDownX, touchDownY);
                touchTargetWasFocused = touchTarget != null && touchTarget.isFocused();
                return super.dispatchTouchEvent(event);
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getRawX() - touchDownX) > touchSlop
                        || Math.abs(event.getRawY() - touchDownY) > touchSlop) touchMoved = true;
                return super.dispatchTouchEvent(event);
            case MotionEvent.ACTION_UP:
                if (shouldPerformTouchClick(event)) {
                    View target = touchTarget;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    resetTouchTracking();
                    // Mobile reuses the TV layouts, but its interaction must remain touch-first.
                    // Giving every tapped poster Leanback focus makes its parent grid retain a
                    // selected row; the next incremental adapter update then realigns that row
                    // and visibly snaps the category page back toward the top.
                    if (!Util.isMobile()) target.requestFocus();
                    target.performClick();
                    return true;
                }
                boolean handled = super.dispatchTouchEvent(event);
                resetTouchTracking();
                return handled;
            case MotionEvent.ACTION_CANCEL:
                resetTouchTracking();
                return super.dispatchTouchEvent(event);
            default:
                return super.dispatchTouchEvent(event);
        }
    }

    private boolean shouldPerformTouchClick(MotionEvent event) {
        if (touchTarget == null || touchMoved) return false;
        // A TV widget may consume a touchscreen tap only to update its focus state. Mobile must
        // never depend on that state: every short, stationary tap is delivered as exactly one
        // click whether the target was focused, selected, or neither. TV keeps its original
        // focus-first behavior for remote compatibility.
        if (!Util.isMobile() && touchTargetWasFocused) return false;
        if (event.getEventTime() - touchDownAt >= ViewConfiguration.getLongPressTimeout()) return false;
        if (!touchTarget.isAttachedToWindow() || !touchTarget.isEnabled() || !touchTarget.isShown()) return false;
        Rect bounds = new Rect();
        return touchTarget.getGlobalVisibleRect(bounds)
                && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    private View findClickableAt(View view, float rawX, float rawY) {
        if (view == null || !view.isShown() || !view.isEnabled()) return null;
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(Math.round(rawX), Math.round(rawY))) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View target = findClickableAt(group.getChildAt(index), rawX, rawY);
                if (target != null) return target;
            }
        }
        if (!view.isClickable() || !view.hasOnClickListeners() || view instanceof EditText || view instanceof AbsSeekBar) return null;
        String className = view.getClass().getName();
        return className.contains("Slider") ? null : view;
    }

    private void resetTouchTracking() {
        touchTarget = null;
        touchTargetWasFocused = false;
        touchMoved = false;
        touchDownAt = 0L;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyDataSetChanged());
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private Resources hackResources(Resources resources) {
        try {
            AutoSizeCompat.autoConvertDensityOfGlobal(resources);
            return resources;
        } catch (Exception ignored) {
            return resources;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    @Override
    public Resources getResources() {
        return hackResources(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
