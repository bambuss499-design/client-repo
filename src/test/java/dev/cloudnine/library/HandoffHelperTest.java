package dev.cloudnine.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HandoffHelperTest {
    @TempDir Path game;

    private String prepareFiles() throws Exception {
        String id = UUID.randomUUID().toString();
        String helperId = UUID.randomUUID().toString();
        Path mods = game.resolve("mods");
        Path handoff = game.resolve("cloudnine/handoff");
        Path markers = game.resolve("cloudnine/managed");
        Files.createDirectories(mods);
        Files.createDirectories(handoff);
        Files.createDirectories(markers);
        Path main = Files.writeString(mods.resolve("Cloud Nine.jar"), "selector");
        Path held = Files.writeString(handoff.resolve("selected-" + id + ".jar.off"), "chosen client");
        Path guard = Files.writeString(handoff.resolve("guardian-" + id + ".jar"), "guardian");
        Path helper = Files.writeString(handoff.resolve("helper-" + helperId + ".jar"), "helper");
        Properties marker = new Properties();
        marker.setProperty("sha256", ManagedFiles.sha256(held));
        store(markers.resolve(id + ".properties"), marker);
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("id", id);
        p.setProperty("helperId", helperId);
        p.setProperty("mainName", main.getFileName().toString());
        p.setProperty("mainSha256", ManagedFiles.sha256(main));
        p.setProperty("selectedSha256", ManagedFiles.sha256(held));
        p.setProperty("guardianSha256", ManagedFiles.sha256(guard));
        p.setProperty("helperSha256", ManagedFiles.sha256(helper));
        store(handoff.resolve("next.properties"), p);
        Files.writeString(mods.resolve("unrelated.jar"), "untouched");
        return id;
    }

    @Test void movesSelectorOutAndRestoresItAfterSelectedSession() throws Exception {
        String id = prepareFiles();
        HandoffHelper.prepare(game);
        assertFalse(Files.exists(game.resolve("mods/Cloud Nine.jar")));
        assertEquals("selector", Files.readString(game.resolve("cloudnine/handoff/disabled-" + id + ".jar.off")));
        assertEquals("chosen client", Files.readString(game.resolve("mods/cloudnine-once-" + id + ".jar")));
        assertTrue(Files.exists(game.resolve("mods/cloudnine-return-" + id + ".jar")));
        HandoffHelper.restore(game, id);
        assertEquals("selector", Files.readString(game.resolve("mods/Cloud Nine.jar")));
        assertEquals("untouched", Files.readString(game.resolve("mods/unrelated.jar")));
        assertFalse(Files.exists(game.resolve("mods/cloudnine-once-" + id + ".jar")));
        assertFalse(Files.exists(game.resolve("mods/cloudnine-return-" + id + ".jar")));
        assertFalse(Files.exists(game.resolve("cloudnine/handoff/next.properties")));
    }

    @Test void refusesToRemoveModifiedClientOrRestoreOverAnotherJar() throws Exception {
        String id = prepareFiles();
        HandoffHelper.prepare(game);
        Path selected = game.resolve("mods/cloudnine-once-" + id + ".jar");
        Files.writeString(selected, "changed");
        assertThrows(Exception.class, () -> HandoffHelper.restore(game, id));
        assertEquals("changed", Files.readString(selected));
        assertFalse(Files.exists(game.resolve("mods/Cloud Nine.jar")));
        assertTrue(Files.exists(game.resolve("cloudnine/handoff/disabled-" + id + ".jar.off")));
    }

    @Test void restoresWhenTheSelectedClientAlreadyDeletedItsOwnCopy() throws Exception {
        String id = prepareFiles();
        HandoffHelper.prepare(game);
        Files.delete(game.resolve("mods/cloudnine-once-" + id + ".jar"));
        HandoffHelper.restore(game, id);
        assertEquals("selector", Files.readString(game.resolve("mods/Cloud Nine.jar")));
        assertFalse(Files.exists(game.resolve("mods/cloudnine-return-" + id + ".jar")));
    }

    @Test void refusesToOverwriteAReplacementSelector() throws Exception {
        String id = prepareFiles();
        HandoffHelper.prepare(game);
        Files.writeString(game.resolve("mods/Cloud Nine.jar"), "new selector");
        assertThrows(Exception.class, () -> HandoffHelper.restore(game, id));
        assertEquals("new selector", Files.readString(game.resolve("mods/Cloud Nine.jar")));
        assertTrue(Files.exists(game.resolve("mods/cloudnine-once-" + id + ".jar")));
    }

    @Test void refusesPathTraversalInState() throws Exception {
        prepareFiles();
        Path state = game.resolve("cloudnine/handoff/next.properties");
        Properties p = new Properties();
        try (var in = Files.newInputStream(state)) { p.load(in); }
        p.setProperty("mainName", "../outside.jar");
        store(state, p);
        assertThrows(Exception.class, () -> HandoffHelper.prepare(game));
        assertEquals("selector", Files.readString(game.resolve("mods/Cloud Nine.jar")));
    }

    @Test void canceledSwitchDoesNothing() throws Exception {
        prepareFiles();
        Files.delete(game.resolve("cloudnine/handoff/next.properties"));
        HandoffHelper.prepare(game);
        assertEquals("selector", Files.readString(game.resolve("mods/Cloud Nine.jar")));
    }

    private static void store(Path path, Properties p) throws Exception {
        try (OutputStream out = Files.newOutputStream(path)) { p.store(out, "test"); }
    }
}
