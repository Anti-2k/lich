package dev.lich.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one run's game log says, made comparable between profiles: warnings and errors (with the exception line under
 * them), mixins applied, the mod count FML reports, and Lich's own lines. Timestamps, thread names, identity hashes,
 * timings and the game directory are taken out, since they differ between any two runs, and so is what Java 8 and
 * Java 25 print differently for the same thing (NullPointerException details, reflection frames).
 */
record LogReport(Integer modsLoaded, Map<String, Integer> problems, TreeSet<String> mixins, Map<String, Integer> lichLines) {
    // Mojang's log4j layout for latest.log: "[12:34:56] [Client thread/WARN]: message".
    private static final Pattern ENTRY = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d] \\[(.+?)/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)]: ?(.*)$");
    private static final Pattern EXCEPTION = Pattern.compile("^(Caused by: )?[\\w$.]+(Exception|Error|Throwable)\\b.*");
    private static final Pattern MODS_LOADED = Pattern.compile("successfully loaded (\\d+) mods");
    private static final Pattern MIXING = Pattern.compile("Mixing (\\S+) from (\\S+) into (\\S+)");
    private static final Pattern HASH = Pattern.compile("@[0-9a-fA-F]{4,}\\b");
    private static final Pattern TIMING = Pattern.compile("\\b\\d+(?:[.,]\\d+)?\\s?(ms|s|seconds|sec)\\b");
    private static final Pattern PORT = Pattern.compile("127\\.0\\.0\\.1:\\d+");
    // Java 14+ explains every NullPointerException (JEP 358); Java 8 gave none.
    private static final Pattern HELPFUL_NPE = Pattern.compile("(NullPointerException): Cannot .*");
    // The same reflective call is sun.reflect's on Java 8 and jdk.internal.reflect's on Java 9+.
    private static final Pattern REFLECTION_FRAME = Pattern.compile(
            "at (?:java\\.base/)?(?:jdk\\.internal\\.reflect|sun\\.reflect)\\.[^\\s(]+\\([^)]*\\)");
    private static final List<String> LICH_PREFIXES = List.of("Lich ", "Compat: ", "Game fix: ", "LWJGL 3: ",
            "Left out of the class path");

    static LogReport read(Path log, Path gameDir) throws IOException {
        if (!Files.isRegularFile(log)) {
            return new LogReport(null, Map.of(), new TreeSet<>(), Map.of());
        }
        return parse(Files.readAllLines(log, StandardCharsets.ISO_8859_1), gameDir);
    }

    static LogReport parse(List<String> lines, Path gameDir) {
        Integer mods = null;
        Map<String, Integer> problems = new TreeMap<>();
        TreeSet<String> mixins = new TreeSet<>();
        Map<String, Integer> lich = new TreeMap<>();
        List<String> dirs = new ArrayList<>();
        if (gameDir != null) {
            // Both spellings: Java prints backslashes on Windows, some mods forward slashes.
            String dir = gameDir.toAbsolutePath().normalize().toString();
            dirs.add(dir);
            dirs.add(dir.replace('\\', '/'));
        }

        String pending = null;
        for (String line : lines) {
            Matcher entry = ENTRY.matcher(line);
            if (entry.matches()) {
                if (pending != null) {
                    problems.merge(pending, 1, Integer::sum);
                    pending = null;
                }
                String level = entry.group(2);
                String message = entry.group(3);
                Matcher modsLoaded = MODS_LOADED.matcher(message);
                if (modsLoaded.find()) {
                    mods = Integer.parseInt(modsLoaded.group(1));
                }
                Matcher mixing = MIXING.matcher(message);
                if (mixing.find()) {
                    mixins.add(mixing.group(1) + " from " + mixing.group(2) + " into " + mixing.group(3));
                }
                if (LICH_PREFIXES.stream().anyMatch(message::startsWith)) {
                    lich.merge(normalize(message, dirs), 1, Integer::sum);
                } else if (level.equals("WARN") || level.equals("ERROR") || level.equals("FATAL")) {
                    pending = level + " " + normalize(message, dirs);
                }
                continue;
            }
            // A stack trace under a warning or error: keep the first exception line, which says what it was.
            if (pending != null && !pending.contains(" | ") && EXCEPTION.matcher(line.strip()).matches()) {
                pending += " | " + normalize(line.strip(), dirs);
            }
        }
        if (pending != null) {
            problems.merge(pending, 1, Integer::sum);
        }
        return new LogReport(mods, problems, mixins, lich);
    }

    static String normalize(String message, List<String> dirs) {
        String result = message;
        for (String dir : dirs) {
            result = result.replace(dir, "<game>");
        }
        result = HASH.matcher(result).replaceAll("@…");
        result = TIMING.matcher(result).replaceAll("#$1");
        result = PORT.matcher(result).replaceAll("127.0.0.1:#");
        result = HELPFUL_NPE.matcher(result).replaceAll("$1");
        result = REFLECTION_FRAME.matcher(result).replaceAll("at <reflection>");
        return result.length() > 400 ? result.substring(0, 400) + "…" : result;
    }

    int problemCount() {
        return problems.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Entries {@code a} has more often than {@code b}, with how many more. */
    static Map<String, Integer> moreIn(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> result = new TreeMap<>();
        a.forEach((key, count) -> {
            int extra = count - b.getOrDefault(key, 0);
            if (extra > 0) {
                result.put(key, extra);
            }
        });
        return result;
    }
}
