package com.cantara.kcp.memory.peer;

import com.cantara.kcp.memory.peer.TaskExecutor;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.PendingTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for the pending task poll loop.
 * Uses an in-memory store and injectable TaskExecutor (no real claude invocation).
 */
class PendingTaskPollTest {

    private Path tempDb;
    private MemoryDatabase db;
    private PendingTaskStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-poll-test-", ".db");
        db = new MemoryDatabase(tempDb);
        store = new PendingTaskStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void noTasksNothingHappens() throws SQLException {
        // No tasks queued for this peer
        Optional<PendingTaskStore.PendingTask> task = store.claim("my-laptop");
        assertTrue(task.isEmpty(), "Should have no tasks to claim");
    }

    @Test
    void taskIsClaimedAndMarkedDone() throws SQLException, IOException {
        // Simulate: hub enqueues a task for "my-laptop"
        String taskId = store.enqueue("my-laptop", "echo hello world");

        // Simulate: peer polls and claims the task
        Optional<PendingTaskStore.PendingTask> claimed = store.claim("my-laptop");
        assertTrue(claimed.isPresent());
        assertEquals("claimed", claimed.get().status());
        assertEquals("echo hello world", claimed.get().prompt());

        // Simulate: peer executes via injectable TaskExecutor
        TaskExecutor executor = (prompt, systemPrompt) -> "hello world";
        String result = executor.execute(claimed.get().prompt());

        // Simulate: peer pushes result back
        store.complete(taskId, result);

        // Verify final state
        Optional<PendingTaskStore.PendingTask> done = store.get(taskId);
        assertTrue(done.isPresent());
        assertEquals("done", done.get().status());
        assertEquals("hello world", done.get().result());
        assertNotNull(done.get().completedAt());
    }

    @Test
    void taskExecutorFailureMarksError() throws SQLException {
        String taskId = store.enqueue("my-laptop", "failing task");

        Optional<PendingTaskStore.PendingTask> claimed = store.claim("my-laptop");
        assertTrue(claimed.isPresent());

        // Simulate: executor throws
        TaskExecutor executor = (prompt, systemPrompt) -> { throw new IOException("Process timed out"); };
        try {
            executor.execute(claimed.get().prompt());
            fail("Should have thrown");
        } catch (IOException e) {
            store.fail(taskId, e.getMessage());
        }

        Optional<PendingTaskStore.PendingTask> errored = store.get(taskId);
        assertTrue(errored.isPresent());
        assertEquals("error", errored.get().status());
        assertEquals("Process timed out", errored.get().error());
    }
}
