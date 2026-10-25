package dev.lich.launchwrapper;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What FML's mod discovery mustn't see. Class path entries that {@code LaunchClassLoader} still loads from
 * but leaves out of {@code getSources()}: Lich's own jars and the ASM 9 that replaced Forge's asm-all
 * (FML's mod discovery scans every source with ASM-5-API visitors, which can't read Java 9+ class files,
 * and none of these jars hold Forge mods anyway), and Lich mods. Lich mods sit in {@code mods/} too, so
 * FML's listing of that folder leaves them out as well ({@link #withoutLichMods}): a jar that is both a
 * Lich mod and a Forge mod is Lich's under Lich.
 */
public final class LichSources {
    private static final Set<String> HIDDEN = ConcurrentHashMap.newKeySet();
    private static final Set<File> HIDDEN_MOD_FILES = ConcurrentHashMap.newKeySet();

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

    /** Keeps a Lich mod's file out of FML's listings of the mods folder. */
    public static void hideModFile(File file) {
        HIDDEN_MOD_FILES.add(file.getAbsoluteFile());
    }

    /**
     * {@code files} without Lich mods. The compat pass passes FML's mod folder listings through this
     * ({@code FileListHelper.sortFileList(File[])}, which both its coremod and its mod discovery use).
     */
    public static File[] withoutLichMods(File[] files) {
        if (files == null || HIDDEN_MOD_FILES.isEmpty()) {
            return files;
        }
        List<File> kept = new ArrayList<>(files.length);
        for (File file : files) {
            if (file == null || !HIDDEN_MOD_FILES.contains(file.getAbsoluteFile())) {
                kept.add(file);
            }
        }
        return kept.size() == files.length ? files : kept.toArray(new File[0]);
    }
}
