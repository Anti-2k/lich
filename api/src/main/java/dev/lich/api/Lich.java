package dev.lich.api;

import dev.lich.loader.LichLoader;
import dev.lich.loader.LichMod;

import java.util.List;
import java.util.Optional;

/** What a Lich mod can ask about the game it runs in. */
public final class Lich {
    private Lich() {
    }

    /** Lich's version, or {@code "dev"} for a development build. */
    public static String version() {
        return LichLoader.lichVersion() == null ? "dev" : LichLoader.lichVersion().toString();
    }

    /** {@code "1.7.10"}, {@code "1.8.9"} or {@code "1.12.2"}. */
    public static String minecraftVersion() {
        return LichLoader.minecraftVersion();
    }

    /**
     * Whether Forge runs too. A mod declaring {@code "forge": "optional"} may use Forge's API, but only
     * behind this check: without Forge its classes don't exist.
     */
    public static boolean isForgePresent() {
        return LichLoader.isForgePresent();
    }

    /** The loaded Lich mods, in load order (a mod after the mods it depends on). */
    public static List<ModInfo> mods() {
        return LichLoader.mods().stream().map(ModInfo::of).toList();
    }

    public static Optional<ModInfo> mod(String id) {
        return LichLoader.mods().stream().filter(mod -> mod.id().equals(id)).findFirst().map(ModInfo::of);
    }

    public static boolean isModLoaded(String id) {
        return mod(id).isPresent();
    }

    /** Facts about a loaded Lich mod, from its {@code lich.mod.json}. */
    public record ModInfo(String id, String version, String name, String description, List<String> authors) {
        static ModInfo of(LichMod mod) {
            var descriptor = mod.descriptor();
            return new ModInfo(descriptor.id(), descriptor.version().toString(), descriptor.name(),
                    descriptor.description(), descriptor.authors());
        }
    }
}
