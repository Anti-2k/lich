package dev.lich.mcdev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lich.remap.core.ClassHierarchy;
import dev.lich.remap.core.JarRemapper;
import dev.lich.remap.core.MappingRemapper;
import dev.lich.remap.core.Mappings;
import dev.lich.remap.core.McpNames;
import dev.lich.remap.core.SrgFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Everything a mod needs to compile against one Minecraft version, kept in a cache shared by every build
 * on the machine: the client jar and its libraries (from Mojang), MCP's SRG mappings and names (from
 * Forge's Maven, see {@link #NAMES}), and from those, the client's classes renamed to MCP names and an MCP → SRG mapping file
 * for Mixin's annotation processor and for reobfuscation. No Forge: a Lich mod that compiles against this
 * only uses what vanilla has, so it runs with and without Forge.
 */
final class MinecraftSetup {
    /** Bump when the generated files change, so caches made by an older plugin are regenerated. */
    private static final String GENERATION = "4";
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    /**
     * Where a version's MCP names come from: the names its Forge mods are written against, so their code ports to
     * Lich unchanged. For 1.8.9 that's stable_22. For 1.7.10 it's the names Forge 1.7.10 ships in its userdev jar
     * ({@code conf/}), which ForgeGradle and RetroFuturaGradle use by default; stable_12 differs from them (it names
     * many members those leave as {@code func_}/{@code field_}, and renames a few, like {@code fontRenderer}).
     *
     * @param path      the file's path on Forge's Maven
     * @param directory where {@code fields.csv} and {@code methods.csv} are inside it
     * @param sha1      the file's SHA-1, or {@code null} to take what the Maven serves
     */
    private record NameSource(String path, String file, String directory, String sha1) {
    }

    private static final Map<String, NameSource> NAMES = Map.of(
            "1.7.10", new NameSource("net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-userdev.jar",
                    "forge-userdev.jar", "conf/", "5670a6438e76598e1d6e4e190982d80a0d626a7c"),
            "1.8.9", new NameSource("de/oceanlabs/mcp/mcp_stable/22-1.8.9/mcp_stable-22-1.8.9.zip", "mcp_stable.zip", "", null));

    /**
     * @param mcpJar     the client's classes with MCP names, to compile against
     * @param mcpToSrg   MCP → SRG in SRG format, for Mixin's annotation processor and reobfuscation
     * @param srgZip     MCP's {@code mcp-<mc>-srg.zip} (obf → SRG), for tools that work on the obfuscated jar
     * @param libraries  the client's libraries on this OS, without natives
     */
    record Result(String minecraft, Path mcpJar, Path mcpToSrg, Path srgZip, List<Path> libraries) {
    }

    private MinecraftSetup() {
    }

    @SuppressWarnings("try") // the lock is held, not used
    static Result prepare(String mc, Path cache) throws IOException {
        NameSource names = NAMES.get(mc);
        if (names == null) {
            throw new IOException("Lich doesn't support Minecraft " + mc + " (only " + NAMES.keySet() + ")");
        }
        Path dir = cache.resolve(mc);
        Files.createDirectories(dir);
        // Several projects (or builds) may set up the same version at once.
        try (FileChannel channel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            JsonObject version = versionJson(mc, dir);
            JsonObject client = version.getAsJsonObject("downloads").getAsJsonObject("client");
            Path clientJar = Http.fetch(client.get("url").getAsString(), dir.resolve("client.jar"), client.get("sha1").getAsString());
            List<Path> libraries = libraries(version, cache.resolve("libraries"));

            String mcp = "de/oceanlabs/mcp/";
            Path srgZip = Http.fetch(FORGE_MAVEN + mcp + "mcp/" + mc + "/mcp-" + mc + "-srg.zip", dir.resolve("mcp-srg.zip"), null);
            Path namesZip = Http.fetch(FORGE_MAVEN + names.path(), dir.resolve(names.file()), names.sha1());

            Path mcpJar = dir.resolve("minecraft-" + mc + "-mcp.jar");
            Path mcpToSrg = dir.resolve("mcp-to-srg.srg");
            Path marker = dir.resolve("generated-" + GENERATION);
            if (!Files.exists(marker) || !Files.exists(mcpJar) || !Files.exists(mcpToSrg)) {
                generate(clientJar, srgZip, McpNames.readZip(namesZip, names.directory()), mcpJar, mcpToSrg);
                Files.writeString(marker, mc + "\n");
            }
            return new Result(mc, mcpJar, mcpToSrg, srgZip, libraries);
        }
    }

