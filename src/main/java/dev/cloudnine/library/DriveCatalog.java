package dev.cloudnine.library;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** Names in the confirmed Drive folder, plus independently published releases where known. */
public final class DriveCatalog {
    private DriveCatalog() { }

    public static final List<String> NAMES = List.of(
            "67Client.zip", "Anubis.zip", "CorzClient.zip", "Dgclient.zip",
            "Gooba.zip", "Krispy.zip", "Waterv7.zip");

    /** The Dgclient ZIP's README links to this exact public release. */
    static final Download DGCLIENT = new Download("Dgclient.zip", "Dgclient.jar", "aetherclient",
            URI.create("https://github.com/nananqj96-cloud/dgclient/releases/download/v2.1.0-pinkbloom/aetherclient-2.1.0.jar"),
            30_428_550L, "4fb75b65e50945abe31b2afe7a4c71710dff4076e8a6e0e980802c0214a5a413");

    record Download(String label, String fileName, String modId, URI uri, long size, String sha256) { }

    static Optional<Download> downloadFor(String label) {
        return DGCLIENT.label().equals(label) ? Optional.of(DGCLIENT) : Optional.empty();
    }
}
