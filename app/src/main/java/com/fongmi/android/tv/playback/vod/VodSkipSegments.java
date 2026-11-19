package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaChapter;
import com.fongmi.android.tv.Constant;
import java.util.List;
import java.util.regex.Pattern;

/** Chapter timestamps belong to one cut. Only user-entered durations carry across episodes. */
final class VodSkipSegments {
    private static final Pattern INTRO = marker("op|opening(?: theme| credits)?|intro|片[头頭](?:曲)?|オープニング");
    private static final Pattern OUTRO = marker("ed|ending(?: theme| credits)?|outro|end credits|credits|片尾(?:曲|字幕)?|エンディング");

    private static Pattern marker(String names) {
        return Pattern.compile("(?i)^(?:" + names + ")(?:\\s*[0-9]+)?(?:\\s*[-:：–]\\s*.+)?$");
    }

    private List<MediaChapter> seen;
    private long seenDuration;
    private long introStart, introEnd, outroStart, outroEnd;
    private boolean skippedIntro, skippedOutro;

    void reset() {
        seen = null;
        skippedIntro = skippedOutro = false;
    }

    /** Returns the end of the marked interval, or -1 when playback should continue. */
    long target(List<MediaChapter> chapters, long position, long duration, long opening, long ending) {
        if (position < 0 || duration <= 0 || position >= duration) return -1;
        if (chapters != seen || duration != seenDuration) read(chapters, duration);
        long introTarget = opening > 0 ? opening : introEnd;
        long from = opening > 0 ? 0 : introStart;
        if (introTarget > 0 && position >= introTarget) skippedIntro = true;
        if (!skippedIntro && opening != 0 && introTarget < duration && position >= from && position < introTarget) {
            skippedIntro = true;
            return introTarget;
        }
        long start = ending > 0 ? duration - ending : outroStart;
        long end = ending > 0 ? duration : outroEnd;
        if (!skippedOutro && ending < duration && ending != 0 && start >= 0 && position >= start && position < end) {
            skippedOutro = true;
            return end;
        }
        return -1;
    }

    private void read(List<MediaChapter> chapters, long duration) {
        seen = chapters;
        seenDuration = duration;
        introStart = introEnd = outroStart = outroEnd = -1;
        long limit = Math.min(Constant.getOpEdLimit(duration), duration / 3);
        // Ignore malformed or unordered tables instead of seeking across unknown content.
        long previous = -1;
        for (MediaChapter chapter : chapters) {
            if (chapter.timeUs < 0 || chapter.timeUs <= previous || chapter.timeUs / 1000 > duration) return;
            previous = chapter.timeUs;
        }
        for (int i = 0; i < chapters.size(); i++) {
            MediaChapter chapter = chapters.get(i);
            if (chapter.label == null) continue;
            long start = chapter.timeUs / 1000;
            long end = i + 1 < chapters.size() ? chapters.get(i + 1).timeUs / 1000 : duration;
            if (end <= start || end - start > limit) continue;
            String label = chapter.label.trim();
            if (start < limit && end < duration && INTRO.matcher(label).matches()) {
                introStart = start;
                introEnd = end;
            } else if (duration - start <= limit && OUTRO.matcher(label).matches()) {
                outroStart = start;
                outroEnd = end;
            }
        }
    }
}
