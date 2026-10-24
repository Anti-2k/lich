package dev.lich.compat.v1_8_9;

import dev.lich.compat.shared.SoundLoaderRace;
import dev.lich.transform.CompatTransformer;

/** Lich's fixes to Minecraft 1.8.9, by SRG class name. */
public final class Minecraft189 {
    private Minecraft189() {
    }

    /** Patches for classes that FML has already patched and deobfuscated to SRG names. */
    public static CompatTransformer.Builder registerSrgPatches(CompatTransformer.Builder builder) {
        return builder.patch(SoundLoaderRace.CLASS, new SoundLoaderRace());
    }
}
