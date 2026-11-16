package dev.cloudnine.guardian;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** A tiny temporary Fabric mod that schedules restoration after the selected session. */
public final class ReturnGuard implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            Path state = game.resolve("cloudnine/handoff/next.properties");
            if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) || Files.size(state) > 65536) return;
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(state)) { properties.load(input); }
            String id = properties.getProperty("id", "");
            String helperId = properties.getProperty("helperId", "");
            if (!validId(id) || !validId(helperId)) throw new IllegalStateException("Invalid handoff identifiers");
            Path helper = game.resolve("cloudnine/handoff/helper-" + helperId + ".jar");
            String expected = properties.getProperty("helperSha256", "");
            if (!expected.matches("[0-9a-f]{64}") || !Files.isRegularFile(helper, LinkOption.NOFOLLOW_LINKS)
                    || !expected.equals(sha256(helper))) throw new IllegalStateException("Handoff helper is missing or changed");
            String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            long start = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            new ProcessBuilder(java.toString(), "-cp", helper.toString(), "dev.cloudnine.library.HandoffHelper",
                    "restore", game.toString(), Long.toString(ProcessHandle.current().pid()), Long.toString(start), id)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(game.resolve("cloudnine/handoff.log").toFile()))
                    .start();
        } catch (Exception e) {
            System.err.println("Cloud Nine could not schedule restoration: " + e.getMessage());
        }
    }

    private static boolean validId(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
