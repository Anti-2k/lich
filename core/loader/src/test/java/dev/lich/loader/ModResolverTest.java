package dev.lich.loader;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link ModResolver} against the Minecraft whitelist: which mods fit, and what they are on this version. */
class ModResolverTest {
    private static LichMod mod(String id, String extra) {
        String json = "{\"schema\": 1, \"id\": \"" + id + "\", \"version\": \"1.0.0\", " + extra + "}";
        return new LichMod(ModDescriptor.parse(json), Path.of(id + ".jar"));
    }

    private static ModResolver.Environment on(String minecraft) {
        return new ModResolver.Environment(minecraft, false, Version.parse("0.4.0"), 25);
    }

    @Test
    void aMultiVersionJarLoadsOnEveryListedVersion() {
        LichMod both = mod("both", "\"minecraft\": [\"1.7.10\", \"1.8.9\"]");
        assertEquals(1, ModResolver.resolve(List.of(both), on("1.7.10")).size());
        assertEquals(1, ModResolver.resolve(List.of(both), on("1.8.9")).size());
    }

    @Test
    void anUnlistedVersionIsALoadingError() {
        LichMod only = mod("only", "\"minecraft\": \"1.8.9\"");
        ModLoadingException e = assertThrows(ModLoadingException.class,
                () -> ModResolver.resolve(List.of(only), on("1.7.10")));
        assertEquals(List.of("only (only.jar) is for Minecraft 1.8.9, not 1.7.10"), e.problems());
    }

    @Test
    void resolvedModsCarryTheRunningVersionsParts() {
        LichMod mod = mod("parts", """
                "minecraft": ["1.7.10", "1.8.9"],
                "mixins": ["shared.json"],
                "versions": {"1.7.10": {"mixins": ["old.json"], "provides": ["render.font"]}}""");
        LichMod resolved = ModResolver.resolve(List.of(mod), on("1.7.10")).getFirst();
        assertEquals(List.of("shared.json", "old.json"), resolved.descriptor().mixins());
        assertEquals(List.of("render.font"), resolved.descriptor().provides());
        assertEquals(List.of("shared.json"), ModResolver.resolve(List.of(mod), on("1.8.9")).getFirst().descriptor().mixins());
    }

    @Test
    void versionOnlyProvidesStillConflict() {
        LichMod first = mod("first", "\"minecraft\": [\"1.7.10\", \"1.8.9\"], \"versions\": {\"1.7.10\": {\"provides\": \"render.font\"}}");
        LichMod second = mod("second", "\"minecraft\": \"1.7.10\", \"provides\": [\"render.font\"]");
        assertThrows(ModLoadingException.class, () -> ModResolver.resolve(List.of(first, second), on("1.7.10")));
        // On 1.8.9 only the first mod is for this version, and it doesn't replace the font renderer there.
        assertEquals(1, ModResolver.resolve(List.of(first), on("1.8.9")).size());
    }
}
