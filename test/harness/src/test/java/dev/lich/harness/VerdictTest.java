package dev.lich.harness;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictTest {
    private static final RunResult.Ending CRASHED = RunResult.Ending.CRASHED;

    @Test
    void bothCompletingIsAPass() {
        Verdict verdict = Verdict.of(Runs.completed("lich-forge"), Runs.completed("forge"));
        assertEquals(Verdict.Kind.PASS, verdict.kind());
        assertTrue(verdict.kind().ok);
    }

    @Test
    void lichFailingWhereForgeCompletesIsAFail() {
        Verdict verdict = Verdict.of(Runs.failed("lich-forge", "spawn", CRASHED, "crash.txt: Ticking: java.lang.RuntimeException"),
                Runs.completed("forge"));
        assertEquals(Verdict.Kind.FAIL, verdict.kind());
        assertFalse(verdict.kind().ok);
        assertTrue(verdict.reason().contains("crashed in spawn"), verdict.reason());
    }

    @Test
    void failingTheSameWayAsForgeIsThePacksProblem() {
        RunResult forge = Runs.failed("forge", "tour", CRASHED, "crash-a.txt: Exception in server tick loop: java.lang.NoClassDefFoundError: x/Y");
        RunResult lich = Runs.failed("lich-forge", "tour", CRASHED, "crash-b.txt: Exception in server tick loop: java.lang.NoClassDefFoundError: x/Y");
        assertEquals(Verdict.Kind.PACK_BROKEN, Verdict.of(lich, forge).kind());
    }

    @Test
    void sameStepButAnotherFailureIsAFail() {
        // Dunkirk Spirit: Java 8 stalled, Lich ran out of memory.
        RunResult forge = Runs.failed("forge", "world", RunResult.Ending.STALLED, "no end of world within 420 s");
        RunResult lich = Runs.failed("lich-forge", "world", RunResult.Ending.OUT_OF_MEMORY, "exit code 1");
        assertEquals(Verdict.Kind.FAIL, Verdict.of(lich, forge).kind());
    }

    @Test
    void lichStoppingEarlierThanABrokenForgeIsAFail() {
        RunResult forge = Runs.failed("forge", "tour", CRASHED, "x");
        RunResult lich = Runs.failed("lich-forge", "mods", CRASHED, "x");
        assertEquals(Verdict.Kind.FAIL, Verdict.of(lich, forge).kind());
    }

    @Test
    void lichGettingFurtherThanABrokenForgeIsThePacksProblem() {
        RunResult forge = Runs.failed("forge", "tour", CRASHED, "x");
        assertEquals(Verdict.Kind.PACK_BROKEN, Verdict.of(Runs.completed("lich-forge"), forge).kind());
        assertEquals(Verdict.Kind.PACK_BROKEN, Verdict.of(Runs.failed("lich-forge", "save", CRASHED, "y"), forge).kind());
    }

    @Test
    void suspectLinesOnlyWithLichNeedAReview() {
        RunResult forge = Runs.withProblems("forge", Map.of("WARN Missing texture at 12 64 -3", 1));
        RunResult lich = Runs.withProblems("lich-forge", Map.of("WARN Missing texture at 40 70 9", 1,
                "ERROR Couldn't patch | java.lang.NoSuchFieldException: modifiers", 1));
        Verdict verdict = Verdict.of(lich, forge);
        assertEquals(Verdict.Kind.REVIEW, verdict.kind());
        assertEquals(List.of("1 suspect log entries only with Lich"), verdict.notes(), "the texture line differs only in numbers");
    }

    @Test
    void aStepProblemOnlyWithLichNeedsAReview() {
        RunResult.Step mods = new RunResult.Step("mods", RunResult.Status.PASSED, 0, 5, Map.of(), "",
                List.of("mod=foo: config screen: java.lang.IllegalAccessError"));
        Verdict verdict = Verdict.of(Runs.withStep(Runs.completed("lich-forge"), mods), Runs.completed("forge"));
        assertEquals(Verdict.Kind.REVIEW, verdict.kind());
        assertTrue(verdict.notes().getFirst().startsWith("mods: 1 problems only with Lich"), verdict.notes().toString());
    }

    @Test
    void aSlowExitOnlyWithLichNeedsAReview() {
        RunResult.Step exit = new RunResult.Step("exit", RunResult.Status.FAILED, 0, 30, Map.of(), "didn't exit within 30 s",
                List.of());
        RunResult lich = Runs.withStep(Runs.completed("lich-forge"), exit);
        assertEquals(Verdict.Kind.REVIEW, Verdict.of(lich, Runs.completed("forge")).kind());
        // GT New Horizons takes over a minute to exit on Java 8 too.
        assertEquals(Verdict.Kind.PASS, Verdict.of(lich, Runs.withStep(Runs.completed("forge"), exit)).kind());
    }

    @Test
    void theDriverBreakingIsTheHarnessesProblemNotThePacks() {
        RunResult forge = Runs.failed("forge", "boot", RunResult.Ending.HARNESS_ERROR, "java.lang.ArrayIndexOutOfBoundsException: 0");
        RunResult lich = Runs.failed("lich-forge", "boot", RunResult.Ending.HARNESS_ERROR, "java.lang.ArrayIndexOutOfBoundsException: 0");
        Verdict verdict = Verdict.of(lich, forge);
        assertEquals(Verdict.Kind.HARNESS, verdict.kind());
        assertFalse(verdict.kind().ok);
    }

    @Test
    void withoutABaselineOnlyCompletingIsOk() {
        assertEquals(Verdict.Kind.UNCHECKED, Verdict.of(Runs.completed("lich-forge"), null).kind());
        assertEquals(Verdict.Kind.FAIL, Verdict.of(Runs.failed("lich-forge", "boot", RunResult.Ending.EXITED, "exit code 1"), null).kind());
    }
}
