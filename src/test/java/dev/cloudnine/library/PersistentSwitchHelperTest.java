package dev.cloudnine.library;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PersistentSwitchHelperTest {
    @TempDir Path game;

    private void addJar(String filename, String id) throws IOException {
        Path dir = game.resolve("cloudnine/clients");
        Files.createDirectories(dir);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dir.resolve(filename)))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\","
                    + "\"environment\":\"client\",\"depends\":{\"minecraft\":\"1.21.11\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private ClientLibrary withFirstActive() throws Exception {
        addJar("One.jar", "client_one");
        addJar("Two.jar", "client_two");
        ClientLibrary first = new ClientLibrary(game, List.of());
        first.stage(first.scan().get(0));
        PersistentSwitchHelper.apply(game);
        return new ClientLibrary(game, List.of());
    }

    @Test void changedActiveFileBlocksReplacementWithoutDeletingAnything() throws Exception {
        ClientLibrary library = withFirstActive();
        ManagedFiles files = new ManagedFiles(game);
        ManagedFiles.Managed old = files.activeOwned().get(0);
        library.stage(library.scan().get(1));
        var plan = PersistentSwitchHelper.readPlan(game);
        Files.writeString(old.file(), "user changed this file");
        assertThrows(IOException.class, () -> PersistentSwitchHelper.apply(game));
        assertEquals("user changed this file", Files.readString(old.file()));
        assertTrue(Files.exists(files.heldJar(plan.selected().id())));
        assertFalse(Files.exists(files.jar(plan.selected().id())));
        assertTrue(Files.exists(PersistentSwitchHelper.state(game)));
    }

    @Test void changedPendingFileIsNotInstalledAndRecoveryRemainsVisible() throws Exception {
        ClientLibrary library = withFirstActive();
        library.stage(library.scan().get(1));
        ManagedFiles files = new ManagedFiles(game);
        var plan = PersistentSwitchHelper.readPlan(game);
        Path pending = files.heldJar(plan.selected().id());
        Files.writeString(pending, "modified pending copy");
        assertThrows(IOException.class, () -> PersistentSwitchHelper.apply(game));
        assertEquals(1, files.activeOwned().size());
        assertEquals("modified pending copy", Files.readString(pending));
        ClientLibrary reopened = new ClientLibrary(game, List.of());
        assertFalse(reopened.canSwitch());
        assertTrue(reopened.switchProblem().contains("recovery"));
        assertEquals(2, reopened.scan().size());
    }

    @Test void failedPendingReplacementKeepsTheOriginalPlanAndCopy() throws Exception {
        addJar("One.jar", "client_one");
        addJar("Two.jar", "client_two");
        ClientLibrary library = new ClientLibrary(game, List.of());
        library.stage(library.scan().get(0));
        var original = PersistentSwitchHelper.readPlan(game);
        ManagedFiles files = new ManagedFiles(game);
        Path pending = files.heldJar(original.selected().id());
        Files.writeString(pending, "changed pending");
        assertThrows(IOException.class, () -> library.stage(library.scan().get(1)));
        assertEquals(original, PersistentSwitchHelper.readPlan(game));
        assertEquals("One", library.pendingLabel());
        assertEquals("changed pending", Files.readString(pending));
        try (var contents = Files.list(game.resolve("cloudnine/handoff"))) {
            assertEquals(1L, contents.filter(p -> p.getFileName().toString().startsWith("selected-")).count());
        }
    }

    @Test void refusesUnownedManagedLookingFileAndTargetCollision() throws Exception {
        ClientLibrary library = withFirstActive();
        ManagedFiles files = new ManagedFiles(game);
        Path unowned = Files.writeString(files.jar(UUID.randomUUID().toString()), "not cloud nine's file");
        assertThrows(IOException.class, () -> library.stage(library.scan().get(1)));
        assertEquals("not cloud nine's file", Files.readString(unowned));
        Files.delete(unowned);
        library.stage(library.scan().get(1));
        var plan = PersistentSwitchHelper.readPlan(game);
        Path collision = Files.writeString(files.jar(plan.selected().id()), "do not overwrite");
        assertThrows(IOException.class, () -> PersistentSwitchHelper.apply(game));
        assertEquals("do not overwrite", Files.readString(collision));
        assertTrue(Files.exists(files.heldJar(plan.selected().id())));
        assertTrue(Files.exists(files.jar(plan.active().get(0).id())));
    }

    @Test void failedMoveRollsBackActiveAndRetainsPendingForRetry() throws Exception {
        ClientLibrary library = withFirstActive();
        library.stage(library.scan().get(1));
        ManagedFiles files = new ManagedFiles(game);
        var plan = PersistentSwitchHelper.readPlan(game);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        assertThrows(IOException.class, () -> PersistentSwitchHelper.apply(game, (from, to) -> {
            if (from.equals(files.heldJar(plan.selected().id())) && failOnce.getAndSet(false)) {
                throw new IOException("simulated locked destination");
            }
            Files.move(from, to);
        }));
        assertTrue(Files.exists(files.jar(plan.active().get(0).id())));
        assertTrue(Files.exists(files.heldJar(plan.selected().id())));
        assertFalse(Files.exists(files.jar(plan.selected().id())));
        assertTrue(Files.exists(PersistentSwitchHelper.state(game)));
        PersistentSwitchHelper.apply(game);
        assertEquals("Two", files.activeOwned().get(0).label());
        assertEquals(1, files.activeOwned().size());
    }

    @Test void resumesAfterOldCopyWasMovedButBeforeNewCopyWasInstalled() throws Exception {
        ClientLibrary library = withFirstActive();
        library.stage(library.scan().get(1));
        ManagedFiles files = new ManagedFiles(game);
        var plan = PersistentSwitchHelper.readPlan(game);
        String old = plan.active().get(0).id();
        Files.move(files.jar(old), files.heldJar(old)); // simulated interrupted helper
        ClientLibrary reopened = new ClientLibrary(game, List.of());
        assertTrue(reopened.isRecovering());
        assertFalse(reopened.canSwitch());
        assertThrows(IOException.class, reopened::cancelPending);
        assertThrows(IOException.class, () -> reopened.stage(reopened.scan().get(0)));
        PersistentSwitchHelper.apply(game);
        assertFalse(Files.exists(files.heldJar(old)));
        assertEquals("Two", files.activeOwned().get(0).label());
        assertFalse(Files.exists(PersistentSwitchHelper.state(game)));
        assertTrue(new ClientLibrary(game, List.of()).canSwitch());
    }

    @Test void resumesAfterNewCopyInstalledAndPartialOldCleanup() throws Exception {
        ClientLibrary library = withFirstActive();
        library.stage(library.scan().get(1));
        ManagedFiles files = new ManagedFiles(game);
        var plan = PersistentSwitchHelper.readPlan(game);
        String old = plan.active().get(0).id(), next = plan.selected().id();
        Files.move(files.jar(old), files.heldJar(old));
        Files.move(files.heldJar(next), files.jar(next));
        Files.delete(files.heldJar(old)); // process died between deleting jar and marker
        ClientLibrary reopened = new ClientLibrary(game, List.of());
        assertTrue(reopened.isRecovering());
        PersistentSwitchHelper.apply(game);
        assertFalse(Files.exists(files.marker(old)));
        assertFalse(Files.exists(PersistentSwitchHelper.state(game)));
        assertEquals("Two", files.activeOwned().get(0).label());
    }

    @Test void oldClientMayRemoveItsOwnCopyOnExit() throws Exception {
        ClientLibrary library = withFirstActive();
        library.stage(library.scan().get(1));
        ManagedFiles files = new ManagedFiles(game);
        var plan = PersistentSwitchHelper.readPlan(game);
        String old = plan.active().get(0).id();
        Files.delete(files.jar(old));
        PersistentSwitchHelper.apply(game);
        assertFalse(Files.exists(files.marker(old)));
        assertEquals("Two", files.activeOwned().get(0).label());
    }

    @Test void legacyHandoffBlocksSwitchingButLibraryRemainsAccessible() throws Exception {
        addJar("One.jar", "client_one");
        Path handoff = game.resolve("cloudnine/handoff");
        Files.createDirectories(handoff);
        Path oldPlan = Files.writeString(handoff.resolve("next.properties"), "legacy state");
        ClientLibrary library = new ClientLibrary(game, List.of());
        assertFalse(library.canSwitch());
        assertTrue(library.switchProblem().contains("older"));
        assertTrue(library.scan().get(0).canStage());
        assertThrows(IOException.class, () -> library.stage(library.scan().get(0)));
        assertEquals("legacy state", Files.readString(oldPlan));
    }

    @Test void pathTraversalInSwitchPlanIsRejected() throws Exception {
        addJar("One.jar", "client_one");
        ClientLibrary library = new ClientLibrary(game, List.of());
        library.stage(library.scan().get(0));
        Path state = PersistentSwitchHelper.state(game);
        var p = new java.util.Properties();
        try (InputStream in = Files.newInputStream(state)) { p.load(in); }
        p.setProperty("selected.id", "../../outside");
        try (var out = Files.newOutputStream(state)) { p.store(out, "tampered"); }
        assertThrows(IOException.class, () -> PersistentSwitchHelper.apply(game));
        assertEquals(0, new ManagedFiles(game).activeOwned().size());
        assertTrue(Files.exists(state));
    }

    @Test void linkedSwitchDirectoryIsRejectedWithoutTouchingItsTarget() throws Exception {
        addJar("One.jar", "client_one");
        ClientLibrary library = new ClientLibrary(game, List.of());
        Path dir = game.resolve("cloudnine/switch");
        Files.delete(dir.resolve("switch.lock"));
        Files.delete(dir);
        Path target = Files.createDirectory(game.resolve("elsewhere"));
        try { Files.createSymbolicLink(dir, target); }
        catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links not supported here");
        }
        assertThrows(IOException.class, () -> library.stage(library.scan().get(0)));
        try (var contents = Files.list(target)) { assertEquals(0L, contents.count()); }
    }

    @Test void realHelperWaitsForItsParentAndRunsFromTheInstalledSelectorJar() throws Exception {
        addJar("One.jar", "client_one");
        ClientLibrary library = new ClientLibrary(game, List.of());
        ManagedFiles files = new ManagedFiles(game);
        ManagedFiles.Managed held = files.stage(library.scan().get(0), new JarInspector());
        Path selector = game.resolve("mods/selector.jar");
        writeHelperJar(selector);
        String exe = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", exe);
        Process parent = new ProcessBuilder(java.toString(), "-cp", selector.toString(), HelperParent.class.getName(),
                game.toString(), held.id()).redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!Files.exists(game.resolve("parent-ready")) && parent.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            if (!Files.exists(game.resolve("parent-ready"))) {
                if (parent.isAlive()) parent.destroyForcibly().waitFor();
                throw new IOException("Parent did not start: " + new String(parent.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(parent.isAlive());
            assertFalse(Files.exists(files.jar(held.id())));
            Files.writeString(game.resolve("allow-exit"), "exit");
            assertTrue(parent.waitFor(8, TimeUnit.SECONDS));
            assertEquals(0, parent.exitValue());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (Files.exists(PersistentSwitchHelper.state(game)) && System.nanoTime() < deadline) Thread.sleep(50);
            assertFalse(Files.exists(PersistentSwitchHelper.state(game)));
            assertTrue(Files.exists(files.jar(held.id())));
            assertTrue(Files.exists(selector));
            assertEquals(1, files.activeOwned().size());
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }

    private static void writeHelperJar(Path jar) throws IOException {
        List<Class<?>> classes = List.of(HelperParent.class, PersistentSwitch.class, PersistentSwitch.Resumed.class,
                PersistentSwitchHelper.class, PersistentSwitchHelper.Item.class, PersistentSwitchHelper.Plan.class,
                PersistentSwitchHelper.IOAction.class, PersistentSwitchHelper.Mover.class,
                ManagedFiles.class, ManagedFiles.Managed.class, LibraryEntry.class, JarInspector.class);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> type : classes) {
                String name = type.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(name));
                try (InputStream in = type.getClassLoader().getResourceAsStream(name)) {
                    if (in == null) throw new IOException("Missing test class " + name);
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }
}
