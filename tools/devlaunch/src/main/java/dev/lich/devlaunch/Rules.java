package dev.lich.devlaunch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Evaluates the {@code rules} arrays of version JSON libraries and arguments, the way Mojang's launcher does. */
final class Rules {
    static final String OS_NAME = osName();
    /** Substituted for {@code ${arch}} in native classifiers, e.g. {@code natives-windows-${arch}}. */
    static final String BITS = System.getProperty("os.arch").contains("64") ? "64" : "32";

    private Rules() {
    }

    /** No rules means allowed; otherwise the last matching rule decides, and nothing matching means disallowed. */
    static boolean allowed(JsonElement rules) {
        if (rules == null || !rules.isJsonArray() || rules.getAsJsonArray().isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules.getAsJsonArray()) {
            JsonObject rule = element.getAsJsonObject();
            if (matches(rule)) {
                allowed = "allow".equals(rule.get("action").getAsString());
            }
        }
        return allowed;
    }

    private static boolean matches(JsonObject rule) {
        if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !os.get("name").getAsString().equals(OS_NAME)) {
                return false;
            }
            if (os.has("arch") && !archMatches(os.get("arch").getAsString())) {
                return false;
            }
            if (os.has("version")
                    && !Pattern.compile(os.get("version").getAsString()).matcher(System.getProperty("os.version")).find()) {
                return false;
            }
        }
        if (rule.has("features")) {
            // devlaunch turns on none of the launcher features (demo user, custom resolution, quick play).
            for (Map.Entry<String, JsonElement> feature : rule.getAsJsonObject("features").entrySet()) {
                if (feature.getValue().getAsBoolean()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean archMatches(String arch) {
        String actual = System.getProperty("os.arch");
        return switch (arch) {
            case "x86" -> actual.equals("x86") || actual.equals("i386") || actual.equals("i686");
            case "x86_64" -> actual.equals("amd64") || actual.equals("x86_64");
            case "arm64" -> actual.equals("aarch64") || actual.equals("arm64");
            default -> actual.equals(arch);
        };
    }

    private static String osName() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return "windows";
        }
        if (name.startsWith("mac")) {
            return "osx";
        }
        return "linux";
    }
}
