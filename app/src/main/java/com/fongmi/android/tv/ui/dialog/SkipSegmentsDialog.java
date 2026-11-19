package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import androidx.media3.common.C;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/** Edits the two independent skip settings for the work currently playing. */
public final class SkipSegmentsDialog {
    private SkipSegmentsDialog() {}

    public static void show(Context context, History history, PlayerManager player, LongConsumer opening, LongConsumer ending) {
        if (history == null || player == null) return;
        String[] rows = {
                context.getString(R.string.skip_intro) + " · " + value(context, history.getOpening()),
                context.getString(R.string.skip_outro) + " · " + value(context, history.getEnding())
        };
        DialogGlass.show(new MaterialAlertDialogBuilder(context).setTitle(R.string.skip_segments)
                .setItems(rows, (dialog, which) -> {
                    boolean intro = which == 0;
                    choose(context, player, intro, intro ? history.getOpening() : history.getEnding(), intro ? opening : ending);
                }).setNegativeButton(android.R.string.cancel, null));
    }

    private static String value(Context context, long ms) {
        if (ms > 0) return Util.timeMs(ms);
        return context.getString(ms < 0 ? R.string.skip_auto : R.string.skip_off);
    }

    private static void choose(Context context, PlayerManager player, boolean intro, long current, LongConsumer save) {
        List<Long> values = new ArrayList<>(List.of(C.TIME_UNSET, 0L));
        List<String> labels = new ArrayList<>(List.of(context.getString(R.string.skip_auto), context.getString(R.string.skip_off)));
        long position = player.getPosition();
        long duration = player.getDuration();
        if (intro ? player.canSetOpening(position, duration) : player.canSetEnding(position, duration)) {
            long mark = intro ? position : duration - position;
            if (mark > 0) {
                values.add(mark);
                labels.add(context.getString(intro ? R.string.skip_mark_intro : R.string.skip_mark_outro, Util.timeMs(position)));
            }
        }
        for (long ms : new long[]{30000, 60000, 90000, 120000, current}) {
            if (ms <= 0 || values.contains(ms)) continue;
            values.add(ms);
            labels.add(Util.timeMs(ms));
        }
        DialogGlass.show(new MaterialAlertDialogBuilder(context).setTitle(intro ? R.string.skip_intro : R.string.skip_outro)
                .setSingleChoiceItems(labels.toArray(new String[0]), values.indexOf(current), (dialog, which) -> {
                    save.accept(values.get(which));
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null));
    }
}
