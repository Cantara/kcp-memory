package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.Decision;
import com.cantara.kcp.memory.store.DecisionStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.logging.Logger;

/**
 * Scans known project directories for .sdd/decisions/*.yaml files and indexes them.
 * Discovers decisions across all projects so kcp_memory_decisions can query globally.
 *
 * <p>Project discovery sources (merged, de-duplicated):
 * <ol>
 *   <li>the {@code project_dir} (session {@code cwd}) of every indexed session and agent session
 *       in the kcp-memory database — the primary source, since Claude Code does not write any
 *       per-project metadata file;</li>
 *   <li>legacy {@code project.json} files with a {@code project_dir} field under the configured roots;</li>
 *   <li>extra project roots from the {@code KCP_MEMORY_DECISION_PROJECTS} environment variable
 *       (path-separator delimited) — for projects that have no Claude Code sessions.</li>
 * </ol>
 * A session cwd is often a subdirectory of the project, so each candidate is walked up to the
 * nearest ancestor that contains {@code .sdd/decisions/}.
 *
 * <p>{@link #rescanIfChanged()} is cheap enough to call on every query: it fingerprints the
 * decision files (name + mtime + size) and re-indexes only projects whose files changed.
 */
public class DecisionScanner {

    private static final Logger LOG = Logger.getLogger(DecisionScanner.class.getName());

    static final String EXTRA_PROJECTS_ENV = "KCP_MEMORY_DECISION_PROJECTS";

    private static final Path DEFAULT_CLAUDE_PROJECTS =
            Path.of(System.getProperty("user.home"), ".claude", "projects");

    private static final Path DECISIONS_SUBDIR = Path.of(".sdd", "decisions");

    private final List<Path> roots;
    private final List<Path> extraProjects;
    private final MemoryDatabase db;
    private final DecisionStore store;
    private final Yaml yaml;

    /** Last indexed fingerprint per project root; guarded by {@code this}. */
    private final Map<Path, String> fingerprints = new HashMap<>();

    public DecisionScanner(MemoryDatabase db) {
        this(List.of(DEFAULT_CLAUDE_PROJECTS), parsePathList(System.getenv(EXTRA_PROJECTS_ENV)), db);
    }

    public DecisionScanner(List<Path> roots, MemoryDatabase db) {
        this(roots, List.of(), db);
    }

    public DecisionScanner(List<Path> roots, List<Path> extraProjects, MemoryDatabase db) {
        this.roots = List.copyOf(roots);
        this.extraProjects = List.copyOf(extraProjects);
        this.db = db;
        this.store = new DecisionStore(db);
        this.yaml = new Yaml();
    }

    /**
     * Full scan: re-index every discovered project regardless of whether its files changed.
     *
     * @return scan result summary
     */
    public synchronized ScanResult scan() {
        fingerprints.clear();
        return rescanIfChanged();
    }

    /**
     * Incremental scan: re-index only projects whose decision files changed (added, removed,
     * modified) since the last scan by this instance. Projects this instance indexed earlier that
     * are no longer discoverable are purged from the index.
     *
     * @return scan result summary ({@code indexed} counts decisions from re-indexed projects;
     *         {@code skipped} counts unchanged projects)
     */
    public synchronized ScanResult rescanIfChanged() {
        List<String> errors = new ArrayList<>();
        int indexed = 0, skipped = 0;

        Set<Path> projectRoots = discoverProjects(errors);
        Map<Path, String> current = new HashMap<>();
        for (Path projectRoot : projectRoots) {
            try {
                current.put(projectRoot, fingerprint(projectRoot));
            } catch (IOException e) {
                errors.add(projectRoot + ": " + e.getMessage());
            }
        }

        // Projects that vanished (directory deleted, or no longer discoverable) — drop their rows.
        for (Path gone : new ArrayList<>(fingerprints.keySet())) {
            if (!current.containsKey(gone)) {
                try {
                    store.deleteByProject(gone.toString());
                } catch (SQLException e) {
                    errors.add(gone + ": " + e.getMessage());
                }
                fingerprints.remove(gone);
            }
        }

        for (Map.Entry<Path, String> e : current.entrySet()) {
            Path projectRoot = e.getKey();
            if (e.getValue().equals(fingerprints.get(projectRoot))) {
                skipped++;
                continue;
            }
            try {
                indexed += reindexProject(projectRoot);
                fingerprints.put(projectRoot, e.getValue());
            } catch (Exception ex) {
                errors.add(projectRoot + ": " + ex.getMessage());
            }
        }

        if (indexed > 0 || !errors.isEmpty()) {
            LOG.info(String.format(
                    "Decision scan complete: %d decisions indexed from %d changed project(s), %d unchanged, %d errors",
                    indexed, current.size() - skipped, skipped, errors.size()));
        }
        return new ScanResult(indexed, skipped, errors.size(), errors);
    }

