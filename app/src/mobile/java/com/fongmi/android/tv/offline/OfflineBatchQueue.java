package com.fongmi.android.tv.offline;

import android.content.Context;
import androidx.annotation.Keep;
import com.google.gson.Gson;
import java.util.*;

/** Durable preparation queue; no Activity, player, or credentials are exposed to the UI. */
public final class OfflineBatchQueue {
    public static final int PENDING = 0, PREPARING = 1, PAUSED = 2, FAILED = 3;
    private static OfflineBatchQueue instance;
    public static synchronized OfflineBatchQueue get(Context context) {
        if (instance == null) instance = new OfflineBatchQueue(context.getApplicationContext());
        return instance;
    }
    @Keep public static final class Job {
        public String id, site, flag, sourceUrl, resolvedUrl = "", mime, error = "";
        public OfflineMetadata meta = new OfflineMetadata();
        public int state, attempt;
        public Job copy() { return new Gson().fromJson(new Gson().toJson(this), Job.class); }
    }
    private final android.content.SharedPreferences prefs;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private OfflineBatchQueue(Context context) {
        prefs = context.getSharedPreferences("offline-batch-v1", 0);
        try {
            Job[] saved = new Gson().fromJson(prefs.getString("jobs", "[]"), Job[].class);
            if (saved != null) for (Job job : saved) {
                if (job == null || job.id == null || !job.id.matches("[a-f0-9]{64}") || job.meta == null) continue;
                job.meta = OfflineMetadata.decode(job.meta.encode());
                if (job.state == PREPARING) job.state = PENDING;
                jobs.put(job.id, job);
            }
        } catch (RuntimeException ignored) { }
    }
    private void save() { prefs.edit().putString("jobs", new Gson().toJson(jobs.values())).commit(); }
    public synchronized List<Job> jobs() {
        List<Job> result = new ArrayList<>(); for (Job job : jobs.values()) result.add(job.copy()); return result;
    }
    public synchronized int add(List<Job> items, Set<String> existing) {
        int count = 0;
        for (Job job : items) if (!jobs.containsKey(job.id) && !existing.contains(job.id)) {
            job.meta.sourceId = job.id; jobs.put(job.id, job.copy()); count++;
        }
        save(); return count;
    }
    public synchronized Job claim() {
        for (Job job : jobs.values()) if (job.state == PENDING) {
            job.state = PREPARING; job.attempt++; save(); return job.copy();
        }
        return null;
    }
    public synchronized boolean active(Job job) {
        Job current = jobs.get(job.id);
        return current != null && current.state == PREPARING && current.attempt == job.attempt;
    }
    public synchronized void finish(Job job, String error) {
        if (!active(job)) return;
        if (error == null) jobs.remove(job.id);
        else { Job current = jobs.get(job.id); current.state = FAILED; current.error = error; }
        save();
    }
    public synchronized void pause(String id) {
        Job job = jobs.get(id); if (job == null) return;
        job.state = PAUSED; job.attempt++; save();
    }
    public synchronized void resume(String id) {
        Job job = jobs.get(id); if (job == null || job.state == PREPARING) return;
        job.state = PENDING; job.error = ""; save();
    }
    public synchronized void remove(String id) { jobs.remove(id); save(); }
    public synchronized void recover() {
        for (Job job : jobs.values()) if (job.state == PREPARING) { job.state = PENDING; job.attempt++; }
        save();
    }
    public synchronized boolean pending() {
        for (Job job : jobs.values()) if (job.state == PENDING || job.state == PREPARING) return true;
        return false;
    }
    public static String identity(String site, String video, String flag, String episode) {
        return OfflineMetadata.id(site + "\n" + video + "\n" + flag + "\n" + episode, Collections.emptyMap());
    }
}
