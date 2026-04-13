package com.cantara.kcp.memory.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD for the pending_tasks table (V7 migration).
 *
 * <p>Implements a simple task queue: peers poll for queued tasks,
 * claim them atomically, execute locally, and push results back.
 */
public class PendingTaskStore {

    /**
     * Immutable snapshot of a pending task row.
     */
    public record PendingTask(
            String id,
            String peerId,
            String prompt,
            String status,
            String createdAt,
            String claimedAt,
            String completedAt,
            String result,
            String error
    ) {}

    private final MemoryDatabase db;

    public PendingTaskStore(MemoryDatabase db) {
        this.db = db;
    }

    /**
     * Queue a new task for a peer.
     *
     * @param peerId the target peer identifier
     * @param prompt the task prompt to execute
     * @return the generated task ID (UUID)
     */
    public String enqueue(String peerId, String prompt) throws SQLException {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        try (PreparedStatement ps = db.getConnection().prepareStatement("""
                INSERT INTO pending_tasks (id, peer_id, prompt, status, created_at)
                VALUES (?, ?, ?, 'queued', ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, peerId);
            ps.setString(3, prompt);
            ps.setString(4, now);
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * Claim the next queued task for a peer (oldest first).
     * Atomically sets status to "claimed" and records claimed_at.
     *
     * @param peerId the peer claiming the task
     * @return the claimed task, or empty if none pending
     */
    public Optional<PendingTask> claim(String peerId) throws SQLException {
        // Find the oldest queued task for this peer
        String findSql = """
                SELECT id FROM pending_tasks
                WHERE peer_id = ? AND status = 'queued'
                ORDER BY created_at ASC
                LIMIT 1
                """;

        String taskId;
        try (PreparedStatement ps = db.getConnection().prepareStatement(findSql)) {
            ps.setString(1, peerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                taskId = rs.getString("id");
            }
        }

        // Claim it
        String now = Instant.now().toString();
        try (PreparedStatement ps = db.getConnection().prepareStatement("""
                UPDATE pending_tasks SET status = 'claimed', claimed_at = ?
                WHERE id = ? AND status = 'queued'
                """)) {
            ps.setString(1, now);
            ps.setString(2, taskId);
            int updated = ps.executeUpdate();
            if (updated == 0) return Optional.empty(); // raced
        }

        return get(taskId);
    }

    /**
     * Mark a task as done with result text.
     *
     * @param taskId the task ID
     * @param result the execution result
     */
    public void complete(String taskId, String result) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement ps = db.getConnection().prepareStatement("""
                UPDATE pending_tasks SET status = 'done', result = ?, completed_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, result);
            ps.setString(2, now);
            ps.setString(3, taskId);
            ps.executeUpdate();
        }
    }

    /**
     * Mark a task as failed with error message.
     *
     * @param taskId the task ID
     * @param error  the error message
     */
    public void fail(String taskId, String error) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement ps = db.getConnection().prepareStatement("""
                UPDATE pending_tasks SET status = 'error', error = ?, completed_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, error);
            ps.setString(2, now);
            ps.setString(3, taskId);
            ps.executeUpdate();
        }
    }

    /**
     * List all tasks for a peer (newest first).
     *
     * @param peerId the peer to list tasks for
     * @param limit  max number of results
     * @return list of tasks, newest first
     */
    public List<PendingTask> list(String peerId, int limit) throws SQLException {
        List<PendingTask> results = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement("""
                SELECT * FROM pending_tasks
                WHERE peer_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """)) {
            ps.setString(1, peerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        }
        return results;
    }

    /**
     * Get a specific task by ID.
     *
     * @param taskId the task ID
     * @return the task, or empty if not found
     */
    public Optional<PendingTask> get(String taskId) throws SQLException {
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT * FROM pending_tasks WHERE id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    private PendingTask mapRow(ResultSet rs) throws SQLException {
        return new PendingTask(
                rs.getString("id"),
                rs.getString("peer_id"),
                rs.getString("prompt"),
                rs.getString("status"),
                rs.getString("created_at"),
                rs.getString("claimed_at"),
                rs.getString("completed_at"),
                rs.getString("result"),
                rs.getString("error")
        );
    }
}
