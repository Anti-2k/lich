package dev.lich.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One profile's run of a pack: a fresh copy of the pack's game directory and the game started through devlaunch.
 * {@link Mode#SERVER} joins the test server automatically and holds there; the run passes if the client logs in and is
 * still connected at the end. {@link Mode#WORLD} adds the world driver, which plays a singleplayer world, so the
 * integrated server runs the mods' server side too (worldgen, tile entities, saving); the run passes if the driver
 * gets to the end without a crash report.
 */
final class GameRun {
    /** What a run does in the game. */
    enum Mode {
        SERVER("joins the local test server and stays in the world", "Joined", "report.md", ""),
        WORLD("plays a singleplayer world (a new one, or the map the pack ships): tours it, stays, saves and quits, then "
                + "opens it again", "In world",
                "world-report.md", "world-");

        final String description;
        final String joinColumn;
        final String reportName;
        final String dirPrefix;

        Mode(String description, String joinColumn, String reportName, String dirPrefix) {
            this.description = description;
            this.joinColumn = joinColumn;
            this.reportName = reportName;
            this.dirPrefix = dirPrefix;
        }
    }

    /** How a run ended. */
    enum Outcome {
        STAYED("joined and stayed", true),
        DISCONNECTED("joined, then lost the connection", false),
        COMPLETED("played, saved and reopened the world", true),
        CRASHED("crashed", false),
        DRIVER_FAILED("the world driver stopped", false),
        EXITED("exited early", false),
        TIMED_OUT("didn't join in time", false),
        STALLED("stalled", false);

        final String text;
        final boolean passed;

        Outcome(String text, boolean passed) {
            this.text = text;
            this.passed = passed;
        }
    }

    /**
     * {@code toJoin} is when the client joined the server, or the singleplayer world; {@code stats} the driver's
     * numbers per phase of a world run (loaded chunks, entities, tile entities, mean tick time).
     */
    record Result(String profile, Outcome outcome, String detail, Path gameDir, Duration toMenu, Duration toJoin,
                  Map<String, Map<String, String>> stats) {
    }

    private static final String MARKER = ".lich-harness";
    private static final String PLAYER = "Dev";
    private static final String DRIVER = "dev.lich.harness.driver.WorldDriver";
    /** Both profiles generate the same world. */
    private static final long SEED = 7_350_118_032L;
    /** How long the game may take to exit once the driver has finished. */
    private static final Duration EXIT_GRACE = Duration.ofSeconds(60);

    private final Path runDir;
    private final Path java8;
    private final Path java25;

    GameRun(Path runDir, Path java8, Path java25) {
        this.runDir = runDir;
        this.java8 = java8;
        this.java25 = java25;
    }

    /** A started game. */
    private record Started(Process game, Path gameDir, long start) {
    }

    /** {@code profile} is a devlaunch profile: {@code forge} (Java 8 baseline) or {@code lich-forge} (Java 25). */
    Result joinServer(Pack pack, Path template, String profile, TestServer server, Duration hold, Duration timeout)
            throws IOException, InterruptedException {
        int mark = server.mark();
        Started started = start(pack, template, profile, Mode.SERVER, List.of(
                "--game-arg", "--server", "--game-arg", "127.0.0.1",
                "--game-arg", "--port", "--game-arg", Integer.toString(TestServer.port(pack.mc()))));
        Process game = started.game();
        Path gameDir = started.gameDir();
        long start = started.start();
        try {
            LogTail log = new LogTail(gameDir.resolve("logs").resolve("latest.log"));
            Duration toMenu = null;
            Duration toJoin = null;
            int joinedAt = -1;
            long deadline = start + timeout.toNanos();
            while (true) {
                if (toMenu == null && log.newLines().stream().anyMatch(GameRun::isMenu)) {
                    toMenu = since(start);
                    log(profile + ": main menu after " + seconds(toMenu));
                }
                if (joinedAt < 0) {
                    joinedAt = server.await(line -> line.contains(PLAYER + "[/127.0.0.1") && line.contains("logged in"), mark,
                            Duration.ZERO);
                    if (joinedAt >= 0) {
                        toJoin = since(start);
                        log(profile + ": joined the server after " + seconds(toJoin) + "; holding " + seconds(hold));
                        deadline = System.nanoTime() + hold.toNanos();
                    }
                }
                if (joinedAt >= 0) {
                    String lost = server.find(line -> line.contains(PLAYER + " lost connection"), joinedAt);
                    if (lost != null) {
                        return new Result(profile, Outcome.DISCONNECTED, lost.substring(lost.indexOf(PLAYER)), gameDir, toMenu,
                                toJoin, Map.of());
                    }
                }
                if (!game.isAlive()) {
                    return new Result(profile, joinedAt >= 0 ? Outcome.DISCONNECTED : Outcome.EXITED,
                            "exit code " + game.exitValue() + crashReport(gameDir), gameDir, toMenu, toJoin, Map.of());
                }
                if (System.nanoTime() > deadline) {
                    return joinedAt >= 0
                            ? new Result(profile, Outcome.STAYED, "", gameDir, toMenu, toJoin, Map.of())
                            : new Result(profile, Outcome.TIMED_OUT, "after " + seconds(timeout) + crashReport(gameDir), gameDir,
                            toMenu, null, Map.of());
                }
                Thread.sleep(500);
            }
        } finally {
            stop(game);
        }
    }

    /**
     * Plays a singleplayer world through the driver. {@code hold} is how long it stays after the tour; {@code stall}
     * how long the game may go without a driver event (startup included) before the run gives up.
     */
    Result playWorld(Pack pack, Path template, String profile, Duration hold, Duration stall)
            throws IOException, InterruptedException {
        Path packDir = runDir.resolve("harness").resolve(pack.id());
        Path driver = packDir.resolve("harness-driver.jar");
        try (InputStream in = GameRun.class.getResourceAsStream("harness-driver.jar")) {
            if (in == null) {
                throw new IOException("harness-driver.jar is missing from the harness's resources");
            }
            Files.createDirectories(packDir);
            Files.copy(in, driver, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> args = new ArrayList<>(List.of(
                "--extra-cp", driver.toString(),
                "--game-arg", "--tweakClass", "--game-arg", DRIVER,
                "--jvm-arg", "-Dlich.harness.seed=" + SEED,
                "--jvm-arg", "-Dlich.harness.worldType=" + pack.worldType(),
                "--jvm-arg", "-Dlich.harness.hold=" + hold.toSeconds()));
        if (pack.save() != null) {
            args.addAll(List.of("--jvm-arg", "-Dlich.harness.save=" + pack.save()));
        }
        Started started = start(pack, template, profile, Mode.WORLD, args);
        Process game = started.game();
        Path gameDir = started.gameDir();
        long start = started.start();
        try {
            LogTail log = new LogTail(gameDir.resolve("logs").resolve("latest.log"));
            Set<String> oldCrashes = crashReports(gameDir);
            Map<String, Map<String, String>> stats = new LinkedHashMap<>();
            Duration toMenu = null;
            Duration toWorld = null;
            long lastEvent = start;
            long doneAt = -1;
            while (true) {
                for (String line : log.newLines()) {
                    if (toMenu == null && isMenu(line)) {
                        toMenu = since(start);
                        log(profile + ": main menu after " + seconds(toMenu));
                    }
                    DriverEvent event = DriverEvent.parse(line);
                    if (event == null) {
                        continue;
                    }
                    lastEvent = System.nanoTime();
                    log(profile + ": " + event.text());
                    switch (event.name()) {
                        case "in-world" -> {
                            if (toWorld == null) {
                                toWorld = since(start);
                            }
                        }
                        case "stats" -> {
                            Map<String, String> values = new LinkedHashMap<>(event.values());
                            String phase = values.remove("phase");
                            stats.put(phase, values);
                        }
                        case "failed" -> {
                            return new Result(profile, Outcome.DRIVER_FAILED, event.text().substring("failed".length()).strip()
                                    + crashReport(gameDir), gameDir, toMenu, toWorld, stats);
                        }
                        case "done" -> doneAt = System.nanoTime();
                        default -> {
                        }
                    }
                }
                // A crash report means a crash even when the game carries on (CoreTweaks catches them).
                String crash = newCrash(gameDir, oldCrashes);
                if (crash != null) {
                    return new Result(profile, Outcome.CRASHED, crash, gameDir, toMenu, toWorld, stats);
                }
                if (!game.isAlive()) {
                    return doneAt >= 0
                            ? new Result(profile, Outcome.COMPLETED, "", gameDir, toMenu, toWorld, stats)
                            : new Result(profile, Outcome.EXITED, "exit code " + game.exitValue(), gameDir, toMenu, toWorld, stats);
                }
                if (doneAt >= 0 && System.nanoTime() - doneAt > EXIT_GRACE.toNanos()) {
                    return new Result(profile, Outcome.COMPLETED, "the game didn't exit within " + seconds(EXIT_GRACE)
                            + " of the driver's end", gameDir, toMenu, toWorld, stats);
                }
                if (System.nanoTime() - lastEvent > stall.toNanos()) {
                    return new Result(profile, Outcome.STALLED, "no driver event for " + seconds(stall)
                            + threadDump(game, gameDir.resolve("stall-threads.txt")), gameDir, toMenu, toWorld, stats);
                }
                Thread.sleep(500);
            }
        } finally {
            stop(game);
        }
    }

    /** Prepares a fresh game directory and starts the game in it, with {@code extra} devlaunch arguments. */
    private Started start(Pack pack, Path template, String profile, Mode mode, List<String> extra)
            throws IOException, InterruptedException {
        boolean lich = profile.startsWith("lich");
        Path packDir = runDir.resolve("harness").resolve(pack.id());
        Path gameDir = packDir.resolve(mode.dirPrefix + profile);
        if (Files.exists(gameDir)) {
            if (!Files.exists(gameDir.resolve(MARKER))) {
                throw new IOException(gameDir + " exists and isn't a harness directory; not deleting it");
            }
            PackInstaller.deleteTree(gameDir);
        }
        Paths.copyTree(template, gameDir);
        Files.writeString(gameDir.resolve(MARKER), pack.id() + " " + profile + "\n");
        quietOptions(gameDir.resolve("options.txt"));

        String version = profile + "-" + pack.mc();
        Path java = lich ? java25 : java8;
        List<String> devlaunch = new ArrayList<>(List.of(
                "--version", version, "--java", java.toString(), "--run-dir", runDir.toString(),
                "--game-dir", gameDir.toString(), "--username", PLAYER,
                "--jvm-arg", "-Xmx" + pack.memoryMb() + "M",
                // Lists every mixin applied, so the report can compare them (PLAN.md §7).
                "--jvm-arg", "-Dmixin.debug.verbose=true"));
        // Downloads everything first, so the timings below are the game's own, and gives the class path.
        List<String> dryRun = devlaunch(devlaunch, List.of("--dry-run"));
        if (lich) {
            devlaunch.addAll(List.of("--jvm-arg", "-Dlich.dev.noMouseGrab=true"));
        } else {
            Path lwjgl = lwjgl2(dryRun);
            devlaunch.addAll(List.of("--extra-cp",
                    NoGrabMouse.build(lwjgl, packDir.resolve("nograb-" + lwjgl.getFileName())).toString()));
        }
        devlaunch.addAll(extra);

        log(profile + ": starting " + pack.name() + " on " + java);
        long start = System.nanoTime();
        Process game = new ProcessBuilder(command(devlaunch))
                .directory(runDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(packDir.resolve(mode.dirPrefix + profile + ".out").toFile())
                .start();
        return new Started(game, gameDir, start);
    }

    private static boolean isMenu(String line) {
        return line.contains("Sound engine started");
    }

    /**
     * No sound and no pause when the window isn't focused (it usually isn't: the user works on the same desktop).
     * Byte for byte otherwise: the game writes the file in the platform charset, and packs ship it that way.
     */
    private static void quietOptions(Path options) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(options)) {
            for (String line : Files.readAllLines(options, StandardCharsets.ISO_8859_1)) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    values.put(line.substring(0, colon), line.substring(colon + 1));
                }
            }
        }
        values.put("pauseOnLostFocus", "false");
        values.put("soundCategory_master", "0.0");
        List<String> lines = new ArrayList<>();
        values.forEach((k, v) -> lines.add(k + ":" + v));
        Files.write(options, lines, StandardCharsets.ISO_8859_1);
    }

    /** Runs devlaunch to completion (a dry run) and returns its output. */
    private List<String> devlaunch(List<String> args, List<String> more) throws IOException, InterruptedException {
        List<String> all = new ArrayList<>(args);
        all.addAll(more);
        Process process = new ProcessBuilder(command(all)).directory(runDir.toFile()).redirectErrorStream(true).start();
        List<String> output = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                output.add(line);
            }
        }
        if (process.waitFor() != 0) {
            throw new IOException("devlaunch failed:\n" + String.join("\n", output));
        }
        return output;
    }

    /** devlaunch in a child JVM, on the harness's own class path (which has devlaunch on it). */
    private static List<String> command(List<String> args) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "dev.lich.devlaunch.Main"));
        command.addAll(args);
        return command;
    }

    /** LWJGL 2's main jar on the dry run's class path. */
    private static Path lwjgl2(List<String> dryRun) throws IOException {
        for (int i = 0; i + 1 < dryRun.size(); i++) {
            if (dryRun.get(i).strip().equals("-cp")) {
                for (String entry : dryRun.get(i + 1).strip().split(java.io.File.pathSeparator)) {
                    Path path = Path.of(entry);
                    String name = path.getFileName().toString();
                    if (name.startsWith("lwjgl-2") && name.endsWith(".jar")) {
                        return path;
                    }
                }
            }
        }
        throw new IOException("No LWJGL 2 jar on the game's class path");
    }

    /** Reads only what was added to the game's log since the last look: with verbose Mixin output it gets big. */
    static final class LogTail {
        private final Path file;
        private long position;
        private String partial = "";

        LogTail(Path file) {
            this.file = file;
        }

        /** The complete lines added since the last call. */
        List<String> newLines() throws IOException {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file)) {
                if (channel.size() < position) {
                    // The game rotated the log at startup.
                    position = 0;
                    partial = "";
                }
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) Math.min(channel.size() - position, 8 << 20));
                channel.read(buffer, position);
                position += buffer.position();
                String chunk = partial + new String(buffer.array(), 0, buffer.position(), StandardCharsets.ISO_8859_1);
                int end = chunk.lastIndexOf('\n');
                // Keep an unfinished last line for the next call.
                partial = chunk.substring(end + 1);
                return end < 0 ? List.of() : chunk.substring(0, end).lines().toList();
            }
        }
    }

    private static Set<String> crashReports(Path gameDir) throws IOException {
        Path reports = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(reports)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(reports)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The first crash report written during the run, as its file name, description and exception; or null. */
    private static String newCrash(Path gameDir, Set<String> old) throws IOException, InterruptedException {
        for (String name : crashReports(gameDir)) {
            if (!old.contains(name)) {
                // The game may still be writing it.
                Thread.sleep(1000);
                return name + crashSummary(gameDir.resolve("crash-reports").resolve(name));
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

    private static String crashReport(Path gameDir) throws IOException {
        return crashReports(gameDir).stream().reduce((a, b) -> b).map(name -> ", crash report " + name).orElse("");
    }

    /**
     * Saves the game's threads ({@code jcmd Thread.print}) before a stalled run is stopped, so the hang can be read
     * afterwards; ", threads in <file>", or empty if that didn't work. The game is devlaunch's child process.
     */
    private static String threadDump(Process devlaunch, Path file) {
        Path jcmd = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "jcmd.exe" : "jcmd");
        for (ProcessHandle game : devlaunch.descendants().toList()) {
            try {
                Process dump = new ProcessBuilder(jcmd.toString(), Long.toString(game.pid()), "Thread.print")
                        .redirectErrorStream(true).redirectOutput(file.toFile()).start();
                if (dump.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && dump.exitValue() == 0) {
                    return ", threads in " + file;
                }
                dump.destroyForcibly();
            } catch (IOException e) {
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
        return "";
    }

    /** Stops devlaunch and the game it started (its child). */
    private static void stop(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor();
    }

    private static Duration since(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    static String seconds(Duration duration) {
        return duration == null ? "-" : duration.toSeconds() + " s";
    }

    private static void log(String message) {
        System.out.println("[harness] " + message);
    }
}
