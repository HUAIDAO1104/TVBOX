package com.fongmi.android.tv.playback.vod;

import java.util.Objects;

/** Immutable playback identity used by both automatic and manual danmaku matching. */
public final class DanmakuMatchContext {

    private final String title;
    private final String year;
    private final String type;
    private final String episode;

    public DanmakuMatchContext(String title, String year, String type, String episode) {
        this.title = Objects.toString(title, "").trim();
        this.year = Objects.toString(year, "").trim();
        this.type = Objects.toString(type, "").trim();
        this.episode = Objects.toString(episode, "").trim();
    }

    public static DanmakuMatchContext empty() {
        return new DanmakuMatchContext("", "", "", "");
    }

    public String getTitle() {
        return title;
    }

    public String getYear() {
        return year;
    }

    public String getType() {
        return type;
    }

    public String getEpisode() {
        return episode;
    }
}
