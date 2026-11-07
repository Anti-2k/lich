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
import java.util.concurrent.TimeUnit;

/**
 * The local vanilla test server ({@code run/server-<mc>}, offline mode, bound to 127.0.0.1), started on Java 8 for the
 * scenario's {@code server} step. It starts while the first game boots; the driver waits for its port before
 * connecting. Its console goes to {@code run/harness/server-<mc>.out}.
 */
final class TestServer implements AutoCloseable {
    private final Process process;
    private final Writer console;
    private volatile boolean ready;

    private TestServer(Process process, Path consoleLog) throws IOException {
        this.process = process;
        this.console = Files.newBufferedWriter(consoleLog, StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.contains("Done (")) {
                        ready = true;
                    }
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
            case "1.12.2" -> 25597;
            default -> throw new IllegalArgumentException("No test server for " + mc);
        };
    }

    /** Whether {@code run/server-<mc>} has a server to start. */
    static boolean available(Path runDir, String mc) {
        return Files.isRegularFile(runDir.resolve("server-" + mc).resolve("server.jar"));
    }

    static TestServer start(Path runDir, String mc, Path java8) throws IOException {
        Path dir = runDir.resolve("server-" + mc);
        if (!available(runDir, mc)) {
            throw new IOException("No test server in " + dir + " (see AGENTS.md: vanilla server.jar, offline mode, 127.0.0.1)");
        }
        int port = port(mc);
        // Someone else's server on the port would get the client instead.
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            probe.getLocalPort();
        } catch (IOException e) {
            throw new IOException("Port " + port + " is in use; stop the server on it first", e);
        }
        System.out.println("[harness] Starting the " + mc + " test server on port " + port);
        Process process = new ProcessBuilder(java8.toString(), "-Xmx1G", "-jar", "server.jar", "nogui")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        Path console = runDir.resolve("harness").resolve("server-" + mc + ".out");
        Files.createDirectories(console.getParent());
        return new TestServer(process, console);
    }

    /** "ready", "starting" or "stopped", for a failed server step's report. */
    String state() {
        return !process.isAlive() ? "stopped (exit code " + process.exitValue() + ")" : ready ? "ready" : "still starting";
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
}
