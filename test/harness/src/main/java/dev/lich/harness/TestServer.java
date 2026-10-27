package dev.lich.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The local vanilla test server ({@code run/server-<mc>}, offline mode, bound to 127.0.0.1), started on Java 8 for a
 * harness run. Its console tells the harness when the client logged in and whether it lost the connection, which is
 * the same on every client profile, unlike the client's own log.
 */
final class TestServer implements AutoCloseable {
    private final Process process;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Writer console;

    private TestServer(Process process, Path consoleLog) throws IOException {
        this.process = process;
        this.console = Files.newBufferedWriter(consoleLog, StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    lines.add(line);
                    synchronized (console) {
                        console.write(line);
                        console.write(System.lineSeparator());
                        console.flush();
                    }
                }
            } catch (IOException ignored) {
                // The server was stopped.
            }
        }, "test-server-console");
        reader.setDaemon(true);
        reader.start();
    }

    static int port(String mc) {
        return switch (mc) {
            case "1.8.9" -> 25599;
            case "1.7.10" -> 25598;
            default -> throw new IllegalArgumentException("No test server for " + mc);
        };
    }

    static TestServer start(Path runDir, String mc, Path java8, Path consoleLog) throws IOException, InterruptedException {
        Path dir = runDir.resolve("server-" + mc);
        if (!Files.isRegularFile(dir.resolve("server.jar"))) {
            throw new IOException("No test server in " + dir + " (see AGENTS.md: vanilla server.jar, offline mode, 127.0.0.1)");
        }
        int port = port(mc);
        // Someone else's server on the port would get the client instead, and the harness couldn't read its console.
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            probe.getLocalPort();
        } catch (IOException e) {
            throw new IOException("Port " + port + " is in use; stop the server on it first", e);
        }
        log("Starting the " + mc + " test server on port " + port);
        Process process = new ProcessBuilder(java8.toString(), "-Xmx1G", "-jar", "server.jar", "nogui")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        TestServer server = new TestServer(process, consoleLog);
        if (server.await(line -> line.contains("Done ("), 0, Duration.ofMinutes(3)) < 0) {
            server.close();
            throw new IOException("The test server didn't start; see " + consoleLog);
        }
        return server;
    }

    /** The number of console lines so far, for {@link #await}'s {@code from}. */
    int mark() {
        return lines.size();
    }

    /** The index of the first line from {@code from} on that matches, waiting up to {@code timeout}; -1 if none. */
    int await(Predicate<String> match, int from, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int next = from;
        do {
            for (; next < lines.size(); next++) {
                if (match.test(lines.get(next))) {
                    return next;
                }
            }
            if (!process.isAlive()) {
                return -1;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        return -1;
    }

    /** The first line from {@code from} on that matches, without waiting; null if none. */
    String find(Predicate<String> match, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (match.test(lines.get(i))) {
                return lines.get(i);
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (process.isAlive()) {
            try {
                process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (IOException e) {
                process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        synchronized (console) {
            console.close();
        }
    }

    private static void log(String message) {
        System.out.println("[harness] " + message);
    }
}
