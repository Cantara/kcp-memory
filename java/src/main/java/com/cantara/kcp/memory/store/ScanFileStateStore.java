package com.cantara.kcp.memory.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Size/mtime of each transcript file as it was when last parsed (table
 * {@code scan_file_state}, #88). Lets a scanner decide "unchanged, skip" from a
 * {@code stat} call instead of reading and parsing the whole file.
 */
public class ScanFileStateStore {

    public static final String SCANNER_SESSION = "session";
    public static final String SCANNER_AGENT = "agent";

    /** Size and last-modified time of a file, read with a single stat call. */
    public record FileStamp(long sizeBytes, long mtimeMs) {
        public static FileStamp of(Path file) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis());
        }
    }

    public record Entry(String entityId, FileStamp stamp) {}

    private final Connection conn;

    public ScanFileStateStore(MemoryDatabase db) {
        this.conn = db.getConnection();
    }

    /** The recorded state for {@code file} under {@code scanner}, or null if none. */
    public Entry get(String scanner, Path file) throws SQLException {
        String sql = "SELECT entity_id, size_bytes, mtime_ms FROM scan_file_state WHERE path = ? AND scanner = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key(file));
            ps.setString(2, scanner);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Entry(rs.getString(1), new FileStamp(rs.getLong(2), rs.getLong(3)));
            }
        }
    }

    /** Record that {@code file}, with the given stamp, has been parsed into {@code entityId}. */
    public void record(String scanner, Path file, String entityId, FileStamp stamp) throws SQLException {
        String sql = """
                INSERT INTO scan_file_state (path, scanner, entity_id, size_bytes, mtime_ms, recorded_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(path) DO UPDATE SET
                  scanner     = excluded.scanner,
                  entity_id   = excluded.entity_id,
                  size_bytes  = excluded.size_bytes,
                  mtime_ms    = excluded.mtime_ms,
                  recorded_at = excluded.recorded_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key(file));
            ps.setString(2, scanner);
            ps.setString(3, entityId != null ? entityId : "");
            ps.setLong(4, stamp.sizeBytes());
            ps.setLong(5, stamp.mtimeMs());
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }
}
