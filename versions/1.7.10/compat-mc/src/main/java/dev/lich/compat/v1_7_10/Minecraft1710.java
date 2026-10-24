package dev.lich.compat.v1_7_10;

import dev.lich.compat.shared.SoundLoaderRace;
import dev.lich.transform.CompatTransformer;

/** Lich's fixes to Minecraft 1.7.10, by SRG class name. */
public final class Minecraft1710 {
    private Minecraft1710() {
    }

    /** Patches for classes that FML has already patched and deobfuscated to SRG names. */
    public static CompatTransformer.Builder registerSrgPatches(CompatTransformer.Builder builder) {
        return builder.patch(SoundLoaderRace.CLASS, new SoundLoaderRace());
    }
}
