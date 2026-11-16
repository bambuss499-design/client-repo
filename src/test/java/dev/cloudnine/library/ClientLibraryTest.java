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

    @Test void onlyDgclientOffersASourceBuildInAnEmptyLibrary() throws Exception {
        ClientLibrary library = new ClientLibrary(game);
        List<LibraryEntry> entries = library.scan();
        assertEquals(List.of("Dgclient.zip"), entries.stream().filter(library::canBuild).map(LibraryEntry::label).toList());
        LibraryEntry dg = entries.stream().filter(e -> e.label().equals("Dgclient.zip")).findFirst().orElseThrow();
        assertTrue(library.willDownloadSource(dg));
        Files.writeString(game.resolve("cloudnine/clients/Dgclient.jar"), "existing invalid file");
        assertTrue(library.scan().stream().noneMatch(library::canBuild));
    }

    @Test void availableLocalBuildRecipesDoNotClaimAllArchivesAreSupported() throws Exception {
        ClientLibrary library = new ClientLibrary(game);
        Path dir = library.clientsDir();
        for (String name : DriveCatalog.NAMES) Files.writeString(dir.resolve(name), "not an inspected source");
        List<LibraryEntry> entries = library.scan();
        assertEquals(List.of("Anubis.zip", "CorzClient.zip", "Dgclient.zip", "Gooba.zip", "Krispy.zip"),
                entries.stream().filter(library::canBuild).map(LibraryEntry::label).toList());
        assertTrue(entries.stream().filter(library::canBuild).noneMatch(library::willDownloadSource));
        assertFalse(library.hasPending());
    }

    @Test void customJarsAddedAfterOpeningCanBeSwitchedAcrossSessions() throws Exception {
        ClientLibrary library = new ClientLibrary(game, List.of());
        assertTrue(library.scan().isEmpty());
        jar("Custom A.JAR", metadata("shared_client", "1.21.11"));
        LibraryEntry first = library.scan().get(0);
        assertEquals("Custom A", first.label());
        library.stage(first);
        PersistentSwitchHelper.apply(game);
        ClientLibrary loaded = new ClientLibrary(game, List.of());
        assertTrue(loaded.isActive(loaded.scan().get(0)));
        jar("Custom B.jar", metadata("shared_client", "1.21.11"));
        List<LibraryEntry> refreshed = loaded.scan();
        assertFalse(loaded.isActive(refreshed.get(1)));
        loaded.stage(refreshed.get(1));
        assertEquals("Custom A", ownedActive(game).get(0).label());
        PersistentSwitchHelper.apply(game);
        assertEquals(1, ownedActive(game).size());
        assertEquals("Custom B", ownedActive(game).get(0).label());
        assertTrue(new ClientLibrary(game, List.of()).isActive(refreshed.get(1)));
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
        assertTrue(entry.problem().contains("Unsupported"));
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

    @Test void selectingReplacingAndCancelingTouchesOnlyPendingCopies() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        jar("Two.jar", metadata("client_two", "1.21.11"));
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Path unrelated = Files.writeString(mods.resolve("my-important-mod.jar"), "leave me alone");
        Path similar = Files.writeString(mods.resolve("cloudnine-once-personal.jar"), "also unrelated");
        ClientLibrary library = new ClientLibrary(game, List.of());
        List<LibraryEntry> entries = library.scan();
        library.stage(entries.get(0));
        assertTrue(library.hasPending());
        assertEquals(0, ownedActive(game).size());
        assertEquals(1, heldCopies(game).size());
        library.stage(entries.get(1));
        assertEquals("Two", library.pendingLabel());
        assertEquals(0, ownedActive(game).size());
        assertEquals(1, heldCopies(game).size());
        library.cancelPending();
        assertFalse(library.hasPending());
        assertTrue(heldCopies(game).isEmpty());
        assertFalse(Files.exists(PersistentSwitchHelper.state(game)));
        PersistentSwitchHelper.apply(game); // the waiting helper sees cancellation
        assertEquals("leave me alone", Files.readString(unrelated));
        assertEquals("also unrelated", Files.readString(similar));
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
                assertEquals(0, ownedActive(game).size());
                assertEquals(1, heldCopies(game).size());
                assertTrue(library.hasPending());
            }
        }
    }

    @Test void nextSessionKeepsActiveAndAnotherSelectionReplacesItOnlyAfterExit() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        jar("Two.jar", metadata("client_two", "1.21.11"));
        ClientLibrary first = new ClientLibrary(game, List.of());
        first.stage(first.scan().get(0));
        assertEquals(0, ownedActive(game).size());
        PersistentSwitchHelper.apply(game); // simulate the process exiting
        ClientLibrary nextSession = new ClientLibrary(game, List.of());
        List<LibraryEntry> entries = nextSession.scan();
        assertTrue(nextSession.isActive(entries.get(0)));
        nextSession.stage(entries.get(1));
        assertEquals(1, ownedActive(game).size());
        assertEquals("One", ownedActive(game).get(0).label());
        nextSession.cancelPending();
        assertEquals("One", ownedActive(game).get(0).label());
        nextSession.stage(entries.get(1));
        PersistentSwitchHelper.apply(game);
        assertEquals(1, ownedActive(game).size());
        assertEquals("Two", ownedActive(game).get(0).label());
        assertTrue(heldCopies(game).isEmpty());
        ClientLibrary thirdSession = new ClientLibrary(game, List.of());
        assertTrue(thirdSession.isActive(thirdSession.scan().get(1)));
        assertFalse(thirdSession.isActive(thirdSession.scan().get(0)));
        // No new selection means the current client stays installed across further exits.
        PersistentSwitchHelper.apply(game);
        assertEquals("Two", ownedActive(game).get(0).label());
    }

    @Test void cleanupRefusesChangedOrUnownedFiles() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        ClientLibrary library = new ClientLibrary(game, List.of());
        library.stage(library.scan().get(0));
        Path staged = heldCopies(game).get(0);
        String id = PersistentSwitchHelper.readPlan(game).selected().id();
        Files.writeString(staged, "someone replaced this file");
        ManagedFiles files = new ManagedFiles(game);
        assertThrows(IOException.class, () -> files.deleteOwned(id));
        assertThrows(IOException.class, library::cancelPending);
        assertTrue(library.hasPending());
        assertEquals("someone replaced this file", Files.readString(staged));
        assertThrows(IOException.class, () -> files.deleteOwned("not-a-uuid"));
    }

    @Test void cleanupDeletesOnlyUntouchedManagedCopy() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        ClientLibrary library = new ClientLibrary(game, List.of());
        library.stage(library.scan().get(0));
        Path staged = heldCopies(game).get(0);
        library.cancelPending();
        assertFalse(Files.exists(staged));
        assertTrue(Files.exists(game.resolve("cloudnine/clients/One.jar")));
    }

    @Test void arrayVersionPredicatesAreAnAlternativeNotAConjunction() throws Exception {
        jar("Options.jar", "{\"schemaVersion\":1,\"id\":\"options_mod\",\"version\":\"1\","
                + "\"depends\":{\"minecraft\":[\"1.20.1\",\"~1.21.11\"]}}");
        assertTrue(new ClientLibrary(game, List.of()).scan().get(0).canStage());
    }

    private static List<ManagedFiles.Managed> ownedActive(Path game) throws IOException {
        return new ManagedFiles(game).activeOwned();
    }

    private static List<Path> heldCopies(Path game) throws IOException {
        try (var stream = Files.list(game.resolve("cloudnine/handoff"))) {
            return stream.filter(path -> path.getFileName().toString().startsWith("selected-")).toList();
        }
    }
}
