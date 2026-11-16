package dev.cloudnine.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds only an explicitly selected, pinned source snapshot. This is NOT a
 * sandbox: Gradle scripts and dependencies run with the user's OS permissions.
 */
final class SourceBuilder {
    private static final long MAX_UNPACKED_BYTES = 512L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private final Path gameDir;
    private final Path clientsDir;
    private final JarInspector inspector;
    private final Runner runner;
    private volatile String status = "";

    SourceBuilder(Path gameDir, Path clientsDir, JarInspector inspector) {
        this(gameDir, clientsDir, inspector, new GradleRunner());
    }

    SourceBuilder(Path gameDir, Path clientsDir, JarInspector inspector, Runner runner) {
        this.gameDir = gameDir;
        this.clientsDir = clientsDir;
        this.inspector = inspector;
        this.runner = runner;
    }

    String status() { return status; }

    /** Writes a new library JAR, but never stages or overwrites the user's files. */
    Path build(DriveCatalog.Source source, Path sourcePath) throws IOException {
        Path buildRoot = gameDir.resolve("cloudnine").resolve("build-work");
        Path logs = gameDir.resolve("cloudnine").resolve("build-logs");
        Files.createDirectories(buildRoot);
        Files.createDirectories(logs);
        Path work = Files.createTempDirectory(buildRoot, "source-");
        String prefix = source.label().substring(0, source.label().length() - 4).replaceAll("[^A-Za-z0-9_-]", "_");
        Path log = logs.resolve(prefix + "-" + UUID.randomUUID() + ".log");
        Path temporaryJar = null;
        try {
            Files.writeString(log, "Cloud Nine source build: " + source.label() + "\nStarted: " + Instant.now()
                    + "\nSource builds run third-party code and are not sandboxed.\n\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            status = "Checking for a Java 21 JDK...";
            runner.preflight();
            Path archive = work.resolve("source.zip");
            DriveCatalog.Archive snapshot;
            if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                status = "Checking the source ZIP...";
                long size = Files.size(sourcePath);
                snapshot = source.archives().stream().filter(item -> item.size() == size).findFirst()
                        .orElseThrow(() -> new IOException("Source ZIP is not the inspected snapshot."));
                try (InputStream input = Files.newInputStream(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    PinnedDownload.copyVerified(input, archive, snapshot.size(), snapshot.sha256());
                }
            } else if (Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Source ZIP must be a regular file, not a link or folder.");
            } else {
                snapshot = source.download().orElseThrow(() -> new IOException("Put the source ZIP in the client folder first."));
                status = "Downloading the pinned source ZIP...";
                PinnedDownload.fetch(snapshot.download(), snapshot.size(), snapshot.sha256(), archive);
                // Cache the verified source for a retry. Do not overwrite a file placed
                // in the folder while the download was in progress.
                try { Files.copy(archive, sourcePath); }
                catch (FileAlreadyExistsException e) { throw new IOException("A source ZIP appeared during download; refresh and retry.", e); }
            }
            append(log, "Verified source snapshot " + snapshot.sha256() + "\n");
            Path project = work.resolve("project");
            Files.createDirectory(project);
            status = "Extracting the verified source...";
            extract(archive, snapshot.root(), project);
            if (!Files.isRegularFile(project.resolve("settings.gradle"), LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(project.resolve("settings.gradle.kts"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Source snapshot has no Gradle settings file.");
            }
            Path wrapper = createWrapper(work, source);
            status = "Building source; first run may take several minutes...";
            runner.run(project, wrapper, source, work, gameDir.resolve("cloudnine").resolve("build-cache"), log);

            Path built = project.resolve(source.output()).normalize();
            if (!built.startsWith(project) || !Files.isRegularFile(built, LinkOption.NOFOLLOW_LINKS)
                    || !built.toRealPath().startsWith(project.toRealPath())) {
                throw new IOException("Build did not produce the expected remapped JAR.");
            }
            if (Files.size(built) > MAX_OUTPUT_BYTES) throw new IOException("Built JAR is too large.");
            temporaryJar = Files.createTempFile(clientsDir, ".cloudnine-build-", ".tmp");
            Files.copy(built, temporaryJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            validateOutput(temporaryJar, source, inspector);
            Path destination = clientsDir.resolve(source.jarName());
            Files.move(temporaryJar, destination); // No replacement, even on an existing invalid JAR.
            temporaryJar = null;
            append(log, "\nBuilt and checked " + source.jarName() + ".\n");
            return destination;
        } catch (IOException | RuntimeException e) {
            try { append(log, "\nBuild failed: " + e + "\n"); } catch (IOException ignored) { }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException(reason + " See cloudnine/build-logs/" + log.getFileName(), e);
        } finally {
            status = "";
            if (temporaryJar != null) Files.deleteIfExists(temporaryJar);
            try { deleteWork(work); }
            catch (IOException cleanup) {
                try { append(log, "Could not remove temporary work directory: " + work + "\n"); } catch (IOException ignored) { }
            }
        }
    }

    static void validateOutput(Path jar, DriveCatalog.Source source, JarInspector inspector) throws IOException {
        LibraryEntry checked = inspector.inspect(jar, source.label());
        if (!checked.canStage() || !source.modId().equals(checked.modId())) {
            throw new IOException("Built JAR did not contain the expected compatible Fabric mod.");
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry mainClass = zip.getEntry(source.entrypointClass());
            if (mainClass == null || mainClass.isDirectory() || mainClass.getSize() == 0) {
                throw new IOException("Built JAR is missing its expected client class.");
            }
        }
    }

    /** Extracts regular files only into a fresh directory, with limits and no archive-supplied paths escaping it. */
    static void extract(Path archive, String root, Path project) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (++entries > MAX_ENTRIES) throw new IOException("Source ZIP has too many entries.");
                String name = entry.getName();
                if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                        || name.chars().anyMatch(Character::isISOControl)) {
                    throw new IOException("Source ZIP contains an unsafe path.");
                }
                for (String part : name.split("/")) {
                    if (part.equals(".") || part.equals("..")) throw new IOException("Source ZIP contains an unsafe path.");
                }
                if (!root.isEmpty()) {
                    if (name.equals(root + "/")) continue;
                    if (!name.startsWith(root + "/")) throw new IOException("Source ZIP has an unexpected root.");
                    name = name.substring(root.length() + 1);
                }
                if (name.isEmpty() || isBuildOutput(name)) continue;
                Path output = project.resolve(name).normalize();
                if (!output.startsWith(project) || output.equals(project)) throw new IOException("Source ZIP contains an unsafe path.");
                if (entry.isDirectory()) { Files.createDirectories(output); continue; }
                if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("Source ZIP entry is too large.");
                Files.createDirectories(output.getParent());
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    long written = 0;
                    while ((read = input.read(buffer)) != -1) {
                        written += read;
                        total += read;
                        if (written > MAX_ENTRY_BYTES || total > MAX_UNPACKED_BYTES) {
                            throw new IOException("Source ZIP exceeds extraction limits.");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static boolean isBuildOutput(String name) {
        return name.equals(".git/") || name.startsWith(".git/") || name.equals(".gradle/") || name.startsWith(".gradle/")
                || name.equals("build/") || name.startsWith("build/")
                || name.startsWith("corz-api/build/") || name.startsWith("corzport/build/")
                || name.startsWith("native/build/");
    }

    private static Path createWrapper(Path work, DriveCatalog.Source source) throws IOException {
        if (!source.gradleVersion().matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || !source.gradleSha256().matches("[0-9a-f]{64}")) throw new IOException("Invalid Gradle pin.");
        Path directory = work.resolve("wrapper");
        Files.createDirectory(directory);
        Path wrapper = directory.resolve("gradle-wrapper.jar");
        try (InputStream in = SourceBuilder.class.getResourceAsStream("/cloudnine/gradle-wrapper.jar")) {
            if (in == null) throw new IOException("Cloud Nine's Gradle wrapper is missing.");
            Files.copy(in, wrapper);
        }
        String properties = "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n"
                + "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + source.gradleVersion() + "-bin.zip\n"
                + "distributionSha256Sum=" + source.gradleSha256() + "\nnetworkTimeout=60000\n"
                + "validateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n";
        Files.writeString(directory.resolve("gradle-wrapper.properties"), properties, StandardCharsets.UTF_8);
        return wrapper;
    }

    private static void deleteWork(Path work) throws IOException {
        if (!Files.exists(work, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(work, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void append(Path log, String text) throws IOException {
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    interface Runner {
        default void preflight() throws IOException { }
        void run(Path project, Path wrapper, DriveCatalog.Source source, Path work, Path cache, Path log) throws IOException;
    }

    /** A dedicated Gradle user home and filtered environment reduce accidental credential exposure; they do not sandbox code. */
    private static final class GradleRunner implements Runner {
        private Path jdk;

        @Override public void preflight() throws IOException { jdk = findJdk(); }

        @Override public void run(Path project, Path wrapper, DriveCatalog.Source source, Path work, Path cache, Path log) throws IOException {
            Path home = work.resolve("home");
            Path temp = work.resolve("temp");
            Files.createDirectories(home);
            Files.createDirectories(temp);
            Files.createDirectories(cache);
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
            Path java = jdk.resolve("bin").resolve(windows ? "java.exe" : "java");
            for (List<String> tasks : source.tasks()) {
                List<String> command = new ArrayList<>(List.of(java.toString(), "-Duser.home=" + home,
                        "-Dfile.encoding=UTF-8", "-cp", wrapper.toString(), "org.gradle.wrapper.GradleWrapperMain",
                        "--no-daemon", "--console=plain", "--stacktrace", "-Duser.home=" + home));
                command.addAll(tasks);
                append(log, "\nRunning Gradle tasks: " + String.join(" ", tasks) + "\n");
                ProcessBuilder process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
                Map<String, String> environment = process.environment();
                Map<String, String> inherited = Map.copyOf(environment);
                environment.clear();
                for (String name : List.of("PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "ComSpec", "COMSPEC",
                        "LANG", "LC_ALL", "ProgramFiles", "ProgramFiles(x86)", "PROCESSOR_ARCHITECTURE")) {
                    if (inherited.containsKey(name)) environment.put(name, inherited.get(name));
                }
                environment.put("JAVA_HOME", jdk.toString());
                environment.put("GRADLE_USER_HOME", cache.toString());
                environment.put("HOME", home.toString());
                environment.put("USERPROFILE", home.toString());
                environment.put("TEMP", temp.toString());
                environment.put("TMP", temp.toString());
                environment.put("TMPDIR", temp.toString());
                Process running = process.start();
                Thread shutdown = new Thread(() -> terminate(running), "cloudnine-stop-source-build");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    if (!running.waitFor(20, TimeUnit.MINUTES)) {
                        terminate(running);
                        throw new IOException("Source build timed out.");
                    }
                    if (running.exitValue() != 0) throw new IOException("Gradle failed (exit " + running.exitValue() + ").");
                } catch (InterruptedException e) {
                    terminate(running);
                    Thread.currentThread().interrupt();
                    throw new IOException("Source build was interrupted.", e);
                } finally {
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException ignored) { /* JVM is shutting down. */ }
                }
            }
        }

        private static void terminate(Process process) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        private static Path findJdk() throws IOException {
            List<Path> homes = new ArrayList<>();
            String configured = System.getenv("JAVA_HOME");
            if (configured != null && !configured.isBlank()) homes.add(Path.of(configured));
            String current = System.getProperty("java.home");
            if (current != null && !current.isBlank()) homes.add(Path.of(current));
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
            String path = System.getenv("PATH");
            if (path != null) {
                for (String part : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (part.isBlank()) continue;
                    Path bin = Path.of(part).toAbsolutePath().normalize();
                    if (bin.getFileName() != null && bin.getFileName().toString().equalsIgnoreCase("bin")
                            && bin.getParent() != null) homes.add(bin.getParent());
                }
            }
            for (Path home : homes) {
                Path resolved = home.toAbsolutePath().normalize();
                if (!Files.isRegularFile(resolved.resolve("bin").resolve(windows ? "javac.exe" : "javac"))
                        || !Files.isRegularFile(resolved.resolve("bin").resolve(windows ? "java.exe" : "java"))) continue;
                Path release = resolved.resolve("release");
                if (!Files.isRegularFile(release)) continue;
                try {
                    for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                        if (line.matches("JAVA_VERSION=\\\"21(?:[.+-].*)?\\\"")) return resolved;
                    }
                } catch (IOException ignored) { /* Try the next installation. */ }
            }
            throw new IOException("Install a Java 21 JDK and set JAVA_HOME before launching Minecraft.");
        }
    }
}
