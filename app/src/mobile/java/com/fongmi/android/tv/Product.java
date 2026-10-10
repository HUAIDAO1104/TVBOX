package com.fongmi.android.tv;

import android.content.Context;
import android.os.Build;
import android.view.DisplayCutout;

import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

public class Product {

    public static int getDeviceType() {
        return 1;
    }

    public static int getColumn(Context context) {
        float widthDp = context.getResources().getConfiguration().screenWidthDp;
        float fontScale = context.getResources().getConfiguration().fontScale;
        int[] targets = {88, 100, 112, 144};
        int target = targets[Math.max(0, Math.min(3, PlayerSetting.getSize()))];
        int columns = (int) ((widthDp - 16) / (target * Math.max(1f, fontScale)));
        return Math.max(2, Math.min(6, columns));
    }

    public static int getColumn(Context context, Style style) {
        return style.isLand() ? Math.max(2, getColumn(context) - 1) : getColumn(context);
    }

    public static int[] getSpec(Context context) {
        return getSpec(context, Style.rect());
    }

    public static int[] getSpec(Context context, Style style) {
        int column = getColumn(context, style);
        int space = ResUtil.dp2px(32) + ResUtil.dp2px(16 * (column - 1)) + getCutout(context);
        if (style.isOval()) space += ResUtil.dp2px(column * 16);
        return getSpec(context, space, column, style);
    }

    private static int[] getSpec(Context context, int space, int column, Style style) {
        int base = ResUtil.getScreenWidth(context) - space;
        int width = base / column;
        int height = (int) (width / style.getRatio());
        return new int[]{width, height};
    }

    private static int getCutout(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0;
        DisplayCutout cutout = ResUtil.getDisplay(context).getCutout();
        if (cutout == null) return 0;
        int left = cutout.getSafeInsetLeft();
        int right = cutout.getSafeInsetRight();
        return left + right;
    }
}
