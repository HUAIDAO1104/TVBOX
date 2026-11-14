package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.NativeDanmaku;
import com.fongmi.android.tv.player.danmaku.DanmakuStatus;
import com.fongmi.android.tv.setting.DanmakuAiSetting;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** A single bounded AI interpretation before any catalogue lookup or cached-document restore. */
final class DanmakuAiEpisodeResolver {
    private static final ExecutorService WORK = Executors.newFixedThreadPool(2);
    private static final Map<String, String> CACHE = new LinkedHashMap<>(32, .75f, true);
    private static final Set<NativeDanmaku.Job> ACTIVE = ConcurrentHashMap.newKeySet();
    record Outcome(String episode, DanmakuStatus.Failure failure) { }

    static void cancelAll() { for (var job : ACTIVE) job.cancel(); ACTIVE.clear(); }

    static NativeDanmaku.Job resolve(DanmakuMatchContext context, int position, DanmakuEpisodePlan plan,
                                     Consumer<Outcome> callback) {
        class Job implements NativeDanmaku.Job {
            volatile boolean cancelled;
            volatile DanmakuAiClient client;
            Future<?> task;
            public void cancel() { cancelled = true; if (client != null) client.cancel(); if (task != null) task.cancel(true); ACTIVE.remove(this); }
            void run() {
                String key = DanmakuAiSetting.baseUrl() + '\u001f' + DanmakuAiSetting.model() + '\u001f'
                        + context.getTitle() + '\u001f' + context.getYear() + '\u001f' + context.getType() + '\u001f' + plan.raw;
                String answer;
                synchronized (CACHE) { answer = CACHE.get(key); }
                DanmakuStatus.Failure reason = DanmakuStatus.Failure.AI_INVALID_RESPONSE;
                if (answer == null && !cancelled) {
                    try {
                        client = DanmakuAiClient.configured();
                        if (cancelled) { client.cancel(); return; }
                        answer = plan.accept(client.complete(plan.input(context, position)));
                        if (!answer.isEmpty() && !cancelled) synchronized (CACHE) {
                            CACHE.put(key, answer);
                            while (CACHE.size() > 256) CACHE.remove(CACHE.keySet().iterator().next());
                        }
                    } catch (Exception error) {
                        reason = "http_401".equals(error.getMessage()) || "http_403".equals(error.getMessage())
                                ? DanmakuStatus.Failure.AI_ACCESS_DENIED : DanmakuStatus.Failure.AI_NETWORK;
                    }
                }
                String episode = answer == null || answer.isEmpty() ? plan.fallback : answer;
                Outcome outcome = new Outcome(episode, reason);
                App.post(() -> { ACTIVE.remove(this); if (!cancelled) callback.accept(outcome); });
            }
        }
        Job job = new Job(); ACTIVE.add(job); job.task = WORK.submit(job::run);
        if (job.cancelled) job.task.cancel(true);
        return job;
    }
}
