package dev.lich.loader;

import java.util.ArrayList;
import java.util.List;

/**
 * A version requirement from {@code lich.mod.json}: {@code *}, an exact version ({@code 1.2.0} or
 * {@code =1.2.0}), a bound ({@code >=0.4}, {@code >1}, {@code <2}, {@code <=2.1}), several bounds that must
 * all hold ({@code >=1.2 <2}), and alternatives separated by {@code ||}.
 */
public final class VersionRange {
    private record Bound(String operator, Version version) {
        boolean test(Version candidate) {
            int compare = candidate.compareTo(version);
            return switch (operator) {
                case ">=" -> compare >= 0;
                case ">" -> compare > 0;
                case "<=" -> compare <= 0;
                case "<" -> compare < 0;
                default -> compare == 0;
            };
        }
    }

    private final String text;
    /** Alternatives, each a list of bounds that must all hold. */
    private final List<List<Bound>> alternatives;

    private VersionRange(String text, List<List<Bound>> alternatives) {
        this.text = text;
        this.alternatives = alternatives;
    }

    public static VersionRange parse(String text) {
        List<List<Bound>> alternatives = new ArrayList<>();
        for (String alternative : text.split("\\|\\|")) {
            List<Bound> bounds = new ArrayList<>();
            for (String token : alternative.strip().split("\\s+")) {
                if (token.isEmpty() || token.equals("*")) {
                    continue;
                }
                String operator = token.startsWith(">=") || token.startsWith("<=") ? token.substring(0, 2)
                        : token.startsWith(">") || token.startsWith("<") || token.startsWith("=") ? token.substring(0, 1) : "=";
                String version = token.substring(operator.equals("=") && !token.startsWith("=") ? 0 : operator.length());
                bounds.add(new Bound(operator, Version.parse(version)));
            }
            alternatives.add(bounds);
        }
        return new VersionRange(text.strip(), alternatives);
    }

    public boolean test(Version version) {
        for (List<Bound> bounds : alternatives) {
            if (bounds.stream().allMatch(bound -> bound.test(version))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return text;
    }
}