    private static void generate(Path clientJar, Path srgZip, McpNames names, Path mcpJar, Path mcpToSrg) throws IOException {
        Mappings obfToSrg = SrgFile.readZip(srgZip, "joined.srg");
        McpExceptions exceptions = McpExceptions.read(srgZip, names);
        McpInnerClasses innerClasses = McpInnerClasses.read(srgZip);
        try (ZipFile client = new ZipFile(clientJar.toFile())) {
            ClassHierarchy hierarchy = ClassHierarchy.fromClassFiles(name -> read(client, name + ".class"));
            JarRemapper.remap(clientJar, mcpJar, new MappingRemapper(names.obfToMcp(obfToSrg), hierarchy),
                    entry -> entry.endsWith(".class"), (name, bytes) -> bytes,
                    writer -> exceptions.visitor(innerClasses.visitor(writer)));
        }
        try (Writer writer = Files.newBufferedWriter(mcpToSrg)) {
            SrgFile.write(names.mcpToSrg(obfToSrg), writer);
        }
    }

    private static byte[] read(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + name + " from " + zip.getName(), e);
        }
    }

    private static JsonObject versionJson(String mc, Path dir) throws IOException {
        Path file = dir.resolve(mc + ".json");
        if (!Files.isRegularFile(file)) {
            Path manifestFile = Http.fetch(MANIFEST, dir.resolve("version_manifest_v2.json.tmp"), null);
            try {
                JsonObject manifest = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
                String url = null;
                String sha1 = null;
                for (JsonElement element : manifest.getAsJsonArray("versions")) {
                    JsonObject entry = element.getAsJsonObject();
                    if (entry.get("id").getAsString().equals(mc)) {
                        url = entry.get("url").getAsString();
                        sha1 = entry.get("sha1").getAsString();
                    }
                }
                if (url == null) {
                    throw new IOException("Minecraft " + mc + " is not in Mojang's version manifest");
                }
                Http.fetch(url, file, sha1);
            } finally {
                Files.deleteIfExists(manifestFile);
            }
        }
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** The libraries this OS gets, as the launcher picks them; natives aren't needed to compile. */
    private static List<Path> libraries(JsonObject version, Path dir) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        List<Path> libraries = new ArrayList<>();
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (!allowed(library) || downloads == null || !downloads.has("artifact")) {
                continue;
            }
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            String name = library.get("name").getAsString();
            // group:artifact without the version: the first version listed wins, as in the launcher.
            if (!seen.add(name.substring(0, name.lastIndexOf(':')))) {
                continue;
            }
            libraries.add(Http.fetch(artifact.get("url").getAsString(), dir.resolve(artifact.get("path").getAsString()),
                    artifact.get("sha1").getAsString()));
        }
        return libraries;
    }

    /** The launcher's rules, for the OS only: the last matching rule decides, and none matching means no. */
    private static boolean allowed(JsonObject library) {
        if (!library.has("rules")) {
            return true;
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osName = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "osx" : "linux";
        boolean allowed = false;
        for (JsonElement element : library.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            boolean matches = !rule.has("os") || !rule.getAsJsonObject("os").has("name")
                    || rule.getAsJsonObject("os").get("name").getAsString().equals(osName);
            if (matches) {
                allowed = rule.get("action").getAsString().equals("allow");
            }
        }
        return allowed;
    }
}
