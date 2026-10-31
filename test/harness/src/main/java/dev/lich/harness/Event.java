package dev.lich.harness;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line the driver sent: {@code <type> <step> [key=value ...] [-- text]}. Types: {@code hello} (pid, Java
 * version), {@code start} and {@code end} of a step (the end with the step's numbers), {@code fail} (the script
 * stopped), {@code problem} (something went wrong but the step carried on), {@code note} (a screen change, an
 * answered prompt) and {@code at} (progress inside a step).
 */
record Event(String type, String step, Map<String, String> values, String text) {
    static Event parse(String line) {
        String body = line.strip();
        String text = null;
        int dashes = body.indexOf(" -- ");
        if (dashes >= 0) {
            text = body.substring(dashes + 4);
            body = body.substring(0, dashes);
        }
        String[] parts = body.split(" ");
        if (parts.length < 2 || parts[0].isEmpty()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 2; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                values.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
        }
        return new Event(parts[0], parts[1], Collections.unmodifiableMap(values), text);
    }

    /** The values and text as one line, for the console and the report. */
    String describe() {
        StringBuilder out = new StringBuilder();
        values.forEach((k, v) -> out.append(out.isEmpty() ? "" : " ").append(k).append('=').append(v));
        if (text != null) {
            out.append(out.isEmpty() ? "" : ": ").append(text);
        }
        return out.toString();
    }
}
