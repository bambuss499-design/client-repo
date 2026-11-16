package dev.cloudnine.library;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SourceBuilderTest {
    @TempDir Path game;

    private Path clients() throws IOException {
        return Files.createDirectories(game.resolve("cloudnine/clients"));
    }

    private Path source(String name, String... entries) throws IOException {
        Path zip = clients().resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private static DriveCatalog.Source profile(Path zip, String hash) throws IOException {
        return new DriveCatalog.Source("Fixture.zip", "Fixture.jar", "fixturemod", "fixture/Main.class",
                "9.1.0", "1".repeat(64), List.of(List.of(":remapJar")), "build/libs/fixture.jar",
                List.of(new DriveCatalog.Archive(Files.size(zip), hash, "root", null)));
    }

    private static void outputJar(Path path, String id, boolean hasClass) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("fabric.mod.json"));
            out.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                    + "\"environment\":\"client\",\"depends\":{\"minecraft\":\"1.21.11\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (hasClass) {
                out.putNextEntry(new ZipEntry("fixture/Main.class"));
                out.write(new byte[]{1, 2, 3});
                out.closeEntry();
            }
        }
    }

    @Test void verifiedSourceIsExtractedBuiltValidatedAndCached() throws Exception {
        Path zip = source("Fixture.zip", "root/settings.gradle", "rootProject.name='fixture'",
                "root/src/Main.java", "class Main {}", "root/build/libs/fixture.jar", "stale output");
        DriveCatalog.Source profile = profile(zip, ManagedFiles.sha256(zip));
        AtomicBoolean ran = new AtomicBoolean();
        SourceBuilder.Runner runner = (project, wrapper, recipe, work, cache, log) -> {
            ran.set(true);
            assertTrue(Files.exists(project.resolve("src/Main.java")));
            assertFalse(Files.exists(project.resolve("build/libs/fixture.jar")), "Archive build outputs must be ignored");
            assertTrue(Files.size(wrapper) > 1000, "Cloud Nine's own wrapper is bundled");
            String properties = Files.readString(wrapper.resolveSibling("gradle-wrapper.properties"));
            assertTrue(properties.contains("distributionSha256Sum=" + recipe.gradleSha256()));
            assertEquals(List.of(List.of(":remapJar")), recipe.tasks());
            outputJar(project.resolve(recipe.output()), "fixturemod", true);
        };
        SourceBuilder builder = new SourceBuilder(game, clients(), new JarInspector(), runner);
        Path built = builder.build(profile, zip);
        assertTrue(ran.get());
        assertEquals(clients().resolve("Fixture.jar"), built);
        assertTrue(new JarInspector().inspect(built).canStage());
        assertEquals("", builder.status());
        try (var work = Files.list(game.resolve("cloudnine/build-work"))) { assertEquals(0, work.count()); }
        try (var logs = Files.list(game.resolve("cloudnine/build-logs"))) { assertEquals(1, logs.count()); }
    }

    @Test void changedSourceNeverRunsTheBuild() throws Exception {
        Path zip = source("Fixture.zip", "root/settings.gradle", "settings");
        AtomicBoolean ran = new AtomicBoolean();
        SourceBuilder builder = new SourceBuilder(game, clients(), new JarInspector(),
                (project, wrapper, recipe, work, cache, log) -> ran.set(true));
        IOException error = assertThrows(IOException.class, () -> builder.build(profile(zip, "0".repeat(64)), zip));
        assertTrue(error.getMessage().contains("checksum"));
        assertFalse(ran.get());
        assertFalse(Files.exists(clients().resolve("Fixture.jar")));
        try (var work = Files.list(game.resolve("cloudnine/build-work"))) { assertEquals(0, work.count()); }
    }

    @Test void buildFailureOrInvalidOutputNeverCreatesLibraryJar() throws Exception {
        Path zip = source("Fixture.zip", "root/settings.gradle", "settings");
        DriveCatalog.Source profile = profile(zip, ManagedFiles.sha256(zip));
        SourceBuilder failing = new SourceBuilder(game, clients(), new JarInspector(),
                (project, wrapper, recipe, work, cache, log) -> { throw new IOException("compiler failed"); });
        assertTrue(assertThrows(IOException.class, () -> failing.build(profile, zip)).getMessage().contains("compiler failed"));
        assertFalse(Files.exists(clients().resolve("Fixture.jar")));
        SourceBuilder noClass = new SourceBuilder(game, clients(), new JarInspector(),
                (project, wrapper, recipe, work, cache, log) -> outputJar(project.resolve(recipe.output()), "fixturemod", false));
        assertTrue(assertThrows(IOException.class, () -> noClass.build(profile, zip)).getMessage().contains("expected client class"));
        assertFalse(Files.exists(clients().resolve("Fixture.jar")));
        SourceBuilder wrongId = new SourceBuilder(game, clients(), new JarInspector(),
                (project, wrapper, recipe, work, cache, log) -> outputJar(project.resolve(recipe.output()), "othermod", true));
        assertThrows(IOException.class, () -> wrongId.build(profile, zip));
        assertFalse(Files.exists(clients().resolve("Fixture.jar")));
        try (var files = Files.list(clients())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".cloudnine-build-")));
        }
    }

    @Test void existingLibraryJarIsNeverOverwritten() throws Exception {
        Path zip = source("Fixture.zip", "root/settings.gradle", "settings");
        DriveCatalog.Source profile = profile(zip, ManagedFiles.sha256(zip));
        Path existing = Files.writeString(clients().resolve("Fixture.jar"), "my original file");
        SourceBuilder builder = new SourceBuilder(game, clients(), new JarInspector(),
                (project, wrapper, recipe, work, cache, log) -> outputJar(project.resolve(recipe.output()), "fixturemod", true));
        assertThrows(IOException.class, () -> builder.build(profile, zip));
        assertEquals("my original file", Files.readString(existing));
    }

    @Test void archivePathsCannotEscapeTheFreshProject() throws Exception {
        for (String bad : List.of("root/../outside.txt", "root/./outside.txt", "/outside.txt", "root/C:/outside.txt", "root/..\\outside.txt", "elsewhere/file.txt")) {
            Path zip = source("Unsafe.zip", bad, "bad");
            Path project = Files.createTempDirectory(game, "extract-");
            assertThrows(IOException.class, () -> SourceBuilder.extract(zip, "root", project), bad);
            assertFalse(Files.exists(game.resolve("outside.txt")));
        }
    }

    @Test void sourceOutputSymlinksCannotRedirectTheCopiedArtifact() throws Exception {
        Path zip = source("Fixture.zip", "root/settings.gradle", "settings");
        DriveCatalog.Source profile = profile(zip, ManagedFiles.sha256(zip));
        Path outside = Files.createDirectory(game.resolve("outside"));
        Path probe = game.resolve("symlink-probe");
        try { Files.createSymbolicLink(probe, outside); Files.delete(probe); }
        catch (UnsupportedOperationException | IOException e) { Assumptions.assumeTrue(false, "Symlinks unsupported: " + e); }
        SourceBuilder builder = new SourceBuilder(game, clients(), new JarInspector(), (project, wrapper, recipe, work, cache, log) -> {
            Files.createSymbolicLink(project.resolve("build"), outside);
            outputJar(outside.resolve("libs/fixture.jar"), "fixturemod", true);
        });
        assertThrows(IOException.class, () -> builder.build(profile, zip));
        assertFalse(Files.exists(clients().resolve("Fixture.jar")));
        assertTrue(Files.exists(outside.resolve("libs/fixture.jar")), "Cleanup must not follow symlinks");
    }
}
