package dev.lich.compat.v1_7_10.forge;

import dev.lich.transform.CompatTransformer;

/** Lich's rules for FML 1.7.10's own classes. */
public final class Forge1710 {
    private static final String FILE_LIST_HELPER = "cpw/mods/fml/relauncher/FileListHelper";

    private Forge1710() {
    }

    /**
     * Keeps Lich mods out of FML's coremod and mod discovery: both list the mods folder through
     * {@code FileListHelper.sortFileList(File[])}, whose argument goes through
     * {@code LichSources.withoutLichMods}. Only launches with Lich mods need it.
     */
    public static CompatTransformer.Builder hideLichMods(CompatTransformer.Builder builder) {
        return builder.filterFirstArgument(FILE_LIST_HELPER, "sortFileList", "([Ljava/io/File;)[Ljava/io/File;",
                "dev/lich/launchwrapper/LichSources", "withoutLichMods");
    }
}
