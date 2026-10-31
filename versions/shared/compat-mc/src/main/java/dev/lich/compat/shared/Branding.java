package dev.lich.compat.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * Lich's line in the game's branding ("Lich 0.3.0 on Java 25"). Called from the classes {@link BrandingPatches}
 * changes: under Forge it goes into FML's branding list (main menu, 1.7.10's F3 screen, crash reports), and in the
 * vanilla profile it's added to the main menu's version line. Only text the player sees: the client brand sent to
 * servers stays {@code vanilla} or {@code fml,forge} (DOCS.md §6.2).
 */
public final class Branding {
    private static final String LINE = "Lich " + version() + " on Java " + Runtime.version().feature();

    private Branding() {
    }

    public static String line() {
        return LINE;
    }

    /** FML's extra branding lines, with Lich's after them (they follow "Powered by Forge ..."). */
    public static List<String> withLich(List<String> lines) {
        List<String> branded = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
        branded.add(LINE);
        return branded;
    }

    /** The vanilla main menu's version line: "Minecraft 1.8.9 / Lich 0.3.0 on Java 25". */
    public static String withLich(String minecraft) {
        return minecraft + " / " + LINE;
    }

    private static String version() {
        String version = Branding.class.getPackage().getImplementationVersion();
        return version == null ? "(dev)" : version;
    }
}
