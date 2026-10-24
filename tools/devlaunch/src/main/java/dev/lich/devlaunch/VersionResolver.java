package dev.lich.devlaunch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads version JSONs from Mojang (cached under {@code cache/versions}) or from disk, and flattens
 * {@code inheritsFrom} chains into one JSON, as the launcher does for Forge and Lich profiles.
 */
final class VersionResolver {
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final Downloader downloader;
    private final Path versionsDir;

    VersionResolver(Downloader downloader, Path versionsDir) {
        this.downloader = downloader;
        this.versionsDir = versionsDir;
    }

    JsonObject resolve(String id) throws IOException {
        Path file = versionsDir.resolve(id).resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            downloadVersionJson(id, file);
        }
        return withParent(read(file));
    }

    JsonObject resolveFile(Path file) throws IOException {
        return withParent(read(file));
    }

    private JsonObject withParent(JsonObject version) throws IOException {
        if (!version.has("inheritsFrom")) {
            // The client jar is cached under the id of the version that declares it.
            if (!version.has("jar")) {
                version.addProperty("jar", version.get("id").getAsString());
            }
            return version;
        }
        return merge(resolve(version.get("inheritsFrom").getAsString()), version);
    }

    /** Child values win; libraries are child-first (so a child can override a parent library); arguments append. */
    static JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject merged = parent.deepCopy();
        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            switch (entry.getKey()) {
                case "inheritsFrom" -> {
                }
                case "libraries" -> {
                    JsonArray libraries = entry.getValue().getAsJsonArray().deepCopy();
                    if (parent.has("libraries")) {
                        libraries.addAll(parent.getAsJsonArray("libraries"));
                    }
                    merged.add("libraries", libraries);
                }
                case "arguments" -> {
                    JsonObject arguments = parent.has("arguments")
                            ? parent.getAsJsonObject("arguments").deepCopy() : new JsonObject();
                    for (Map.Entry<String, JsonElement> kind : entry.getValue().getAsJsonObject().entrySet()) {
                        JsonArray list = arguments.has(kind.getKey()) ? arguments.getAsJsonArray(kind.getKey()) : new JsonArray();
                        list.addAll(kind.getValue().getAsJsonArray());
                        arguments.add(kind.getKey(), list);
                    }
                    merged.add("arguments", arguments);
                }
                default -> merged.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return merged;
    }

    private void downloadVersionJson(String id, Path target) throws IOException {
        Path manifestFile = downloader.fetch(MANIFEST_URL, versionsDir.resolve("version_manifest_v2.json.tmp"), null);
        JsonObject manifest;
        try {
            manifest = read(manifestFile);
        } finally {
            // Never cached: a stale manifest would hide newly listed versions.
            Files.deleteIfExists(manifestFile);
        }
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(id)) {
                downloader.fetch(entry.get("url").getAsString(), target, entry.get("sha1").getAsString());
                return;
            }
        }
        throw new IOException("Version " + id + " is not in Mojang's version manifest");
    }

    static JsonObject read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
