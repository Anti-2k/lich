package dev.lich.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A modpack the harness tests with, pinned to one release in {@code packs.json}, so every run gets exactly the same
 * mods. Its {@link Source} says where it comes from. Its optional {@code setup} answers first-launch prompts the way
 * a player would, once, in the pack's game directory: {@code unzip} unpacks archives the pack ships into it,
 * {@code copy} copies directories (target: source; Blightfall's world-creation screen, through Lockdown, copies its
 * map template {@code flans/} into {@code saves/}), and {@code write} writes files. Without that, a prompt comes up on every run (a fresh copy each time) on the desktop of
 * whoever is using it: SkyBlock Enhanced's PackCore asks which of its config presets to unpack. {@code worldType} is
 * the world type the pack's create-world screen starts on (The Lost Era's DefaultWorldGenerator makes it RTG), which
 * the world driver uses too; {@code default} without one. {@code save} names a world the pack ships in {@code saves/}
 * (Blightfall is played on its own map): the world driver opens it instead of creating one.
 */
record Pack(String id, String name, String mc, String forge, Source source, int memoryMb,
            List<String> unzip, Map<String, String> copy, Map<String, String> write, String worldType, String save) {
    /**
     * Where a pack comes from, and the pin that fixes its contents. Every file is checked against the hash its
     * format gives.
     * <ul>
     *     <li>{@code mrpack}: a Modrinth {@code .mrpack} ({@code url}, {@code sha512}).</li>
     *     <li>{@code solder}: a build of a Technic pack on a Solder API ({@code api}, {@code slug}, {@code build}),
     *     whose mods are zips (MD5) unpacked into the game directory in order.</li>
     *     <li>{@code ftb}: a version of an FTB pack ({@code pack}, {@code version} ids), files with SHA-1s.</li>
     * </ul>
     */
    record Source(String type, Map<String, String> values) {
        String get(String key) {
            String value = values.get(key);
            if (value == null) {
                throw new IllegalStateException("A " + type + " pack source needs " + key);
            }
            return value;
        }

        /** Where the pack comes from, for people. */
        String describe() {
            return switch (type) {
                case "mrpack" -> get("url");
                case "solder" -> "Technic pack " + get("slug") + " build " + get("build") + " (" + get("api") + ")";
                case "ftb" -> "FTB pack " + get("pack") + " version " + get("version");
                default -> type;
            };
        }
    }

    static Map<String, Pack> all() {
        try (InputStream in = Pack.class.getResourceAsStream("packs.json")) {
            if (in == null) {
                throw new IllegalStateException("packs.json is missing from the harness jar");
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Pack> packs = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                packs.put(entry.getKey(), parse(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
            return packs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Pack parse(String id, JsonObject p) {
        List<String> unzip = new ArrayList<>();
        Map<String, String> copy = new LinkedHashMap<>();
        Map<String, String> write = new LinkedHashMap<>();
        if (p.has("setup")) {
            JsonObject setup = p.getAsJsonObject("setup");
            if (setup.has("unzip")) {
                setup.getAsJsonArray("unzip").forEach(e -> unzip.add(e.getAsString()));
            }
            if (setup.has("copy")) {
                setup.getAsJsonObject("copy").entrySet().forEach(e -> copy.put(e.getKey(), e.getValue().getAsString()));
            }
            if (setup.has("write")) {
                setup.getAsJsonObject("write").entrySet().forEach(e -> write.put(e.getKey(), e.getValue().getAsString()));
            }
        }
        JsonObject s = p.getAsJsonObject("source");
        Map<String, String> values = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : s.entrySet()) {
            if (!e.getKey().equals("type")) {
                values.put(e.getKey(), e.getValue().getAsString());
            }
        }
        Source source = new Source(s.get("type").getAsString(), Map.copyOf(values));
        return new Pack(id, p.get("name").getAsString(), p.get("mc").getAsString(), p.get("forge").getAsString(), source,
                p.get("memoryMb").getAsInt(), List.copyOf(unzip), Map.copyOf(copy), Map.copyOf(write),
                p.has("worldType") ? p.get("worldType").getAsString() : "default",
                p.has("save") ? p.get("save").getAsString() : null);
    }

    /** Identifies the pinned pack and its setup, so a template built for an older pin or setup is rebuilt. */
    String fingerprint() {
        return source.type() + " " + new TreeMap<>(source.values()) + " "
                + Integer.toHexString(Objects.hash(unzip, new TreeMap<>(copy), new TreeMap<>(write)));
    }

    static Pack get(String id) {
        Pack pack = all().get(id);
        if (pack == null) {
            throw new IllegalArgumentException("Unknown pack " + id + "; known: " + String.join(", ", all().keySet()));
        }
        return pack;
    }
}
