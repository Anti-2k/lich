package dev.lich.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * What the player-facing installer installs for one Minecraft version: Lich's version, its JVM flags and its
 * libraries. The build writes it ({@link #write}) into {@code lich/<mc>.json} next to Lich's own jars in
 * {@code lich/jars/}, and the installer jar carries both. Libraries Lich doesn't build are listed by download URL
 * and SHA-1 instead of copied, so Prism fetches them from where they're published.
 */
record Payload(String mc, String version, List<String> jvmFlags, List<Library> libraries) {
    /** Lich's own jars ({@code dev.lich:*}) travel inside the installer; the rest come from these repositories. */
    private static final Map<String, String> REPOSITORIES = Map.of(
            "de.oceanlabs.mcp", "https://maven.minecraftforge.net/",
            "net.fabricmc", "https://maven.fabricmc.net/");
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    /** Writes {@code <dir>/<mc>.json} and copies Lich's own jars into {@code <dir>/jars/}. */
    static void write(Path dir, String mc, String version, List<String> jvmFlags, Map<String, Path> libraries)
            throws IOException {
        Files.createDirectories(dir.resolve("jars"));
        JsonArray list = new JsonArray();
        for (Map.Entry<String, Path> library : libraries.entrySet()) {
            String name = library.getKey();
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            if (name.startsWith("dev.lich:")) {
                String file = "jars/" + ProfileInstaller.fileName(name);
                Files.copy(library.getValue(), dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                entry.addProperty("file", file);
            } else {
                String group = name.substring(0, name.indexOf(':'));
                entry.addProperty("url", REPOSITORIES.getOrDefault(group, MAVEN_CENTRAL) + ProfileInstaller.mavenPath(name));
                entry.addProperty("sha1", sha1(library.getValue()));
                entry.addProperty("size", Files.size(library.getValue()));
            }
            list.add(entry);
        }
        JsonArray flags = new JsonArray();
        jvmFlags.forEach(flags::add);
        JsonObject json = new JsonObject();
        json.addProperty("mc", mc);
        json.addProperty("version", version);
        json.add("jvmFlags", flags);
        json.add("libraries", list);
        Files.writeString(dir.resolve(mc + ".json"), PrismComponent.GSON.toJson(json) + "\n");
    }

    /** Reads {@code <dir>/<mc>.json}; {@code dir} may be inside the installer jar. Null if there's none for {@code mc}. */
    static Payload read(Path dir, String mc) throws IOException {
        Path file = dir.resolve(mc + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonObject json = PrismComponent.readJson(file);
        List<String> flags = new ArrayList<>();
        json.getAsJsonArray("jvmFlags").forEach(flag -> flags.add(flag.getAsString()));
        List<Library> libraries = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            String name = library.get("name").getAsString();
            libraries.add(library.has("file")
                    ? new Library.Local(name, dir.resolve(library.get("file").getAsString()))
                    : new Library.Remote(name, library.get("url").getAsString(), library.get("sha1").getAsString(),
                            library.get("size").getAsLong()));
        }
        return new Payload(mc, json.get("version").getAsString(), flags, libraries);
    }

    static String sha1(Path file) throws IOException {
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
