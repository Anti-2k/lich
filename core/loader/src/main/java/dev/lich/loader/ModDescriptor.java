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
 * A mod's {@code lich.mod.json} (PLAN.md §5.1), checked for shape: every field has the right type, the id
 * and version are well-formed, and unknown kinds of entrypoints or Forge requirements are rejected rather
 * than ignored. Whether the mod fits this game (Minecraft version, dependencies) is {@link ModResolver}'s job.
 *
 * @param depends   other mods' ids (and {@code lich}) → version range; {@code forge} is in {@link #forge}
 * @param forge     {@code required}, {@code optional} (the default) or {@code incompatible}
 * @param javaVersion the lowest Java feature version the mod runs on, 0 if it doesn't say
 */
public record ModDescriptor(String id, Version version, String name, String description, List<String> authors,
                            String minecraft, Map<String, VersionRange> depends, String forge,
                            Map<String, List<String>> entrypoints, List<String> hooks, List<String> provides,
                            List<String> mixins, String accessWidener, List<String> transformers, int javaVersion) {
    public static final String FILE = "lich.mod.json";
    public static final List<String> ENTRYPOINTS = List.of("preLaunch", "init", "client");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");

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
        String minecraft = string(root, "minecraft", null);
        if (minecraft == null) {
            throw new IllegalArgumentException("Mod " + id + " doesn't say which Minecraft version it's for (\"minecraft\")");
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

        Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> kind : object(root, "entrypoints").entrySet()) {
            if (!ENTRYPOINTS.contains(kind.getKey())) {
                throw new IllegalArgumentException("Mod " + id + " has an unknown kind of entrypoint \"" + kind.getKey()
                        + "\" (known: " + ENTRYPOINTS + ")");
            }
            entrypoints.put(kind.getKey(), strings(kind.getValue(), "entrypoints." + kind.getKey()));
        }

        return new ModDescriptor(id, version, string(root, "name", id), string(root, "description", ""),
                strings(root.get("authors"), "authors"), minecraft, depends, forge, entrypoints,
                strings(root.get("hooks"), "hooks"), strings(root.get("provides"), "provides"),
                strings(root.get("mixins"), "mixins"), string(root, "accessWidener", null),
                strings(root.get("transformers"), "transformers"), integer(root, "javaVersion", 0));
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
