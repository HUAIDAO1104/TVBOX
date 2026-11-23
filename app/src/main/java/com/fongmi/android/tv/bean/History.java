package com.fongmi.android.tv.bean;

import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.room.Entity;


import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Diffable;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity(primaryKeys = {"cid", "key"})
public class History implements Diffable<History> {

    static final int MAX_EPISODE_URL_LENGTH = 16 * 1024;

    @NonNull
    @SerializedName("key")
    private String key;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("vodName")
    private String vodName;
    @SerializedName("vodFlag")
    private String vodFlag;
    @SerializedName("vodRemarks")
    private String vodRemarks;
    @SerializedName("episodeUrl")
    private String episodeUrl;
    @SerializedName("revSort")
    private boolean revSort;
    @SerializedName("revPlay")
    private boolean revPlay;
    @SerializedName("createTime")
    private long createTime;
    @SerializedName("opening")
    private long opening;
    @SerializedName("ending")
    private long ending;
    @SerializedName("position")
    private long position;
    @SerializedName("duration")
    private long duration;
    @SerializedName("speed")
    private float speed;
    @SerializedName("scale")
    private int scale;
    @SerializedName("cid")
    private int cid;

    private transient long updateTime;

    public History() {
        this.speed = 1;
        this.scale = -1;
        this.ending = C.TIME_UNSET;
        this.opening = C.TIME_UNSET;
        this.position = C.TIME_UNSET;
        this.duration = C.TIME_UNSET;
    }

    public static History objectFrom(String str) {
        return App.gson().fromJson(str, History.class);
    }

    public static List<History> arrayFrom(String str) {
        Type listType = TypeToken.getParameterized(List.class, History.class).getType();
        List<History> items = App.gson().fromJson(str, listType);
        return items == null ? Collections.emptyList() : items;
    }

    public static List<History> get() {
        return get(VodConfig.getCid());
    }

    public static List<History> get(int cid) {
        return AppDatabase.get().getHistoryDao().find(cid, System.currentTimeMillis() - Constant.HISTORY_TIME);
    }

    public static History find(String key) {
        return AppDatabase.get().getHistoryDao().find(VodConfig.getCid(), key);
    }

