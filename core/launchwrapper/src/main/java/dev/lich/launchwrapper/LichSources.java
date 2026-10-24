package dev.lich.launchwrapper;

import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class path entries that {@code LaunchClassLoader} still loads from but leaves out of
 * {@code getSources()}: Lich's own jars and the ASM 9 that replaced Forge's asm-all. FML's mod
 * discovery scans every source with ASM-5-API visitors, which can't read Java 9+ class files (nest
 * members, module-info), and none of these jars hold Forge mods anyway.
 */
public final class LichSources {
    private static final Set<String> HIDDEN = ConcurrentHashMap.newKeySet();

    private LichSources() {
    }

    public static void hide(URL source) {
        HIDDEN.add(source.toExternalForm());
    }

    public static boolean isHidden(URL source) {
        return !HIDDEN.isEmpty() && HIDDEN.contains(source.toExternalForm());
    }

    public static boolean anyHidden() {
        return !HIDDEN.isEmpty();
    }
}
