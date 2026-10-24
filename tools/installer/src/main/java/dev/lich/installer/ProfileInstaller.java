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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
        Path flagsFile = null;
        Map<String, Path> libraries = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mc" -> mc = args[++i];
                case "--root" -> root = Path.of(args[++i]);
                case "--prism-instance" -> prismInstance = Path.of(args[++i]);
                case "--flags" -> flagsFile = Path.of(args[++i]);
                case "--library" -> {
                    // group:artifact:version=path/to.jar
                    String[] parts = args[++i].split("=", 2);
                    libraries.put(parts[0], Path.of(parts[1]));
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (mc == null || (root == null) == (prismInstance == null) || flagsFile == null) {
            throw new IllegalArgumentException("Usage: --mc <version> (--root <launcher dir> | --prism-instance <instance dir>)"
                    + " --flags <jvm-flags.txt> [--library <coords>=<jar>]...");
        }
        if (prismInstance != null) {
            String version = libraries.keySet().stream().filter(c -> c.startsWith("dev.lich:")).findFirst().map(c -> c.split(":")[2]).orElse("dev");
            PrismComponent.install(prismInstance, mc, version, readFlags(flagsFile), libraries);
            System.out.println("[installer] Installed Lich " + version + " into Prism instance " + prismInstance);
            return;
        }
        Path json = install(mc, root, readFlags(flagsFile), libraries);
        System.out.println("[installer] Wrote " + json);
    }

    /**
     * Copies the libraries and writes {@code lich-<mc>}; returns that JSON's path. When Lich knows the
     * Forge build for {@code mc}, it also writes {@code forge-<mc>} (plain Forge, as Forge's installer
     * would) and {@code lich-forge-<mc>} on top of it.
     */
    static Path install(String mc, Path root, List<String> jvmFlags, Map<String, Path> libraries) throws IOException {
        JsonArray libraryList = new JsonArray();
        for (Map.Entry<String, Path> library : libraries.entrySet()) {
            String path = mavenPath(library.getKey());
            Path target = root.resolve("libraries").resolve(path);
            Files.createDirectories(target.getParent());
            Files.copy(library.getValue(), target, StandardCopyOption.REPLACE_EXISTING);

            JsonObject artifact = new JsonObject();
            artifact.addProperty("path", path);
            artifact.addProperty("sha1", sha1(target));
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
                String forgeId = "forge-" + mc;
                Path forgeJson = root.resolve("versions").resolve(forgeId).resolve(forgeId + ".json");
                Files.createDirectories(forgeJson.getParent());
                Files.write(forgeJson, forge.readAllBytes());
                writeLichProfile(root, "lich-forge-" + mc, forgeId, libraryList, arguments);
            }
        }
        return json;
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

    /** {@code group:artifact:version} → {@code group/as/dirs/artifact/version/artifact-version.jar}. */
    static String mavenPath(String coordinates) {
        String[] parts = coordinates.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected group:artifact:version, got " + coordinates);
        }
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + ".jar";
    }

    private static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