    /** Project roots (directories containing .sdd/decisions/) indexed by this scanner. */
    public synchronized Set<Path> knownProjects() {
        return new TreeSet<>(fingerprints.keySet());
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** Discover project roots that contain a .sdd/decisions/ directory. */
    Set<Path> discoverProjects(List<String> errors) {
        Set<Path> candidates = new LinkedHashSet<>();
        try {
            candidates.addAll(projectDirsFromDatabase());
        } catch (SQLException e) {
            errors.add("Discovery (database) error: " + e.getMessage());
        }
        try {
            candidates.addAll(projectDirsFromProjectJson());
        } catch (IOException e) {
            errors.add("Discovery (project.json) error: " + e.getMessage());
        }
        candidates.addAll(extraProjects);

        Set<Path> projects = new TreeSet<>();
        for (Path candidate : candidates) {
            Path root = findDecisionRoot(candidate);
            if (root != null) projects.add(root);
        }
        return projects;
    }

    /** Distinct session working directories already known to kcp-memory. */
    private Set<Path> projectDirsFromDatabase() throws SQLException {
        Set<Path> dirs = new LinkedHashSet<>();
        String sql = """
                SELECT project_dir FROM sessions WHERE project_dir IS NOT NULL AND project_dir != ''
                UNION
                SELECT project_dir FROM agent_sessions WHERE project_dir IS NOT NULL AND project_dir != ''
                """;
        try (Statement st = db.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                try {
                    dirs.add(Path.of(rs.getString(1)));
                } catch (InvalidPathException ignored) {
                    // malformed cwd — skip
                }
            }
        }
        return dirs;
    }

    /** Legacy source: project.json files with a project_dir field under the configured roots. */
    private Set<Path> projectDirsFromProjectJson() throws IOException {
        Set<Path> projects = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.exists(root)) continue;

