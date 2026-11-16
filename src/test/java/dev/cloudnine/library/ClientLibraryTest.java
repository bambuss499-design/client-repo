package dev.cloudnine.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ClientLibraryTest {
    @TempDir Path game;

    private Path jar(String name, String json) throws IOException {
        Path dir = game.resolve("cloudnine/clients");
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static String metadata(String id, String version) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\",\"environment\":\"client\","
                + "\"depends\":{\"minecraft\":\"" + version + "\",\"fabric-api\":\"*\"}}";
    }

    @Test void sortsAndNamesEntriesFromFilesAndRejectsWrongVersion() throws Exception {
        jar("Zeta Client.jar", metadata("zeta_client", "1.21.11"));
        jar("Alpha Client.jar", metadata("alpha_client", "1.20.1"));
        ClientLibrary library = new ClientLibrary(game, List.of());
        List<LibraryEntry> entries = library.scan();
        assertEquals(List.of("Alpha Client", "Zeta Client"), entries.stream().map(LibraryEntry::label).toList());
        assertFalse(entries.get(0).canStage());
        assertTrue(entries.get(0).problem().contains("1.21.11"));
        assertTrue(entries.get(1).canStage());
        assertEquals(List.of("fabric-api"), entries.get(1).dependencies());
    }

    @Test void confirmedDriveCatalogIsVisibleEvenInAnEmptyLibrary() throws Exception {
        ClientLibrary library = new ClientLibrary(game);
        List<LibraryEntry> entries = library.scan();
        assertEquals(DriveCatalog.NAMES, entries.stream().map(LibraryEntry::label).toList());
        assertTrue(entries.stream().noneMatch(LibraryEntry::canStage));
    }

    @Test void onlyDgclientOffersItsPinnedPublicRelease() throws Exception {
        ClientLibrary library = new ClientLibrary(game);
        List<LibraryEntry> entries = library.scan();
        assertEquals(List.of("Dgclient.zip"), entries.stream().filter(library::canDownload).map(LibraryEntry::label).toList());
        Files.writeString(game.resolve("cloudnine/clients/Dgclient.jar"), "existing file");
        assertTrue(library.scan().stream().noneMatch(library::canDownload));
    }

    @Test void corzImportRejectsAnUnexpectedArchiveWithoutOverwritingFiles() throws Exception {
        ClientLibrary library = new ClientLibrary(game);
        Path dir = game.resolve("cloudnine/clients");
        LibraryEntry missing = library.scan().stream().filter(e -> e.label().equals("CorzClient.zip")).findFirst().orElseThrow();
        assertFalse(library.canImport(missing));
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(dir.resolve("CorzClient.zip")))) {
            archive.putNextEntry(new ZipEntry(DriveCatalog.CORZ.member()));
            archive.write("wrong jar".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("../outside.txt"));
            archive.write("never extracted".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        LibraryEntry entry = library.scan().stream().filter(e -> e.label().equals("CorzClient.zip")).findFirst().orElseThrow();
        assertTrue(library.canImport(entry));
        assertThrows(IOException.class, () -> library.importAndStage(entry));
        assertFalse(Files.exists(dir.resolve("CorzClient.jar")));
        assertFalse(Files.exists(game.resolve("cloudnine/outside.txt")));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".cloudnine-import-")));
        }
    }

    @Test void catalogNamePairsWithMatchingJarEvenWithoutSourceZip() throws Exception {
        jar("Anubis.jar", metadata("anubis", "1.21.11"));
        List<LibraryEntry> entries = new ClientLibrary(game).scan();
        assertEquals(7, entries.size());
        LibraryEntry anubis = entries.stream().filter(e -> e.label().equals("Anubis.zip")).findFirst().orElseThrow();
        assertTrue(anubis.canStage());
        assertEquals("Anubis.jar", anubis.file().getFileName().toString());
    }

    @Test void anActualCatalogZipDoesNotDuplicateItsPlaceholder() throws Exception {
        Path dir = game.resolve("cloudnine/clients");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Anubis.zip"), "archive");
        assertEquals(7, new ClientLibrary(game).scan().size());
    }

    @Test void sourceArchiveIsListedByExactNameButCannotBeExecuted() throws Exception {
        Path dir = game.resolve("cloudnine/clients");
        Files.createDirectories(dir);
        Path archive = Files.writeString(dir.resolve("My Source.ZIP"), "untrusted archive, never opened");
        ClientLibrary library = new ClientLibrary(game, List.of());
        LibraryEntry entry = library.scan().get(0);
        assertEquals("My Source.ZIP", entry.label());
        assertFalse(entry.canStage());
        assertTrue(entry.problem().contains("compiled"));
        assertEquals(archive, entry.file());
        assertThrows(IOException.class, () -> library.stage(entry));
    }

    @Test void matchingCompiledJarUsesAndRetainsTheZipName() throws Exception {
        Path jar = jar("anubis.jar", metadata("anubis", "1.21.11"));
        Files.writeString(game.resolve("cloudnine/clients/Anubis.zip"), "source archive");
        ClientLibrary library = new ClientLibrary(game, List.of());
        List<LibraryEntry> entries = library.scan();
        assertEquals(1, entries.size());
        LibraryEntry entry = entries.get(0);
        assertEquals("Anubis.zip", entry.label());
        assertEquals(jar, entry.file());
        assertTrue(entry.canStage());
        library.stage(entry);
        assertEquals("Anubis.zip", library.pendingLabel());
        assertTrue(library.isPending(library.scan().get(0)));
        library.cancelPending();
    }

    @Test void ambiguousCaseInsensitivePairsNeverChooseAnArbitraryJar() throws Exception {
        Path first = jar("Foo.jar", metadata("foo_one", "1.21.11"));
        Path second = jar("foo.JAR", metadata("foo_two", "1.21.11"));
        Assumptions.assumeFalse(Files.isSameFile(first, second), "Requires a case-sensitive filesystem");
        Files.writeString(game.resolve("cloudnine/clients/Foo.zip"), "source archive");
        List<LibraryEntry> entries = new ClientLibrary(game, List.of()).scan();
        LibraryEntry source = entries.stream().filter(entry -> entry.label().equals("Foo.zip")).findFirst().orElseThrow();
        assertFalse(source.canStage());
        assertTrue(source.problem().contains("Ambiguous"));
        assertEquals(3, entries.size());
    }

    @Test void pairedJarStillRequiresCompatibleFabricMetadata() throws Exception {
        jar("Old.jar", metadata("old_client", "1.20.1"));
        Files.writeString(game.resolve("cloudnine/clients/Old.zip"), "source archive");
        LibraryEntry entry = new ClientLibrary(game, List.of()).scan().get(0);
        assertEquals("Old.zip", entry.label());
        assertFalse(entry.canStage());
        assertTrue(entry.problem().contains("1.21.11"));
    }

    @Test void rejectsMissingMetadataServerOnlyAndSelectorItself() throws Exception {
        Path dir = game.resolve("cloudnine/clients");
        Files.createDirectories(dir);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dir.resolve("invalid.jar")))) {
            zip.putNextEntry(new ZipEntry("other.txt")); zip.write(1); zip.closeEntry();
        }
        jar("server.jar", "{\"schemaVersion\":1,\"id\":\"servermod\",\"version\":\"1\",\"environment\":\"server\"}");
        jar("self.jar", metadata("cloudnine", "*"));
        assertTrue(new ClientLibrary(game, List.of()).scan().stream().noneMatch(LibraryEntry::canStage));
    }

    @Test void selectingAndReplacingOnlyTouchesManagedFiles() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        jar("Two.jar", metadata("client_two", "1.21.11"));
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Path unrelated = mods.resolve("my-important-mod.jar");
        Files.writeString(unrelated, "leave me alone");
        ClientLibrary library = new ClientLibrary(game, List.of());
        List<LibraryEntry> entries = library.scan();
        library.stage(entries.get(0));
        assertTrue(library.hasPending());
        assertEquals(1, managedJars(mods).size());
        library.stage(entries.get(1));
        assertEquals("Two", library.pendingLabel());
        assertEquals(1, managedJars(mods).size());
        assertEquals("leave me alone", Files.readString(unrelated));
        library.cancelPending();
        assertFalse(library.hasPending());
        assertTrue(managedJars(mods).isEmpty());
        assertTrue(Files.exists(unrelated));
    }

    @Test void concurrentSelectionsStillLeaveOnlyOnePendingCopy() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        jar("Two.jar", metadata("client_two", "1.21.11"));
        ClientLibrary library = new ClientLibrary(game, List.of());
        List<LibraryEntry> entries = library.scan();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 12; round++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<?> first = executor.submit(() -> { start.await(); library.stage(entries.get(0)); return null; });
                Future<?> second = executor.submit(() -> { start.await(); library.stage(entries.get(1)); return null; });
                start.countDown();
                first.get();
                second.get();
                assertEquals(1, managedJars(game.resolve("mods")).size());
                assertTrue(library.hasPending());
            }
        }
    }

    @Test void nextSessionIsRecognizedAndStagingAnotherDoesNotRemoveActive() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        jar("Two.jar", metadata("client_two", "1.21.11"));
        ClientLibrary first = new ClientLibrary(game, List.of());
        first.stage(first.scan().get(0));
        ClientLibrary nextSession = new ClientLibrary(game, List.of());
        List<LibraryEntry> entries = nextSession.scan();
        assertTrue(nextSession.isActive(entries.get(0)));
        nextSession.stage(entries.get(1));
        assertEquals(2, managedJars(game.resolve("mods")).size());
        nextSession.cancelPending();
        assertEquals(1, managedJars(game.resolve("mods")).size());
        assertTrue(nextSession.isActive(entries.get(0)));
    }

    @Test void cleanupRefusesChangedOrUnownedFiles() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        ClientLibrary library = new ClientLibrary(game, List.of());
        library.stage(library.scan().get(0));
        Path staged = managedJars(game.resolve("mods")).get(0);
        String id = staged.getFileName().toString().substring(ManagedFiles.PREFIX.length(), staged.getFileName().toString().length() - 4);
        Files.writeString(staged, "someone replaced this file");
        ManagedFiles files = new ManagedFiles(game);
        assertThrows(IOException.class, () -> files.deleteOwned(id));
        assertEquals("someone replaced this file", Files.readString(staged));
        assertThrows(IOException.class, () -> files.deleteOwned("not-a-uuid"));
    }

    @Test void cleanupDeletesOnlyUntouchedManagedCopy() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        ClientLibrary library = new ClientLibrary(game, List.of());
        library.stage(library.scan().get(0));
        Path staged = managedJars(game.resolve("mods")).get(0);
        String id = staged.getFileName().toString().substring(ManagedFiles.PREFIX.length(), staged.getFileName().toString().length() - 4);
        new ManagedFiles(game).deleteOwned(id);
        assertFalse(Files.exists(staged));
        assertTrue(Files.exists(game.resolve("cloudnine/clients/One.jar")));
    }

    @Test void arrayVersionPredicatesAreAnAlternativeNotAConjunction() throws Exception {
        jar("Options.jar", "{\"schemaVersion\":1,\"id\":\"options_mod\",\"version\":\"1\","
                + "\"depends\":{\"minecraft\":[\"1.20.1\",\"~1.21.11\"]}}");
        assertTrue(new ClientLibrary(game, List.of()).scan().get(0).canStage());
    }

    private static List<Path> managedJars(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().startsWith(ManagedFiles.PREFIX)).toList();
        }
    }
}
