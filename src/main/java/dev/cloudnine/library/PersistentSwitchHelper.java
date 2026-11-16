package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** JDK-only process: wait for Minecraft to exit, then replace verified managed copies. */
public final class PersistentSwitchHelper {
    private PersistentSwitchHelper() { }

    record Item(String id, String sha256) { }
    record Plan(Item selected, List<Item> active) {
        Plan { active = List.copyOf(active); }
    }
    @FunctionalInterface interface IOAction<T> { T run() throws IOException; }
    @FunctionalInterface interface Mover { void move(Path from, Path to) throws IOException; }

    public static void main(String[] args) {
        if (args.length != 3) return;
        try {
            Path game = Path.of(args[0]).toAbsolutePath().normalize();
            long pid = Long.parseLong(args[1]);
            long start = Long.parseLong(args[2]);
            var parent = ProcessHandle.of(pid);
            if (parent.isPresent()) {
                long actual = parent.get().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
                // A reused PID is not the Minecraft process we were asked to wait for.
                if (start == 0L || actual == 0L || start == actual) parent.get().onExit().get();
            }
            for (int attempt = 0; attempt < 50; attempt++) {
                try { apply(game); return; }
                catch (IOException e) {
                    if (attempt == 49) throw e;
                    TimeUnit.MILLISECONDS.sleep(200);
                }
            }
        } catch (Exception e) {
            System.err.println("Cloud Nine switch failed; files were left for recovery: " + e.getMessage());
            for (Throwable suppressed : e.getSuppressed()) System.err.println("Rollback: " + suppressed.getMessage());
        }
    }

    static Path state(Path game) { return game.resolve("cloudnine/switch/next.properties"); }

