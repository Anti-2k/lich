package dev.lich.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lich's test harness, so far for Forge mod compatibility through whole modpacks (PLAN.md §7): one pinned pack run in
 * the Forge baseline on Java 8 and through Lich on Java 25, joined to the test server or in a singleplayer world, then
 * the two logs compared.
 */
public final class Main {
    private static final String USAGE = """
            Usage: harness <command> <pack> [options]
              fetch <pack>     download the pack into <run-dir>/packs/<pack>/minecraft
              prism <pack>     create the Prism instances <pack>-forge-<mc> and <pack>-lich-<mc> from it
              run <pack>       run it in each profile, joined to the local test server, and write a report
              world <pack>     play a singleplayer world in each profile (create, tour, hold, save, reopen), and
                               write a report
            Options:
              --run-dir <dir>       Lich's run directory (cache, test servers, harness output)
              --java8-home <dir>    the JDK 8 the Forge baseline and the test server run on
              --instances <dir>     Prism's instances folder (prism)
              --mc <version>        the version the dev profiles were installed for; must match the pack (run)
              --profiles <list>     devlaunch profiles to run, comma-separated (default forge,lich-forge)
              --hold <seconds>      how long each profile stays in the world (default 60)
              --timeout <seconds>   how long a profile may take to join (run), or go without a step of the
                                    world driver (world) (default 1200)
            """;

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("[harness] Failed: " + e);
            e.printStackTrace();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
        }
    }

    static int run(String[] args) throws IOException, InterruptedException {
        if (args.length < 2) {
            throw new IllegalArgumentException(USAGE);
        }
        String command = args[0];
        Pack pack = Pack.get(args[1]);
        Path runDir = Path.of("run");
        Path java8Home = Path.of("C:/Program Files/Java/jdk-8");
        Path instances = null;
        String mc = null;
        List<String> profiles = List.of("forge", "lich-forge");
        Duration hold = Duration.ofSeconds(60);
        Duration timeout = Duration.ofSeconds(1200);
        for (int i = 2; i < args.length; i++) {
            String option = args[i];
            String value = i + 1 < args.length ? args[++i] : null;
            if (value == null) {
                throw new IllegalArgumentException(option + " needs a value\n\n" + USAGE);
            }
            switch (option) {
                case "--run-dir" -> runDir = Path.of(value);
                case "--java8-home" -> java8Home = Path.of(value);
                case "--instances" -> instances = Path.of(value);
                case "--mc" -> mc = value;
                case "--profiles" -> profiles = List.of(value.split(","));
                case "--hold" -> hold = Duration.ofSeconds(Long.parseLong(value));
                case "--timeout" -> timeout = Duration.ofSeconds(Long.parseLong(value));
                default -> throw new IllegalArgumentException("Unknown option " + option + "\n\n" + USAGE);
            }
        }
        runDir = runDir.toAbsolutePath().normalize();
        Path java8 = java8Home.resolve("bin").resolve(exe("java"));
        Path java25Home = Path.of(System.getProperty("java.home"));
        if (!Files.isRegularFile(java8)) {
            throw new IllegalArgumentException("No Java 8 at " + java8 + "; pass --java8-home (-Plich.java8Home)");
        }

        Http http = new Http(runDir.resolve("cache").resolve("harness-downloads"));
        Path template = new PackInstaller(http, runDir).install(pack);
        switch (command) {
            case "fetch" -> {
                return 0;
            }
            case "prism" -> {
                if (instances == null) {
                    throw new IllegalArgumentException("prism needs --instances");
                }
                List<Path> created = PrismInstances.create(pack, template, instances, java8Home, java25Home);
                System.out.println("[harness] Add Lich to the second one with:");
                System.out.println("  ./gradlew :installer:installPrism \"-Plich.mc=" + pack.mc() + "\" \"-Plich.prismInstance="
                        + created.get(1).toString().replace('\\', '/') + "\"");
                System.out.println("[harness] Prism lists new instances after a restart or a rescan (Folders > Instances, then back).");
                return 0;
            }
            case "run" -> {
                checkVersion(pack, mc);
                return runProfiles(pack, template, runDir, java8, java25Home.resolve("bin").resolve(exe("java")), GameRun.Mode.SERVER,
                        profiles, hold, timeout);
            }
            case "world" -> {
                checkVersion(pack, mc);
                return runProfiles(pack, template, runDir, java8, java25Home.resolve("bin").resolve(exe("java")), GameRun.Mode.WORLD,
                        profiles, hold, timeout);
            }
            default -> throw new IllegalArgumentException("Unknown command " + command + "\n\n" + USAGE);
        }
    }

    private static void checkVersion(Pack pack, String mc) {
        if (mc != null && !mc.equals(pack.mc())) {
            throw new IllegalArgumentException(pack.name() + " is for Minecraft " + pack.mc()
                    + "; pass -Plich.mc=" + pack.mc() + " so the matching dev profiles get installed");
        }
    }

    private static int runProfiles(Pack pack, Path template, Path runDir, Path java8, Path java25, GameRun.Mode mode,
                                   List<String> profiles, Duration hold, Duration timeout) throws IOException, InterruptedException {
        Path out = runDir.resolve("harness").resolve(pack.id());
        Files.createDirectories(out);
        GameRun runner = new GameRun(runDir, java8, java25);
        List<GameRun.Result> results = new ArrayList<>();
        List<LogReport> logs = new ArrayList<>();
        // A world run needs no test server.
        try (TestServer server = mode == GameRun.Mode.SERVER ? TestServer.start(runDir, pack.mc(), java8, out.resolve("server.out")) : null) {
            for (String profile : profiles) {
                GameRun.Result result = mode == GameRun.Mode.SERVER
                        ? runner.joinServer(pack, template, profile, server, hold, timeout)
                        : runner.playWorld(pack, template, profile, hold, timeout);
                System.out.println("[harness] " + profile + ": " + result.outcome().text
                        + (result.detail().isEmpty() ? "" : " (" + result.detail() + ")"));
                results.add(result);
                logs.add(LogReport.read(result.gameDir().resolve("logs").resolve("latest.log"), result.gameDir()));
            }
        }
        Path report = Report.write(out.resolve(mode.reportName), pack, mode, PackInstaller.countMods(template), results, logs);
        System.out.println("[harness] Report: " + report);
        return results.stream().allMatch(r -> r.outcome().passed) ? 0 : 1;
    }

    private static String exe(String name) {
        return System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name;
    }
}
