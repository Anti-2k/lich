package dev.lich.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The Markdown report of a harness run: each profile's outcome, then what the Lich run logged that the Forge baseline
 * didn't (the part to read), what only the baseline logged, and mixins applied on one side only.
 */
final class Report {
    private static final int MAX_LINES = 300;

    private Report() {
    }

    static Path write(Path file, Pack pack, long modJars, List<GameRun.Result> results, List<LogReport> logs) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# " + pack.name() + ": harness run");
        out.add("");
        out.add(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ". Minecraft " + pack.mc()
                + ", Forge " + pack.forge() + ", " + modJars + " mod jars. Each profile joins the local test server and "
                + "stays in the world.");
        out.add("");
        out.add("| Profile | Result | Mods loaded | Main menu | Joined | Warnings + errors | Mixins applied |");
        out.add("|---|---|---|---|---|---|---|");
        for (int i = 0; i < results.size(); i++) {
            GameRun.Result r = results.get(i);
            LogReport log = logs.get(i);
            out.add("| " + r.profile() + " | " + r.outcome().text + (r.detail().isEmpty() ? "" : " (" + r.detail() + ")")
                    + " | " + (log.modsLoaded() == null ? "-" : log.modsLoaded()) + " | " + GameRun.seconds(r.toMenu())
                    + " | " + GameRun.seconds(r.toJoin()) + " | " + log.problemCount() + " | " + log.mixins().size() + " |");
        }
        out.add("");

        int baseline = indexOf(results, "forge");
        int lich = indexOf(results, "lich-forge");
        if (baseline >= 0 && lich >= 0) {
            LogReport f = logs.get(baseline);
            LogReport l = logs.get(lich);
            section(out, "Only with Lich", "Warnings and errors the Lich run logged more often than the Forge baseline.",
                    LogReport.moreIn(l.problems(), f.problems()));
            section(out, "Only with Forge", "Warnings and errors the baseline logged more often than the Lich run.",
                    LogReport.moreIn(f.problems(), l.problems()));
            TreeSet<String> onlyLich = new TreeSet<>(l.mixins());
            onlyLich.removeAll(f.mixins());
            TreeSet<String> onlyForge = new TreeSet<>(f.mixins());
            onlyForge.removeAll(l.mixins());
            list(out, "Mixins applied only with Lich", onlyLich);
            list(out, "Mixins applied only with Forge", onlyForge);
            section(out, "Lich's own lines", "Compat-pass hits, game fixes and LWJGL 3 rewrites.", l.lichLines());
        }

        out.add("## Logs");
        out.add("");
        for (GameRun.Result r : results) {
            out.add("- " + r.profile() + ": `" + r.gameDir().resolve("logs").resolve("latest.log") + "`");
        }
        Files.write(file, out, StandardCharsets.UTF_8);
        return file;
    }

    private static int indexOf(List<GameRun.Result> results, String profile) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).profile().equals(profile)) {
                return i;
            }
        }
        return -1;
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
