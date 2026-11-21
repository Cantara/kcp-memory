package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.Decision;
import com.cantara.kcp.memory.store.DecisionStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DecisionScannerTest {

    private static final String DECISIONS_YAML = """
            decisions:
              - id: "burner-api-key"
                type: "decision"
                domain: "burner-profile"
                what: "Should the burner sandbox get its own model API key?"
                why: "Hostile content summarized through the shared key could get the main account flagged."
                learned: "total-control#30"
                tags: ["burner", "sandbox"]
              - id: "lambda-inline-code-limit"
                type: anti-pattern
                domain: deployment
                what: "Do not use inline code for Lambda handlers"
                why: "Inline code has a 4KB limit."
                learned: "session-abc"
            """;

    @TempDir Path tmp;

    private Path tempDb;
    private MemoryDatabase db;
    private DecisionStore store;
    private Path claudeProjects;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-decision-test-", ".db");
        db = new MemoryDatabase(tempDb);
        store = new DecisionStore(db);
        claudeProjects = Files.createDirectories(tmp.resolve("claude-projects"));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    /** Create a project with .sdd/decisions/index.yaml and return its root. */
    private Path project(String name, String yaml) throws Exception {
        Path root = Files.createDirectories(tmp.resolve(name));
        Path dir = Files.createDirectories(root.resolve(".sdd/decisions"));
        Files.writeString(dir.resolve("index.yaml"), yaml);
        return root;
    }

    /** Write a Claude Code session transcript whose cwd is {@code cwd}, and index it. */
    private void sessionIn(Path cwd, String sessionId) throws Exception {
        Path slugDir = Files.createDirectories(claudeProjects.resolve("slug-" + sessionId));
        Files.writeString(slugDir.resolve(sessionId + ".jsonl"),
                "{\"type\":\"user\",\"timestamp\":\"2026-09-23T10:00:00Z\",\"cwd\":\"" + cwd
                        + "\",\"message\":{\"content\":\"hello\"}}\n");
        new SessionScanner(claudeProjects, db).scan(false);
    }

    private DecisionScanner scanner() {
        return new DecisionScanner(List.of(claudeProjects), db);
    }

    @Test
    void discoversProjectFromSessionCwdWithoutProjectJson() throws Exception {
        Path proj = project("proj-a", DECISIONS_YAML);
        sessionIn(proj, "sess-a");

        DecisionScanner.ScanResult r = scanner().scan();

        assertEquals(2, r.indexed());
        assertEquals(0, r.errorCount(), r.errors().toString());
        List<Decision> hits = store.search("burner api key", 10);
        assertEquals(1, hits.size());
        assertEquals("burner-api-key", hits.get(0).id());
        assertEquals(proj.toAbsolutePath().normalize().toString(), hits.get(0).projectPath());
    }

    @Test
    void sessionCwdInSubdirectoryResolvesToProjectRoot() throws Exception {
        Path proj = project("proj-b", DECISIONS_YAML);
        Path sub = Files.createDirectories(proj.resolve("java/src"));
        sessionIn(sub, "sess-b");

        DecisionScanner s = scanner();
        s.scan();

        assertEquals(Set.of(proj.toAbsolutePath().normalize()), s.knownProjects());
        assertEquals(2, store.count());
    }

    @Test
    void extraProjectRootsAreDiscoveredWithoutSessions() throws Exception {
        Path proj = project("proj-c", DECISIONS_YAML);

        DecisionScanner s = new DecisionScanner(List.of(claudeProjects), List.of(proj), db);
        s.scan();

        assertEquals(2, store.count());
    }

    @Test
    void rescanPicksUpRecordAddedAfterStartupAndSkipsUnchanged() throws Exception {
        Path proj = project("proj-d", DECISIONS_YAML);
        sessionIn(proj, "sess-d");
        DecisionScanner s = scanner();
        s.scan();
        assertTrue(store.search("zebra", 10).isEmpty());

        // Nothing changed: no re-index
        DecisionScanner.ScanResult unchanged = s.rescanIfChanged();
        assertEquals(0, unchanged.indexed());
        assertEquals(1, unchanged.skipped());

        Path file = proj.resolve(".sdd/decisions/index.yaml");
        Files.writeString(file, DECISIONS_YAML + """
                  - id: "zebra-crossing"
                    type: "decision"
                    domain: "testing"
                    what: "Added mid-session zebra record"
                    why: "Must be visible without restarting the MCP server."
                    learned: "test"
                """);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));

        DecisionScanner.ScanResult changed = s.rescanIfChanged();
        assertEquals(3, changed.indexed());
        assertEquals("zebra-crossing", store.search("zebra", 10).get(0).id());
    }

    @Test
    void rescanDropsRecordsRemovedFromFile() throws Exception {
        Path proj = project("proj-e", DECISIONS_YAML);
        sessionIn(proj, "sess-e");
        DecisionScanner s = scanner();
        s.scan();
        assertEquals(2, store.count());

        Path file = proj.resolve(".sdd/decisions/index.yaml");
        Files.writeString(file, "decisions: []\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        s.rescanIfChanged();

        assertEquals(0, store.count());
    }

    @Test
    void unquotedDateAndBadRecordDoNotBreakRestOfFile() throws Exception {
        Path proj = project("proj-f", """
                decisions:
                  - id: dated-record
                    type: decision
                    domain: testing
                    what: "Record with unquoted date fields"
                    why: "YAML parses these as java.util.Date"
                    learned: 2026-09-23
                    updated: 2026-09-24
                    tags: [dates, 2026]
                  - id: missing-what
                    type: decision
                    domain: testing
                    why: "No what field; must be skipped, not fatal"
                    learned: test
                  - "not a mapping"
                  - id: after-the-bad-ones
                    type: decision
                    domain: testing
                    what: "Still indexed after bad neighbours"
                    why: "One bad record must not kill the file"
                    learned: test
                """);
        // A non-decisions YAML file in the same folder (like kompo.ai's schema.yaml)
        Files.writeString(proj.resolve(".sdd/decisions/schema.yaml"), "fields:\n  id: string\n");

        DecisionScanner s = new DecisionScanner(List.of(claudeProjects), List.of(proj), db);
        DecisionScanner.ScanResult r = s.scan();

        assertEquals(2, r.indexed());
        assertEquals(0, r.errorCount(), r.errors().toString());
        Decision dated = store.search("unquoted", 10).get(0);
        assertEquals("2026-09-23", dated.learned());
        assertEquals("2026-09-24", dated.updated());
        assertEquals(List.of("dates", "2026"), dated.tags());
        assertEquals(1, store.search("neighbours", 10).size());
    }

    @Test
    void parseDecisionStringifiesScalars() {
        Decision d = DecisionScanner.parseDecision(Map.of(
                "id", 42, "type", "decision", "domain", "x", "what", "w", "why", true,
                "learned", "l", "alternatives", "single"), "/p");
        assertEquals("42", d.id());
        assertEquals("true", d.why());
        assertEquals(List.of("single"), d.alternatives());
    }

    @Test
    void findDecisionRootReturnsNullWithoutDecisionsDir() throws Exception {
        Path plain = Files.createDirectories(tmp.resolve("plain/sub"));
        assertNull(DecisionScanner.findDecisionRoot(plain));
    }
}
