package dev.lich.loader;

import dev.lich.launchwrapper.LichTransformers;
import dev.lich.mixin.LichMixins;
import dev.lich.remap.core.AccessWidener;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.List;

/**
 * Sets up the Lich mods once the class loader exists. Boot puts it right after Mixin's tweaker, so
 * Mixin is up when this registers Lich's hook config and the mods' configs, and both are in place
 * before the first game class loads. It also adds the mods' access wideners to Lich's last transformers.
 * Only launches with Lich mods have it.
 */
public final class LichTweaker implements ITweaker {
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        LichMixins.start(LichLoader.mixinConfigs());
        AccessWidener widener = LichLoader.accessWidener();
        if (widener != null) {
            LichTransformers.addLast(new AccessWidenerTransformer(widener));
        }
    }

    @Override
    public String getLaunchTarget() {
        // Never the primary tweaker: Lich's vanilla tweaker or FML's picks the launch target.
        throw new UnsupportedOperationException("LichTweaker is never the primary tweaker");
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
