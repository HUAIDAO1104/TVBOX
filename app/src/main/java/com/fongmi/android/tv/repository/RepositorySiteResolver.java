package com.fongmi.android.tv.repository;

import androidx.annotation.WorkerThread;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Rehydrates a repository-scoped site after process death without changing the active config. */
public final class RepositorySiteResolver {

    public static final long DEFAULT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);

    private RepositorySiteResolver() {
    }

    /**
     * Resolves on the shared background pool and always delivers the callback on the main thread.
     * Disabled repositories/items are included so History and Keep entries remain recoverable.
     */
    public static Future<?> resolve(String scopedKey, Callback callback) {
        return resolve(scopedKey, DEFAULT_TIMEOUT_MS, callback);
    }

    public static Future<?> resolve(String scopedKey, long timeoutMs, Callback callback) {
        Objects.requireNonNull(callback, "callback");
        Site cached = RepositorySiteRegistry.find(scopedKey);
        if (cached != null) {
            App.post(() -> callback.onResolved(cached));
            return CompletableFuture.completedFuture(cached);
        }
        return Task.submitLarge(() -> {
            Resolution resolution = resolveBlocking(scopedKey, timeoutMs);
            if (Thread.currentThread().isInterrupted()) return;
            App.post(() -> {
                if (resolution.isSuccess()) callback.onResolved(resolution.site());
                else callback.onFailure(resolution.failure());
            });
        });
    }

    @WorkerThread
    public static Resolution resolveBlocking(String scopedKey, long timeoutMs) {
        Site cached = RepositorySiteRegistry.find(scopedKey);
        if (cached != null) return Resolution.success(cached);
        if (!RepositorySiteKey.isScoped(scopedKey)) return Resolution.failure(Failure.INVALID_KEY);

        RepositoryManager manager = RepositoryManager.get();
        RepositorySiteLocator.Match match = RepositorySiteLocator.find(
                scopedKey, manager.getAll(), manager::getAllItems);
        if (match == null) return Resolution.failure(Failure.NOT_FOUND);

        long timeout = Math.max(1, timeoutMs);
        String tag = "RepositoryResolve-" + RepositorySiteKey.fingerprint(scopedKey);
        try {
            String content = Decoder.getJson(UrlUtil.convert(match.item().getUrl()), tag, timeout);
            List<Site> sites = RepositorySiteParser.parse(match.repository(), match.item(), content);
            Site site = sites.stream()
                    .filter(item -> item.getOriginKey().equals(match.originKey()))
                    .findFirst()
                    .orElse(null);
            if (site == null) return Resolution.failure(Failure.SITE_UNAVAILABLE);
            // Accept keys produced before config type was added to the fingerprint. Keeping the
            // requested key lets existing History/Keep rows resolve through the same registry.
            if (!site.getKey().equals(scopedKey)) site.setKey(scopedKey);
            return Resolution.success(RepositorySiteRegistry.register(site));
        } catch (InterruptedIOException e) {
            return Resolution.failure(Failure.TIMEOUT);
        } catch (Exception e) {
            return Resolution.failure(Failure.LOAD_FAILED);
        }
    }

    public interface Callback {
        void onResolved(Site site);

        void onFailure(Failure failure);
    }

    public enum Failure {
        INVALID_KEY,
        NOT_FOUND,
        TIMEOUT,
        LOAD_FAILED,
        SITE_UNAVAILABLE
    }

    public record Resolution(Site site, Failure failure) {

        static Resolution success(Site site) {
            return new Resolution(site, null);
        }

        static Resolution failure(Failure failure) {
            return new Resolution(null, failure);
        }

        public boolean isSuccess() {
            return site != null;
        }
    }
}
