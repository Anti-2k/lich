package dev.lich.compat.v1_12_2;

import dev.lich.compat.shared.BrandingPatches;
import dev.lich.compat.shared.ShutdownRace;
import dev.lich.compat.shared.SoundLoaderRace;
import dev.lich.transform.CompatTransformer;

/** Lich's fixes to Minecraft 1.12.2, by SRG class name. */
public final class Minecraft1122 {
    private Minecraft1122() {
    }

    /**
     * Patches for classes that FML (or, without {@code forge}, Lich's remapper) has already patched and deobfuscated
     * to SRG names, plus Lich's line in the game's branding.
     */
    public static CompatTransformer.Builder registerSrgPatches(CompatTransformer.Builder builder, boolean forge) {
        builder.patch(SoundLoaderRace.CLASS, new SoundLoaderRace())
                .patch(ShutdownRace.CLASS, new ShutdownRace());
        return BrandingPatches.register(builder, "1.12.2", forge);
    }
}
