package dev.lich.devlaunch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command-line options for devlaunch. */
record Options(
        String version,
        Path versionJson,
        Path java,
        Path runDir,
        Path gameDir,
        String username,
        String mainClass,
        boolean skipAssets,
        boolean dryRun,
        List<String> jvmArgs,
        List<String> gameArgs,
        List<Path> extraClasspath) {

    static final String USAGE = """
            Usage: devlaunch (--version <id> | --version-json <file>) [options]
              --version <id>          Mojang version id, e.g. 1.8.9 or 1.7.10
              --version-json <file>   local version JSON (may use inheritsFrom), e.g. a Lich profile
              --java <path>           java executable for the game (default: the JVM running devlaunch)
              --run-dir <dir>         cache and game directories live here (default: ./run)
              --game-dir <dir>        game directory (default: <run-dir>/<version id>)
              --username <name>       offline username (default: Dev)
              --main-class <class>    override the version's main class
              --jvm-arg <arg>         extra JVM argument, repeatable
              --game-arg <arg>        extra game argument, repeatable
              --extra-cp <path>       extra classpath entry, put first, repeatable
              --skip-assets           only fetch the asset index, not the objects (no sounds)
              --dry-run               resolve and download everything, print the command, don't launch
            """;

    static Options parse(String[] args) {
        String version = null;
        Path versionJson = null;
        Path java = null;
        Path runDir = Path.of("run");
        Path gameDir = null;
        String username = "Dev";
        String mainClass = null;
        boolean skipAssets = false;
        boolean dryRun = false;
        List<String> jvmArgs = new ArrayList<>();
        List<String> gameArgs = new ArrayList<>();
        List<Path> extraClasspath = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--version" -> version = value(args, ++i, option);
                case "--version-json" -> versionJson = Path.of(value(args, ++i, option));
                case "--java" -> java = Path.of(value(args, ++i, option));
                case "--run-dir" -> runDir = Path.of(value(args, ++i, option));
                case "--game-dir" -> gameDir = Path.of(value(args, ++i, option)).toAbsolutePath().normalize();
                case "--username" -> username = value(args, ++i, option);
                case "--main-class" -> mainClass = value(args, ++i, option);
                case "--jvm-arg" -> jvmArgs.add(value(args, ++i, option));
                case "--game-arg" -> gameArgs.add(value(args, ++i, option));
                case "--extra-cp" -> extraClasspath.add(Path.of(value(args, ++i, option)).toAbsolutePath());
                case "--skip-assets" -> skipAssets = true;
                case "--dry-run" -> dryRun = true;
                case "--help", "-h" -> throw new IllegalArgumentException(USAGE);
                default -> throw new IllegalArgumentException("Unknown option " + option + "\n\n" + USAGE);
            }
        }
        if ((version == null) == (versionJson == null)) {
            throw new IllegalArgumentException("Give exactly one of --version and --version-json\n\n" + USAGE);
        }
        if (java == null) {
            String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            java = Path.of(System.getProperty("java.home"), "bin", exe);
        }
        if (!Files.isRegularFile(java)) {
            throw new IllegalArgumentException("Java executable not found: " + java);
        }
        return new Options(version, versionJson, java, runDir.toAbsolutePath().normalize(), gameDir, username, mainClass,
                skipAssets, dryRun, List.copyOf(jvmArgs), List.copyOf(gameArgs), List.copyOf(extraClasspath));
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
