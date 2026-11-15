package dev.cloudnine.library;

import org.junit.jupiter.api.Test;
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
        Files.writeString(game.resolve("cloudnine/clients/source.zip"), "not a jar");
        ClientLibrary library = new ClientLibrary(game);
        List<LibraryEntry> entries = library.scan();
        assertEquals(List.of("Alpha Client", "Zeta Client"), entries.stream().map(LibraryEntry::label).toList());
        assertFalse(entries.get(0).canStage());
        assertTrue(entries.get(0).problem().contains("1.21.11"));
        assertTrue(entries.get(1).canStage());
        assertEquals(List.of("fabric-api"), entries.get(1).dependencies());
    }

    @Test void rejectsMissingMetadataServerOnlyAndSelectorItself() throws Exception {
        Path dir = game.resolve("cloudnine/clients");
        Files.createDirectories(dir);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dir.resolve("invalid.jar")))) {
            zip.putNextEntry(new ZipEntry("other.txt")); zip.write(1); zip.closeEntry();
        }
        jar("server.jar", "{\"schemaVersion\":1,\"id\":\"servermod\",\"version\":\"1\",\"environment\":\"server\"}");
        jar("self.jar", metadata("cloudnine", "*"));
        assertTrue(new ClientLibrary(game).scan().stream().noneMatch(LibraryEntry::canStage));
    }

    @Test void selectingAndReplacingOnlyTouchesManagedFiles() throws Exception {
        jar("One.jar", metadata("client_one", "1.21.11"));
        jar("Two.jar", metadata("client_two", "1.21.11"));
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Path unrelated = mods.resolve("my-important-mod.jar");
        Files.writeString(unrelated, "leave me alone");
        ClientLibrary library = new ClientLibrary(game);
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
        ClientLibrary library = new ClientLibrary(game);
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
        ClientLibrary first = new ClientLibrary(game);
        first.stage(first.scan().get(0));
        ClientLibrary nextSession = new ClientLibrary(game);
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
        ClientLibrary library = new ClientLibrary(game);
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
        ClientLibrary library = new ClientLibrary(game);
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
        assertTrue(new ClientLibrary(game).scan().get(0).canStage());
    }

    private static List<Path> managedJars(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().startsWith(ManagedFiles.PREFIX)).toList();
        }
    }
}
