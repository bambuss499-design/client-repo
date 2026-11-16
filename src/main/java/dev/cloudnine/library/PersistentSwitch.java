package dev.cloudnine.library;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Plans a post-exit replacement without ever removing the selector. */
final class PersistentSwitch {
    record Resumed(ManagedFiles.Managed selection, boolean recovering) { }

    private final Path game;
    private final Path self;
    private final ManagedFiles managed;
    private String armedId;
    private Process helper;

    PersistentSwitch(Path game, Path self, ManagedFiles managed) {
        this.game = game;
        this.self = self == null ? null : self.toAbsolutePath().normalize();
        this.managed = managed;
    }

    /** Resume an interrupted switch after this Minecraft session exits. */
    Resumed resume() throws IOException {
        return PersistentSwitchHelper.locked(game, () -> {
            checkLegacyHandoff();
            if (!Files.exists(PersistentSwitchHelper.state(game), LinkOption.NOFOLLOW_LINKS)) return null;
            var plan = PersistentSwitchHelper.readPlan(game);
            var selected = PersistentSwitchHelper.validate(game, plan);
            startHelper();
            armedId = selected.id();
            return new Resumed(selected, !PersistentSwitchHelper.unstarted(game, plan, selected));
        });
    }

    void arm(ManagedFiles.Managed selection) throws IOException {
        PersistentSwitchHelper.locked(game, () -> {
            checkLegacyHandoff();
            Path state = PersistentSwitchHelper.state(game);
            boolean replacing = Files.exists(state, LinkOption.NOFOLLOW_LINKS);
            if (replacing) {
                var existing = PersistentSwitchHelper.readPlan(game);
                var selected = PersistentSwitchHelper.validate(game, existing);
                if (armedId == null || !armedId.equals(existing.selected().id())
                        || !PersistentSwitchHelper.unstarted(game, existing, selected)) {
                    throw new IOException("A previous switch is being recovered; quit and wait before relaunching.");
                }
            } else if (armedId != null) {
                throw new IOException("The pending switch plan changed; it was left alone.");
            }
            ManagedFiles.Managed held = managed.readOwned(selection.id())
                    .filter(item -> item.file().equals(managed.heldJar(selection.id()))
                            && item.sha256().equals(selection.sha256()))
                    .orElseThrow(() -> new IOException("The pending JAR is missing or changed."));
            List<ManagedFiles.Managed> active = managed.activeOwned();
            if (active.size() > 64) throw new IOException("Too many managed clients to switch safely.");
            Properties p = new Properties();
            p.setProperty("format", "2");
            p.setProperty("selected.id", held.id());
            p.setProperty("selected.sha256", held.sha256());
            p.setProperty("activeCount", Integer.toString(active.size()));
            for (int i = 0; i < active.size(); i++) {
                p.setProperty("active." + i + ".id", active.get(i).id());
                p.setProperty("active." + i + ".sha256", active.get(i).sha256());
            }
            // Start the waiter first. If it cannot start, the old plan is untouched.
            startHelper();
            writePlan(state, p, replacing);
            armedId = held.id();
            return null;
        });
    }

    void disarm(String id) throws IOException {
        PersistentSwitchHelper.locked(game, () -> {
            var plan = PersistentSwitchHelper.readPlan(game);
            var selected = PersistentSwitchHelper.validate(game, plan);
            if (!id.equals(armedId) || !id.equals(plan.selected().id())) {
                throw new IOException("The pending switch changed; it was left alone.");
            }
            if (!PersistentSwitchHelper.unstarted(game, plan, selected)) {
                throw new IOException("A previous switch is being recovered; quit and wait before relaunching.");
            }
            // Remove the plan first: a helper that subsequently wakes up does nothing.
            Files.delete(PersistentSwitchHelper.state(game));
            armedId = null;
            return null;
        });
    }

    private void checkLegacyHandoff() throws IOException {
        if (Files.exists(game.resolve("cloudnine/handoff/next.properties"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("An older Cloud Nine handoff needs recovery before switching; see the README.");
        }
    }

    private void startHelper() throws IOException {
        // A null self is for the standalone source-build harness and unit tests.
        if (self == null || helper != null && helper.isAlive()) return;
        if (!game.resolve("mods").equals(self.getParent())
                || !self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                || !Files.isRegularFile(self, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Install Cloud Nine directly in this profile's mods folder to switch clients.");
        }
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "javaw.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(java)) java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path log = game.resolve("cloudnine/switch.log");
        if (Files.exists(log, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The switch log is not a regular file.");
        }
        long start = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        helper = new ProcessBuilder(java.toString(), "-cp", self.toString(), PersistentSwitchHelper.class.getName(),
                game.toString(), Long.toString(ProcessHandle.current().pid()), Long.toString(start))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            if (helper.waitFor(250, TimeUnit.MILLISECONDS)) throw new IOException("The switch helper exited too early; see cloudnine/switch.log.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting the switch helper.", e);
        }
    }

    private static void writePlan(Path state, Properties properties, boolean replace) throws IOException {
        Path temp = Files.createTempFile(state.getParent(), ".next-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { properties.store(out, "Cloud Nine persistent switch"); }
            if (!replace) {
                Files.move(temp, state);
            } else {
                try { Files.move(temp, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING); }
            }
        } finally { Files.deleteIfExists(temp); }
    }
}
