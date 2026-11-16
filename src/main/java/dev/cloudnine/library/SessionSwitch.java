package dev.cloudnine.library;

import dev.cloudnine.guardian.ReturnGuard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Prepares a one-session handoff without modifying the installed JAR while Minecraft is running. */
final class SessionSwitch {
    private static final String GUARD_METADATA = """
            {"schemaVersion":1,"id":"cloudnine_return","version":"1.0.0",
             "name":"Cloud Nine return helper","environment":"client",
             "entrypoints":{"preLaunch":["dev.cloudnine.guardian.ReturnGuard"]},
             "depends":{"fabricloader":">=0.19.3","java":">=21"}}
            """;
    private final Path game;
    private final Path self;
    private final Path handoff;
    private final String helperId = UUID.randomUUID().toString();
    private Process helperProcess;
    private String helperHash;
    private String armedId;

    SessionSwitch(Path game, Path self) {
        this.game = game;
        this.self = self.toAbsolutePath().normalize();
        handoff = game.resolve("cloudnine/handoff");
    }

    void arm(ManagedFiles.Managed selection) throws IOException {
        Path mods = game.resolve("mods");
        if (!mods.equals(self.getParent()) || !self.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                || !Files.isRegularFile(self, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Install Cloud Nine as a JAR directly in this profile's mods folder to switch clients.");
        }
        if (Files.isSymbolicLink(mods) || Files.isSymbolicLink(game.resolve("cloudnine"))
                || Files.isSymbolicLink(handoff)) throw new IOException("Linked mods or handoff folders are not supported.");
        Files.createDirectories(handoff);
        Path state = handoff.resolve("next.properties");
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS) && armedId == null) {
            throw new IOException("An unfinished switch needs recovery; see cloudnine/handoff.log and the README.");
        }
        String id = selection.id();
        Path guardian = handoff.resolve("guardian-" + id + ".jar");
        Path helper = handoff.resolve("helper-" + helperId + ".jar");
        boolean guardianMade = false;
        try {
            if (helperHash == null) {
                writeClassJar(helper, HandoffHelper.class, false);
                helperHash = ManagedFiles.sha256(helper);
            } else if (!Files.isRegularFile(helper, LinkOption.NOFOLLOW_LINKS) || !helperHash.equals(ManagedFiles.sha256(helper))) {
                throw new IOException("The handoff helper changed; it was left alone.");
            }
            writeClassJar(guardian, ReturnGuard.class, true);
            guardianMade = true;
            Properties p = new Properties();
            p.setProperty("format", "1");
            p.setProperty("id", id);
            p.setProperty("helperId", helperId);
            p.setProperty("mainName", self.getFileName().toString());
            p.setProperty("mainSha256", ManagedFiles.sha256(self));
            p.setProperty("selectedSha256", ManagedFiles.sha256(selection.file()));
            p.setProperty("guardianSha256", ManagedFiles.sha256(guardian));
            p.setProperty("helperSha256", helperHash);
            writeState(state, p);
            if (helperProcess == null || !helperProcess.isAlive()) {
                String javaName = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java";
                Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
                long start = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
                helperProcess = new ProcessBuilder(java.toString(), "-cp", helper.toString(), HandoffHelper.class.getName(),
                        "prepare", game.toString(), Long.toString(ProcessHandle.current().pid()), Long.toString(start))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(game.resolve("cloudnine/handoff.log").toFile()))
                        .start();
                try {
                    if (helperProcess.waitFor(250, TimeUnit.MILLISECONDS)) throw new IOException("Handoff helper exited too early.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while starting handoff helper.", e);
                }
            }
            armedId = id;
        } catch (IOException | RuntimeException e) {
            // With no state the waiting helper does nothing after Minecraft exits.
            Files.deleteIfExists(state);
            if (guardianMade) Files.deleteIfExists(guardian);
            throw e;
        }
    }

    void disarm(String id) throws IOException {
        if (armedId == null || !armedId.equals(id)) return;
        Path state = handoff.resolve("next.properties");
        Path guardian = handoff.resolve("guardian-" + id + ".jar");
        if (Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) && Files.size(state) <= 65536) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(state)) { p.load(in); }
            if (!id.equals(p.getProperty("id"))) throw new IOException("Handoff state changed; it was left alone.");
            if (Files.exists(guardian, LinkOption.NOFOLLOW_LINKS)) {
                String hash = p.getProperty("guardianSha256", "");
                if (!Files.isRegularFile(guardian, LinkOption.NOFOLLOW_LINKS) || !hash.matches("[0-9a-f]{64}")
                        || !hash.equals(ManagedFiles.sha256(guardian))) throw new IOException("Return helper changed; it was left alone.");
                Files.delete(guardian);
            }
            Files.delete(state);
        } else if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Handoff state changed; it was left alone.");
        }
        armedId = null;
    }

    private static void writeClassJar(Path file, Class<?> clazz, boolean guard) throws IOException {
        String className = clazz.getName().replace('.', '/') + ".class";
        boolean created = false;
        try {
            OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW);
            created = true;
            try (output; JarOutputStream jar = new JarOutputStream(output);
                 InputStream bytes = clazz.getClassLoader().getResourceAsStream(className)) {
                if (bytes == null) throw new IOException("Missing helper class: " + className);
                if (guard) {
                    jar.putNextEntry(new JarEntry("fabric.mod.json"));
                    jar.write(GUARD_METADATA.getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                }
                jar.putNextEntry(new JarEntry(className));
                bytes.transferTo(jar);
                jar.closeEntry();
            }
        } catch (IOException e) {
            if (created) Files.deleteIfExists(file);
            throw e;
        }
    }

    private static void writeState(Path state, Properties properties) throws IOException {
        Path temp = Files.createTempFile(state.getParent(), ".next-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { properties.store(out, "Cloud Nine one-session handoff"); }
            try { Files.move(temp, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
