package dev.lich.launchwrapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Class name prefixes that {@code LaunchClassLoader} always leaves to its parent, kept outside its
 * {@code classLoaderExceptions} field, which mods edit by reflection. OneConfig removes LaunchWrapper's own
 * {@code org.lwjgl.} exclusion; with the game on LWJGL 3 that would load a second LWJGL in
 * LaunchClassLoader, whose natives can only be bound to one class loader.
 */
public final class LichExclusions {
    private static final List<String> PREFIXES = new CopyOnWriteArrayList<>();

    private LichExclusions() {
    }

    public static void add(String prefix) {
        if (!PREFIXES.contains(prefix)) {
            PREFIXES.add(prefix);
        }
    }

    public static boolean matches(String className) {
        for (String prefix : PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
