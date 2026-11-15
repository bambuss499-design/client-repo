package dev.cloudnine.library;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.time.Instant;

/** Owns a library inside a single Minecraft game directory. */
public final class ClientLibrary {
    private final Path gameDir;
    private final Path clientsDir;
    private final JarInspector inspector = new JarInspector();
    private final ManagedFiles managed;
    private final List<ManagedFiles.Managed> active = new ArrayList<>();
    private volatile ManagedFiles.Managed pending;

    public ClientLibrary(Path gameDir) throws IOException {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        clientsDir = this.gameDir.resolve("cloudnine").resolve("clients");
        managed = new ManagedFiles(this.gameDir);
        Files.createDirectories(clientsDir);
        managed.prepareDirectories();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(managed.markers(), "*.properties")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - ".properties".length());
                try { managed.readOwned(id).ifPresent(active::add); }
                catch (IOException ignored) { /* A damaged marker is never authority to delete a file. */ }
            }
        }
    }

    public Path clientsDir() { return clientsDir; }

    public List<LibraryEntry> scan() throws IOException {
        List<LibraryEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(clientsDir)) {
            for (Path path : paths) {
                if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    entries.add(inspector.inspect(path));
                }
            }
        }
        entries.sort(Comparator.comparing(LibraryEntry::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    public synchronized void stage(LibraryEntry entry) throws IOException {
        Path source = entry.file().toAbsolutePath().normalize();
        if (!source.getParent().equals(clientsDir) || !Files.isRegularFile(source)) {
            throw new IOException("The selected file is no longer in the client library.");
        }
        LibraryEntry checked = inspector.inspect(source);
        if (!checked.canStage()) throw new IOException(checked.problem());
        ManagedFiles.Managed replacement = managed.stage(checked, inspector);
        try {
            if (pending != null) managed.deleteOwned(pending.id());
        } catch (IOException e) {
            try { managed.deleteOwned(replacement.id()); } catch (IOException ignored) { }
            throw new IOException("Could not replace the previous selection; it was left intact.", e);
        }
        pending = replacement;
    }

    public boolean hasPending() { return pending != null; }
    public String pendingLabel() {
        ManagedFiles.Managed current = pending;
        return current == null ? "" : current.label();
    }
    public boolean isPending(LibraryEntry entry) {
        ManagedFiles.Managed current = pending;
        return current != null && current.modId().equals(entry.modId()) && current.label().equals(entry.label());
    }
    public boolean isActive(LibraryEntry entry) {
        return !entry.modId().isBlank() && active.stream().anyMatch(item -> item.modId().equals(entry.modId()));
    }
    public List<String> activeLabels() { return active.stream().map(ManagedFiles.Managed::label).toList(); }

    public synchronized void cancelPending() throws IOException {
        if (pending != null) {
            managed.deleteOwned(pending.id());
            pending = null;
        }
    }

    /** Starts a tiny Java helper that waits for this JVM to exit before deleting active copies. */
    public void scheduleOneSessionCleanup() throws IOException {
        if (active.isEmpty()) return;
        try {
            Path codeSource = Path.of(OneShotCleanup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            long start = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            List<String> command = new ArrayList<>(List.of(java.toString(), "-cp", codeSource.toString(),
                    OneShotCleanup.class.getName(), gameDir.toString(), Long.toString(ProcessHandle.current().pid()),
                    Long.toString(start)));
            active.forEach(item -> command.add(item.id()));
            new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(gameDir.resolve("cloudnine").resolve("cleanup.log").toFile()))
                    .start();
        } catch (URISyntaxException e) {
            throw new IOException("Could not start the one-session cleanup helper.", e);
        }
    }
}
