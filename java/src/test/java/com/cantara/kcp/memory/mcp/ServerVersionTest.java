package com.cantara.kcp.memory.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The daemon must report the version it was built as. SERVER_VERSION was a hand-written constant
 * that two releases (v0.37.1, v0.38.0) did not bump, so /health, `kcp-memory status` and the MCP
 * serverInfo said 0.37.0 for both. It now comes from the pom through a filtered resource, and surefire
 * passes the pom's version to this test as kcp.expected.version.
 */
class ServerVersionTest {

    @Test
    void reports_the_version_it_was_built_as() {
        String expected = System.getProperty("kcp.expected.version");
        assertNotNull(expected, "surefire must pass kcp.expected.version");
        assertEquals(expected, McpServer.SERVER_VERSION);
    }

    @Test
    void version_is_a_plain_semver() {
        assertTrue(McpServer.SERVER_VERSION.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?"),
                "unexpected version string: " + McpServer.SERVER_VERSION);
    }
}
