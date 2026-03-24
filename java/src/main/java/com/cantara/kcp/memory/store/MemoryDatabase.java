package com.cantara.kcp.memory.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Manages the SQLite connection and schema initialisation.
 * Default location: ~/.kcp/memory.db
 */
public class MemoryDatabase implements AutoCloseable {

    private static final String DEFAULT_DB_PATH =
            System.getProperty("user.home") + "/.kcp/memory.db";

    private final Connection connection;

    public MemoryDatabase() throws SQLException {
        this(Path.of(DEFAULT_DB_PATH));
    }

    public MemoryDatabase(Path dbPath) throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("Cannot create parent directory for " + dbPath, e);
        }
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.connection = DriverManager.getConnection(url);
        configure();
        initSchema();
    }

    private void configure() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA cache_size=-4000");    // 4 MB page cache
        }
    }

    private void initSchema() throws SQLException {
        // Bootstrap the migration tracking table (always idempotent)
        boolean trackingTableNew;
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version TEXT PRIMARY KEY,
                        applied_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """);
            try (java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
                trackingTableNew = rs.next() && rs.getLong(1) == 0;
            }
        }

        // If this is an existing DB that predates migration tracking, detect and record
        // already-applied migrations based on structural evidence rather than re-running them.
        if (trackingTableNew) {
            backfillMigrationTracking();
        }

        for (String resource : new String[]{
                "/db/V1__initial_schema.sql",
                "/db/V2__tool_events.sql",
                "/db/V3__agent_sessions.sql",
                "/db/V4__output_preview.sql",
                "/db/V5__manifest_version.sql"}) {

            String version = resource.substring(resource.lastIndexOf('/') + 1, resource.lastIndexOf('.'));

            boolean alreadyApplied;
            try (java.sql.PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM schema_migrations WHERE version = ?")) {
                ps.setString(1, version);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    alreadyApplied = rs.next();
                }
            }
            if (alreadyApplied) continue;

            String sql = loadResource(resource);
            for (String stmt : splitStatements(sql)) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    try (Statement st = connection.createStatement()) {
                        st.execute(trimmed);
                    }
                }
            }

            try (java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version) VALUES (?)")) {
                ps.setString(1, version);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Backfill migration tracking for databases that predate the schema_migrations table.
     * Detects already-applied migrations by checking structural evidence (tables/columns).
     */
    private void backfillMigrationTracking() throws SQLException {
        record MigrationCheck(String version, String checkSql) {}
        var checks = List.of(
                new MigrationCheck("V1__initial_schema",
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='sessions'"),
                new MigrationCheck("V2__tool_events",
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='tool_events'"),
                new MigrationCheck("V3__agent_sessions",
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='agent_sessions'"),
                new MigrationCheck("V4__output_preview",
                        "SELECT 1 FROM pragma_table_info('tool_events') WHERE name='output_preview'"),
                new MigrationCheck("V5__manifest_version",
                        "SELECT 1 FROM pragma_table_info('tool_events') WHERE name='manifest_version'")
        );
        for (MigrationCheck check : checks) {
            boolean present;
            try (Statement st = connection.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(check.checkSql())) {
                present = rs.next();
            } catch (SQLException ignored) {
                present = false;
            }
            if (present) {
                try (java.sql.PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR IGNORE INTO schema_migrations (version) VALUES (?)")) {
                    ps.setString(1, check.version());
                    ps.executeUpdate();
                }
            }
        }
    }

    private String loadResource(String path) throws SQLException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new SQLException("Schema resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Cannot read schema resource: " + path, e);
        }
    }

    /**
     * Split SQL into individual statements, correctly handling trigger bodies
     * (which contain semicolons inside BEGIN...END blocks).
     */
    private List<String> splitStatements(String sql) {
        List<String> result = new java.util.ArrayList<>();
        int depth = 0; // tracks BEGIN...END nesting
        StringBuilder current = new StringBuilder();

        for (String line : sql.split("\n")) {
            String upper = line.trim().toUpperCase();
            // Skip pure comment lines
            if (upper.startsWith("--")) continue;

            // Entering a trigger body
            if (upper.endsWith("BEGIN")) depth++;

            current.append(line).append('\n');

            if (depth > 0 && upper.equals("END;")) {
                // Closing a trigger
                depth--;
                if (depth == 0) {
                    String stmt = current.toString().trim();
                    if (!stmt.isEmpty()) result.add(stmt);
                    current = new StringBuilder();
                }
            } else if (depth == 0 && line.trim().endsWith(";")) {
                // Normal statement
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) result.add(stmt);
                current = new StringBuilder();
            }
        }
        // Flush anything remaining
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) result.add(remaining);
        return result;
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