    public static List<History> findByName(String name) {
        try {
            return AppDatabase.get().getHistoryDao().findByName(VodConfig.getCid(), name);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public static void delete(int cid) {
        AppDatabase.get().getHistoryDao().delete(cid);
    }

    public static void sync(List<History> targets) {
        sync(VodConfig.getCid(), targets, false);
    }

    public static int sync(int cid, List<History> targets, boolean replace) {
        AppDatabase db = AppDatabase.get();
        return db.runInTransaction(() -> {
            if (replace) db.getHistoryDao().delete(cid);
            int changes = 0;
            for (History incoming : targets) {
                if (incoming == null || incoming.key == null || !incoming.key.contains(AppDatabase.SYMBOL)
                        || incoming.key.endsWith(AppDatabase.SYMBOL) || incoming.getVodName().isEmpty()) continue;
                incoming.setKey(com.fongmi.android.tv.history.HistoryKey.local(com.fongmi.android.tv.history.HistoryKey.content(incoming.key), cid));
                History local = db.getHistoryDao().find(cid, incoming.key);
                if (local != null && local.createTime >= incoming.createTime) continue;
                incoming.setEpisodeUrl(incoming.getEpisodeUrl());
                db.getHistoryDao().insertOrUpdate(incoming.cid(cid));
                changes++;
            }
            return changes;
        });
    }

    @NonNull
    public String getKey() {
        return key;
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getVodPic() {
        return vodPic == null ? "" : vodPic;
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public String getVodName() {
        return vodName == null ? "" : vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodFlag() {
        return vodFlag == null ? "" : vodFlag;
    }

    public void setVodFlag(String vodFlag) {
        this.vodFlag = vodFlag;
    }

    public String getVodRemarks() {
        return vodRemarks == null ? "" : vodRemarks;
    }

    public void setVodRemarks(String vodRemarks) {
        this.vodRemarks = vodRemarks;
    }

    public String getEpisodeUrl() {
        return episodeUrl == null ? "" : episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        // Some drive sources embed an entire request payload in the episode identifier. Persisting
        // a multi-megabyte value makes Android 9's fixed 2 MB CursorWindow crash while saving seek
        // progress. The episode name remains available as the resume-position fallback.
        this.episodeUrl = episodeUrl != null && episodeUrl.length() <= MAX_EPISODE_URL_LENGTH
                ? episodeUrl : "";
    }

    public boolean isRevSort() {
        return revSort;
    }

    public void setRevSort(boolean revSort) {
        this.revSort = revSort;
    }

    public boolean isRevPlay() {
        return revPlay;
    }

    public void setRevPlay(boolean revPlay) {
        this.revPlay = revPlay;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public long getOpening() {
        return opening;
    }

    public void setOpening(long opening) {
        this.opening = opening;
    }

    public long getEnding() {
        return ending;
    }

    public void setEnding(long ending) {
        this.ending = ending;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public int getCid() {
        return cid;
    }

    public void setCid(int cid) {
        this.cid = cid;
    }

    public History cid(int cid) {
        setCid(cid);
        return this;
    }

    public String getSiteName() {
        return VodConfig.get().getSite(getSiteKey()).getName();
    }

    public String getSiteKey() {
        return getKey().split(AppDatabase.SYMBOL, 2)[0];
    }

    public String getVodId() {
        return com.fongmi.android.tv.history.HistoryKey.vodId(getKey());
    }

    public Flag getFlag() {
        return Flag.create(getVodFlag());
    }

    public Episode getEpisode() {
        return Episode.create(getVodRemarks(), getEpisodeUrl());
    }

    public int getSiteVisible() {
        return TextUtils.isEmpty(getSiteName()) ? View.GONE : View.VISIBLE;
    }

    public int getRevPlayText() {
        return isRevPlay() ? R.string.play_backward : R.string.play_forward;
    }

    public int getRevPlayHint() {
        return isRevPlay() ? R.string.play_backward_hint : R.string.play_forward_hint;
    }

    public boolean unfinished() {
        return position > 0 && duration > position && duration - position > Math.min(30000L, duration / 20);
    }

    public boolean canSave() {
        return getPosition() >= 0 && getDuration() > 0;
    }

    public boolean canSync() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (updateTime != 0 && now - updateTime < 5000) return false;
        updateTime = now;
        return true;
    }

    public void replace(String key) {
        delete();
        setKey(key);
    }

    public History save(int cid) {
        if (key != null && key.contains(AppDatabase.SYMBOL))
            setKey(com.fongmi.android.tv.history.HistoryKey.local(com.fongmi.android.tv.history.HistoryKey.content(key), cid));
        return cid(cid).save();
    }

    public History save() {
        AppDatabase db = AppDatabase.get();
        db.runInTransaction(() -> {
            History previous = db.getHistoryDao().find(cid, key);
            if (previous == null || previous.createTime <= createTime) db.getHistoryDao().insertOrUpdate(this);
        });
        return this;
    }

    public History delete() {
        AppDatabase.get().getHistoryDao().delete(getCid(), getKey());
        AppDatabase.get().getTrackDao().delete(getKey());
        return this;
    }

    public void findEpisode(List<Flag> flags) {
        if (flags.isEmpty()) return;
        setVodFlag(flags.get(0).getFlag());
        if (!flags.get(0).getEpisodes().isEmpty()) setVodRemarks(flags.get(0).getEpisodes().get(0).getName());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof History it)) return false;
        return Objects.equals(getKey(), it.getKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey());
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public boolean isSameItem(History other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(History other) {
        return other != null && Objects.equals(getVodName(), other.getVodName())
                && Objects.equals(getVodPic(), other.getVodPic())
                && getCreateTime() == other.getCreateTime();
    }
}
