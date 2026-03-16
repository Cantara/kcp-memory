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
        for (String resource : new String[]{
                "/db/V1__initial_schema.sql",
                "/db/V2__tool_events.sql",
                "/db/V3__agent_sessions.sql"}) {
            String sql = loadResource(resource);
            for (String stmt : splitStatements(sql)) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    try (Statement st = connection.createStatement()) {
                        st.execute(trimmed);
                    }
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
