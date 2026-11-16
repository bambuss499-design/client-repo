package dev.cloudnine.smoke;

import dev.cloudnine.library.ClientLibrary;
import dev.cloudnine.library.LibraryEntry;

import java.nio.file.Files;
import java.nio.file.Path;

/** Manual CI-only harness. Run in a container with no credentials or private source archives. */
public final class SourceBuildSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass an isolated game directory");
        Path game = Path.of(args[0]);
        ClientLibrary library = new ClientLibrary(game);
        LibraryEntry source = library.scan().stream().filter(entry -> entry.label().equals("Dgclient.zip"))
                .findFirst().orElseThrow();
        if (!library.canBuild(source) || !library.willDownloadSource(source)) {
            throw new IllegalStateException("Dgclient's pinned source download is not offered");
        }
        library.buildAndStage(source);
        if (!library.hasPending() || !Files.isRegularFile(library.clientsDir().resolve("Dgclient.jar"))) {
            throw new IllegalStateException("Source build did not stage its output");
        }
        System.out.println("CLOUDNINE_SOURCE_SMOKE: built and staged Dgclient from the pinned public source snapshot");
    }
}
