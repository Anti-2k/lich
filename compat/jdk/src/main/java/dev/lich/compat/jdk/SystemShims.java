package dev.lich.compat.jdk;

/** Replacements for {@code java.lang.System} methods that Java 25 no longer supports. */
public final class SystemShims {
    private SystemShims() {
    }

    /**
     * {@code System.setSecurityManager} always throws since Java 24 (JEP 486). FML installs a security
     * manager only to stop mods from calling {@code System.exit}, so dropping it loses nothing that
     * keeps the game running.
     */
    @SuppressWarnings("removal")
    public static void setSecurityManager(SecurityManager securityManager) {
    }
}
