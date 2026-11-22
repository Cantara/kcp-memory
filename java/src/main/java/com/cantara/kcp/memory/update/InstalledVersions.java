package com.cantara.kcp.memory.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the versions of the tools that are actually installed, so an update check compares a
 * release against what is on this machine and not against another tool's version number.
 */
public final class InstalledVersions {

    /** kcp-commands bakes its version into the jar as this properties entry, key {@code version}. */
    static final String CMD_VERSION_ENTRY = "kcp-commands-version.properties";

    private InstalledVersions() {}

    /** Version of {@code ~/.kcp/kcp-commands-daemon.jar}, or null when it is not installed or unreadable. */
    public static String kcpCommands() {
        return kcpCommands(Path.of(System.getProperty("user.home"), ".kcp"));
    }

    /**
     * Version of the kcp-commands jar in {@code kcpDir}. Null when there is no jar, it has no version
     * entry (an older release), or it cannot be read. A caller treats null as "do not compare",
     * never as a version.
     */
    public static String kcpCommands(Path kcpDir) {
        Path jar = kcpDir.resolve(UpdateChecker.CMD_JAR);
        if (!Files.isRegularFile(jar)) return null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(CMD_VERSION_ENTRY);
            if (entry == null) return null;
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }
            String version = props.getProperty("version");
            return version == null || version.isBlank() ? null : version.strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
