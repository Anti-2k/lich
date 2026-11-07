package dev.lich.compat.jdk;

/**
 * {@code new ScriptEngineManager(null)} only looks among the engines installed in the JDK. Java 8 had Nashorn there;
 * Java 15+ has no JavaScript engine, and the one Lich ships (DOCS.md §2.3) is on the class path, where a {@code null}
 * loader doesn't look. Solar Flux Reborn asks for "Nashorn" this way and stops in pre-init on the {@code null} engine
 * (Divine Journey 2). The compat pass passes the loader of every {@code ScriptEngineManager(ClassLoader)} call through
 * {@link #withNashorn}.
 */
public final class ScriptEngines {
    private ScriptEngines() {
    }

    /** {@code loader} itself, or for {@code null} the system class loader, which sees the JDK's engines and Lich's Nashorn. */
    public static ClassLoader withNashorn(ClassLoader loader) {
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }
}
