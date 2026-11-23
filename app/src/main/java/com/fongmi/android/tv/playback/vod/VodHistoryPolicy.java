package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

public class VodHistoryPolicy {

    private String lastSubmitted = "";

    public History findOrCreate(String key, String mark, Vod item) {
        History history = History.find(key);
        lastSubmitted = history == null ? "" : history.toString();
        history = history == null ? create(key, item) : history;
        if (!TextUtils.isEmpty(mark)) history.setVodRemarks(mark);

        history.setVodName(item.getName());
        return history;
    }

    private History create(String key, Vod item) {
        History history = new History();
        history.setKey(key);
        history.setCid(VodConfig.getCid());
        history.setVodName(item.getName());
        history.findEpisode(item.getFlags());
        return history;
    }

    public void save(History history) {
        save(history, false);
    }

    public void save(History history, boolean exit) {
        if (history == null || !history.canSave() || Setting.isIncognito()) return;
        String data = history.toString();
        boolean changed = !data.equals(lastSubmitted);
        if (!changed && !exit) return;
        lastSubmitted = data;
        History snapshot = changed ? History.objectFrom(data) : null;
        Task.execute(() -> {
            if (Setting.isIncognito()) return;
            if (snapshot != null) snapshot.save();
            if (exit) {
                RefreshEvent.history();
                com.fongmi.android.tv.history.HistoryLink.afterSave();
            }
        });
    }

    public void sync(History history) {
        save(history);
    }

    public void updateEpisode(History history, Flag flag, Episode episode) {
        if (history == null || flag == null || episode == null) return;
        history.setPosition(episode.matchesName(history.getEpisode()) ? history.getPosition() : C.TIME_UNSET);
        history.setVodFlag(flag.getFlag());
        history.setVodRemarks(episode.getName());
        history.setEpisodeUrl(episode.getUrl());
    }

    public void updateTime(History history, long time, long position, long duration) {
        if (history == null || position < 0 || duration <= 0) return;
        if (history.getPosition() == position && history.getDuration() == duration) return;
        history.setCreateTime(Math.max(time, history.getCreateTime() + 1));
        history.setPosition(position);
        history.setDuration(duration);
        if (history.canSave() && history.canSync()) sync(history);
    }

    public long startPositionMs(History history) {
        // A per-series opening may be longer than a trailer or bonus episode.
        // Apply it when the current media duration becomes known.
        return history == null ? C.TIME_UNSET : history.getPosition();
    }
}
