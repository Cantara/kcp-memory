package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.Decision;
import com.cantara.kcp.memory.store.DecisionStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.logging.Logger;

/**
 * Scans known project directories for .sdd/decisions/*.yaml files and indexes them.
 * Discovers decisions across all projects so kcp_memory_decisions can query globally.
 */
public class DecisionScanner {

    private static final Logger LOG = Logger.getLogger(DecisionScanner.class.getName());

    private static final Path DEFAULT_CLAUDE_PROJECTS =
            Path.of(System.getProperty("user.home"), ".claude", "projects");

    private final List<Path> roots;
    private final DecisionStore store;
    private final Yaml yaml;

    public DecisionScanner(MemoryDatabase db) {
        this(List.of(DEFAULT_CLAUDE_PROJECTS), db);
    }

    public DecisionScanner(List<Path> roots, MemoryDatabase db) {
        this.roots = List.copyOf(roots);
        this.store = new DecisionStore(db);
        this.yaml = new Yaml();
    }

    /**
     * Scan all configured roots for decision YAML files.
     *
     * @return scan result summary
     */
    public ScanResult scan() {
        List<String> errors = new ArrayList<>();
        int indexed = 0, skipped = 0;

        try {
            Set<Path> projectRoots = discoverProjects();
            for (Path projectRoot : projectRoots) {
                try {
                    int count = scanProject(projectRoot);
                    if (count > 0) indexed += count;
                    else skipped++;
                } catch (Exception e) {
                    errors.add(projectRoot + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            errors.add("Discovery error: " + e.getMessage());
        }

        LOG.info(String.format("Decision scan complete: %d decisions indexed, %d projects skipped, %d errors",
                indexed, skipped, errors.size()));
        return new ScanResult(indexed, skipped, errors.size(), errors);
    }

    /**
     * Discover project root directories by walking Claude projects and extracting project_dir.
     */
    private Set<Path> discoverProjects() throws IOException {
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
                                String projectDir = content.substring(start, end);
                                Path projectPath = Path.of(projectDir);
                                if (Files.exists(projectPath)) {
                                    projects.add(projectPath);
                                }
                            }
                        } catch (IOException e) {
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
     * Scan a single project for .sdd/decisions/*.yaml files and index them.
     *
     * @return number of decisions indexed
     */
    private int scanProject(Path projectRoot) throws Exception {
        Path decisionsDir = projectRoot.resolve(".sdd/decisions");
        if (!Files.exists(decisionsDir) || !Files.isDirectory(decisionsDir)) {
            return 0;
        }

        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(decisionsDir, "*.yaml")) {
            for (Path yamlFile : stream) {
                count += indexDecisionFile(yamlFile, projectRoot.toString());
            }
        }

        return count;
    }

    /**
     * Parse a decision YAML file and index all decisions it contains.
     *
     * @param yamlFile    path to index.yaml or similar
     * @param projectPath project root path string
     * @return number of decisions indexed
     */
    @SuppressWarnings("unchecked")
    private int indexDecisionFile(Path yamlFile, String projectPath) throws Exception {
        String content = Files.readString(yamlFile);
        Map<String, Object> root = yaml.load(content);

        Object decisionsObj = root.get("decisions");
        if (!(decisionsObj instanceof List)) {
            LOG.warning("No 'decisions' array in " + yamlFile);
            return 0;
        }

        List<Map<String, Object>> decisionsList = (List<Map<String, Object>>) decisionsObj;
        int count = 0;

        for (Map<String, Object> d : decisionsList) {
            try {
                Decision decision = parseDecision(d, projectPath);
                store.upsert(decision);
                count++;
            } catch (Exception e) {
                LOG.warning("Failed to parse decision in " + yamlFile + ": " + e.getMessage());
            }
        }

        return count;
    }

    /**
     * Parse a single decision map from YAML into a Decision record.
     */
    @SuppressWarnings("unchecked")
    private Decision parseDecision(Map<String, Object> d, String projectPath) {
        String id = (String) d.get("id");
        String type = (String) d.get("type");
        String domain = (String) d.get("domain");
        String what = (String) d.get("what");
        String why = (String) d.get("why");
        String learned = (String) d.get("learned");
        String updated = (String) d.get("updated");

        List<String> alternatives = d.containsKey("alternatives")
                ? (List<String>) d.get("alternatives")
                : List.of();

        List<String> tags = d.containsKey("tags")
                ? (List<String>) d.get("tags")
                : List.of();

        return new Decision(id, type, domain, what, why, alternatives, learned, updated, tags, projectPath);
    }

    /**
     * Simple scan result record.
     */
    public record ScanResult(int indexed, int skipped, int errorCount, List<String> errors) {
    }
}
