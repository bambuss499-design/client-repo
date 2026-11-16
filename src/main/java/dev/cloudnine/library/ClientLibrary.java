package dev.cloudnine.library;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Owns a library inside a single Minecraft game directory. */
public final class ClientLibrary {
    private final Path gameDir;
    private final Path clientsDir;
    private final JarInspector inspector = new JarInspector();
    private final ManagedFiles managed;
    private final List<String> catalogNames;
    private final PersistentSwitch persistentSwitch;
    private final SourceBuilder sourceBuilder;
    private final List<ManagedFiles.Managed> active = new ArrayList<>();
    private volatile ManagedFiles.Managed pending;
    private volatile String switchProblem = "";
    private volatile boolean recovering;
    private volatile boolean building;
    private volatile String lastBuildFailure = "";

    /** Offline inspection/build mode; no post-exit helper is launched without an installed selector JAR. */
    public ClientLibrary(Path gameDir) throws IOException {
        this(gameDir, DriveCatalog.NAMES, null);
    }

    /** Production install: self is the selector's installed JAR in the game's mods folder. */
    public ClientLibrary(Path gameDir, Path self) throws IOException {
        this(gameDir, DriveCatalog.NAMES, self);
    }

    ClientLibrary(Path gameDir, List<String> catalogNames) throws IOException {
        this(gameDir, catalogNames, null);
    }

