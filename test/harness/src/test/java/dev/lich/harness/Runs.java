package dev.lich.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Made-up runs for the tests: every step up to {@code failedIn} passed, that one failed, the rest weren't reached. */
final class Runs {
    static final List<String> STEPS = List.of("boot", "mods", "world", "spawn", "tour", "hold", "save", "reload", "exit");

    private Runs() {
    }

    static RunResult completed(String profile) {
        return run(profile, null, RunResult.Ending.COMPLETED, "", Map.of());
    }

    static RunResult failed(String profile, String failedIn, RunResult.Ending ending, String detail) {
        return run(profile, failedIn, ending, detail, Map.of());
    }

    static RunResult withProblems(String profile, Map<String, Integer> problems) {
        return run(profile, null, RunResult.Ending.COMPLETED, "", problems);
    }

    static RunResult run(String profile, String failedIn, RunResult.Ending ending, String detail,
                         Map<String, Integer> problems) {
        List<RunResult.Step> steps = new ArrayList<>();
        boolean reached = true;
        for (String name : STEPS) {
            RunResult.Status status;
            if (!reached) {
                status = RunResult.Status.NOT_REACHED;
            } else if (name.equals(failedIn)) {
                status = RunResult.Status.FAILED;
                reached = false;
            } else {
                status = RunResult.Status.PASSED;
            }
            steps.add(new RunResult.Step(name, status, 0, 10, Map.of(), status == RunResult.Status.FAILED ? detail : "",
                    List.of()));
        }
        return new RunResult(profile, "2026-10-31 12:00", "abc123", 100, steps, ending, detail, "", "C:/game",
                new LogDigest(10, problems, List.of(), Map.of(), false), null);
    }

    static RunResult withStep(RunResult run, RunResult.Step step) {
        List<RunResult.Step> steps = new ArrayList<>(run.steps());
        steps.replaceAll(s -> s.name().equals(step.name()) ? step : s);
        return new RunResult(run.profile(), run.startedAt(), run.lich(), run.seconds(), steps, run.ending(), run.detail(),
                run.progress(), run.gameDir(), run.log(), run.key());
    }
}
