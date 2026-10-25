package dev.lich.mixin;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapper;

/**
 * Mixin's LaunchWrapper service, which caps the compatibility level at Java 8 because LaunchWrapper used to
 * mean Java 8. Under Lich the game runs on Java 25 and Lich mods are Java 25 classes, so the cap only
 * produced a warning per config; this service lifts it. Boot selects it with {@code -Dmixin.service}.
 */
public final class LichMixinService extends MixinServiceLaunchWrapper {
    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return null;
    }
}
