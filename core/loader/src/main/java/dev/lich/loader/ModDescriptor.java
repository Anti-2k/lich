package dev.lich.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A mod's {@code lich.mod.json} (DOCS.md §5.1), checked for shape: every field has the right type, the id
 * and version are well-formed, and unknown kinds of entrypoints or Forge requirements are rejected rather
 * than ignored. Whether the mod fits this game (Minecraft version, dependencies) is {@link ModResolver}'s job.
 *
 * @param minecraft the Minecraft versions the jar runs on, a whitelist: the mod loads if any entry accepts
 *                  the running version. In the file it's one string or a list, each an exact version or a range
 * @param depends   other mods' ids (and {@code lich}) → version range; {@code forge} is in {@link #forge}
 * @param forge     {@code required}, {@code optional} (the default) or {@code incompatible}
 * @param versions  exact Minecraft version → what the jar adds on that version only; empty once
 *                  {@link #forMinecraft} has merged the running version's part in
 * @param javaVersion the lowest Java feature version the mod runs on, 0 if it doesn't say
 */
public record ModDescriptor(String id, Version version, String name, String description, List<String> authors,
                            List<VersionRange> minecraft, Map<String, VersionRange> depends, String forge,
                            Map<String, List<String>> entrypoints, List<String> hooks, List<String> provides,
                            List<String> mixins, String accessWidener, List<String> transformers, int javaVersion,
                            Map<String, VersionPart> versions) {
    public static final String FILE = "lich.mod.json";
    public static final List<String> ENTRYPOINTS = List.of("preLaunch", "init", "client");
    /** What a {@code versions} entry may set; anything else is rejected, so a typo can't silently drop a Mixin. */
    private static final List<String> VERSION_PART_KEYS = List.of("entrypoints", "hooks", "provides", "mixins", "accessWidener");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");

    /**
     * The parts of a multi-version jar that only one Minecraft version uses (its Mixin configs, entrypoints
     * and access widener are compiled against that version's SRG names). Lists add to the top-level ones;
     * an access widener here replaces the top-level one.
     */
    public record VersionPart(Map<String, List<String>> entrypoints, List<String> hooks, List<String> provides,
                              List<String> mixins, String accessWidener) {
    }

    /** Whether the whitelist accepts Minecraft {@code minecraft}. */
    public boolean supportsMinecraft(String minecraft) {
        Version running = Version.parse(minecraft);
        return this.minecraft.stream().anyMatch(range -> range.test(running));
    }

    /** The whitelist as the player reads it: {@code 1.7.10, 1.8.9}. */
    public String minecraftText() {
        return String.join(", ", minecraft.stream().map(VersionRange::toString).toList());
    }

    /** This descriptor with {@code minecraft}'s part of {@link #versions} merged in (DOCS.md §5.1). */
    public ModDescriptor forMinecraft(String minecraft) {
        VersionPart part = null;
        Version running = Version.parse(minecraft);
        for (Map.Entry<String, VersionPart> entry : versions.entrySet()) {
            if (Version.parse(entry.getKey()).compareTo(running) == 0) {
                part = entry.getValue();
            }
        }
        if (part == null) {
            return versions.isEmpty() ? this : withParts(entrypoints, hooks, provides, mixins, accessWidener);
        }
        Map<String, List<String>> mergedEntrypoints = new LinkedHashMap<>();
        for (String kind : ENTRYPOINTS) {
            List<String> classes = concat(entrypoints(kind), part.entrypoints().getOrDefault(kind, List.of()));
            if (!classes.isEmpty()) {
                mergedEntrypoints.put(kind, classes);
            }
        }
        return withParts(mergedEntrypoints, concat(hooks, part.hooks()), concat(provides, part.provides()),
                concat(mixins, part.mixins()), part.accessWidener() != null ? part.accessWidener() : accessWidener);
    }

    private ModDescriptor withParts(Map<String, List<String>> entrypoints, List<String> hooks, List<String> provides,
                                    List<String> mixins, String accessWidener) {
        return new ModDescriptor(id, version, name, description, authors, minecraft, depends, forge, entrypoints,
                hooks, provides, mixins, accessWidener, transformers, javaVersion, Map.of());
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** Parses and checks {@code json}; the exception message says what's wrong, for the player to read. */
    public static ModDescriptor parse(String json) {
        JsonObject root;
        try {
            // Gson 2.2.4, the game's: no static JsonParser.parseString yet.
            JsonElement element = new JsonParser().parse(json);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("lich.mod.json must be a JSON object");
            }
            root = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("lich.mod.json isn't valid JSON: " + e.getMessage(), e);
        }

        int schema = integer(root, "schema", -1);
        if (schema != 1) {
            throw new IllegalArgumentException("Unsupported lich.mod.json schema " + (schema < 0 ? "(missing)" : schema)
                    + "; this Lich reads schema 1");
        }
        String id = string(root, "id", null);
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Mod id \"" + id + "\" must be 2-64 characters of a-z, 0-9, _ and -, starting with a letter");
        }
        String versionText = string(root, "version", null);
        if (versionText == null) {
            throw new IllegalArgumentException("Mod " + id + " has no version");
        }
        Version version = Version.parse(versionText);
        List<VersionRange> minecraft = new ArrayList<>();
        for (String entry : strings(root.get("minecraft"), "minecraft")) {
            minecraft.add(VersionRange.parse(entry));
        }
        if (minecraft.isEmpty()) {
            throw new IllegalArgumentException("Mod " + id + " doesn't say which Minecraft versions it's for (\"minecraft\")");
        }

        Map<String, VersionRange> depends = new LinkedHashMap<>();
        String forge = "optional";
        for (Map.Entry<String, JsonElement> dependency : object(root, "depends").entrySet()) {
            String value = asString(dependency.getValue(), "depends." + dependency.getKey());
            if (dependency.getKey().equals("forge")) {
                if (!List.of("required", "optional", "incompatible").contains(value)) {
                    throw new IllegalArgumentException("Mod " + id + ": depends.forge must be required, optional or incompatible, not " + value);
                }
                forge = value;
            } else {
                depends.put(dependency.getKey(), VersionRange.parse(value));
            }
        }

        Map<String, VersionPart> versions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(root, "versions").entrySet()) {
            String key = "versions." + entry.getKey();
            Version mc;
            try {
                mc = Version.parse(entry.getKey());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Mod " + id + ": \"versions\" keys are exact Minecraft versions, not \""
                        + entry.getKey() + "\"", e);
            }
            if (minecraft.stream().noneMatch(range -> range.test(mc))) {
                throw new IllegalArgumentException("Mod " + id + " has a \"" + key + "\" part, but \"minecraft\" doesn't list "
                        + entry.getKey());
            }
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("\"" + key + "\" must be an object");
            }
            JsonObject part = entry.getValue().getAsJsonObject();
            for (Map.Entry<String, JsonElement> field : part.entrySet()) {
                if (!VERSION_PART_KEYS.contains(field.getKey())) {
                    throw new IllegalArgumentException("Mod " + id + ": \"" + key + "\" can't set \"" + field.getKey()
                            + "\" (it may set " + VERSION_PART_KEYS + ")");
                }
            }
            versions.put(entry.getKey(), new VersionPart(entrypoints(part, id, key + ".entrypoints"),
                    strings(part.get("hooks"), key + ".hooks"), strings(part.get("provides"), key + ".provides"),
                    strings(part.get("mixins"), key + ".mixins"), string(part, "accessWidener", null)));
        }

        return new ModDescriptor(id, version, string(root, "name", id), string(root, "description", ""),
                strings(root.get("authors"), "authors"), minecraft, depends, forge, entrypoints(root, id, "entrypoints"),
                strings(root.get("hooks"), "hooks"), strings(root.get("provides"), "provides"),
                strings(root.get("mixins"), "mixins"), string(root, "accessWidener", null),
                strings(root.get("transformers"), "transformers"), integer(root, "javaVersion", 0), versions);
    }

    /** {@code entrypoints} of {@code object}: known kinds only, each a list of class names. */
    private static Map<String, List<String>> entrypoints(JsonObject object, String id, String key) {
        Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> kind : object(object, "entrypoints").entrySet()) {
            if (!ENTRYPOINTS.contains(kind.getKey())) {
                throw new IllegalArgumentException("Mod " + id + " has an unknown kind of entrypoint \"" + kind.getKey()
                        + "\" in \"" + key + "\" (known: " + ENTRYPOINTS + ")");
            }
            entrypoints.put(kind.getKey(), strings(kind.getValue(), key + "." + kind.getKey()));
        }
        return entrypoints;
    }

    public List<String> entrypoints(String kind) {
        return entrypoints.getOrDefault(kind, List.of());
    }

    private static String string(JsonObject root, String key, String fallback) {
        JsonElement element = root.get(key);
        return element == null || element.isJsonNull() ? fallback : asString(element, key);
    }

    private static String asString(JsonElement element, String key) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a string");
        }
        return element.getAsString();
    }

    private static int integer(JsonObject root, String key, int fallback) {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a number");
        }
        return element.getAsInt();
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return new JsonObject();
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("\"" + key + "\" must be an object");
        }
        return element.getAsJsonObject();
    }

    /** A string or an array of strings, as a list; missing or null is empty. */
    private static List<String> strings(JsonElement element, String key) {
        List<String> values = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return values;
        }
        if (element.isJsonPrimitive()) {
            values.add(asString(element, key));
            return values;
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a string or a list of strings");
        }
        JsonArray array = element.getAsJsonArray();
        for (JsonElement value : array) {
            values.add(asString(value, key));
        }
        return values;
    }
}
