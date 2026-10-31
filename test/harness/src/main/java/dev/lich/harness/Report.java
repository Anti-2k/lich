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

/**
 * A pack's Markdown report ({@code run/harness/<pack>/report.md}): the verdict and why, each step side by side on
 * both profiles, the driver's numbers, then what only one side logged, the suspect lines first.
 */
final class Report {
    private static final int MAX_LINES = 200;
    /** The numbers a step reports that are worth a column; the rest stay in the JSON. */
    private static final List<String> NUMBERS = List.of("chunks", "entities", "tileEntities", "mspt", "clientChunks",
            "heapMb", "mods", "configs", "tabs", "pages", "blocks", "galleryTileEntities", "regionFiles");

    private Report() {
    }

    static Path write(Path file, Pack pack, long modJars, Verdict verdict, RunResult lich, RunResult baseline,
                      boolean baselineReused) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# " + pack.name() + ": " + Verdict.icon(verdict.kind()) + " " + verdict.kind().text);
        out.add("");
        out.add(verdict.reason() + ".");
        out.add("");
        verdict.notes().forEach(note -> out.add("- " + note));
        if (!verdict.notes().isEmpty()) {
            out.add("");
        }
        out.add("Minecraft " + pack.mc() + ", Forge " + pack.forge() + ", " + modJars + " mod jars. Lich " + lich.lich()
                + ", run " + lich.startedAt() + "; Forge baseline "
                + (baseline == null ? "none" : baseline.startedAt() + (baselineReused ? " (reused)" : " (new)")) + ".");
        out.add("");

        List<RunResult> runs = baseline == null ? List.of(lich) : List.of(baseline, lich);
        steps(out, runs);
        numbers(out, runs);

        if (baseline != null) {
            Map<String, Integer> onlyLichMasked = LogDigest.moreIn(lich.log().maskedProblems(), baseline.log().maskedProblems());
            Map<String, Integer> onlyForgeMasked = LogDigest.moreIn(baseline.log().maskedProblems(), lich.log().maskedProblems());
            section(out, "Suspect, only with Lich", "Log entries only the Lich run has, that look like a Java 25 or Lich "
                    + "problem (linkage and access errors, JDK internals, LWJGL). Read these first.", Triage.suspects(onlyLichMasked));
            section(out, "Only with Lich", "Warnings and errors the Lich run logged more often than Forge "
                    + "(numbers masked as #).", onlyLichMasked);
            section(out, "Only with Forge", "Warnings and errors Forge logged more often than the Lich run.", onlyForgeMasked);
            TreeSet<String> onlyLich = new TreeSet<>(lich.log().mixins());
            onlyLich.removeAll(baseline.log().mixins());
            TreeSet<String> onlyForge = new TreeSet<>(baseline.log().mixins());
            onlyForge.removeAll(lich.log().mixins());
            list(out, "Mixins applied only with Lich", onlyLich);
            list(out, "Mixins applied only with Forge", onlyForge);
        }
        section(out, "Lich's own lines", "Compat-pass hits, game fixes and LWJGL 3 rewrites.", lich.log().lichLines());

