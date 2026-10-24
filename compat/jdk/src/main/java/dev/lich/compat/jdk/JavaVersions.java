package dev.lich.compat.jdk;

/** Version-string checks for patched library code that only knows the {@code 1.x} scheme. */
public final class JavaVersions {
    private JavaVersions() {
    }

    /** {@code newest} for a Java 9+ version string, {@code null} otherwise (what the unpatched code returned). */
    public static Object newestIfModern(String version, Object newest) {
        return isModern(version) ? newest : null;
    }

    /** Whether {@code version} is a Java 9+ version string ({@code "9"}, {@code "25"}, {@code "25.0.4"}). */
    public static boolean isModern(String version) {
        if (version == null || version.startsWith("1.")) {
            return false;
        }
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        if (end == 0 || end > 4) {
            return false;
        }
        return Integer.parseInt(version, 0, end, 10) >= 9;
    }
}
