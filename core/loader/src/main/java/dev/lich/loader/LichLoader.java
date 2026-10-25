package dev.lich.loader;

import dev.lich.remap.core.AccessWidener;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lich's mod loader: which Lich mods are installed, in load order, and what they asked for (hooks, Mixin
 * configs, access wideners). Boot calls {@link #load} once, before the game's classes exist; everything
 * else reads the result. It lives in the system class loader (a class loader exclusion), so boot, the
 * launch code and the API in {@code LaunchClassLoader} all see this one copy.
 *
 * <p>With no Lich mods installed, nothing here changes the game: no Mixin, no hooks, no wideners.
 */
public final class LichLoader {
    /** The Mixin config of Lich's own hooks, in the per-version API jar. */
    public static final String HOOKS_MIXIN_CONFIG = "lich.hooks.mixins.json";
    /** Hooks every Lich mod gets: its entrypoints run from them. */
    public static final String LIFECYCLE_HOOK = "lifecycle";

    private static volatile State state = new State(List.of(), Set.of(), null, false, null, null);

    private record State(List<LichMod> mods, Set<String> hooks, String minecraft, boolean forge, Version lich,
                         AccessWidener accessWidener) {
    }

    private LichLoader() {
    }

    /**
     * Finds and checks the Lich mods for this launch. {@code lichVersion} is {@code null} for a
     * development build. Throws {@link ModLoadingException} if they can't load.
     */
    public static List<LichMod> load(Path gameDir, String minecraft, boolean forge, String lichVersion) {
        Version lich = lichVersion == null ? null : Version.parse(lichVersion);
        List<LichMod> found = ModDiscovery.discover(gameDir, minecraft, System.getProperty("lich.devMods"));
        List<LichMod> mods = found.isEmpty() ? List.of()
                : ModResolver.resolve(found, new ModResolver.Environment(minecraft, forge, lich, Runtime.version().feature()));

        Set<String> hooks = new LinkedHashSet<>();
        if (!mods.isEmpty()) {
            hooks.add(LIFECYCLE_HOOK);
        }
        for (LichMod mod : mods) {
            hooks.addAll(mod.descriptor().hooks());
            hooks.addAll(mod.descriptor().provides());
        }
        state = new State(List.copyOf(mods), Set.copyOf(hooks), minecraft, forge, lich, accessWidener(mods));
        return state.mods;
    }

    /** The access wideners of every mod, merged; {@code null} if none has one. */
    private static AccessWidener accessWidener(List<LichMod> mods) {
        List<AccessWidener.Entry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (LichMod mod : mods) {
            String path = mod.descriptor().accessWidener();
            if (path == null) {
                continue;
            }
            try {
                String text = read(mod.source(), path);
                if (text == null) {
                    problems.add(mod + ": its access widener " + path + " is missing");
                    continue;
                }
                AccessWidener widener = AccessWidener.parse(text);
                if (!widener.namespace().equals("srg")) {
                    problems.add(mod + ": its access widener uses \"" + widener.namespace()
                            + "\" names; a built mod's must use \"srg\" (the dev.lich.mod plugin rewrites it)");
                    continue;
                }
                entries.addAll(widener.entries());
            } catch (IOException e) {
                problems.add(mod + ": bad access widener " + path + ": " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new ModLoadingException(problems);
        }
        return entries.isEmpty() ? null : new AccessWidener("srg", entries);
    }

    private static String read(Path source, String path) throws IOException {
        if (Files.isDirectory(source)) {
            Path file = source.resolve(path);
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** The loaded Lich mods, dependencies first. */
    public static List<LichMod> mods() {
        return state.mods;
    }

    /** Whether some loaded mod asked for hook {@code id}, so Lich wove it into the game. */
    public static boolean isHookEnabled(String id) {
        return state.hooks.contains(id);
    }

    /** The hook ids loaded mods asked for. */
    public static Set<String> hooks() {
        return state.hooks;
    }

    /** The mod whose jar (or directory) is {@code codeSource}, or {@code null}: who a class belongs to. */
    public static LichMod modOf(URL codeSource) {
        if (codeSource == null) {
            return null;
        }
        // LaunchClassLoader's code sources are the class's own URL, jar:file:/...!/a/B.class for a jar.
        String location = codeSource.toExternalForm();
        if (location.startsWith("jar:")) {
            int separator = location.indexOf("!/");
            location = location.substring("jar:".length(), separator < 0 ? location.length() : separator);
        }
        for (LichMod mod : state.mods) {
            String source = mod.url().toExternalForm();
            if (location.equals(source) || (source.endsWith("/") && location.startsWith(source))) {
                return mod;
            }
        }
        return null;
    }

    public static boolean isForgePresent() {
        return state.forge;
    }

    public static String minecraftVersion() {
        return state.minecraft;
    }

    /** Lich's version, or {@code null} for a development build. */
    public static Version lichVersion() {
        return state.lich;
    }

    /** The merged access widener of the loaded mods (SRG names), or {@code null}. */
    public static AccessWidener accessWidener() {
        return state.accessWidener;
    }

    /** Lich's hook config, then every mod's Mixin configs, in load order. */
    public static List<String> mixinConfigs() {
        List<String> configs = new ArrayList<>();
        if (!state.mods.isEmpty()) {
            configs.add(HOOKS_MIXIN_CONFIG);
        }
        for (LichMod mod : state.mods) {
            configs.addAll(mod.descriptor().mixins());
        }
        return configs;
    }
}
