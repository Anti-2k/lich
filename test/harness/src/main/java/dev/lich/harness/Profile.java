package dev.lich.harness;

/** The two ways the harness runs a pack: plain Forge on Java 8 (the baseline) and Forge through Lich on Java 25. */
enum Profile {
    FORGE("forge", "Forge (Java 8)"),
    LICH("lich-forge", "Lich (Java 25)");

    /** The devlaunch profile, {@code <id>-<mc>}, and the game directory's name. */
    final String id;
    final String title;

    Profile(String id, String title) {
        this.id = id;
        this.title = title;
    }
}
