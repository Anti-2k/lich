package dev.lich.loader;

import java.util.ArrayList;
import java.util.List;

/**
 * A mod or Lich version ({@code 1.2.3}, {@code 0.1.0-SNAPSHOT}). Only the numeric parts take part in
 * comparisons: a pre-release or build suffix is kept for display but ignored, so a Lich snapshot satisfies
 * {@code >=0.1} like the release it leads to. Missing parts count as 0 ({@code 1.2} = {@code 1.2.0}).
 */
public record Version(String text, List<Integer> parts) implements Comparable<Version> {
    public Version {
        parts = List.copyOf(parts);
    }

    public static Version parse(String text) {
        String numeric = text.strip();
        int suffix = indexOfAny(numeric, '-', '+');
        if (suffix >= 0) {
            numeric = numeric.substring(0, suffix);
        }
        List<Integer> parts = new ArrayList<>();
        for (String part : numeric.split("\\.")) {
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a version: \"" + text + "\"");
            }
        }
        return new Version(text.strip(), parts);
    }

    private static int indexOfAny(String text, char... chars) {
        int first = -1;
        for (char c : chars) {
            int index = text.indexOf(c);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    @Override
    public int compareTo(Version other) {
        for (int i = 0; i < Math.max(parts.size(), other.parts.size()); i++) {
            int compare = Integer.compare(part(i), other.part(i));
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    private int part(int index) {
        return index < parts.size() ? parts.get(index) : 0;
    }

    @Override
    public String toString() {
        return text;
    }
}
