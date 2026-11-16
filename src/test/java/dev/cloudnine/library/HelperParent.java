package dev.cloudnine.library;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only short-lived Minecraft stand-in for exercising the real helper process. */
public final class HelperParent {
    public static void main(String[] args) throws Exception {
        Path game = Path.of(args[0]);
        ManagedFiles files = new ManagedFiles(game);
        ManagedFiles.Managed selection = files.readOwned(args[1]).orElseThrow();
        new PersistentSwitch(game, game.resolve("mods/selector.jar"), files).arm(selection);
        Files.writeString(game.resolve("parent-ready"), "waiting");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (!Files.exists(game.resolve("allow-exit")) && System.nanoTime() < deadline) Thread.sleep(20);
    }
}