    private ClientLibrary(Path gameDir, List<String> catalogNames, Path self) throws IOException {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.catalogNames = List.copyOf(catalogNames);
        clientsDir = this.gameDir.resolve("cloudnine").resolve("clients");
        managed = new ManagedFiles(this.gameDir);
        managed.prepareDirectories();
        if (Files.isSymbolicLink(clientsDir)) throw new IOException("Linked client libraries are not supported.");
        Files.createDirectories(clientsDir);
        persistentSwitch = new PersistentSwitch(this.gameDir, self, managed);
        sourceBuilder = new SourceBuilder(this.gameDir, clientsDir, inspector);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(managed.markers(), "*.properties")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - ".properties".length());
                try { managed.readOwned(id).filter(item -> item.file().getParent().equals(this.gameDir.resolve("mods"))).ifPresent(active::add); }
                catch (IOException ignored) { /* A damaged marker is never authority to delete a file. */ }
            }
        }
        try {
            PersistentSwitch.Resumed resumed = persistentSwitch.resume();
            if (resumed != null) {
                pending = resumed.selection();
                recovering = resumed.recovering();
            }
        } catch (IOException e) {
            // Keep the library and folder accessible, but never stage over a damaged plan.
            switchProblem = "Switch needs recovery: " + e.getMessage();
            System.err.println("Cloud Nine: " + switchProblem);
        }
    }

    public Path clientsDir() { return clientsDir; }
    public String switchProblem() { return switchProblem; }
    public boolean isRecovering() { return recovering; }
    public boolean canSwitch() { return switchProblem.isEmpty() && !recovering; }

    private void requireSwitchable() throws IOException {
        if (!switchProblem.isEmpty()) throw new IOException(switchProblem);
        if (recovering) throw new IOException("An interrupted switch is being recovered; quit and wait before relaunching.");
    }

    public List<LibraryEntry> scan() throws IOException {
        // A source ZIP is not itself an executable mod. Pair it only with a
        // unique built JAR of the same basename; source builds are explicit.
        Map<String, List<Path>> archives = new HashMap<>();
        Map<String, List<Path>> jars = new HashMap<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(clientsDir)) {
            for (Path path : paths) {
                if (!Files.isRegularFile(path)) continue;
                String filename = path.getFileName().toString();
                String lower = filename.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip")) {
                    archives.computeIfAbsent(lower.substring(0, lower.length() - 4), ignored -> new ArrayList<>()).add(path);
                } else if (lower.endsWith(".jar")) {
                    jars.computeIfAbsent(lower.substring(0, lower.length() - 4), ignored -> new ArrayList<>()).add(path);
                }
            }
        }

        // Built-in names are a snapshot of the confirmed Drive folder. They are
        // placeholders, not clients: no private Drive credentials are embedded.
        for (String name : catalogNames) {
            String key = name.toLowerCase(Locale.ROOT).substring(0, name.length() - 4);
            archives.computeIfAbsent(key, ignored -> List.of(clientsDir.resolve(name)));
        }

        List<LibraryEntry> entries = new ArrayList<>();
        Set<Path> paired = new HashSet<>();
        for (var group : archives.entrySet()) {
            List<Path> candidates = jars.getOrDefault(group.getKey(), List.of());
            boolean unique = group.getValue().size() == 1 && candidates.size() == 1;
            for (Path archive : group.getValue()) {
                String label = archive.getFileName().toString();
                if (unique) {
                    Path jar = candidates.get(0);
                    paired.add(jar);
                    entries.add(inspector.inspect(jar, label));
                } else {
                    boolean ambiguous = group.getValue().size() > 1 || candidates.size() > 1;
                    String problem = ambiguous ? "Ambiguous names: rename duplicate files."
                            : sourceProblem(label, archive);
                    entries.add(new LibraryEntry(archive, label, "", "", List.of(), problem));
                }
            }
        }
        for (List<Path> group : jars.values()) {
            for (Path jar : group) if (!paired.contains(jar)) entries.add(inspector.inspect(jar));
        }
        entries.sort(Comparator.comparing(LibraryEntry::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(LibraryEntry::label));
        return List.copyOf(entries);
    }

    private static String sourceProblem(String label, Path archive) {
        if ("67Client.zip".equalsIgnoreCase(label)) return "Source not inspected; Drive denied access.";
        if ("Waterv7.zip".equalsIgnoreCase(label)) return "Inspected source has no build project.";
        if (DriveCatalog.sourceFor(label).isPresent()) {
            return Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                    ? "Source ZIP ready for a build attempt." : "Put the source ZIP in the client folder.";
        }
        return "Unsupported source ZIP; no build recipe is known.";
    }

    /** Only the inspected snapshots have build recipes; the Dgclient snapshot can also be downloaded. */
    public boolean canBuild(LibraryEntry entry) {
        if (!isAvailableSource(entry)) return false;
        return DriveCatalog.sourceFor(entry.label()).filter(source ->
                !Files.exists(clientsDir.resolve(source.jarName()), LinkOption.NOFOLLOW_LINKS)
                        && (Files.isRegularFile(entry.file(), LinkOption.NOFOLLOW_LINKS)
                        || (!Files.exists(entry.file(), LinkOption.NOFOLLOW_LINKS) && source.download().isPresent())))
                .isPresent();
    }

    public boolean willDownloadSource(LibraryEntry entry) {
        return canBuild(entry) && !Files.exists(entry.file(), LinkOption.NOFOLLOW_LINKS);
    }

    public String buildStatus() { return sourceBuilder.status(); }
    public boolean isBuilding() { return building; }
    public String lastBuildFailure() { return lastBuildFailure; }

    private boolean isAvailableSource(LibraryEntry entry) {
        return !entry.canStage() && entry.problem() != null && !entry.problem().startsWith("Ambiguous")
                && clientsDir.equals(entry.file().toAbsolutePath().normalize().getParent())
                && entry.file().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /** Called only after the user explicitly confirms that the third-party build may execute. */
    public synchronized void buildAndStage(LibraryEntry entry) throws IOException {
        requireSwitchable();
        DriveCatalog.Source source = DriveCatalog.sourceFor(entry.label())
                .filter(ignored -> canBuild(entry))
                .orElseThrow(() -> new IOException("No supported source build is available for this entry."));
        building = true;
        lastBuildFailure = "";
        try {
            Path built = sourceBuilder.build(source, entry.file());
            stage(inspector.inspect(built, entry.label()));
        } catch (IOException | RuntimeException e) {
            lastBuildFailure = e.getMessage() == null ? "See cloudnine/build-logs." : e.getMessage();
            throw e;
        } finally { building = false; }
    }

    public synchronized void stage(LibraryEntry entry) throws IOException {
        requireSwitchable();
        Path source = entry.file().toAbsolutePath().normalize();
        if (!clientsDir.equals(source.getParent()) || !Files.isRegularFile(source)
                || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Select a compiled JAR in the client library, not a source ZIP.");
        }
        LibraryEntry checked = inspector.inspect(source, entry.label());
        if (!checked.canStage()) throw new IOException(checked.problem());
        ManagedFiles.Managed replacement = managed.stage(checked, inspector);
        ManagedFiles.Managed previous = pending;
        try {
            // A new plan replaces the old pending plan atomically; the active client
            // stays untouched until the helper sees the game process exit.
            persistentSwitch.arm(replacement);
            pending = replacement;
        } catch (IOException | RuntimeException e) {
            try { managed.deleteOwned(replacement.id()); } catch (IOException ignored) { }
            throw new IOException("Could not prepare the client switch: " + e.getMessage(), e);
        }
        if (previous != null) {
            try { managed.deleteOwned(previous.id()); }
            catch (IOException e) {
                // The new plan is already committed. An altered old pending copy is
                // left outside mods rather than misreporting the successful switch.
                System.err.println("Cloud Nine left an old pending copy for recovery: " + e.getMessage());
            }
        }
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
        return !entry.modId().isBlank() && active.stream().anyMatch(item ->
                item.modId().equals(entry.modId()) && item.label().equals(entry.label()));
    }
    public List<String> activeLabels() { return active.stream().map(ManagedFiles.Managed::label).toList(); }

    public synchronized void cancelPending() throws IOException {
        requireSwitchable();
        if (pending != null) {
            ManagedFiles.Managed canceled = pending;
            persistentSwitch.disarm(canceled.id());
            pending = null;
            try { managed.deleteOwned(canceled.id()); }
            catch (IOException e) { throw new IOException("Switch canceled, but its saved copy was left for recovery: " + e.getMessage(), e); }
        }
    }
}
