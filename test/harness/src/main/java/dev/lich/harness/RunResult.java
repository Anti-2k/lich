package dev.lich.harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One profile's run of a pack, as the report and a later comparison need it: how each step went, how the run ended,
 * and the digest of its game log. Stored as JSON next to the run, so a Forge baseline can be compared with Lich runs
 * long after it ran.
 *
 * @param steps    every step of the scenario, in order, including the ones the run never reached
 * @param ending   how the run ended; {@code detail} says more (the crash, the stall, the exit code)
 * @param progress the last progress the driver reported (the mod, tab or block it was on): where a crash happened
 * @param key      what the run depended on, for a baseline (see {@link Baseline}); null for a Lich run
 */
record RunResult(String profile, String startedAt, String lich, long seconds, List<Step> steps, Ending ending,
                 String detail, String progress, String gameDir, LogDigest log, String key) {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** How a run ended. */
    enum Ending {
        COMPLETED("completed every step"),
        CRASHED("crashed"),
        JVM_CRASHED("the JVM crashed"),
        OUT_OF_MEMORY("ran out of memory"),
        DRIVER_FAILED("a step failed"),
        /** The driver's own bug, not the game's: a harness problem, whatever the pack or Lich do. */
        HARNESS_ERROR("the harness's driver broke"),
        STALLED("stalled"),
        EXITED("exited early");

        final String text;

        Ending(String text) {
            this.text = text;
        }
    }

    enum Status {
        PASSED, FAILED, NOT_REACHED
    }

    /**
     * One step: {@code startSecond} counts from the game's start; {@code values} are the numbers the driver gave at
     * its end; {@code problems} what went wrong without stopping it (from the driver, normalized).
     */
    record Step(String name, Status status, long startSecond, long seconds, Map<String, String> values, String detail,
                List<String> problems) {
    }

    Step step(String name) {
        return steps.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    /** The steps that passed before the run ended (the exit included). */
    long passed() {
        return steps.stream().takeWhile(s -> s.status() == Status.PASSED).count();
    }

    /** The step the run ended in, or null if it completed. */
    Step failedStep() {
        return steps.stream().filter(s -> s.status() == Status.FAILED && !s.name().equals("exit")).findFirst().orElse(null);
    }

    void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    static RunResult read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RunResult.class);
        } catch (RuntimeException e) {
            // Written by an older harness; it'll be replaced.
            return null;
        }
    }
}