        out.add("## Files");
        out.add("");
        for (RunResult run : runs) {
            out.add("- " + run.profile() + ": `" + Path.of(run.gameDir()).resolve("logs").resolve("latest.log") + "`");
        }
        Files.write(file, out, StandardCharsets.UTF_8);
        return file;
    }

    private static void steps(List<String> out, List<RunResult> runs) {
        StringBuilder header = new StringBuilder("| Step |");
        StringBuilder rule = new StringBuilder("|---|");
        for (RunResult run : runs) {
            header.append(' ').append(title(run)).append(" |");
            rule.append("---|");
        }
        out.add(header.toString());
        out.add(rule.toString());
        for (RunResult.Step step : runs.getLast().steps()) {
            StringBuilder row = new StringBuilder("| " + step.name() + " |");
            for (RunResult run : runs) {
                row.append(' ').append(cell(run.step(step.name()))).append(" |");
            }
            out.add(row.toString());
        }
        out.add("");
        for (RunResult run : runs) {
            out.add("- " + title(run) + ": " + Verdict.describe(run) + (run.progress().isEmpty() || run.ending() == RunResult.Ending.COMPLETED
                    ? "" : " (last progress: " + run.progress() + ")") + "; " + run.seconds() + " s in all, "
                    + (run.log().modsLoaded() == null ? "?" : run.log().modsLoaded()) + " mods loaded, "
                    + run.log().problemCount() + " warnings and errors, " + run.log().mixins().size() + " mixins applied.");
        }
        out.add("");
        for (RunResult run : runs) {
            for (RunResult.Step step : run.steps()) {
                for (String problem : step.problems()) {
                    out.add("- " + title(run) + ", " + step.name() + ": `" + problem.replace("`", "'") + "`");
                }
            }
        }
        out.add("");
    }

    private static String cell(RunResult.Step step) {
        if (step == null) {
            return "-";
        }
        return switch (step.status()) {
            case PASSED -> "✓ " + step.seconds() + " s" + (step.problems().isEmpty() ? "" : ", " + step.problems().size() + " problems");
            case FAILED -> "✗ " + step.seconds() + " s" + (step.detail().isEmpty() ? "" : ": " + shorten(step.detail()));
            case NOT_REACHED -> "·";
        };
    }

    /** The driver's numbers after each step, side by side. */
    private static void numbers(List<String> out, List<RunResult> runs) {
        Map<String, List<String>> rows = new TreeMap<>();
        List<String> order = new ArrayList<>();
        for (RunResult.Step step : runs.getLast().steps()) {
            for (String key : NUMBERS) {
                List<String> cells = new ArrayList<>();
                boolean any = false;
                for (RunResult run : runs) {
                    RunResult.Step s = run.step(step.name());
                    String value = s == null ? null : s.values().get(key);
                    any |= value != null;
                    cells.add(value == null ? "-" : value);
                }
                if (any) {
                    String row = step.name() + " " + key;
                    order.add(row);
                    rows.put(row, cells);
                }
            }
        }
        if (order.isEmpty()) {
            return;
        }
        out.add("## Numbers");
        out.add("");
        out.add("What the driver measured at the end of each step. Counts depend on timing, so only large differences mean "
                + "something; mspt is the integrated server's mean tick time, heapMb the heap in use.");
        out.add("");
        StringBuilder header = new StringBuilder("| Step | Value |");
        StringBuilder rule = new StringBuilder("|---|---|");
        for (RunResult run : runs) {
            header.append(' ').append(title(run)).append(" |");
            rule.append("---|");
        }
        out.add(header.toString());
        out.add(rule.toString());
        for (String row : order) {
            int space = row.indexOf(' ');
            out.add("| " + row.substring(0, space) + " | " + row.substring(space + 1) + " | " + String.join(" | ", rows.get(row)) + " |");
        }
        out.add("");
    }

    private static String title(RunResult run) {
        return run.profile().equals(Profile.FORGE.id) ? Profile.FORGE.title : Profile.LICH.title;
    }

    private static String shorten(String text) {
        String flat = text.replace('|', '/').replace('\n', ' ');
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    private static void section(List<String> out, String title, String note, Map<String, Integer> entries) {
        out.add("## " + title + " (" + entries.size() + ")");
        out.add("");
        out.add(note);
        out.add("");
        int n = 0;
        for (Map.Entry<String, Integer> e : entries.entrySet()) {
            if (n++ == MAX_LINES) {
                out.add("- … " + (entries.size() - MAX_LINES) + " more");
                break;
            }
            out.add("- " + (e.getValue() > 1 ? "×" + e.getValue() + " " : "") + "`" + e.getKey().replace("`", "'") + "`");
        }
        out.add("");
    }

    private static void list(List<String> out, String title, TreeSet<String> entries) {
        out.add("## " + title + " (" + entries.size() + ")");
        out.add("");
        int n = 0;
        for (String e : entries) {
            if (n++ == MAX_LINES) {
                out.add("- … " + (entries.size() - MAX_LINES) + " more");
                break;
            }
            out.add("- `" + e + "`");
        }
        out.add("");
    }
}
