package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Streams one explicitly selected public release; its exact length and SHA-256 are pinned. */
final class PinnedDownload {
    private PinnedDownload() { }

    static void fetch(DriveCatalog.Download release, Path temporaryFile) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) release.uri().toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(45_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "CloudNine-ClientSelector/0.3");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Release download returned HTTP " + status + ".");
            long advertised = connection.getContentLengthLong();
            if (advertised >= 0 && advertised != release.size()) throw new IOException("Release size does not match the pinned version.");
            try (InputStream input = connection.getInputStream()) {
                copyVerified(input, temporaryFile, release.size(), release.sha256());
            }
        } finally { connection.disconnect(); }
    }

    static void copyVerified(InputStream input, Path temporaryFile, long expectedSize, String expectedHash) throws IOException {
        if (expectedSize < 1 || !expectedHash.matches("[0-9a-f]{64}")) throw new IOException("Invalid pinned release metadata.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > expectedSize) throw new IOException("Release exceeded the pinned size.");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (total != expectedSize || !actual.equals(expectedHash)) throw new IOException("Release checksum or size did not match.");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
