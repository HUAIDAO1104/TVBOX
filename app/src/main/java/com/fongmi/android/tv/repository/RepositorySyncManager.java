package com.fongmi.android.tv.repository;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.SecretRedactor;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RepositorySyncManager {

    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long MAX_BYTES = 2L * 1024L * 1024L;

    public interface Listener {
        default void onStart(Repository repository) {
        }

        default void onSuccess(Repository repository, boolean cached) {
        }

        default void onError(Repository repository, String message, boolean hasCache) {
        }
    }

    private final Set<Long> syncing = ConcurrentHashMap.newKeySet();

    public boolean isSyncing(long repositoryId) {
        return syncing.contains(repositoryId);
    }

    public void sync(Repository repository, Listener listener) {
        if (repository == null || !syncing.add(repository.getId())) return;
        Task.execute(() -> run(repository, listener == null ? new Listener() {} : listener));
    }

    private void run(Repository repository, Listener listener) {
        long now = System.currentTimeMillis();
        repository.setLastSyncAt(now);
        repository.setStatus(RepositoryStatus.SYNCING);
        repository.setErrorMessage("");
        AppDatabase.get().getRepositoryDao().update(repository);
        App.post(() -> listener.onStart(repository));
        try {
            if (!repository.isEnabled()) throw new IllegalStateException("Repository is disabled");
            if (TextUtils.isEmpty(repository.getUrl())) throw new IllegalArgumentException("Repository URL is empty");
            Throwable lastError = null;
            for (String candidate : RepositoryUrlResolver.candidates(repository.getUrl())) {
                try {
                    boolean cached = syncCandidate(repository, candidate);
                    App.post(() -> listener.onSuccess(repository, cached));
                    return;
                } catch (Throwable error) {
                    lastError = error;
                }
            }
            if (lastError instanceof Exception exception) throw exception;
            if (lastError != null) throw new IOException(lastError);
            throw new IOException("Repository URL is empty");
        } catch (Throwable e) {
            String message = safeMessage(e);
            repository.setLastFailureAt(System.currentTimeMillis());
            repository.setStatus(repository.isEnabled() ? RepositoryStatus.FAILED : RepositoryStatus.DISABLED);
            repository.setErrorMessage(message);
            repository.setUpdatedAt(System.currentTimeMillis());
            AppDatabase.get().getRepositoryDao().update(repository);
            boolean hasCache = AppDatabase.get().getRepositoryItemDao().count(repository.getId()) > 0;
            App.post(() -> listener.onError(repository, message, hasCache));
        } finally {
            syncing.remove(repository.getId());
        }
    }

    private boolean syncCandidate(Repository repository, String url) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        if (!repository.getEtag().isEmpty()) builder.header("If-None-Match", repository.getEtag());
        if (!repository.getLastModified().isEmpty()) builder.header("If-Modified-Since", repository.getLastModified());
        try (Response response = OkHttp.client(TIMEOUT_MS).newCall(builder.build()).execute()) {
            if (response.code() == 304) {
                if (AppDatabase.get().getRepositoryItemDao().count(repository.getId()) == 0) {
                    throw new IOException("Repository returned 304 without a local cache");
                }
                markSuccess(repository, response, true);
                return true;
            }
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " from " + response.request().url().host());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response");
            if (body.contentLength() > MAX_BYTES) throw new IOException("Repository is larger than 2 MB");
            String json = body.string();
            if (json.length() > MAX_BYTES) throw new IOException("Repository is larger than 2 MB");
            List<RepositoryItem> items = RepositoryParser.parse(repository, json);
            List<RepositoryItem> cached = AppDatabase.get().getRepositoryItemDao().findByRepository(repository.getId());
            List<Long> staleIds = RepositoryItemMerge.reconcile(cached, items, repository.getName());
            AppDatabase.get().runInTransaction(() -> {
                for (long id : staleIds) AppDatabase.get().getRepositoryItemDao().delete(id);
                AppDatabase.get().getRepositoryItemDao().insertOrUpdate(items);
            });
            markSuccess(repository, response, false);
            return false;
        }
    }

    private void markSuccess(Repository repository, Response response, boolean cached) {
        long now = System.currentTimeMillis();
        repository.setLastSuccessAt(now);
        repository.setStatus(RepositoryStatus.SUCCESS);
        repository.setErrorMessage("");
        String etag = response.header("ETag");
        String modified = response.header("Last-Modified");
        if (etag != null) repository.setEtag(etag);
        if (modified != null) repository.setLastModified(modified);
        repository.setUpdatedAt(now);
        AppDatabase.get().getRepositoryDao().update(repository);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (TextUtils.isEmpty(message)) return error.getClass().getSimpleName();
        try {
            message = SecretRedactor.redact(message);
        } catch (Throwable ignored) {
            // Error reporting must never turn a recoverable repository failure into an app crash.
            // Do not return the original message here because it may contain a cookie or token.
            return error.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
