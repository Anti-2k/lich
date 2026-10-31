package dev.lich.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a pack's run says about Lich, from the Lich run and the Forge baseline, step by step:
 * <ul>
 *     <li>{@link Kind#PASS}: Lich got as far as the baseline, with nothing suspect only on its side;</li>
 *     <li>{@link Kind#REVIEW}: it got as far, but logged suspect errors the baseline didn't ({@link Triage}), hit
 *     problems in a step the baseline didn't, or didn't exit when the baseline did;</li>
 *     <li>{@link Kind#FAIL}: it stopped earlier than the baseline, or in the same step for another reason;</li>
 *     <li>{@link Kind#PACK_BROKEN}: the baseline fails too, and Lich fails the same way or gets further: the pack's
 *     own problem;</li>
 *     <li>{@link Kind#UNCHECKED}: no baseline to compare with.</li>
 * </ul>
 */
record Verdict(Kind kind, String reason, List<String> notes) {
    private static final Pattern THROWABLE = Pattern.compile("[\\w$.]+(?:Exception|Error)\\b");

    enum Kind {
        PASS("pass", true),
        REVIEW("review", true),
        FAIL("FAIL", false),
        PACK_BROKEN("pack broken", true),
        UNCHECKED("unchecked", true),
        /** The driver broke on one side: says nothing about Lich or the pack, and needs fixing in the harness. */
        HARNESS("harness problem", false);

        final String text;
        /** Whether the harness exits 0 for it. A Lich run that fails without a baseline is a {@link #FAIL}. */
        final boolean ok;

        Kind(String text, boolean ok) {
            this.text = text;
            this.ok = ok;
        }
    }

    static Verdict of(RunResult lich, RunResult baseline) {
        for (RunResult run : baseline == null ? List.of(lich) : List.of(baseline, lich)) {
            if (run.ending() == RunResult.Ending.HARNESS_ERROR) {
                return new Verdict(Kind.HARNESS, (run == lich ? "on Lich, " : "on Forge, ") + describe(run), List.of());
            }
        }
        if (baseline == null) {
            return lich.ending() == RunResult.Ending.COMPLETED
                    ? new Verdict(Kind.UNCHECKED, "completed; no Forge baseline to compare with", reviewNotes(lich, null))
                    : new Verdict(Kind.FAIL, "Lich " + describe(lich) + "; no Forge baseline to tell if Forge does too", List.of());
        }
        boolean lichDone = lich.ending() == RunResult.Ending.COMPLETED;
        boolean baseDone = baseline.ending() == RunResult.Ending.COMPLETED;
        if (baseDone && !lichDone) {
            return new Verdict(Kind.FAIL, "Lich " + describe(lich) + "; Forge completed", List.of());
        }
        if (!baseDone) {
            long lichPassed = lich.passed();
            long basePassed = baseline.passed();
            if (lichDone || lichPassed > basePassed) {
                return new Verdict(Kind.PACK_BROKEN, "Forge " + describe(baseline) + "; Lich got further ("
                        + (lichDone ? "completed" : describe(lich)) + ")", List.of());
            }
            if (lichPassed < basePassed) {
                return new Verdict(Kind.FAIL, "Lich " + describe(lich) + ", earlier than Forge, which "
                        + describe(baseline), List.of());
            }
            if (signature(lich).equals(signature(baseline))) {
                return new Verdict(Kind.PACK_BROKEN, "both " + describe(baseline), List.of());
            }
            return new Verdict(Kind.FAIL, "in the same step, Lich " + describe(lich) + " but Forge " + describe(baseline),
                    List.of());
        }
        List<String> notes = reviewNotes(lich, baseline);
        return notes.isEmpty()
                ? new Verdict(Kind.PASS, "completed like Forge", List.of())
                : new Verdict(Kind.REVIEW, "completed like Forge, with differences to look at", notes);
    }

    /** What makes a completed Lich run worth a look. */
    private static List<String> reviewNotes(RunResult lich, RunResult baseline) {
        List<String> notes = new ArrayList<>();
        if (baseline != null) {
            int suspects = Triage.suspects(LogDigest.moreIn(lich.log().maskedProblems(), baseline.log().maskedProblems())).size();
            if (suspects > 0) {
                notes.add(suspects + " suspect log entries only with Lich");
            }
        }
        for (RunResult.Step step : lich.steps()) {
            RunResult.Step before = baseline == null ? null : baseline.step(step.name());
            List<String> extra = new ArrayList<>(step.problems().stream().map(LogDigest::masked).toList());
            if (before != null) {
                extra.removeAll(before.problems().stream().map(LogDigest::masked).toList());
            }
            if (!extra.isEmpty()) {
                notes.add(step.name() + ": " + extra.size() + " problems" + (baseline == null ? "" : " only with Lich")
                        + ", first: " + extra.getFirst());
            }
        }
        RunResult.Step exit = lich.step("exit");
        RunResult.Step baseExit = baseline == null ? null : baseline.step("exit");
        if (exit != null && exit.status() == RunResult.Status.FAILED
                && (baseExit == null || baseExit.status() == RunResult.Status.PASSED)) {
            notes.add("exit: " + exit.detail());
        }
        return notes;
    }

    /** "crashed in tour: <detail>", for people. */
    static String describe(RunResult run) {
        if (run.ending() == RunResult.Ending.COMPLETED) {
            return "completed";
        }
        RunResult.Step step = run.failedStep();
        return run.ending().text + (step == null ? "" : " in " + step.name()) + (run.detail().isEmpty() ? "" : ": " + run.detail());
    }

    /** How a run failed, for telling whether two runs failed the same way: ending, step, and the exception's class. */
    static String signature(RunResult run) {
        RunResult.Step step = run.failedStep();
        Matcher throwable = THROWABLE.matcher(run.detail());
        return run.ending() + " " + (step == null ? "" : step.name()) + " " + (throwable.find() ? throwable.group() : "");
    }

    static String icon(Kind kind) {
        return Map.of(Kind.PASS, "✅", Kind.REVIEW, "🔍", Kind.FAIL, "❌", Kind.PACK_BROKEN, "⚠️", Kind.UNCHECKED, "❔",
                Kind.HARNESS, "🔧").get(kind);
    }
}
