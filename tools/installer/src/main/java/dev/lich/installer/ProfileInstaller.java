package dev.lich.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a Lich version profile into a launcher-style directory: {@code versions/lich-<mc>/lich-<mc>.json}
 * and the Lich jars under {@code libraries/}. The profile inherits everything from the vanilla version
 * and changes only the main class, adds Lich's jars (first on the class path) and Lich's JVM flags.
 * For the Forge builds Lich supports it also writes the Forge profile and a Lich profile on top of it.
 */
public final class ProfileInstaller {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final String MAIN_CLASS = "dev.lich.boot.Main";

    private ProfileInstaller() {
    }

    public static void main(String[] args) throws IOException {
        String mc = null;
        Path root = null;
        Path prismInstance = null;
        Path payload = null;
        Path flagsFile = null;
        String forgeBuild = null;
        Map<String, Path> libraries = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mc" -> mc = args[++i];
                case "--root" -> root = Path.of(args[++i]);
                case "--prism-instance" -> prismInstance = Path.of(args[++i]);
                case "--payload" -> payload = Path.of(args[++i]);
                case "--flags" -> flagsFile = Path.of(args[++i]);
                case "--forge" -> forgeBuild = args[++i];
                case "--library" -> {
                    // group:artifact:version=path/to.jar
                    String[] parts = args[++i].split("=", 2);
                    libraries.put(parts[0], Path.of(parts[1]));
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        long targets = java.util.stream.Stream.of(root, prismInstance, payload).filter(java.util.Objects::nonNull).count();
        if (mc == null || targets != 1 || flagsFile == null) {
            throw new IllegalArgumentException("Usage: --mc <version> (--root <launcher dir> | --prism-instance <instance dir>"
                    + " | --payload <dir>) --flags <jvm-flags.txt> [--forge <build>] [--library <coords>=<jar>]...");
        }
        String version = libraries.keySet().stream().filter(c -> c.startsWith("dev.lich:")).findFirst().map(c -> c.split(":")[2]).orElse("dev");
        if (prismInstance != null) {
            List<Library> local = libraries.entrySet().stream()
                    .<Library>map(library -> new Library.Local(library.getKey(), library.getValue())).toList();
            PrismComponent.install(prismInstance, mc, version, readFlags(flagsFile), local);
            System.out.println("[installer] Installed Lich " + version + " into Prism instance " + prismInstance);
            return;
        }
        if (payload != null) {
            // What the player-facing installer jar carries (PrismInstaller).
            Payload.write(payload, mc, version, readFlags(flagsFile), libraries);
            System.out.println("[installer] Wrote the installer payload for " + mc + " into " + payload);
            return;
        }
        Path json = install(mc, root, readFlags(flagsFile), libraries, forgeBuild);
        System.out.println("[installer] Wrote " + json);
    }

    /**
     * Copies the libraries and writes {@code lich-<mc>}; returns that JSON's path. When Lich knows the
     * Forge build for {@code mc}, it also writes {@code forge-<mc>} (plain Forge, as Forge's installer
     * would) and {@code lich-forge-<mc>} on top of it.
     */
    static Path install(String mc, Path root, List<String> jvmFlags, Map<String, Path> libraries) throws IOException {
        return install(mc, root, jvmFlags, libraries, null);
    }

    /** {@link #install(String, Path, List, Map)} with a given Forge build (one Lich supports; null: the default one). */
    static Path install(String mc, Path root, List<String> jvmFlags, Map<String, Path> libraries, String forgeBuild)
            throws IOException {
        String forgeVersion = forgeBuild != null ? forgeBuild : PrismInstance.FORGE_VERSIONS.get(mc);
        if (forgeBuild != null && !PrismInstance.supportsForge(mc, forgeBuild)) {
            throw new IllegalArgumentException("Lich doesn't support Forge " + forgeBuild + " for Minecraft " + mc);
        }
        JsonArray libraryList = new JsonArray();
        for (Map.Entry<String, Path> library : libraries.entrySet()) {
            String path = mavenPath(library.getKey());
            Path target = root.resolve("libraries").resolve(path);
            Files.createDirectories(target.getParent());
            Files.copy(library.getValue(), target, StandardCopyOption.REPLACE_EXISTING);

            JsonObject artifact = new JsonObject();
            artifact.addProperty("path", path);
            artifact.addProperty("sha1", Payload.sha1(target));
            artifact.addProperty("size", Files.size(target));
            // Empty: installed locally, nothing for a launcher to download.
            artifact.addProperty("url", "");
            JsonObject downloads = new JsonObject();
            downloads.add("artifact", artifact);
            JsonObject entry = new JsonObject();
            entry.addProperty("name", library.getKey());
            entry.add("downloads", downloads);
            libraryList.add(entry);
        }

        JsonArray jvm = new JsonArray();
        jvmFlags.forEach(jvm::add);
        JsonObject arguments = new JsonObject();
        arguments.add("jvm", jvm);

        Path json = writeLichProfile(root, "lich-" + mc, mc, libraryList, arguments);
        try (InputStream forge = ProfileInstaller.class.getResourceAsStream("forge-" + mc + ".json")) {
            if (forge != null) {
                String template = new String(forge.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String forgeId = "forge-" + mc;
                writeForgeProfile(root, forgeId, template, forgeId, forgeVersion, libraryList, arguments, mc);
                // A version with several builds also gets a profile per build: the harness runs each pack on the one it pins.
                List<String> also = PrismInstance.FORGE_ALSO.getOrDefault(mc, List.of());
                if (!also.isEmpty()) {
                    for (String build : java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(PrismInstance.FORGE_VERSIONS.get(mc)), also.stream()).toList()) {
                        writeForgeProfile(root, forgeId + "-" + build, template, forgeId, build, libraryList, arguments, mc);
                    }
                }
            }
        }
        return json;
    }

    /** Writes the plain Forge profile {@code id} for {@code build}, and the Lich profile {@code lich-<id>} on top of it. */
    private static void writeForgeProfile(Path root, String id, String template, String templateId, String build,
                                          JsonArray libraries, JsonObject arguments, String mc) throws IOException {
        Path json = root.resolve("versions").resolve(id).resolve(id + ".json");
        Files.createDirectories(json.getParent());
        // The Forge builds of one Minecraft version differ in the Forge jar only; 1.7.10 and 1.8.9 have one build.
        Files.writeString(json, template.replace("\"id\": \"" + templateId + "\"", "\"id\": \"" + id + "\"")
                .replace("${forge}", String.valueOf(build)));
        writeLichProfile(root, "lich-" + id, id, libraries, arguments);
    }

    private static Path writeLichProfile(Path root, String id, String parent, JsonArray libraries, JsonObject arguments)
            throws IOException {
        String now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        JsonObject profile = new JsonObject();
        profile.addProperty("id", id);
        profile.addProperty("inheritsFrom", parent);
        profile.addProperty("type", "release");
        profile.addProperty("time", now);
        profile.addProperty("releaseTime", now);
        profile.addProperty("mainClass", MAIN_CLASS);
        profile.add("libraries", libraries);
        // Game arguments come from the parent's minecraftArguments; only JVM arguments are added.
        profile.add("arguments", arguments);

        Path json = root.resolve("versions").resolve(id).resolve(id + ".json");
        Files.createDirectories(json.getParent());
        Files.writeString(json, GSON.toJson(profile) + "\n");
        return json;
    }

    static List<String> readFlags(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /**
     * {@code group:artifact:version[:classifier][@extension]} →
     * {@code group/as/dirs/artifact/version/artifact-version[-classifier].extension} (default {@code jar}).
     */
    static String mavenPath(String coordinates) {
        String[] parts = coordinates.split("@", 2)[0].split(":");
        if (parts.length != 3 && parts.length != 4) {
            throw new IllegalArgumentException("Expected group:artifact:version[:classifier][@extension], got " + coordinates);
        }
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + fileName(coordinates);
    }

    /** {@code artifact-version[-classifier].extension}, as Maven and Prism name a library's file. */
    static String fileName(String coordinates) {
        String[] withExtension = coordinates.split("@", 2);
        String[] parts = withExtension[0].split(":");
        String extension = withExtension.length == 2 ? withExtension[1] : "jar";
        return parts[1] + "-" + parts[2] + (parts.length == 4 ? "-" + parts[3] : "") + "." + extension;
    }
}
