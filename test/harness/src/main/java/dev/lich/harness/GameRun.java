package dev.lich.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One profile's run of a pack: a fresh copy of the pack's game directory, the game started through devlaunch with an
 * automatic join to the test server, and then held in the world for a while. The run passes if the client logs in
 * and is still connected at the end.
 */
final class GameRun {
    /** How a run ended. */
    enum Outcome {
        STAYED("joined and stayed"),
        DISCONNECTED("joined, then lost the connection"),
        EXITED("exited before joining"),
        TIMED_OUT("didn't join in time");

        final String text;

        Outcome(String text) {
            this.text = text;
        }
    }

    record Result(String profile, Outcome outcome, String detail, Path gameDir, Duration toMenu, Duration toJoin) {
    }

    private static final String MARKER = ".lich-harness";
    private static final String PLAYER = "Dev";

    private final Path runDir;
    private final Path java8;
    private final Path java25;

    GameRun(Path runDir, Path java8, Path java25) {
        this.runDir = runDir;
        this.java8 = java8;
        this.java25 = java25;
    }

    /** {@code profile} is a devlaunch profile: {@code forge} (Java 8 baseline) or {@code lich-forge} (Java 25). */
    Result run(Pack pack, Path template, String profile, TestServer server, Duration hold, Duration timeout)
            throws IOException, InterruptedException {
        boolean lich = profile.startsWith("lich");
        Path packDir = runDir.resolve("harness").resolve(pack.id());
        Path gameDir = packDir.resolve(profile);
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
        devlaunch.addAll(List.of("--game-arg", "--server", "--game-arg", "127.0.0.1",
                "--game-arg", "--port", "--game-arg", Integer.toString(TestServer.port(pack.mc()))));

        log(profile + ": starting " + pack.name() + " on " + java);
        int mark = server.mark();
        long start = System.nanoTime();
        Process game = new ProcessBuilder(command(devlaunch))
                .directory(runDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(packDir.resolve(profile + ".out").toFile())
                .start();
        try {
            LogTail log = new LogTail(gameDir.resolve("logs").resolve("latest.log"));
            Duration toMenu = null;
            Duration toJoin = null;
            int joinedAt = -1;
            long deadline = start + timeout.toNanos();
            while (true) {
                if (toMenu == null && log.contains("Sound engine started")) {
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
                        return new Result(profile, Outcome.DISCONNECTED, lost.substring(lost.indexOf(PLAYER)), gameDir, toMenu, toJoin);
                    }
                }
                if (!game.isAlive()) {
                    return new Result(profile, joinedAt >= 0 ? Outcome.DISCONNECTED : Outcome.EXITED,
                            "exit code " + game.exitValue() + crashReport(gameDir), gameDir, toMenu, toJoin);
                }
                if (System.nanoTime() > deadline) {
                    return joinedAt >= 0
                            ? new Result(profile, Outcome.STAYED, "", gameDir, toMenu, toJoin)
                            : new Result(profile, Outcome.TIMED_OUT, "after " + seconds(timeout) + crashReport(gameDir), gameDir, toMenu, null);
                }
                Thread.sleep(500);
            }
        } finally {
            stop(game);
        }
    }

    /** No sound and no pause when the window isn't focused (it usually isn't: the user works on the same desktop). */
    private static void quietOptions(Path options) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(options)) {
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
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
        Files.write(options, lines, StandardCharsets.UTF_8);
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
    private static final class LogTail {
        private final Path file;
        private long position;
        private String carry = "";

        LogTail(Path file) {
            this.file = file;
        }

        boolean contains(String text) throws IOException {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file)) {
                if (channel.size() < position) {
                    // The game rotated the log at startup.
                    position = 0;
                    carry = "";
                }
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) Math.min(channel.size() - position, 8 << 20));
                channel.read(buffer, position);
                position += buffer.position();
                // Keep the end, in case the text is split between two reads.
                String chunk = carry + new String(buffer.array(), 0, buffer.position(), StandardCharsets.ISO_8859_1);
                carry = chunk.substring(Math.max(0, chunk.length() - text.length()));
                return chunk.contains(text);
            }
        }
    }

    private static String crashReport(Path gameDir) throws IOException {
        Path reports = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(reports)) {
            return "";
        }
        try (Stream<Path> files = Files.list(reports)) {
            return files.map(p -> p.getFileName().toString()).sorted().reduce((a, b) -> b).map(name -> ", crash report " + name).orElse("");
        }
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
