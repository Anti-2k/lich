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
 * map template {@code flans/} into {@code saves/}), {@code write} writes files, and {@code remove} deletes what the
 * pack ships but can't be tested with (a relauncher that leaves Lich for another loader), and {@code download} fetches
 * a file the pack should have shipped ({@code url}, {@code sha512}: Glitch's Modern Warfare ships a second copy of
 * ModularWarfare under MCglTF's name, and not MCglTF). Without that, a prompt comes up on every run (a fresh copy each time) on the desktop of
 * whoever is using it: SkyBlock Enhanced's PackCore asks which of its config presets to unpack. {@code worldType} is
 * the world type the pack's create-world screen starts on (The Lost Era's DefaultWorldGenerator makes it RTG), which
 * the world driver uses too; {@code default} without one. {@code save} names a world the pack ships in {@code saves/}
 * (Blightfall is played on its own map): the world driver opens it instead of creating one.
 * <p>
 * For the harness's runs: {@code tags} put the pack in suites ({@code @quick}); {@code skip} says why the pack can't
 * run unattended, which leaves it out of every suite (it still runs when named); {@code noServer} says why the pack
 * can't play on the vanilla test server (mods that change vanilla packets, such as RoughlyEnoughIDs), which leaves the
 * {@code server} step out of its scenario; {@code gallery} turns on the step that
 * places every block, and {@code galleryExclude} names blocks that step must leave out (ones that crash the game on
 * Forge too).
 */
record Pack(String id, String name, String mc, String forge, Source source, int memoryMb,
            List<String> unzip, Map<String, String> copy, Map<String, String> write, List<String> remove,
            Map<String, Download> download,
            String worldType, String save, List<String> tags, String skip, String noServer, boolean gallery,
            List<String> galleryExclude) {
    /** A file {@code setup} downloads into the pack, checked against its SHA-512. */
    record Download(String url, String sha512) {
    }

    /**
     * Where a pack comes from, and the pin that fixes its contents. Every file is checked against the hash its
     * format gives.
     * <ul>
     *     <li>{@code mrpack}: a Modrinth {@code .mrpack} ({@code url}, {@code sha512}).</li>
     *     <li>{@code solder}: a build of a Technic pack on a Solder API ({@code api}, {@code slug}, {@code build}),
     *     whose mods are zips (MD5) unpacked into the game directory in order.</li>
     *     <li>{@code ftb}: a version of an FTB pack ({@code pack}, {@code version} ids), files with SHA-1s.</li>
     *     <li>{@code zip}: a Technic pack that is one client zip, not on Solder ({@code url}, {@code sha512}, which
     *     the pin adds: Technic gives none), laid out like a Solder mod zip.</li>
     *     <li>{@code atlauncher}: a version of an ATLauncher pack ({@code pack} safe name, {@code version}), mods with
     *     MD5s from its {@code Configs.json}.</li>
     * </ul>
     * Any source may also give {@code packForge}: the Forge the pack itself names, when it isn't the one the dev
     * profile runs ({@code forge}). The pack runs on the newer build, as most 1.7.10 packs do in practice.
     */
    record Source(String type, Map<String, String> values) {
        String get(String key) {
            String value = values.get(key);
            if (value == null) {
                throw new IllegalStateException("A " + type + " pack source needs " + key);
            }
            return value;
        }

        /** The Forge the pack names for itself: {@code packForge} if given, else the one that runs. */
        String packForge(String runForge) {
            return values.getOrDefault("packForge", runForge);
        }

        /** Where the pack comes from, for people. */
        String describe() {
            return switch (type) {
                case "mrpack" -> get("url");
                case "solder" -> "Technic pack " + get("slug") + " build " + get("build") + " (" + get("api") + ")";
                case "ftb" -> (values.getOrDefault("namespace", "ftb").equals("curseforge") ? "CurseForge pack " : "FTB pack ")
                        + get("pack") + " version " + get("version");
                case "zip" -> get("url");
                case "atlauncher" -> "ATLauncher pack " + get("pack") + " version " + get("version");
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
        List<String> remove = new ArrayList<>();
        Map<String, Download> download = new LinkedHashMap<>();
        if (p.has("setup")) {
            JsonObject setup = p.getAsJsonObject("setup");
            if (setup.has("remove")) {
                setup.getAsJsonArray("remove").forEach(e -> remove.add(e.getAsString()));
            }
            if (setup.has("download")) {
                for (Map.Entry<String, JsonElement> e : setup.getAsJsonObject("download").entrySet()) {
                    JsonObject file = e.getValue().getAsJsonObject();
                    download.put(e.getKey(), new Download(file.get("url").getAsString(), file.get("sha512").getAsString()));
                }
            }
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
                List.copyOf(remove), Map.copyOf(download),
                p.has("worldType") ? p.get("worldType").getAsString() : "screen",
                p.has("save") ? p.get("save").getAsString() : null,
                strings(p, "tags"), p.has("skip") ? p.get("skip").getAsString() : null,
                p.has("noServer") ? p.get("noServer").getAsString() : null,
                p.has("gallery") && p.get("gallery").getAsBoolean(), strings(p, "galleryExclude"));
    }

    private static List<String> strings(JsonObject p, String key) {
        List<String> list = new ArrayList<>();
        if (p.has(key)) {
            p.getAsJsonArray(key).forEach(e -> list.add(e.getAsString()));
        }
        return List.copyOf(list);
    }

    /**
     * The packs a selection names: comma-separated pack ids, {@code all}, {@code @<tag>} or {@code @<mc version>}.
     * Packs with a {@code skip} reason only come when named by id.
     */
    static List<Pack> select(String selection) {
        Map<String, Pack> all = all();
        List<Pack> chosen = new ArrayList<>();
        for (String part : selection.split(",")) {
            String name = part.strip();
            if (name.equals("all") || name.startsWith("@")) {
                String tag = name.equals("all") ? null : name.substring(1);
                for (Pack pack : all.values()) {
                    if (pack.skip() == null && (tag == null || pack.tags().contains(tag) || pack.mc().equals(tag))
                            && !chosen.contains(pack)) {
                        chosen.add(pack);
                    }
                }
            } else if (!name.isEmpty()) {
                Pack pack = get(name);
                if (!chosen.contains(pack)) {
                    chosen.add(pack);
                }
            }
        }
        if (chosen.isEmpty()) {
            throw new IllegalArgumentException("No packs in " + selection);
        }
        return chosen;
    }

    /** Identifies the pinned pack and its setup, so a template built for an older pin or setup is rebuilt. */
    String fingerprint() {
        return source.type() + " " + new TreeMap<>(source.values()) + " "
                + Integer.toHexString(Objects.hash(unzip, new TreeMap<>(copy), new TreeMap<>(write)))
                + (remove.isEmpty() ? "" : " remove=" + remove)
                + (download.isEmpty() ? "" : " download=" + new TreeMap<>(download));
    }

    static Pack get(String id) {
        Pack pack = all().get(id);
        if (pack == null) {
            throw new IllegalArgumentException("Unknown pack " + id + "; known: " + String.join(", ", all().keySet()));
        }
        return pack;
    }
}
