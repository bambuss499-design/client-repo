package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Owns only files that have both our random name and an intact matching marker.
 * Never moves, deletes, or replaces an unrelated file in the mods directory.
 */
final class ManagedFiles {
    static final String PREFIX = "cloudnine-once-";
    private final Path mods;
    private final Path markers;
    private final Path handoff;

    record Managed(String id, Path file, String label, String modId) { }

    ManagedFiles(Path gameDir) {
        mods = gameDir.resolve("mods");
        markers = gameDir.resolve("cloudnine").resolve("managed");
        handoff = gameDir.resolve("cloudnine").resolve("handoff");
    }

    void prepareDirectories() throws IOException {
        Files.createDirectories(mods);
        Files.createDirectories(markers);
    }

    Managed stage(LibraryEntry source, JarInspector inspector) throws IOException {
        prepareDirectories();
        String id = UUID.randomUUID().toString();
        Path temporaryJar = mods.resolve("." + PREFIX + id + ".tmp");
        Path stagedJar = jar(id);
        Path temporaryMarker = markers.resolve("." + id + ".tmp");
        Path marker = marker(id);
        boolean committed = false;
        boolean jarTempCreated = false;
        boolean markerTempCreated = false;
        boolean markerCreated = false;
        try {
            // CREATE_NEW and non-replacing moves mean even a UUID collision cannot overwrite a user file.
            try (InputStream input = Files.newInputStream(source.file());
                 OutputStream output = Files.newOutputStream(temporaryJar, StandardOpenOption.CREATE_NEW)) {
                jarTempCreated = true;
                input.transferTo(output);
            }
            LibraryEntry checked = inspector.inspect(temporaryJar, source.label());
            if (!checked.canStage()) throw new IOException(checked.problem());
            Properties properties = new Properties();
            properties.setProperty("sha256", sha256(temporaryJar));
            properties.setProperty("label", source.label());
            properties.setProperty("modId", checked.modId());
            try (OutputStream output = Files.newOutputStream(temporaryMarker, StandardOpenOption.CREATE_NEW)) {
                markerTempCreated = true;
                properties.store(output, "Cloud Nine one-session selection");
            }
            Files.move(temporaryMarker, marker);
            markerTempCreated = false;
            markerCreated = true;
            Files.move(temporaryJar, stagedJar);
            jarTempCreated = false;
            committed = true;
            return new Managed(id, stagedJar, source.label(), checked.modId());
        } finally {
            if (jarTempCreated) Files.deleteIfExists(temporaryJar);
            if (markerTempCreated) Files.deleteIfExists(temporaryMarker);
            if (!committed && markerCreated) Files.deleteIfExists(marker);
        }
    }

    Optional<Managed> readOwned(String id) throws IOException {
        if (!isId(id)) return Optional.empty();
        Path marker = marker(id);
        Path staged = jar(id);
        Path held = heldJar(id);
        boolean hasStaged = Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS);
        boolean hasHeld = Files.isRegularFile(held, LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || hasStaged == hasHeld || Files.size(marker) > 65536) return Optional.empty();
        Path jar = hasStaged ? staged : held;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(marker)) { properties.load(input); }
        String hash = properties.getProperty("sha256", "");
        if (!hash.matches("[0-9a-f]{64}") || !hash.equals(sha256(jar))) return Optional.empty();
        return Optional.of(new Managed(id, jar, properties.getProperty("label", "Selected client"),
                properties.getProperty("modId", "")));
    }

    void deleteOwned(String id) throws IOException {
        Managed owned = readOwned(id).orElseThrow(() -> new IOException("The staged file is missing or changed; it was left alone."));
        Files.delete(owned.file());
        Files.deleteIfExists(marker(id));
    }

    Managed hold(String id) throws IOException {
        Managed owned = readOwned(id).orElseThrow(() -> new IOException("The staged file is missing or changed."));
        if (!owned.file().equals(jar(id))) throw new IOException("The staged file is already held.");
        if (Files.isSymbolicLink(handoff) || Files.isSymbolicLink(handoff.getParent())) {
            throw new IOException("Linked handoff folders are not supported.");
        }
        Files.createDirectories(handoff);
        Files.move(owned.file(), heldJar(id));
        return new Managed(id, heldJar(id), owned.label(), owned.modId());
    }

    Path markers() { return markers; }
    Path jar(String id) { return mods.resolve(PREFIX + id + ".jar"); }
    Path heldJar(String id) { return handoff.resolve("selected-" + id + ".jar.off"); }
    private Path marker(String id) { return markers.resolve(id + ".properties"); }

    static boolean isId(String id) {
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException | NullPointerException ex) { return false; }
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

}
