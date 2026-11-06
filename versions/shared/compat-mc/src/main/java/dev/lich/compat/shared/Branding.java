package dev.lich.compat.shared;

import dev.lich.loader.LichLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Lich's lines in the game's branding. Called from the classes {@link BrandingPatches} changes. Under Forge, FML's
 * branding list gets {@code Lich 0.3.0 with Forge 11.15.1.2318 on Java 25} and a count of the Lich mods after
 * Forge's own lines, before FML's count of the Forge mods. In the vanilla profile the main menu draws the version
 * line, {@code Lich 0.3.0 on Java 25} and the Lich mod count, top to bottom. Only text the player sees: the
 * client brand sent to servers stays {@code vanilla} or {@code fml,forge} (DOCS.md §6.2).
 */
public final class Branding {
    private static final String VERSION = version();
    private static final int JAVA = Runtime.version().feature();

    private Branding() {
    }

    /** {@code Lich 0.3.0 on Java 25}: the line without Forge. */
    public static String line() {
        return "Lich " + VERSION + " on Java " + JAVA;
    }

    /** {@code Lich 0.3.0 with Forge 11.15.1.2318 on Java 25}: the line when Forge runs. */
    public static String line(String forgeVersion) {
        if (forgeVersion == null || forgeVersion.isEmpty()) {
            return line();
        }
        return "Lich " + VERSION + " with Forge " + forgeVersion + " on Java " + JAVA;
    }

    /**
     * FML's extra branding lines, with Lich's line and mod count after them. They follow Forge's own lines, and
     * FML's own mod count comes after them: the menu draws the list bottom up, so that one ends up lowest.
     */
    public static List<String> withLich(List<String> lines, String forgeVersion) {
        List<String> branded = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
        branded.add(line(forgeVersion));
        branded.add(modCountLine());
        return branded;
    }

    /** FML's finished list without its "Powered by Forge 11.15.1.2318" line: Lich's line already says which Forge. */
    public static List<String> withoutPoweredBy(List<String> lines) {
        List<String> kept = new ArrayList<>(lines);
        kept.removeIf(line -> line.startsWith("Powered by Forge"));
        return kept;
    }

    /** {@code 2 mods loaded, 2 mods active}, for the Lich mods; FML's identical line counts the Forge mods. */
    public static String modCountLine() {
        int count = LichLoader.mods().size();
        String mods = count == 1 ? " mod" : " mods";
        return count + mods + " loaded, " + count + mods + " active";
    }

    /** The vanilla main menu's extra lines, top to bottom: 1 is Lich's line, 2 the Lich mod count. */
    public static String menuLine(int index) {
        return index == 1 ? line() : modCountLine();
    }

    private static String version() {
        String version = Branding.class.getPackage().getImplementationVersion();
        return version == null ? "(dev)" : version;
    }
}
