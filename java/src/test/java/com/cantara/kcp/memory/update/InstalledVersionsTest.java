package com.cantara.kcp.memory.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The installed kcp-commands version is read from its jar, never borrowed from kcp-memory (#96). */
class InstalledVersionsTest {

    @TempDir Path kcpDir;

    private Path jarWith(String entryName, String content) throws Exception {
        Path jar = kcpDir.resolve(UpdateChecker.CMD_JAR);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    void readsTheVersionBakedIntoTheJar() throws Exception {
        jarWith("kcp-commands-version.properties", "version=0.30.1\n");
        assertEquals("0.30.1", InstalledVersions.kcpCommands(kcpDir));
    }

    @Test
    void noJarMeansNotInstalled() {
        assertNull(InstalledVersions.kcpCommands(kcpDir));
    }

    @Test
    void aJarWithoutAVersionEntryIsUnknown() throws Exception {
        jarWith("META-INF/something-else", "version=9.9.9\n");
        assertNull(InstalledVersions.kcpCommands(kcpDir));
    }

    @Test
    void aBlankVersionIsUnknown() throws Exception {
        jarWith("kcp-commands-version.properties", "version=   \n");
        assertNull(InstalledVersions.kcpCommands(kcpDir));
    }

    @Test
    void aFileThatIsNotAJarIsUnknownNotAnError() throws Exception {
        Files.writeString(kcpDir.resolve(UpdateChecker.CMD_JAR), "not a zip");
        assertNull(InstalledVersions.kcpCommands(kcpDir));
    }
}
