package dev.cloudnine.library;

import java.nio.file.Path;
import java.util.List;

/** A file name is the display name; metadata is only used for a safety preview. */
public record LibraryEntry(Path file, String label, String modId, String version,
                           List<String> dependencies, String problem) {
    public LibraryEntry {
        dependencies = List.copyOf(dependencies);
    }

    public boolean canStage() {
        return problem == null;
    }
}
