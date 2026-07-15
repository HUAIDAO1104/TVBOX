package com.fongmi.android.tv.repository;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

/** Performs a read-only, bounded validation without switching the active configuration. */
public final class RepositoryItemChecker {

    public interface Listener {
        default void onStart(RepositoryItem item) {
        }

        default void onComplete(RepositoryItem item, boolean success) {
        }
    }

    private RepositoryItemChecker() {
    }

    public static void check(RepositoryItem item, Listener listener) {
        if (item == null) return;
        Listener callback = listener == null ? new Listener() {} : listener;
        item.setCheckStatus("CHECKING");
        item.setErrorMessage("");
        item.setUpdatedAt(System.currentTimeMillis());
        com.fongmi.android.tv.db.AppDatabase.get().getRepositoryItemDao().update(item);
        callback.onStart(item);
        Task.execute(() -> {
            boolean success = false;
            String error = "";
            try {
                String content = Decoder.getJson(UrlUtil.convert(item.getUrl()), "RepositoryItem-" + item.getId());
                validate(item, content);
                success = true;
            } catch (Throwable throwable) {
                error = throwable.getMessage();
                if (error == null || error.isBlank()) error = throwable.getClass().getSimpleName();
            }
            RepositoryManager.get().updateItemCheck(item, success, error);
            boolean result = success;
            App.post(() -> callback.onComplete(item, result));
        });
    }

    private static void validate(RepositoryItem item, String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Empty configuration");
        if (item.getType() == 1 && !Json.isObj(content)) return;
        if (item.getType() == 2) return;
        if (!Json.isObj(content)) throw new IllegalArgumentException("Configuration is not a JSON object");
        JsonObject object = Json.parse(content).getAsJsonObject();
        if (object.has("msg")) throw new IllegalArgumentException("Configuration returned an error");
        if (item.getType() == 1) {
            if (!object.has("lives") && !object.has("urls")) throw new IllegalArgumentException("No live configuration found");
        } else if (!object.has("sites") && !object.has("spider") && !object.has("urls")) {
            throw new IllegalArgumentException("No VOD configuration found");
        }
    }
}