            Files.walkFileTree(root, Set.of(), 3, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals("project.json")) {
                        try {
                            String content = Files.readString(file);
                            // Extract project_dir from JSON (simple string search, no full JSON parse)
                            int dirIdx = content.indexOf("\"project_dir\"");
                            if (dirIdx > 0) {
                                int start = content.indexOf("\"", dirIdx + 13) + 1;
                                int end = content.indexOf("\"", start);
                                projects.add(Path.of(content.substring(start, end)));
                            }
                        } catch (IOException | RuntimeException e) {
                            LOG.warning("Failed to read project.json: " + file + " — " + e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return projects;
    }

    /**
     * Walk up from {@code dir} to the nearest ancestor (inclusive) containing .sdd/decisions/.
     * Returns null if none is found before the filesystem root.
     */
    static Path findDecisionRoot(Path dir) {
        Path p = dir.toAbsolutePath().normalize();
        while (p != null) {
            if (Files.isDirectory(p.resolve(DECISIONS_SUBDIR))) return p;
            p = p.getParent();
        }
        return null;
    }

    static List<Path> parsePathList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<Path> paths = new ArrayList<>();
        for (String s : value.split(File.pathSeparator)) {
            if (!s.isBlank()) paths.add(Path.of(s.strip()));
        }
        return paths;
    }

    // ------------------------------------------------------------------
    // Indexing
    // ------------------------------------------------------------------

    private List<Path> decisionFiles(Path projectRoot) throws IOException {
        Path decisionsDir = projectRoot.resolve(DECISIONS_SUBDIR);
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(decisionsDir)) return files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(decisionsDir, "*.{yaml,yml}")) {
            for (Path f : stream) {
                if (Files.isRegularFile(f)) files.add(f);
            }
        }
        Collections.sort(files);
        return files;
    }

    /** Cheap change detector: file names + mtimes + sizes. */
    private String fingerprint(Path projectRoot) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path f : decisionFiles(projectRoot)) {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
            sb.append(f.getFileName()).append('|')
              .append(a.lastModifiedTime().toMillis()).append('|')
              .append(a.size()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Replace all indexed decisions for a project with the current contents of its decision files,
     * so removed records disappear too. Runs in one transaction so concurrent readers never see a
     * half-indexed project.
     *
     * @return number of decisions indexed
     */
    private int reindexProject(Path projectRoot) throws Exception {
        String projectPath = projectRoot.toString();
        Connection conn = db.getConnection();
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            store.deleteByProject(projectPath);
            int count = 0;
            for (Path yamlFile : decisionFiles(projectRoot)) {
                count += indexDecisionFile(yamlFile, projectPath);
            }
            conn.commit();
            return count;
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    /**
     * Parse a decision YAML file and index all decisions it contains.
     * A malformed record is logged and skipped; it never takes down the rest of the file.
     *
     * @param yamlFile    path to index.yaml or similar
     * @param projectPath project root path string
     * @return number of decisions indexed
     */
    @SuppressWarnings("unchecked")
    private int indexDecisionFile(Path yamlFile, String projectPath) {
        Object rootObj;
        try {
            rootObj = yaml.load(Files.readString(yamlFile));
        } catch (Exception e) {
            LOG.warning("Failed to parse decision file " + yamlFile + ": " + e.getMessage());
            return 0;
        }

        Object decisionsObj = rootObj instanceof Map<?, ?> m ? m.get("decisions") : null;
        if (!(decisionsObj instanceof List<?> decisionsList)) {
            // e.g. schema.yaml next to index.yaml — not a decisions file
            LOG.fine("No 'decisions' array in " + yamlFile);
            return 0;
        }

        int count = 0;
        for (Object item : decisionsList) {
            try {
                if (!(item instanceof Map<?, ?> d)) {
                    throw new IllegalArgumentException("entry is not a mapping: " + item);
                }
                Decision decision = parseDecision((Map<String, Object>) d, projectPath);
                store.upsert(decision, yamlFile.toString());
                count++;
            } catch (Exception e) {
                LOG.warning("Skipping decision in " + yamlFile + ": " + e.getMessage());
            }
        }
        return count;
    }

    /**
     * Parse a single decision map from YAML into a Decision record.
     * Scalars are stringified, so unquoted YAML dates, numbers and booleans are accepted.
     */
    static Decision parseDecision(Map<String, Object> d, String projectPath) {
        return new Decision(
                str(d.get("id")),
                str(d.get("type")),
                str(d.get("domain")),
                str(d.get("what")),
                str(d.get("why")),
                strList(d.get("alternatives")),
                str(d.get("learned")),
                str(d.get("updated")),
                strList(d.get("tags")),
                projectPath);
    }

    private static String str(Object o) {
        if (o == null) return null;
        if (o instanceof Date date) {
            // SnakeYAML turns an unquoted 2026-09-23 into a java.util.Date at UTC midnight
            return date.toInstant().toString().replaceFirst("T00:00:00Z$", "");
        }
        return String.valueOf(o);
    }

    private static List<String> strList(Object o) {
        if (o == null) return List.of();
        if (o instanceof Collection<?> c) {
            List<String> out = new ArrayList<>(c.size());
            for (Object item : c) {
                if (item != null) out.add(str(item));
            }
            return out;
        }
        return List.of(str(o));  // a single scalar where a list was expected
    }

    /**
     * Simple scan result record.
     */
    public record ScanResult(int indexed, int skipped, int errorCount, List<String> errors) {
    }
}
