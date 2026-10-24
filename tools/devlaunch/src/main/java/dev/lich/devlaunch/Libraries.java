package dev.lich.devlaunch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Downloads a version's libraries and extracts its natives. */
final class Libraries {
    private static final String DEFAULT_REPO = "https://libraries.minecraft.net/";

    private Libraries() {
    }

    /**
     * Returns the classpath (libraries in JSON order, first occurrence of an artifact wins) and leaves
     * this OS's natives extracted in {@code nativesDir}.
     */
    static List<Path> resolve(JsonObject version, Path librariesDir, Path nativesDir, Downloader downloader)
            throws IOException {
        // Natives are re-extracted every launch, so switching LWJGL versions never leaves stale DLLs behind.
        deleteRecursively(nativesDir);
        Files.createDirectories(nativesDir);

        List<Path> classpath = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            if (!Rules.allowed(library.get("rules"))) {
                continue;
            }
            Coordinates coordinates = Coordinates.parse(library.get("name").getAsString());
            JsonObject downloads = library.getAsJsonObject("downloads");

            boolean hasArtifact = downloads != null ? downloads.has("artifact") : !library.has("natives");
            if (hasArtifact && seen.add(coordinates.key())) {
                JsonObject artifact = downloads != null ? downloads.getAsJsonObject("artifact") : null;
                classpath.add(fetch(library, artifact, coordinates, librariesDir, downloader));
            }

            if (library.has("natives")) {
                JsonElement classifier = library.getAsJsonObject("natives").get(Rules.OS_NAME);
                if (classifier == null) {
                    continue;
                }
                Coordinates natives = coordinates.withClassifier(classifier.getAsString().replace("${arch}", Rules.BITS));
                if (!seen.add(natives.key())) {
                    continue;
                }
                JsonObject artifact = downloads != null && downloads.has("classifiers")
                        ? downloads.getAsJsonObject("classifiers").getAsJsonObject(natives.classifier()) : null;
                Path jar = fetch(library, artifact, natives, librariesDir, downloader);
                List<String> excludes = new ArrayList<>();
                if (library.has("extract") && library.getAsJsonObject("extract").has("exclude")) {
                    library.getAsJsonObject("extract").getAsJsonArray("exclude").forEach(e -> excludes.add(e.getAsString()));
                }
                extract(jar, nativesDir, excludes);
            }
        }
        return classpath;
    }

    private static Path fetch(JsonObject library, JsonObject artifact, Coordinates coordinates, Path librariesDir,
                              Downloader downloader) throws IOException {
        if (artifact != null) {
            Path target = librariesDir.resolve(artifact.has("path") ? artifact.get("path").getAsString() : coordinates.path());
            String url = artifact.has("url") ? artifact.get("url").getAsString() : "";
            if (url.isEmpty()) {
                // Installer-provided libraries (Forge, later Lich) have no URL and must already be on disk.
                if (!Files.isRegularFile(target)) {
                    throw new IOException("Library " + coordinates + " has no download URL and is missing: " + target);
                }
                return target;
            }
            String sha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;
            return downloader.fetch(url, target, sha1);
        }
        String repo = library.has("url") ? library.get("url").getAsString() : DEFAULT_REPO;
        if (!repo.endsWith("/")) {
            repo += "/";
        }
        return downloader.fetch(repo + coordinates.path(), librariesDir.resolve(coordinates.path()), null);
    }

    private static void extract(Path jar, Path targetDir, List<String> excludes) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || excludes.stream().anyMatch(entry.getName()::startsWith)) {
                    continue;
                }
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new IOException("Native entry escapes the natives directory: " + entry.getName() + " in " + jar);
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Maven coordinates {@code group:artifact:version[:classifier][@extension]}. */
    record Coordinates(String group, String artifact, String version, String classifier, String extension) {
        static Coordinates parse(String name) {
            String extension = "jar";
            int at = name.indexOf('@');
            if (at >= 0) {
                extension = name.substring(at + 1);
                name = name.substring(0, at);
            }
            String[] parts = name.split(":");
            if (parts.length < 3 || parts.length > 4) {
                throw new IllegalArgumentException("Bad library name: " + name);
            }
            return new Coordinates(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : null, extension);
        }

        Coordinates withClassifier(String classifier) {
            return new Coordinates(group, artifact, version, classifier, extension);
        }

        /** Identity for de-duplication: the version is left out, so the first declared version wins. */
        String key() {
            return group + ":" + artifact + (classifier == null ? "" : ":" + classifier);
        }

        String path() {
            String file = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + "." + extension;
            return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file;
        }

        @Override
        public String toString() {
            return group + ":" + artifact + ":" + version + (classifier == null ? "" : ":" + classifier);
        }
    }
}
