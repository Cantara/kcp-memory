package com.cantara.kcp.memory.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for PendingTaskStore — pending task queue lifecycle.
 */
class PendingTaskStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private PendingTaskStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-pending-test-", ".db");
        db = new MemoryDatabase(tempDb);
        store = new PendingTaskStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void enqueueCreatesQueuedTask() throws SQLException {
        String taskId = store.enqueue("laptop", "run the tests");

        assertNotNull(taskId);
        assertFalse(taskId.isBlank());

        Optional<PendingTaskStore.PendingTask> task = store.get(taskId);
        assertTrue(task.isPresent());
        assertEquals("laptop", task.get().peerId());
        assertEquals("run the tests", task.get().prompt());
        assertEquals("queued", task.get().status());
        assertNotNull(task.get().createdAt());
        assertNull(task.get().claimedAt());
        assertNull(task.get().completedAt());
        assertNull(task.get().result());
        assertNull(task.get().error());
    }

    @Test
    void claimReturnsNextTask() throws SQLException {
        store.enqueue("laptop", "first task");
        Thread.yield(); // ensure ordering
        store.enqueue("laptop", "second task");

        Optional<PendingTaskStore.PendingTask> claimed = store.claim("laptop");
        assertTrue(claimed.isPresent());
        assertEquals("first task", claimed.get().prompt());
        assertEquals("claimed", claimed.get().status());
        assertNotNull(claimed.get().claimedAt());
    }

    @Test
    void claimReturnsEmptyWhenNoPending() throws SQLException {
        Optional<PendingTaskStore.PendingTask> claimed = store.claim("laptop");
        assertTrue(claimed.isEmpty());
    }

    @Test
    void claimReturnsEmptyWhenAllClaimed() throws SQLException {
        store.enqueue("laptop", "only task");
        store.claim("laptop"); // claim it

        Optional<PendingTaskStore.PendingTask> second = store.claim("laptop");
        assertTrue(second.isEmpty());
    }

    @Test
    void completeSetsDoneStatus() throws SQLException {
        String taskId = store.enqueue("laptop", "build project");
        store.claim("laptop");

        store.complete(taskId, "Build successful");

        Optional<PendingTaskStore.PendingTask> task = store.get(taskId);
        assertTrue(task.isPresent());
        assertEquals("done", task.get().status());
        assertEquals("Build successful", task.get().result());
        assertNotNull(task.get().completedAt());
        assertNull(task.get().error());
    }

    @Test
    void failSetsErrorStatus() throws SQLException {
        String taskId = store.enqueue("laptop", "deploy app");
        store.claim("laptop");

        store.fail(taskId, "Connection refused");

        Optional<PendingTaskStore.PendingTask> task = store.get(taskId);
        assertTrue(task.isPresent());
        assertEquals("error", task.get().status());
        assertEquals("Connection refused", task.get().error());
        assertNotNull(task.get().completedAt());
        assertNull(task.get().result());
    }

    @Test
    void listReturnsMostRecentFirst() throws SQLException {
        store.enqueue("laptop", "task A");
        store.enqueue("laptop", "task B");
        store.enqueue("laptop", "task C");

        List<PendingTaskStore.PendingTask> tasks = store.list("laptop", 50);
        assertEquals(3, tasks.size());
        // Most recent first
        assertEquals("task C", tasks.get(0).prompt());
        assertEquals("task B", tasks.get(1).prompt());
        assertEquals("task A", tasks.get(2).prompt());
    }

    @Test
    void listLimitsResults() throws SQLException {
        for (int i = 0; i < 5; i++) {
            store.enqueue("laptop", "task " + i);
        }

        List<PendingTaskStore.PendingTask> tasks = store.list("laptop", 3);
        assertEquals(3, tasks.size());
    }

    @Test
    void differentPeersIsolated() throws SQLException {
        store.enqueue("laptop", "laptop task");
        store.enqueue("ec2-b", "ec2 task");

        Optional<PendingTaskStore.PendingTask> claimed = store.claim("laptop");
        assertTrue(claimed.isPresent());
        assertEquals("laptop task", claimed.get().prompt());
        assertEquals("laptop", claimed.get().peerId());

        // ec2-b task still available for ec2-b
        Optional<PendingTaskStore.PendingTask> ec2Claimed = store.claim("ec2-b");
        assertTrue(ec2Claimed.isPresent());
        assertEquals("ec2 task", ec2Claimed.get().prompt());

        // laptop has no more tasks
        assertTrue(store.claim("laptop").isEmpty());
    }
}
