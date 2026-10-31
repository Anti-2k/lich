package dev.lich.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Follows one profile's game through the scenario, from the driver's events, until it completes, fails a step,
 * crashes (a crash report counts even when the game carries on: CoreTweaks catches crashes), the JVM dies, it runs out
 * of memory, exits early, or a step overruns its deadline (a thread dump is saved first). Then the game is stopped and
 * its log read.
 */
final class GameRun {
    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Pack pack;
    private final Profile profile;
    private final Scenario scenario;
    private final RunResult baseline;
    private final Path gameDir;
    private final Path jcmd;

    private final Map<String, RunResult.Step> steps = new LinkedHashMap<>();
    private final Map<String, List<String>> problems = new LinkedHashMap<>();
    private String current;
    private long currentStart;
    private String progress = "";
    private String screen = "";
    private long launched;

    GameRun(Pack pack, Profile profile, Scenario scenario, RunResult baseline, Path gameDir, Path java) {
        this.pack = pack;
        this.profile = profile;
        this.scenario = scenario;
        this.baseline = baseline;
        this.gameDir = gameDir;
        this.jcmd = java.resolveSibling(java.getFileName().toString().replace("java", "jcmd"));
    }

    /** Follows {@code game}, started just now, to its end. */
    RunResult follow(Process game, DriverLink link, String lich, String key) throws IOException, InterruptedException {
        launched = System.nanoTime();
        String startedAt = LocalDateTime.now().format(STARTED);
        // The boot step runs from the game's start, not from the driver's first event.
        current = "boot";
        currentStart = launched;
        Set<String> oldCrashes = crashReports();
        RunResult.Ending ending = null;
        String detail = "";
        try {
            loop:
            while (true) {
                for (Event event : link.drain()) {
                    String failed = handle(event);
                    if (failed != null) {
                        ending = "true".equals(event.values().get("driver"))
                                ? RunResult.Ending.HARNESS_ERROR : RunResult.Ending.DRIVER_FAILED;
                        detail = failed;
                        // The failure may be the start of a crash: give its report a moment.
                        Thread.sleep(2000);
                        String crash = newCrash(oldCrashes);
                        if (crash != null) {
                            ending = RunResult.Ending.CRASHED;
                            detail = crash;
                        }
                        break loop;
                    }
                }
                String crash = newCrash(oldCrashes);
                if (crash != null) {
                    ending = RunResult.Ending.CRASHED;
                    detail = crash;
                    break;
                }
                if (!game.isAlive()) {
                    if (current.equals("exit")) {
                        endStep("exit", Map.of("exitCode", Integer.toString(game.exitValue())), "");
                        ending = RunResult.Ending.COMPLETED;
                    } else {
                        ending = hsErrFiles().isEmpty() ? RunResult.Ending.EXITED : RunResult.Ending.JVM_CRASHED;
                        detail = endingDetail(ending, game.exitValue());
                    }
                    break;
                }
                Duration deadline = scenario.deadline(current, baseline);
                if (System.nanoTime() - currentStart > deadline.toNanos()) {
                    String threads = threadDump(game, gameDir.resolveSibling("stall-threads-" + profile.id + ".txt"));
                    if (current.equals("exit")) {
                        failStep("exit", "didn't exit within " + deadline.toSeconds() + " s" + threads);
                        ending = RunResult.Ending.COMPLETED;
                    } else {
                        ending = RunResult.Ending.STALLED;
                        detail = "no end of " + current + " within " + deadline.toSeconds() + " s"
                                + (screen.isEmpty() ? "" : ", screen " + screen) + threads;
                    }
                    break;
                }
                Thread.sleep(250);
            }
        } finally {
            stop(game);
        }
        // A crash while shutting down (1.8.9's shutdown race) leaves its report as the game exits.
        String lateCrash = ending == RunResult.Ending.COMPLETED ? newCrash(oldCrashes) : null;
        if (lateCrash != null) {
            ending = RunResult.Ending.CRASHED;
            detail = lateCrash;
            steps.remove(current);
        }
        if (ending != RunResult.Ending.COMPLETED && steps.get(current) == null) {
            failStep(current, detail);
        }
        LogDigest log = LogDigest.read(gameDir.resolve("logs").resolve("latest.log"), gameDir);
        if (log.outOfMemory() && ending != RunResult.Ending.COMPLETED && ending != RunResult.Ending.JVM_CRASHED) {
            ending = RunResult.Ending.OUT_OF_MEMORY;
        }
        return new RunResult(profile.id, startedAt, lich, seconds(launched), allSteps(), ending, detail, progress,
                gameDir.toString(), log, key);
    }

