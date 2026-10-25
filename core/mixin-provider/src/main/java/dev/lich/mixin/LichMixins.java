package dev.lich.mixin;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.util.List;

/**
 * Starts Lich's Mixin for the Lich mods, after Mixin's own tweaker has bootstrapped it: MixinExtras'
 * injectors, the client side (Mixin can't tell it without FML), and the configs, Lich's hooks first.
 * Mixin's classes come from the jars boot put on the class path for this launch only.
 */
public final class LichMixins {
    private LichMixins() {
    }

    public static void start(List<String> configs) {
        MixinExtrasBootstrap.init();
        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        if (environment.getSide() == MixinEnvironment.Side.UNKNOWN) {
            environment.setSide(MixinEnvironment.Side.CLIENT);
        }
        Mixins.addConfigurations(configs.toArray(new String[0]));
    }
}
