package dev.cloudnine.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DgBridgePackagerTest {
    @TempDir Path work;

    private Path jar(boolean signed) throws IOException {
        Path path = work.resolve("built.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("fabric.mod.json"));
            out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            if (signed) {
                out.putNextEntry(new ZipEntry("META-INF/SIGNATURE.SF")); out.write(1); out.closeEntry();
            }
        }
        return path;
    }

    private Path bridgeFiles(boolean valid) throws IOException {
        Path project = Files.createDirectory(work.resolve("project"));
        Path root = project.resolve("corz-api/build/classes/java/main");
        for (String name : DgBridgePackager.CLASSES) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.write(file, valid ? new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 1}
                    : new byte[]{1, 2, 3});
        }
        return project;
    }

    @Test void addsOnlyTheClassesCompiledFromTheBridgeSource() throws Exception {
        Path original = jar(false);
        Path project = bridgeFiles(true);
        Path result = DgBridgePackager.complete(original, project, work);
        assertFalse(result.equals(original));
        try (ZipFile built = new ZipFile(original.toFile()); ZipFile completed = new ZipFile(result.toFile())) {
            assertThrows(IOException.class, () -> DgBridgePackager.validate(built));
            DgBridgePackager.validate(completed);
            assertTrue(completed.getEntry("fabric.mod.json") != null);
        }
        assertEquals(result, DgBridgePackager.complete(result, project, work));
    }

    @Test void rejectsInvalidOrMissingCompiledBridgeClasses() throws Exception {
        Path original = jar(false);
        Path project = bridgeFiles(false);
        assertThrows(IOException.class, () -> DgBridgePackager.complete(original, project, work));
        assertFalse(Files.exists(work.resolve("dgclient-with-bridge.jar")));
    }

    @Test void doesNotAlterSignedJars() throws Exception {
        Path original = jar(true);
        Path project = bridgeFiles(true);
        assertThrows(IOException.class, () -> DgBridgePackager.complete(original, project, work));
        assertFalse(Files.exists(work.resolve("dgclient-with-bridge.jar")));
    }
}
