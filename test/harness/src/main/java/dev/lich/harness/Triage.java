package dev.lich.harness;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Picks, out of what only the Lich run logged, the lines that look like a Java 25 or Lich problem rather than run-to-run
 * noise: the linkage and access errors a removed or encapsulated JDK API gives (DOCS.md §2), the JDK internals mods
 * reach for, and LWJGL. The report lists these first; a run that has any gets the verdict "review".
 */
final class Triage {
    private static final Pattern SUSPECT = Pattern.compile(String.join("|", List.of(
            "IllegalAccessError", "IllegalAccessException", "InaccessibleObjectException", "NoSuchFieldError",
            "NoSuchFieldException", "NoSuchMethodError", "NoSuchMethodException", "NoClassDefFoundError",
            "ClassNotFoundException", "ClassCastException", "UnsupportedOperationException",
            "ExceptionInInitializerError", "VerifyError", "IncompatibleClassChangeError", "AbstractMethodError",
            "LinkageError", "UnsupportedClassVersionError", "SecurityException", "ArrayIndexOutOfBoundsException",
            "Unable to make", "does not (?:export|open)", "module java\\.", "jdk\\.internal", "sun\\.misc",
            "sun\\.reflect", "sun\\.awt", "sun\\.nio", "\\bUnsafe\\b", "\\bjavax\\.", "\\blwjgl\\b", "\\bLWJGL\\b",
            "\\bLich\\b", "dev\\.lich", "URLClassLoader", "AppClassLoader", "ImageIO", "\\bNashorn\\b")));

    private Triage() {
    }

    static boolean suspect(String line) {
        return SUSPECT.matcher(line).find();
    }

    /** The entries of {@code onlyLich} that are suspect. */
    static Map<String, Integer> suspects(Map<String, Integer> onlyLich) {
        Map<String, Integer> result = new TreeMap<>();
        onlyLich.forEach((line, count) -> {
            if (suspect(line)) {
                result.put(line, count);
            }
        });
        return result;
    }
}
