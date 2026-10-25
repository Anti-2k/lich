package dev.lich.launchwrapper;

import net.minecraft.launchwrapper.LaunchClassLoader;

/** Callbacks into Lich from {@link LaunchCore}, at points that LaunchWrapper's own API has no hook for. */
public interface LaunchHooks {
    LaunchHooks NONE = new LaunchHooks() {
    };

    /** The class loader exists and is the context class loader; no tweaker has been loaded yet. */
    default void classLoaderCreated(LaunchClassLoader classLoader) {
    }

    /** Every tweaker has run and the launch target's {@code main} is about to be called. */
    default void launching(LaunchClassLoader classLoader) {
    }
}
