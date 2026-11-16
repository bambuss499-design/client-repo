package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Completes the Dgclient source build with its own compiled, pure-Java Corz bridge. */
final class DgBridgePackager {
    private static final String PREFIX = "dev/aether/corz/";
    static final List<String> CLASSES = List.of(
            PREFIX + "CorzModuleHandle.class", PREFIX + "CorzPortBus.class", PREFIX + "CorzPortProvider.class",
            PREFIX + "CorzSettingHandle.class", PREFIX + "CorzSettingHandle$Kind.class");
    private static final long MAX_CLASS_BYTES = 1024 * 1024;
    private static final long MAX_EXPANDED_JAR = 512L * 1024 * 1024;

    private DgBridgePackager() { }

    /** Only missing bridge classes are inserted; no precompiled client is used. */
    static Path complete(Path built, Path project, Path work) throws IOException {
        try (ZipFile jar = new ZipFile(built.toFile())) {
            List<String> missing = new ArrayList<>();
            for (String name : CLASSES) {
                ZipEntry entry = jar.getEntry(name);
                if (entry == null) missing.add(name);
                else if (entry.isDirectory() || entry.getSize() == 0) throw new IOException("Dgclient has an invalid bridge class.");
            }
            if (missing.isEmpty()) return built;
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toUpperCase(Locale.ROOT);
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    throw new IOException("Cannot add the bridge to a signed Dgclient JAR.");
                }
            }
            // Copy only class files produced by this build of the verified source project.
            List<byte[]> classes = new ArrayList<>();
            Path classesDir = project.resolve("corz-api/build/classes/java/main");
            Path projectReal = project.toRealPath();
            for (String name : missing) {
                Path path = classesDir.resolve(name).normalize();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(projectReal)
                        || Files.size(path) > MAX_CLASS_BYTES) {
                    throw new IOException("The Dgclient build did not produce the required Corz bridge classes.");
                }
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length < 8 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe
                        || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe) {
                    throw new IOException("The Dgclient build produced an invalid Corz bridge class.");
                }
                classes.add(bytes);
            }
            Path result = work.resolve("dgclient-with-bridge.jar");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(result, StandardOpenOption.CREATE_NEW))) {
                entries = jar.entries();
                long expanded = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry copy = new ZipEntry(entry.getName());
                    if (entry.getTime() >= 0) copy.setTime(entry.getTime());
                    output.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            byte[] buffer = new byte[65536];
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                expanded += read;
                                if (expanded > MAX_EXPANDED_JAR) throw new IOException("Dgclient JAR exceeds packaging limits.");
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                    output.closeEntry();
                }
                for (int i = 0; i < missing.size(); i++) {
                    output.putNextEntry(new ZipEntry(missing.get(i)));
                    output.write(classes.get(i));
                    output.closeEntry();
                }
            }
            return result;
        }
    }

    static void validate(ZipFile jar) throws IOException {
        for (String name : CLASSES) {
            ZipEntry entry = jar.getEntry(name);
            if (entry == null || entry.isDirectory() || entry.getSize() <= 0) {
                throw new IOException("Built Dgclient is missing its Corz bridge classes.");
            }
        }
    }
}