    /** Serialize helpers and UI updates; a stale lock file is harmless after a process exits. */
    static <T> T locked(Path game, IOAction<T> action) throws IOException {
        ManagedFiles files = new ManagedFiles(game);
        files.prepareDirectories();
        Path dir = state(game).getParent();
        if (Files.isSymbolicLink(dir)) throw new IOException("Linked switch folders are not supported.");
        Files.createDirectories(dir);
        Path lock = dir.resolve("switch.lock");
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The switch lock is not a regular file.");
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            return action.run();
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another Cloud Nine switch is already in progress.", e);
        }
    }

    static Plan readPlan(Path game) throws IOException {
        Path state = state(game);
        if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) || Files.size(state) > 65536) {
            throw new IOException("Switch plan is missing or invalid.");
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(state)) { p.load(in); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid switch plan.", e); }
        if (!"2".equals(p.getProperty("format"))) throw new IOException("Unsupported switch plan.");
        Item selected = item(p, "selected");
        int count;
        try { count = Integer.parseInt(p.getProperty("activeCount", "")); }
        catch (NumberFormatException e) { throw new IOException("Invalid switch plan count.", e); }
        if (count < 0 || count > 64) throw new IOException("Invalid switch plan count.");
        Set<String> ids = new HashSet<>();
        ids.add(selected.id());
        List<Item> active = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Item item = item(p, "active." + i);
            if (!ids.add(item.id())) throw new IOException("Duplicate managed client in switch plan.");
            active.add(item);
        }
        return new Plan(selected, active);
    }

    private static Item item(Properties p, String prefix) throws IOException {
        String id = p.getProperty(prefix + ".id", "");
        String hash = p.getProperty(prefix + ".sha256", "");
        if (!ManagedFiles.isId(id) || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid switch identifiers.");
        return new Item(id, hash);
    }

    /** Verify all files before moving any; a missing old copy is safe, a changed one is not. */
    static ManagedFiles.Managed validate(Path game, Plan plan) throws IOException {
        ManagedFiles files = new ManagedFiles(game);
        ManagedFiles.Managed selection = requireOwned(files, plan.selected());
        Set<String> expected = new HashSet<>();
        expected.add(plan.selected().id());
        for (Item old : plan.active()) {
            expected.add(old.id());
            if (Files.exists(files.jar(old.id()), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(files.heldJar(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                requireOwned(files, old);
            } else if (Files.exists(files.marker(old.id()), LinkOption.NOFOLLOW_LINKS)
                    && !old.sha256().equals(files.readMarker(old.id()).getProperty("sha256"))) {
                throw new IOException("An old managed-client marker was changed; it was left alone.");
            }
        }
        for (ManagedFiles.Managed present : files.activeOwned()) {
            if (!expected.contains(present.id())) throw new IOException("An unexpected managed client is present in mods.");
        }
        return selection;
    }

    static boolean unstarted(Path game, Plan plan, ManagedFiles.Managed selection) {
        ManagedFiles files = new ManagedFiles(game);
        if (!selection.file().equals(files.heldJar(selection.id()))) return false;
        for (Item old : plan.active()) {
            if (Files.exists(files.heldJar(old.id()), LinkOption.NOFOLLOW_LINKS)) return false;
        }
        return true;
    }

    private static ManagedFiles.Managed requireOwned(ManagedFiles files, Item item) throws IOException {
        return files.readOwned(item.id()).filter(found -> item.sha256().equals(found.sha256()))
                .orElseThrow(() -> new IOException("Managed client " + item.id() + " is missing, duplicated, or changed."));
    }

    static void apply(Path game) throws IOException { apply(game, (from, to) -> Files.move(from, to)); }

    /** Idempotent after interruption: old files are held before the new one is installed. */
    static void apply(Path game, Mover mover) throws IOException {
        locked(game, () -> {
            Path state = state(game);
            if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) return null; // canceled
            Plan plan = readPlan(game);
            ManagedFiles files = new ManagedFiles(game);
            ManagedFiles.Managed selection = validate(game, plan);
            try {
                for (Item old : plan.active()) {
                    if (Files.exists(files.jar(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                        requireOwned(files, old);
                        mover.move(files.jar(old.id()), files.heldJar(old.id()));
                    }
                }
                // Recheck immediately before installing the new client.
                selection = validate(game, plan);
                for (Item old : plan.active()) {
                    if (Files.exists(files.jar(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("An old client is still in mods; refusing to add another.");
                    }
                }
                if (selection.file().equals(files.heldJar(selection.id()))) {
                    mover.move(selection.file(), files.jar(selection.id()));
                }
            } catch (IOException failure) {
                // If installation did not happen, restore verified old copies where possible.
                if (!Files.exists(files.jar(plan.selected().id()), LinkOption.NOFOLLOW_LINKS)) {
                    for (Item old : plan.active()) {
                        if (Files.exists(files.heldJar(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                            try {
                                ManagedFiles.Managed owned = requireOwned(files, old);
                                if (Files.exists(files.jar(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                                    throw new IOException("Refusing to overwrite " + files.jar(old.id()).getFileName());
                                }
                                mover.move(owned.file(), files.jar(old.id()));
                            } catch (IOException rollback) { failure.addSuppressed(rollback); }
                        }
                    }
                }
                throw failure;
            }
            // The selected client is now the only planned copy in mods. Clean up
            // retired copies last; partial cleanup is safe to retry on the next exit.
            for (Item old : plan.active()) {
                if (Files.exists(files.heldJar(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                    ManagedFiles.Managed owned = requireOwned(files, old);
                    if (!owned.file().equals(files.heldJar(old.id()))) throw new IOException("Old client moved unexpectedly.");
                    files.deleteOwned(old.id());
                } else if (Files.exists(files.marker(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(files.jar(old.id()), LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("An old client reappeared in mods; it was left alone.");
                    }
                    if (!old.sha256().equals(files.readMarker(old.id()).getProperty("sha256"))) {
                        throw new IOException("An old managed-client marker was changed; it was left alone.");
                    }
                    Files.delete(files.marker(old.id()));
                }
            }
            if (!plan.equals(readPlan(game))) throw new IOException("Switch plan changed; it was left for recovery.");
            Files.delete(state);
            System.out.println("Cloud Nine switch complete; the selector remains installed.");
            return null;
        });
    }
}
