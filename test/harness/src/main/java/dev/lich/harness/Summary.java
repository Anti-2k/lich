package dev.lich.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The summary of one harness invocation, {@code run/harness/summary.md}: a row per pack with its verdict, what changed
 * since its previous run (from {@link History}), and how long it took.
 */
final class Summary {
    /** One pack's row; {@code previous} is its history entry from before this run, or null. */
    record Row(Pack pack, Verdict verdict, RunResult lich, boolean baselineReused, History.Entry previous, Path report) {
    }

    private Summary() {
    }

    static Path write(Path file, String lich, List<Row> rows, long seconds) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# Harness run");
        out.add("");
        out.add("Lich " + lich + ", " + rows.size() + " packs in " + seconds / 60 + " min " + seconds % 60 + " s.");
        out.add("");
        out.add("| Pack | MC | Verdict | Since last run | Lich steps | Lich time | Forge | Why |");
        out.add("|---|---|---|---|---|---|---|---|");
        for (Row row : rows) {
            out.add("| [" + row.pack().name() + "](" + file.getParent().relativize(row.report()).toString().replace('\\', '/')
                    + ") | " + row.pack().mc() + " | " + Verdict.icon(row.verdict().kind()) + " " + row.verdict().kind().text
                    + " | " + change(row) + " | " + row.lich().passed() + "/" + row.lich().steps().size()
                    + " | " + row.lich().seconds() + " s | " + (row.baselineReused() ? "reused" : "ran")
                    + " | " + row.verdict().reason().replace('|', '/') + " |");
        }
        Files.write(file, out, StandardCharsets.UTF_8);
        return file;
    }

    /** "fixed", "regressed", "same" or "first run", against the pack's previous verdict. */
    static String change(Row row) {
        if (row.previous() == null) {
            return "first run";
        }
        boolean okNow = row.verdict().kind().ok;
        boolean okBefore = !row.previous().verdict().equals(Verdict.Kind.FAIL.name());
        if (okNow == okBefore) {
            return row.previous().verdict().equals(row.verdict().kind().name()) ? "same" : "was " + row.previous().verdict();
        }
        return okNow ? "**fixed**" : "**regressed**";
    }
}
