package dev.cloudnine.library;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Independent JVM so Windows can release the selected jar before it is removed. */
public final class OneShotCleanup {
    private OneShotCleanup() { }

    public static void main(String[] args) {
        if (args.length < 4) return;
        try {
            Path gameDir = Path.of(args[0]).toAbsolutePath().normalize();
            long pid = Long.parseLong(args[1]);
            long originalStart = Long.parseLong(args[2]);
            Optional<ProcessHandle> parent = ProcessHandle.of(pid);
            if (parent.isPresent()) {
                long actualStart = parent.get().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
                // Do not wait on an unrelated process if the original PID has been reused.
                if (originalStart == 0L || actualStart == 0L || originalStart == actualStart) parent.get().onExit().get();
            }
            ManagedFiles files = new ManagedFiles(gameDir);
            for (int i = 3; i < args.length; i++) {
                if (!ManagedFiles.isId(args[i])) continue;
                for (int attempt = 0; attempt < 50; attempt++) {
                    try { files.deleteOwned(args[i]); break; }
                    catch (Exception e) {
                        if (attempt == 49) System.err.println("Could not remove " + args[i] + ": " + e.getMessage());
                        else TimeUnit.MILLISECONDS.sleep(200);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Cloud Nine cleanup failed: " + e.getMessage());
        }
    }
}
