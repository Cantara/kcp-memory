package com.cantara.kcp.memory.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When a kcp-commands update is reported (#96). */
class UpdateCheckerVersionsTest {

    @Test
    void anUnknownInstalledVersionIsNeverReportedAsOutdated() {
        // What the daemon used to do on a machine without kcp-commands, or when it passed kcp-memory's own
        // version: it produced "kcp-commands update available: 0.39.1 -> 0.30.0".
        var v = new UpdateChecker.Versions(null, "0.30.1", "0.39.2", "0.39.2");
        assertFalse(v.commandsOutdated());
        assertFalse(v.anyOutdated());
    }

    @Test
    void anInstalledVersionBehindTheLatestIsOutdated() {
        var v = new UpdateChecker.Versions("0.30.0", "0.30.1", "0.39.2", "0.39.2");
        assertTrue(v.commandsOutdated());
        assertTrue(v.anyOutdated());
    }

    @Test
    void anInstalledVersionEqualToTheLatestIsCurrent() {
        var v = new UpdateChecker.Versions("0.30.1", "0.30.1", "0.39.2", "0.39.2");
        assertFalse(v.commandsOutdated());
    }

    @Test
    void anUnknownLatestVersionIsNeverOutdated() {
        var v = new UpdateChecker.Versions("0.30.1", null, "0.39.2", "0.39.2");
        assertFalse(v.commandsOutdated());
    }

    @Test
    void memoryStillReportsItsOwnUpdate() {
        var v = new UpdateChecker.Versions(null, null, "0.39.1", "0.39.2");
        assertTrue(v.memoryOutdated());
        assertTrue(v.anyOutdated());
    }
}
