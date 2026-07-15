package com.fongmi.android.tv.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fongmi.android.tv.bean.Repository;
import com.fongmi.android.tv.bean.RepositoryItem;
import com.fongmi.android.tv.db.AppDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

@RunWith(AndroidJUnit4.class)
public class RepositorySyncManagerInstrumentedTest {

    private static final long WAIT_SECONDS = 10;
    private static final String LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT";

    private final RepositorySyncManager syncManager = new RepositorySyncManager();

    private MockWebServer server;
    private Repository repository;
    private long repositoryId;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        repository = new Repository();
        repository.setStableId("androidtest-sync-" + UUID.randomUUID());
        repository.setName("Android test repository");
        repository.setUrl(server.url("/repository.json").toString());
        repository.setEnabled(true);
        repository.setStatus(RepositoryStatus.IDLE);
        repository.touch();
        repositoryId = AppDatabase.get().getRepositoryDao().insert(repository);
        assertTrue("Repository fixture must be inserted", repositoryId > 0);
        repository.setId(repositoryId);

        RepositoryItem cached = new RepositoryItem();
        cached.setRepositoryId(repositoryId);
        cached.setItemId("cached-item");
        cached.setName("Last usable cache");
        cached.setUrl("https://cache.invalid/original.json");
        cached.setType(0);
        cached.setSortOrder(0);
        cached.setEnabled(true);
        cached.setCreatedAt(System.currentTimeMillis());
        cached.setUpdatedAt(System.currentTimeMillis());
        long cachedId = AppDatabase.get().getRepositoryItemDao().insert(cached);
        assertTrue("Cache fixture must be inserted", cachedId > 0);
    }

    @After
    public void tearDown() throws Exception {
        waitUntilIdle();
        if (repositoryId > 0) AppDatabase.get().getRepositoryDao().delete(repositoryId);
        if (server != null) server.close();
    }

    @Test
    public void conditionalRequestSendsValidatorsAnd304KeepsCache() throws Exception {
        repository.setEtag("\"fixture-etag-v1\"");
        repository.setLastModified(LAST_MODIFIED);
        AppDatabase.get().getRepositoryDao().update(repository);
        List<String> before = cacheSignature();
        server.enqueue(new MockResponse.Builder().code(304).build());

        SyncOutcome outcome = syncAndAwait();
        RecordedRequest request = server.takeRequest(WAIT_SECONDS, TimeUnit.SECONDS);

        assertNotNull("Conditional request was not received", request);
        assertEquals("\"fixture-etag-v1\"", request.getHeaders().get("If-None-Match"));
        assertEquals(LAST_MODIFIED, request.getHeaders().get("If-Modified-Since"));
        assertTrue(outcome.success);
        assertTrue(outcome.cached);
        assertFalse(outcome.error);
        assertEquals(before, cacheSignature());

        Repository stored = storedRepository();
        assertEquals(RepositoryStatus.SUCCESS, stored.getStatus());
        assertTrue(stored.getLastSuccessAt() > 0);
        assertEquals("", stored.getErrorMessage());
    }

    @Test
    public void http404MarksFailedAndKeepsLastUsableCache() throws Exception {
        List<String> before = cacheSignature();
        server.enqueue(new MockResponse.Builder().code(404).body("missing").build());

        SyncOutcome outcome = syncAndAwait();

        assertTrue(outcome.error);
        assertTrue(outcome.hasCache);
        assertFalse(outcome.success);
        assertEquals(before, cacheSignature());

        Repository stored = storedRepository();
        assertEquals(RepositoryStatus.FAILED, stored.getStatus());
        assertTrue(stored.getLastFailureAt() > 0);
        assertTrue(stored.getErrorMessage().contains("HTTP 404"));
    }

    @Test
    public void invalidJsonMarksFailedAndKeepsLastUsableCache() throws Exception {
        List<String> before = cacheSignature();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{ not-valid-json")
                .build());

        SyncOutcome outcome = syncAndAwait();

        assertTrue(outcome.error);
        assertTrue(outcome.hasCache);
        assertFalse(outcome.success);
        assertEquals(before, cacheSignature());
        assertEquals(RepositoryStatus.FAILED, storedRepository().getStatus());
    }

    @Test
    public void successfulRefreshReplacesCacheAndPersistsResponseValidators() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("ETag", "\"fixture-etag-v2\"")
                .addHeader("Last-Modified", LAST_MODIFIED)
                .body("{\"urls\":["
                        + "{\"id\":\"vod-a\",\"name\":\"Fixture A\",\"url\":\"https://fixture.invalid/a.json\",\"type\":0},"
                        + "{\"id\":\"live-b\",\"name\":\"Fixture B\",\"url\":\"https://fixture.invalid/b.json\",\"type\":1}"
                        + "]}")
                .build());

        SyncOutcome outcome = syncAndAwait();

        assertTrue(outcome.success);
        assertFalse(outcome.cached);
        assertFalse(outcome.error);

        List<RepositoryItem> items = AppDatabase.get().getRepositoryItemDao().findByRepository(repositoryId);
        assertEquals(2, items.size());
        assertEquals("Fixture A", items.get(0).getName());
        assertEquals("https://fixture.invalid/a.json", items.get(0).getUrl());
        assertEquals("Fixture B", items.get(1).getName());
        assertFalse(items.stream().anyMatch(item -> "cached-item".equals(item.getItemId())));

        Repository stored = storedRepository();
        assertEquals(RepositoryStatus.SUCCESS, stored.getStatus());
        assertEquals("\"fixture-etag-v2\"", stored.getEtag());
        assertEquals(LAST_MODIFIED, stored.getLastModified());
        assertEquals("", stored.getErrorMessage());
        assertTrue(stored.getLastSuccessAt() > 0);
    }

    private SyncOutcome syncAndAwait() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SyncOutcome outcome = new SyncOutcome();
        syncManager.sync(repository, new RepositorySyncManager.Listener() {
            @Override
            public void onSuccess(Repository value, boolean cached) {
                outcome.success = true;
                outcome.cached = cached;
                latch.countDown();
            }

            @Override
            public void onError(Repository value, String message, boolean hasCache) {
                outcome.error = true;
                outcome.hasCache = hasCache;
                outcome.message = message;
                latch.countDown();
            }
        });
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) fail("Repository sync callback timed out");
        waitUntilIdle();
        return outcome;
    }

    private void waitUntilIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (repositoryId > 0 && syncManager.isSyncing(repositoryId) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse("Repository sync did not become idle", repositoryId > 0 && syncManager.isSyncing(repositoryId));
    }

    private Repository storedRepository() {
        Repository stored = AppDatabase.get().getRepositoryDao().findById(repositoryId);
        assertNotNull(stored);
        return stored;
    }

    private List<String> cacheSignature() {
        return AppDatabase.get().getRepositoryItemDao().findByRepository(repositoryId).stream()
                .map(item -> item.getItemId() + "|" + item.getName() + "|" + item.getUrl() + "|" + item.getType())
                .toList();
    }

    private static final class SyncOutcome {
        private boolean success;
        private boolean cached;
        private boolean error;
        private boolean hasCache;
        @SuppressWarnings("unused")
        private String message;
    }
}