    /** Takes in one event; returns the reason if the driver failed. */
    private String handle(Event event) {
        String line = event.describe();
        switch (event.type()) {
            case "start" -> {
                if (!event.step().equals("boot")) {
                    current = event.step();
                    currentStart = System.nanoTime();
                }
                log("started " + event.step());
            }
            case "end" -> {
                endStep(event.step(), event.values(), "");
                log(event.step() + " done in " + steps.get(event.step()).seconds() + " s" + (line.isEmpty() ? "" : ": " + line));
            }
            case "fail" -> {
                log(event.step() + " failed: " + event.text());
                return event.text();
            }
            case "problem" -> {
                problems.computeIfAbsent(event.step(), s -> new ArrayList<>())
                        .add(LogDigest.normalize(line, List.of(gameDir.toString())));
                log(event.step() + " problem: " + line);
            }
            case "note" -> {
                if (event.values().containsKey("screen")) {
                    screen = event.values().get("screen");
                } else {
                    log(event.step() + ": " + line);
                }
                if (event.values().containsKey("answered")) {
                    problems.computeIfAbsent(event.step(), s -> new ArrayList<>()).add("answered a prompt: " + line);
                }
            }
            case "at" -> progress = event.step() + " " + line;
            case "hello" -> log("driver connected: " + line);
            default -> {
            }
        }
        return null;
    }

    private void endStep(String name, Map<String, String> values, String detail) {
        long start = name.equals(current) ? currentStart : launched;
        steps.put(name, new RunResult.Step(name, RunResult.Status.PASSED, (start - launched) / 1_000_000_000L,
                seconds(start), Map.copyOf(values), detail, problemsOf(name)));
    }

    private void failStep(String name, String detail) {
        steps.put(name, new RunResult.Step(name, RunResult.Status.FAILED, (currentStart - launched) / 1_000_000_000L,
                seconds(currentStart), Map.of(), detail, problemsOf(name)));
    }

    private List<String> problemsOf(String step) {
        return List.copyOf(problems.getOrDefault(step, List.of()));
    }

    private List<RunResult.Step> allSteps() {
        List<RunResult.Step> all = new ArrayList<>();
        for (String name : scenario.steps()) {
            RunResult.Step step = steps.get(name);
            all.add(step != null ? step : new RunResult.Step(name, RunResult.Status.NOT_REACHED, 0, 0, Map.of(), "",
                    problemsOf(name)));
        }
        return all;
    }

    /** Why a game that exited before the end did. */
    private String endingDetail(RunResult.Ending ending, int exitCode) throws IOException {
        String crash = crashReports().stream().reduce((a, b) -> b).map(name -> ", last crash report " + name).orElse("");
        if (ending == RunResult.Ending.JVM_CRASHED) {
            return "exit code " + exitCode + ", " + hsErrFiles().getFirst() + crash;
        }
        return "exit code " + exitCode + (screen.isEmpty() ? "" : ", screen " + screen) + crash;
    }

    private List<String> hsErrFiles() throws IOException {
        try (Stream<Path> files = Files.list(gameDir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("hs_err_pid")).sorted().toList();
        }
    }

    private Set<String> crashReports() throws IOException {
        Path reports = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(reports)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(reports)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The first crash report written during the run, as its file name, description and exception; or null. */
    private String newCrash(Set<String> old) throws IOException, InterruptedException {
        for (String name : crashReports()) {
            if (!old.contains(name)) {
                // The game may still be writing it.
                Thread.sleep(1000);
                return name + crashSummary(gameDir.resolve("crash-reports").resolve(name))
                        + (progress.isEmpty() ? "" : " (at " + progress + ")");
            }
        }
        return null;
    }

    /** ": <description>: <exception>" from a crash report; empty while it's still being written. */
    static String crashSummary(Path report) throws IOException {
        List<String> lines = Files.readAllLines(report, StandardCharsets.ISO_8859_1);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Description: ")) {
                String description = lines.get(i).substring("Description: ".length());
                for (int j = i + 1; j < lines.size(); j++) {
                    if (!lines.get(j).isBlank()) {
                        return ": " + description + ": " + lines.get(j).strip();
                    }
                }
                return ": " + description;
            }
        }
        return "";
    }

    /**
     * Saves the game's threads ({@code jcmd Thread.print}, from the game's own JDK) before it's stopped, so a hang can
     * be read afterwards; ", threads in <file>", or empty if that didn't work.
     */
    private String threadDump(Process game, Path file) {
        try {
            Process dump = new ProcessBuilder(jcmd.toString(), Long.toString(game.pid()), "Thread.print")
                    .redirectErrorStream(true).redirectOutput(file.toFile()).start();
            if (dump.waitFor(60, TimeUnit.SECONDS) && dump.exitValue() == 0) {
                return ", threads in " + file;
            }
            dump.destroyForcibly();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "";
    }

    private static void stop(Process game) throws InterruptedException {
        if (game.isAlive()) {
            game.descendants().forEach(ProcessHandle::destroyForcibly);
            game.destroyForcibly();
        }
        game.waitFor();
    }

    private static long seconds(long since) {
        return (System.nanoTime() - since) / 1_000_000_000L;
    }

    private void log(String message) {
        System.out.println("[harness] " + pack.id() + " " + profile.id + " " + seconds(launched) + "s: " + message);
    }
}
