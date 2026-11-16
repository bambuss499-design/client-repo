package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Streams a specific pinned source archive. Identity checks do not establish safety. */
final class PinnedDownload {
    private PinnedDownload() { }

    static void fetch(URI uri, long size, String sha256, Path temporaryFile) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(45_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "CloudNine-ClientSelector/0.4");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Source download returned HTTP " + status + ".");
            long advertised = connection.getContentLengthLong();
            if (advertised >= 0 && advertised != size) throw new IOException("Source size does not match the pinned snapshot.");
            try (InputStream input = connection.getInputStream()) {
                copyVerified(input, temporaryFile, size, sha256);
            }
        } finally { connection.disconnect(); }
    }

    static void copyVerified(InputStream input, Path temporaryFile, long expectedSize, String expectedHash) throws IOException {
        if (expectedSize < 1 || !expectedHash.matches("[0-9a-f]{64}")) throw new IOException("Invalid pinned source metadata.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > expectedSize) throw new IOException("Source exceeded the pinned size.");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (total != expectedSize || !actual.equals(expectedHash)) throw new IOException("Source checksum or size did not match.");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
