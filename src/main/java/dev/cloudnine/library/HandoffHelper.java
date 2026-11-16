package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Legacy 0.4 handoff implementation retained for recovery tests; new switches never remove the selector. */
public final class HandoffHelper {
    private HandoffHelper() { }

    public static void main(String[] args) {
        if (args.length < 4) return;
        try {
            Path game = Path.of(args[1]).toAbsolutePath().normalize();
            long pid = Long.parseLong(args[2]);
            long start = Long.parseLong(args[3]);
            var parent = ProcessHandle.of(pid);
            if (parent.isPresent()) {
                long actual = parent.get().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
                if (start == 0L || actual == 0L || start == actual) parent.get().onExit().get();
            }
            for (int attempt = 0; attempt < 30; attempt++) {
                try {
                    if (args[0].equals("prepare")) prepare(game);
                    else if (args[0].equals("restore") && args.length == 5) restore(game, args[4]);
                    else throw new IOException("Invalid handoff mode");
                    return;
                } catch (IOException e) {
                    if (attempt == 29) throw e;
                    TimeUnit.MILLISECONDS.sleep(200);
                }
            }
        } catch (Exception e) {
            System.err.println("Cloud Nine handoff failed; see recovery instructions: " + e.getMessage());
        }
    }

    /** Called only after the selector JVM exits. The selected JAR remains held until then. */
    static void prepare(Path game) throws IOException {
        Path state = statePath(game);
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) return; // canceled
        Properties p = readState(game);
        String id = p.getProperty("id");
        Path mods = game.resolve("mods");
        Path handoff = game.resolve("cloudnine/handoff");
        Path main = mods.resolve(mainName(p));
        Path disabled = handoff.resolve("disabled-" + id + ".jar.off");
        Path held = handoff.resolve("selected-" + id + ".jar.off");
        Path staged = mods.resolve("cloudnine-once-" + id + ".jar");
        Path guardian = handoff.resolve("guardian-" + id + ".jar");
        Path activeGuardian = mods.resolve("cloudnine-return-" + id + ".jar");
        verifyMainAndSelected(game, p, main, held, false);
        requireHash(guardian, p.getProperty("guardianSha256"));
        requireAbsent(disabled); requireAbsent(staged); requireAbsent(activeGuardian);
        boolean movedMain = false, movedSelected = false;
        try {
            Files.move(main, disabled);
            movedMain = true;
            Files.move(held, staged);
            movedSelected = true;
            // The guard is added last. Its pre-launch callback starts the return helper.
            Files.move(guardian, activeGuardian);
            System.out.println("Cloud Nine disabled; selected client is ready for the next launch.");
        } catch (IOException failure) {
            if (movedSelected) rollback(staged, held, p.getProperty("selectedSha256"), failure);
            if (movedMain) rollback(disabled, main, p.getProperty("mainSha256"), failure);
            throw failure;
        }
    }

    /** Called only after the selected JVM exits. Never overwrite or delete changed files. */
    static void restore(Path game, String expectedId) throws IOException {
        Properties p = readState(game);
        String id = p.getProperty("id");
        if (!id.equals(expectedId)) throw new IOException("Selection changed; leaving all files alone");
        Path mods = game.resolve("mods");
        Path handoff = game.resolve("cloudnine/handoff");
        Path main = mods.resolve(mainName(p));
        Path disabled = handoff.resolve("disabled-" + id + ".jar.off");
        Path held = handoff.resolve("selected-" + id + ".jar.off");
        Path staged = mods.resolve("cloudnine-once-" + id + ".jar");
        Path guardian = handoff.resolve("guardian-" + id + ".jar");
        Path activeGuardian = mods.resolve("cloudnine-return-" + id + ".jar");
        // Some clients remove their own staged JAR when they exit. Its absence
        // is safe; a present but modified file is still never touched.
        verifyMainAndSelected(game, p, disabled, staged, true);
        requireHash(activeGuardian, p.getProperty("guardianSha256"));
        requireAbsent(main); requireAbsent(held); requireAbsent(guardian);
        boolean movedSelected = false, movedGuard = false;
        try {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(staged, held);
                movedSelected = true;
            }
            Files.move(activeGuardian, guardian);
            movedGuard = true;
            Files.move(disabled, main);
        } catch (IOException failure) {
            if (movedGuard) rollback(guardian, activeGuardian, p.getProperty("guardianSha256"), failure);
            if (movedSelected) rollback(held, staged, p.getProperty("selectedSha256"), failure);
            throw failure;
        }
        // These are all generated, hash-verified Cloud Nine files. A failed deletion
        // leaves recoverable data outside mods rather than loading two clients.
        if (movedSelected) Files.delete(held);
        Files.delete(guardian);
        Files.deleteIfExists(game.resolve("cloudnine/managed/" + id + ".properties"));
        Files.delete(statePath(game));
        System.out.println("Cloud Nine restored; the selected one-session client was removed.");
    }

    private static Properties readState(Path game) throws IOException {
        if (Files.isSymbolicLink(game.resolve("mods")) || Files.isSymbolicLink(game.resolve("cloudnine"))
                || Files.isSymbolicLink(game.resolve("cloudnine/handoff"))) throw new IOException("Unexpected linked directory");
        Path state = statePath(game);
        if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) || Files.size(state) > 65536) throw new IOException("Handoff state is missing or invalid");
        Properties p = readProperties(state);
        if (!"1".equals(p.getProperty("format")) || !validId(p.getProperty("id")) || !validId(p.getProperty("helperId"))) {
            throw new IOException("Invalid handoff identifiers");
        }
        mainName(p);
        return p;
    }

    private static void verifyMainAndSelected(Path game, Properties p, Path main, Path selected, boolean allowMissing) throws IOException {
        requireHash(main, p.getProperty("mainSha256"));
        if (!allowMissing || Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) {
            requireHash(selected, p.getProperty("selectedSha256"));
        } else if (!p.getProperty("selectedSha256", "").matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid selection checksum");
        }
        Path marker = game.resolve("cloudnine/managed/" + p.getProperty("id") + ".properties");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 65536
                || !p.getProperty("selectedSha256", "").equals(readProperties(marker).getProperty("sha256"))) {
            throw new IOException("The selection marker is missing or changed");
        }
        Path helper = game.resolve("cloudnine/handoff/helper-" + p.getProperty("helperId") + ".jar");
        requireHash(helper, p.getProperty("helperSha256"));
    }

    private static String mainName(Properties p) throws IOException {
        String name = p.getProperty("mainName", "");
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
                || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                || name.startsWith("cloudnine-once-") || name.startsWith("cloudnine-return-")) {
            throw new IOException("Invalid selector file name");
        }
        return name;
    }

    private static Path statePath(Path game) { return game.resolve("cloudnine/handoff/next.properties"); }
    private static Properties readProperties(Path path) throws IOException {
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(path)) { result.load(input); }
        return result;
    }
    private static boolean validId(String id) {
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException | NullPointerException ex) { return false; }
    }
    private static void requireAbsent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing to overwrite " + path.getFileName());
    }
    private static void requireHash(Path path, String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !hash.equals(sha256(path))) throw new IOException("Missing or changed file: " + path.getFileName());
    }
    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void rollback(Path from, Path to, String hash, IOException failure) {
        try { requireHash(from, hash); requireAbsent(to); Files.move(from, to); }
        catch (IOException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
    }
}
