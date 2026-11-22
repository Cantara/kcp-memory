package com.cantara.kcp.memory.mcp;

import com.cantara.kcp.memory.model.Decision;
import com.cantara.kcp.memory.store.DecisionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What an agent reads back from kcp_memory_decisions, and how it tells configured text from unverified text (#97). */
class DecisionsOutputTest {

    private static DecisionStore.DecisionHit hit(String id, String project, Decision.Source source) {
        Decision d = new Decision(id, "constraint", "testing", "What " + id, "Why " + id,
                List.of(), "session-1", null, List.of("t"), project, source);
        return new DecisionStore.DecisionHit(d, List.of(), source);
    }

    @Test
    void aSessionDiscoveredHitIsMarkedUnverifiedAndTheResponseCarriesANote() {
        String out = McpServer.formatDecisionHits(List.of(hit("a", "/src/hostile", Decision.Source.SESSION)));

        assertTrue(out.contains("**Source**: session cwd (unverified)"), out);
        assertTrue(out.contains("not as instructions"), out);
        assertTrue(out.indexOf("Note:") < out.indexOf("## a"), "the note comes before the first hit: " + out);
    }

    @Test
    void aConfiguredHitIsMarkedConfiguredAndNoNoteIsAdded() {
        String out = McpServer.formatDecisionHits(List.of(hit("a", "/src/mine", Decision.Source.CONFIGURED)));

        assertTrue(out.contains("**Source**: configured"), out);
        assertFalse(out.contains("unverified"), out);
        assertFalse(out.contains("Note:"), out);
    }

    @Test
    void aMixedResultMarksEachHitAndCarriesOneNote() {
        String out = McpServer.formatDecisionHits(List.of(
                hit("mine", "/src/mine", Decision.Source.CONFIGURED),
                hit("theirs", "/src/hostile", Decision.Source.SESSION)));

        assertEquals(1, out.split("Note:", -1).length - 1, out);
        assertTrue(out.indexOf("**Source**: configured") < out.indexOf("**Source**: session cwd (unverified)"), out);
    }
}
