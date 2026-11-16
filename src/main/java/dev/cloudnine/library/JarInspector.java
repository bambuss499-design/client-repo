package dev.cloudnine.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads metadata only. It does not execute code or promise that a mod is safe. */
public final class JarInspector {
    private static final int MAX_METADATA_BYTES = 512 * 1024;
    private static final String MINECRAFT = "1.21.11";

    public LibraryEntry inspect(Path path) {
        String filename = path.getFileName().toString();
        String label = filename.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? filename.substring(0, filename.length() - 4) : filename;
        return inspect(path, label);
    }

    public LibraryEntry inspect(Path path, String label) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry metadata = zip.getEntry("fabric.mod.json");
            if (metadata == null || metadata.isDirectory()) {
                return invalid(path, label, "No fabric.mod.json; this is not a Fabric mod JAR.");
            }
            byte[] bytes;
            try (InputStream input = zip.getInputStream(metadata)) {
                bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            }
            if (bytes.length > MAX_METADATA_BYTES) {
                return invalid(path, label, "Fabric metadata is too large to inspect.");
            }
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = string(json, "id", "");
            String version = string(json, "version", "unknown");
            if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
                return invalid(path, label, "Missing or invalid Fabric mod ID.");
            }
            if ("cloudnine".equals(id) || "cloudnine_return".equals(id)) {
                return invalid(path, label, "Cloud Nine cannot select a second copy of itself.");
            }
            if ("server".equals(string(json, "environment", "*"))) {
                return invalid(path, label, "This mod is server-only.");
            }
            JsonObject depends = json.has("depends") && json.get("depends").isJsonObject()
                    ? json.getAsJsonObject("depends") : new JsonObject();
            if (depends.has("minecraft") && !matchesMinecraft(depends.get("minecraft"))) {
                return invalid(path, label, "Metadata does not support Minecraft " + MINECRAFT + ".");
            }
            List<String> extras = new ArrayList<>();
            for (String key : depends.keySet()) {
                if (!List.of("minecraft", "java", "fabricloader").contains(key)) extras.add(key);
            }
            extras.sort(String.CASE_INSENSITIVE_ORDER);
            return new LibraryEntry(path, label, id, version, extras, null);
        } catch (Exception e) {
            return invalid(path, label, "Could not read valid Fabric metadata.");
        }
    }

    private static boolean matchesMinecraft(JsonElement element) throws Exception {
        Version current = Version.parse(MINECRAFT);
        if (element.isJsonArray()) {
            for (JsonElement alternative : element.getAsJsonArray()) {
                if (alternative.isJsonPrimitive() && VersionPredicate.parse(alternative.getAsString()).test(current)) return true;
            }
            return false;
        }
        return element.isJsonPrimitive() && VersionPredicate.parse(element.getAsString()).test(current);
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static LibraryEntry invalid(Path path, String label, String reason) {
        return new LibraryEntry(path, label, "", "", List.of(), reason);
    }
}
