package dev.lich.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * McInstanceLoader's {@code .packconfig} files: {@code [Section]} headers, then {@code key = value} lines. Some packs
 * (The Lost Era) list most of their mods in its {@code resources.packconfig} instead of the {@code .mrpack} index, for
 * that mod to download at the first launch.
 */
final class PackConfig {
    /** One {@code [Section]}, keys in file order. */
    record Section(String name, Map<String, String> values) {
        String get(String key) {
            return values.get(key);
        }
    }

    private PackConfig() {
    }

    static List<Section> parse(String text) {
        List<Section> sections = new ArrayList<>();
        Map<String, String> values = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                values = new LinkedHashMap<>();
                sections.add(new Section(line.substring(1, line.length() - 1), values));
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0 || values == null) {
                continue;
            }
            // Values are file names and URLs, which may contain spaces but no '=' before the first one.
            values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return sections;
    }
}
