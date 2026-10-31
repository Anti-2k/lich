package dev.lich.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Prism instance as the installer sees it: its {@code mmc-pack.json} components and its {@code instance.cfg}.
 * Knows which Forge build Lich supports for each Minecraft version and what else an instance may not have.
 */
final class PrismInstance {
    static final String MINECRAFT = "net.minecraft";
    static final String FORGE = "net.minecraftforge";
    static final String LWJGL2 = "org.lwjgl";
    /** The Forge build Lich supports per Minecraft version (DOCS.md §0). */
    static final Map<String, String> FORGE_VERSIONS = Map.of("1.7.10", "10.13.4.1614", "1.8.9", "11.15.1.2318");
    /** The LWJGL 2 component both versions' Minecraft asks Prism for; Lich replaces it with LWJGL 3 at launch. */
    static final String LWJGL2_VERSION = "2.9.4-nightly-20150209";
    /** Components Lich can't run with, and why. */
    private static final Map<String, String> INCOMPATIBLE = Map.of(
            "net.fabricmc.fabric-loader", "Fabric (and Legacy Fabric) isn't supported",
            "org.quiltmc.quilt-loader", "Quilt isn't supported",
            "net.neoforged", "NeoForge isn't supported",
            "org.lwjgl3", "Prism's LWJGL 3 component: Lich brings its own LWJGL 3. Switch the instance back to LWJGL 2");

    final Path dir;
    final JsonObject pack;

    private PrismInstance(Path dir, JsonObject pack) {
        this.dir = dir;
        this.pack = pack;
    }

    static PrismInstance read(Path dir) throws IOException {
        Path pack = dir.resolve("mmc-pack.json");
        if (!Files.isRegularFile(pack)) {
            throw new IOException("Not a Prism instance (no mmc-pack.json): " + dir);
        }
        return new PrismInstance(dir, PrismComponent.readJson(pack));
    }

    static boolean isInstance(Path dir) {
        return Files.isRegularFile(dir.resolve("mmc-pack.json")) && Files.isRegularFile(dir.resolve("instance.cfg"));
    }

    /**
     * Creates a new instance folder for {@code mc}: Minecraft, its LWJGL 2 and, with {@code forge}, Lich's Forge build.
     * Prism fills in the rest (the components' metadata, the game's libraries) the first time it loads it.
     */
    static PrismInstance create(Path dir, String name, String mc, boolean forge) throws IOException {
        if (Files.exists(dir)) {
            throw new IOException("Already exists: " + dir);
        }
        JsonArray components = new JsonArray();
        JsonObject lwjgl = component(LWJGL2, LWJGL2_VERSION);
        lwjgl.addProperty("dependencyOnly", true);
        components.add(lwjgl);
        JsonObject minecraft = component(MINECRAFT, mc);
        minecraft.addProperty("important", true);
        components.add(minecraft);
        if (forge) {
            components.add(component(FORGE, FORGE_VERSIONS.get(mc)));
        }
        JsonObject pack = new JsonObject();
        pack.add("components", components);
        pack.addProperty("formatVersion", 1);

        Files.createDirectories(dir.resolve("minecraft"));
        Files.writeString(dir.resolve("mmc-pack.json"), PrismComponent.GSON.toJson(pack) + "\n");
        IniFile cfg = new IniFile(List.of());
        cfg.set("ConfigVersion", "1.3");
        cfg.set("InstanceType", "OneSix");
        cfg.set("iconKey", "default");
        cfg.set("name", name);
        cfg.write(dir.resolve("instance.cfg"));
        return new PrismInstance(dir, pack);
    }

    /** The display name Prism shows ({@code name=} in {@code instance.cfg}), or the folder's name. */
    String name() throws IOException {
        String name = IniFile.read(dir.resolve("instance.cfg")).get("name");
        return name == null || name.isEmpty() ? dir.getFileName().toString() : name;
    }

    /** The {@code version} of component {@code uid}, or null when the instance doesn't have it. */
    String version(String uid) {
        JsonObject component = find(uid);
        return component == null ? null : component.get("version").getAsString();
    }

    String minecraft() {
        return version(MINECRAFT);
    }

    /** Why Lich can't be installed into this instance, or null if it can. */
    String problem() {
        String mc = minecraft();
        if (mc == null) {
            return "it has no Minecraft component";
        }
        if (!FORGE_VERSIONS.containsKey(mc)) {
            return "it's Minecraft " + mc + "; Lich supports " + String.join(" and ", FORGE_VERSIONS.keySet().stream().sorted().toList());
        }
        for (JsonElement element : pack.getAsJsonArray("components")) {
            String uid = element.getAsJsonObject().get("uid").getAsString();
            for (Map.Entry<String, String> incompatible : INCOMPATIBLE.entrySet()) {
                if (uid.equals(incompatible.getKey()) || uid.startsWith(incompatible.getKey() + ".")) {
                    return incompatible.getValue();
                }
            }
        }
        return null;
    }

