package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Owns only UUID-named copies with an intact, matching marker. */
final class ManagedFiles {
    // Keep the old prefix so copies left by previous versions can be recognized.
    static final String PREFIX = "cloudnine-once-";
    private final Path mods;
    private final Path markers;
    private final Path handoff;

    record Managed(String id, Path file, String label, String modId, String sha256) { }

    ManagedFiles(Path gameDir) {
        mods = gameDir.resolve("mods");
        markers = gameDir.resolve("cloudnine/managed");
        handoff = gameDir.resolve("cloudnine/handoff");
    }

    void prepareDirectories() throws IOException {
        if (Files.isSymbolicLink(mods) || Files.isSymbolicLink(markers.getParent())
                || Files.isSymbolicLink(markers) || Files.isSymbolicLink(handoff)) {
            throw new IOException("Linked mods or Cloud Nine management folders are not supported.");
        }
        Files.createDirectories(mods);
        Files.createDirectories(markers);
        Files.createDirectories(handoff);
    }

    Managed stage(LibraryEntry source, JarInspector inspector) throws IOException {
        prepareDirectories();
        String id = UUID.randomUUID().toString();
        // The pending client must not appear in mods until the current JVM exits.
        Path temporaryJar = handoff.resolve("." + PREFIX + id + ".tmp");
        Path stagedJar = heldJar(id);
        Path temporaryMarker = markers.resolve("." + id + ".tmp");
        Path marker = marker(id);
        boolean committed = false;
        boolean jarTempCreated = false;
        boolean markerTempCreated = false;
        boolean markerCreated = false;
        try {
            // CREATE_NEW and non-replacing moves do not overwrite a user's file.
            try (InputStream input = Files.newInputStream(source.file());
                 OutputStream output = Files.newOutputStream(temporaryJar, StandardOpenOption.CREATE_NEW)) {
                jarTempCreated = true;
                input.transferTo(output);
            }
            LibraryEntry checked = inspector.inspect(temporaryJar, source.label());
            if (!checked.canStage()) throw new IOException(checked.problem());
            String hash = sha256(temporaryJar);
            Properties properties = new Properties();
            properties.setProperty("sha256", hash);
            properties.setProperty("label", source.label());
            properties.setProperty("modId", checked.modId());
            try (OutputStream output = Files.newOutputStream(temporaryMarker, StandardOpenOption.CREATE_NEW)) {
                markerTempCreated = true;
                properties.store(output, "Cloud Nine managed selection");
            }
            Files.move(temporaryMarker, marker);
            markerTempCreated = false;
            markerCreated = true;
            Files.move(temporaryJar, stagedJar);
            jarTempCreated = false;
            committed = true;
            return new Managed(id, stagedJar, source.label(), checked.modId(), hash);
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
        // Refuse links, directories, duplicate copies, and missing markers.
        if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS) != hasStaged
                || Files.exists(held, LinkOption.NOFOLLOW_LINKS) != hasHeld
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || hasStaged == hasHeld || Files.size(marker) > 65536) return Optional.empty();
        Path jar = hasStaged ? staged : held;
        Properties properties = readMarker(id);
        String hash = properties.getProperty("sha256", "");
        if (!hash.matches("[0-9a-f]{64}") || !hash.equals(sha256(jar))) return Optional.empty();
        return Optional.of(new Managed(id, jar, properties.getProperty("label", "Selected client"),
                properties.getProperty("modId", ""), hash));
    }

    Properties readMarker(String id) throws IOException {
        Path marker = marker(id);
        if (!isId(id) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 65536) {
            throw new IOException("A managed-client marker is missing or invalid.");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(marker)) { properties.load(input); }
        catch (IllegalArgumentException e) { throw new IOException("A managed-client marker is invalid.", e); }
        return properties;
    }

    /** Ignore unrelated names; a UUID-shaped managed name without valid ownership blocks a switch. */
    List<Managed> activeOwned() throws IOException {
        List<Managed> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, PREFIX + "*.jar")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                String id = name.substring(PREFIX.length(), name.length() - 4);
                if (!isId(id)) continue;
                Managed item = readOwned(id).filter(value -> value.file().equals(path)).orElseThrow(() ->
                        new IOException("A managed-looking JAR is missing its marker or was changed: " + name));
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    void deleteOwned(String id) throws IOException {
        Managed owned = readOwned(id).orElseThrow(() -> new IOException("The managed file is missing or changed; it was left alone."));
        Files.delete(owned.file());
        Files.deleteIfExists(marker(id));
    }

    Path markers() { return markers; }
    Path jar(String id) { return mods.resolve(PREFIX + id + ".jar"); }
    Path heldJar(String id) { return handoff.resolve("selected-" + id + ".jar.off"); }
    Path marker(String id) { return markers.resolve(id + ".properties"); }

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
