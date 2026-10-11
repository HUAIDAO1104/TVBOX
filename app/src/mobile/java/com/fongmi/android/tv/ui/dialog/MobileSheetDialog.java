package com.fongmi.android.tv.ui.dialog;

import android.widget.FrameLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Shared geometry for mobile selection and playback options: full phone width, bounded height and native scrolling. */
public abstract class MobileSheetDialog extends BaseBottomSheetDialog {
    @Override public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (getView() != null) getView().post(() -> {
            if (isAdded() && getDialog() instanceof BottomSheetDialog dialog) setBehavior(dialog);
        });
    }

    @Override protected void setBehavior(BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        int available = getResources().getDisplayMetrics().heightPixels;
        android.graphics.Rect visible = new android.graphics.Rect();
        requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        if (visible.height() > 0) available = visible.height();
        sheet.getLayoutParams().height = Math.round(available * 0.86f);
        sheet.requestLayout();
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }
}