    /**
     * What {@link #ensureForge} would change, or null if nothing: Forge missing (only when {@code wanted}), or another
     * build than Lich's. An instance that has Forge always gets Lich's build, wanted or not, since Lich can't run any other.
     */
    String forgeChange(boolean wanted) {
        String expected = FORGE_VERSIONS.get(minecraft());
        String current = version(FORGE);
        if (current == null) {
            return wanted ? "add Forge " + expected : null;
        }
        return current.equals(expected) ? null : "change Forge " + current + " to " + expected + ", the build Lich supports";
    }

    /** Applies {@link #forgeChange}: adds Forge right after Minecraft, or switches its build. */
    void ensureForge(boolean wanted) {
        String expected = FORGE_VERSIONS.get(minecraft());
        JsonObject forge = find(FORGE);
        if (forge == null) {
            if (!wanted) {
                return;
            }
            JsonArray components = pack.getAsJsonArray("components");
            JsonArray updated = new JsonArray();
            for (JsonElement component : components) {
                updated.add(component);
                if (MINECRAFT.equals(component.getAsJsonObject().get("uid").getAsString())) {
                    updated.add(component(FORGE, expected));
                }
            }
            pack.add("components", updated);
        } else if (!expected.equals(forge.get("version").getAsString())) {
            forge.addProperty("version", expected);
            // Prism's cache of the old build's metadata; it refreshes it from the new version.
            forge.remove("cachedVersion");
            forge.remove("cachedRequires");
        }
    }

    void writePack() throws IOException {
        Files.writeString(dir.resolve("mmc-pack.json"), PrismComponent.GSON.toJson(pack) + "\n");
    }

    /**
     * Points the instance at {@code java} and turns off Prism's checks that would pick Java 8 for these versions
     * instead ({@code AutomaticJava}) or refuse Java 25 ({@code IgnoreJavaCompatibility}).
     */
    void useJava(Path java) throws IOException {
        Path file = dir.resolve("instance.cfg");
        IniFile cfg = IniFile.read(file);
        cfg.set("OverrideJavaLocation", "true");
        // Qt's INI format treats backslashes as escapes; Prism itself writes forward slashes.
        cfg.set("JavaPath", java.toAbsolutePath().toString().replace('\\', '/'));
        cfg.set("AutomaticJava", "false");
        cfg.set("IgnoreJavaCompatibility", "true");
        // Prism's cached facts about the previous Java; it checks the new one again on launch.
        for (String key : List.of("JavaSignature", "JavaVersion", "JavaVendor", "JavaArchitecture", "JavaRealArchitecture")) {
            cfg.remove(key);
        }
        cfg.write(file);
    }

    /** Undoes {@link #useJava}: the instance goes back to Prism's own Java choice. */
    void resetJava() throws IOException {
        Path file = dir.resolve("instance.cfg");
        IniFile cfg = IniFile.read(file);
        cfg.set("OverrideJavaLocation", "false");
        cfg.remove("JavaPath");
        cfg.set("AutomaticJava", "true");
        cfg.set("IgnoreJavaCompatibility", "false");
        cfg.write(file);
    }

    private JsonObject find(String uid) {
        for (JsonElement element : pack.getAsJsonArray("components")) {
            JsonObject component = element.getAsJsonObject();
            if (uid.equals(component.get("uid").getAsString())) {
                return component;
            }
        }
        return null;
    }

    private static JsonObject component(String uid, String version) {
        JsonObject component = new JsonObject();
        component.addProperty("uid", uid);
        component.addProperty("version", version);
        return component;
    }

    /** The {@code [General]} section of a Prism INI file, edited in place; other lines and sections stay as they are. */
    static final class IniFile {
        private final List<String> lines;

        IniFile(List<String> lines) {
            this.lines = new ArrayList<>(lines);
            if (!this.lines.contains("[General]")) {
                this.lines.addFirst("[General]");
            }
        }

        static IniFile read(Path file) throws IOException {
            return new IniFile(Files.isRegularFile(file) ? Files.readAllLines(file) : List.of());
        }

        String get(String key) {
            int line = find(key);
            return line < 0 ? null : lines.get(line).substring(key.length() + 1);
        }

        void set(String key, String value) {
            int line = find(key);
            if (line >= 0) {
                lines.set(line, key + "=" + value);
            } else {
                lines.add(end(), key + "=" + value);
            }
        }

        void remove(String key) {
            int line = find(key);
            if (line >= 0) {
                lines.remove(line);
            }
        }

        void write(Path file) throws IOException {
            Files.write(file, lines);
        }

        private int find(String key) {
            for (int i = lines.indexOf("[General]") + 1; i < end(); i++) {
                if (lines.get(i).startsWith(key + "=")) {
                    return i;
                }
            }
            return -1;
        }

        /** The line after {@code [General]}'s last one. */
        private int end() {
            int i = lines.indexOf("[General]") + 1;
            while (i < lines.size() && !lines.get(i).startsWith("[")) {
                i++;
            }
            // Before the blank lines that separate it from the next section.
            while (i > 0 && lines.get(i - 1).isBlank()) {
                i--;
            }
            return i;
        }
    }
}
