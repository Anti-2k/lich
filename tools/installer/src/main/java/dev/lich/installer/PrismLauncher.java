package dev.lich.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Where Prism Launcher keeps its data and instances on this machine, whether it's running, and which Java to use. */
final class PrismLauncher {
    private static final Pattern JAVA_VERSION = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?");

    private PrismLauncher() {
    }

    /** Prism's data folders that exist here, most likely first: the standard install, then Flatpak's. */
    static List<Path> findDataDirs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                candidates.add(Path.of(appData, "PrismLauncher"));
            }
        } else if (os.contains("mac")) {
            candidates.add(home.resolve("Library/Application Support/PrismLauncher"));
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            candidates.add(xdg != null && !xdg.isEmpty() ? Path.of(xdg, "PrismLauncher") : home.resolve(".local/share/PrismLauncher"));
            candidates.add(home.resolve(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"));
        }
        return candidates.stream().filter(PrismLauncher::isDataDir).toList();
    }

    /** A Prism data folder has {@code prismlauncher.cfg} (a portable install keeps it next to the executable). */
    static boolean isDataDir(Path dir) {
        return Files.isRegularFile(dir.resolve("prismlauncher.cfg"));
    }

    /** The instances folder: {@code InstanceDir} in {@code prismlauncher.cfg}, relative to the data folder unless absolute. */
    static Path instancesDir(Path dataDir) throws IOException {
        String configured = PrismInstance.IniFile.read(dataDir.resolve("prismlauncher.cfg")).get("InstanceDir");
        return dataDir.resolve(configured == null || configured.isEmpty() ? "instances" : configured);
    }

    static List<Path> instances(Path dataDir) throws IOException {
        Path dir = instancesDir(dataDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(PrismInstance::isInstance).sorted().toList();
        }
    }

    /** A free folder name for a new instance called {@code name}. */
    static Path newInstanceDir(Path dataDir, String name) throws IOException {
        Path dir = instancesDir(dataDir);
        String base = name.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        if (base.isEmpty() || base.chars().allMatch(c -> c == '.')) {
            base = "Lich";
        }
        Path candidate = dir.resolve(base);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = dir.resolve(base + " (" + i + ")");
        }
        return candidate;
    }

    /**
     * Whether Prism is running. It keeps instance settings in memory and writes them back (on launch, on any
     * settings change), which would undo the installer's edits, so it should be closed first.
     */
    static boolean isRunning() {
        long self = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses()
                .filter(process -> process.pid() != self)
                .map(process -> process.info().command().orElse(""))
                .map(command -> Path.of(command.isEmpty() ? "-" : command).getFileName().toString().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.equals("prismlauncher") || name.equals("prismlauncher.exe"));
    }

    /**
     * The Java running the installer, as the instance should start it: on Windows {@code javaw.exe}, which opens no
     * console window. The installer is Java 25 bytecode, so this Java is new enough.
     */
    static Path currentJava() {
        Path java = Path.of(ProcessHandle.current().info().command().orElseThrow(
                () -> new IllegalStateException("Can't tell which Java runs the installer: pass --java")));
        Path javaw = java.resolveSibling("javaw.exe");
        return Files.isRegularFile(javaw) ? javaw : java;
    }

    /** The feature version of the Java at {@code java} (25 for 25.0.4, 8 for 1.8.0_51), from {@code java -version}. */
    static int javaVersion(Path java) throws IOException {
        // javaw has no console to print to on some systems: ask java next to it.
        Path console = java.getFileName().toString().equalsIgnoreCase("javaw.exe") ? java.resolveSibling("java.exe") : java;
        Process process = new ProcessBuilder(console.toString(), "-version").redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking " + java, e);
        }
        Matcher matcher = JAVA_VERSION.matcher(output);
        if (!matcher.find()) {
            throw new IOException("Not a Java runtime (no version in `java -version`): " + java);
        }
        int major = Integer.parseInt(matcher.group(1));
        return major == 1 && matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : major;
    }
}
