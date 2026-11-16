package dev.cloudnine.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class PinnedDownloadTest {
    @TempDir Path dir;

    @Test void verifiesExactSizeAndHash() throws Exception {
        byte[] bytes = "selected release".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path file = dir.resolve("download.tmp");
        PinnedDownload.copyVerified(new ByteArrayInputStream(bytes), file, bytes.length, hash);
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertThrows(Exception.class, () -> PinnedDownload.copyVerified(new ByteArrayInputStream(bytes), file, bytes.length, "0".repeat(64)));
        assertThrows(Exception.class, () -> PinnedDownload.copyVerified(new ByteArrayInputStream(bytes), file, bytes.length - 1, hash));
        assertThrows(Exception.class, () -> PinnedDownload.copyVerified(new ByteArrayInputStream(bytes), file, bytes.length + 1, hash));
    }
}
