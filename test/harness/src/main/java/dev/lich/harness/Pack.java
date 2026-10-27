package dev.lich.harness;

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

/**
 * A modpack the harness tests with, pinned to one release in {@code packs.json}: a Modrinth {@code .mrpack} and its
 * SHA-512, so every run (and the user's Prism instances) gets exactly the same mods. Its optional {@code setup}
 * answers first-launch prompts the way a player would, once, in the pack's game directory: {@code unzip} unpacks
 * archives the pack ships into it and {@code write} writes files. Without that, a prompt comes up on every run (a
 * fresh copy each time) on the desktop of whoever is using it: SkyBlock Enhanced's PackCore asks which of its config
 * presets to unpack.
 */
record Pack(String id, String name, String mc, String forge, String url, String sha512, int memoryMb,
            List<String> unzip, Map<String, String> write) {
    static Map<String, Pack> all() {
        try (InputStream in = Pack.class.getResourceAsStream("packs.json")) {
            if (in == null) {
                throw new IllegalStateException("packs.json is missing from the harness jar");
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Pack> packs = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                JsonObject p = entry.getValue().getAsJsonObject();
                List<String> unzip = new ArrayList<>();
                Map<String, String> write = new LinkedHashMap<>();
                if (p.has("setup")) {
                    JsonObject setup = p.getAsJsonObject("setup");
                    if (setup.has("unzip")) {
                        setup.getAsJsonArray("unzip").forEach(e -> unzip.add(e.getAsString()));
                    }
                    if (setup.has("write")) {
                        setup.getAsJsonObject("write").entrySet().forEach(e -> write.put(e.getKey(), e.getValue().getAsString()));
                    }
                }
                packs.put(entry.getKey(), new Pack(entry.getKey(), p.get("name").getAsString(), p.get("mc").getAsString(),
                        p.get("forge").getAsString(), p.get("url").getAsString(), p.get("sha512").getAsString(),
                        p.get("memoryMb").getAsInt(), List.copyOf(unzip), Map.copyOf(write)));
            }
            return packs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Identifies the pinned pack and its setup, so a template built for an older setup is rebuilt. */
    String fingerprint() {
        return sha512 + " " + Integer.toHexString(java.util.Objects.hash(unzip, new java.util.TreeMap<>(write)));
    }

    static Pack get(String id) {
        Pack pack = all().get(id);
        if (pack == null) {
            throw new IllegalArgumentException("Unknown pack " + id + "; known: " + String.join(", ", all().keySet()));
        }
        return pack;
    }
}
