package dev.lich.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step the world driver ({@code dev.lich.harness.driver.WorldDriver}) logged: {@code LICH-HARNESS <name>
 * [key=value ...]} anywhere in a game log line. {@code text} is everything after the marker, for the console and for
 * {@code failed}, whose reason is free text.
 */
record DriverEvent(String name, Map<String, String> values, String text) {
    static final String MARKER = "LICH-HARNESS ";

    /** The event in a log line, or null if it has none. */
    static DriverEvent parse(String line) {
        int at = line.indexOf(MARKER);
        if (at < 0) {
            return null;
        }
        String text = line.substring(at + MARKER.length()).strip();
        String[] words = text.split(" ");
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < words.length; i++) {
            int eq = words[i].indexOf('=');
            if (eq > 0) {
                values.put(words[i].substring(0, eq), words[i].substring(eq + 1));
            }
        }
        return new DriverEvent(words[0], values, text);
    }
}
