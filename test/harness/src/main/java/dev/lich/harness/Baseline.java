package dev.lich.harness;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Forge-on-Java-8 run a pack's Lich runs are compared with, kept in {@code run/harness/<pack>/baseline.json}.
 * Nothing in Lich changes it, so it's run again only when something it depends on does: the pinned pack and its
 * setup, the driver, the scenario's settings, the Forge profile, the heap and the Java 8 it runs on (its {@link #key}).
 * A baseline that played more steps than a run still compares; one that played fewer, or others, is run again.
 */
final class Baseline {
    /** Bump when what a baseline records changes. */
    private static final String FORMAT = "baseline 1";

    private Baseline() {
    }

    static Path file(Path packDir) {
        return packDir.resolve("baseline.json");
    }

    static String key(Pack pack, Scenario scenario, Path driverJar, Path forgeProfile, Path java8) throws IOException {
        MessageDigest digest = sha256();
        update(digest, FORMAT);
        update(digest, pack.fingerprint());
        update(digest, pack.memoryMb() + " " + pack.worldType() + " " + pack.save() + " " + pack.galleryExclude());
        update(digest, scenario.key());
        update(digest, java8.toAbsolutePath().normalize().toString());
        digest.update(Files.readAllBytes(forgeProfile));
        // The driver's classes, not the jar: a rebuilt jar has new timestamps but the same driver.
        try (ZipFile jar = new ZipFile(driverJar.toFile())) {
            for (ZipEntry entry : jar.stream().sorted((a, b) -> a.getName().compareTo(b.getName())).toList()) {
                update(digest, entry.getName());
                try (InputStream in = jar.getInputStream(entry)) {
                    digest.update(in.readAllBytes());
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    /**
     * The stored baseline, if it was made with {@code key} and played at least the steps {@code scenario} does, cut to
     * those steps ({@link #cut}).
     */
    static RunResult usable(Path packDir, String key, Scenario scenario) throws IOException {
        RunResult stored = RunResult.read(file(packDir));
        // A baseline the driver broke on says nothing about Forge.
        if (stored == null || !key.equals(stored.key()) || stored.ending() == RunResult.Ending.HARNESS_ERROR) {
            return null;
        }
        List<String> had = stored.steps().stream().map(RunResult.Step::name).filter(s -> !s.equals("exit")).toList();
        List<String> want = scenario.driverSteps();
        return want.size() <= had.size() && had.subList(0, want.size()).equals(want) ? cut(stored, scenario) : null;
    }

    /**
     * {@code run} as far as {@code scenario} goes: its later steps left out, and a failure in one of them doesn't
     * count. Its exit stays: it's the same shutdown, after more steps.
     */
    static RunResult cut(RunResult run, Scenario scenario) {
        List<RunResult.Step> steps = run.steps().stream().filter(s -> scenario.steps().contains(s.name())).toList();
        if (steps.size() == run.steps().size()) {
            return run;
        }
        RunResult.Step failed = run.failedStep();
        boolean failedLater = failed != null && !scenario.steps().contains(failed.name());
        if (failedLater) {
            // The exit after a failure never ran; for the steps kept, the run went on, so their exit was fine.
            steps = steps.stream().map(s -> s.name().equals("exit") && s.status() != RunResult.Status.PASSED
                    ? new RunResult.Step("exit", RunResult.Status.PASSED, s.startSecond(), 0, s.values(), "", s.problems())
                    : s).toList();
        }
        return new RunResult(run.profile(), run.startedAt(), run.lich(), run.seconds(), steps,
                failedLater ? RunResult.Ending.COMPLETED : run.ending(), failedLater ? "" : run.detail(),
                failedLater ? "" : run.progress(), run.gameDir(), run.log(), run.key());
    }

    private static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
