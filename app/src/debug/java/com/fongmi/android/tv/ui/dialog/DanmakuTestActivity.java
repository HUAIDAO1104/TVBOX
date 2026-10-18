package com.fongmi.android.tv.ui.dialog;

/** Isolated playback-sheet host; no homepage voice/permission/startup callbacks. */
public final class DanmakuTestActivity extends androidx.appcompat.app.AppCompatActivity {
    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        var surface = new android.widget.FrameLayout(this);
        surface.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(surface);
        if (com.fongmi.android.tv.utils.Util.isLeanback())
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }
}
