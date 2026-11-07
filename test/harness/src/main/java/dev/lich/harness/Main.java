package dev.lich.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lich's test harness for Forge mod compatibility through whole modpacks (DOCS.md §7). Each pinned pack plays the same
 * {@link Scenario} on Forge with Java 8 (the baseline, reused while nothing it depends on changes) and through Lich on
 * Java 25, one game at a time; then the two runs are compared step by step into a {@link Verdict}, a report per pack
 * and a summary.
 */
public final class Main {
    private static final String USAGE = """
            Usage: harness <command> <packs> [options]
              fetch <packs>   download each pack into <run-dir>/packs/<pack>/minecraft
              prism <pack>    create the Prism instances <pack>-forge-<mc> and <pack>-lich-<mc> from it
              run <packs>     play the scenario on Forge (Java 8) and Lich (Java 25), compare, and write
                              <run-dir>/harness/<pack>/report.md and <run-dir>/harness/summary.md
            <packs>: pack ids, comma-separated, or all, @<tag> (@quick) or @<mc version> (@1.12.2). Packs with a
            "skip" reason in packs.json only run when named.
            Options:
              --run-dir <dir>       Lich's run directory (cache, test servers, harness output)
              --java8-home <dir>    the JDK 8 the Forge baseline and the test server run on
              --instances <dir>     Prism's instances folder (prism)
              --baseline <mode>     auto: rerun Forge only when what it depends on changed (default);
                                    rerun: always; skip: Lich alone, compared with nothing; forge: Forge alone,
                                    compared with the last Lich run (after a skip run broke; same step options)
              --until <step>        stop after this step (boot, mods, server, world, spawn, tour, inventory,
                                    gallery, hold, save, reload)
              --skip <steps>        leave these out, comma-separated (mods, server, tour, inventory, gallery,
                                    hold, reload); server goes straight to the world
              --mods <on|off>       open FML's mod list and every config screen (default off)
              --hold <seconds>      how long the hold step stays in the world (default 20)
              --gallery <on|off>    place every block (default: the pack's "gallery" setting)
              --server <on|off>     join the local test server (default on, when run/server-<mc> exists)
            """;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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

    /** The parsed command line. */
    private record Options(Path runDir, Path java8Home, Path instances, String baseline, String until, Set<String> skip,
                           int hold, Boolean gallery, boolean server, boolean mods) {
        Path java8() {
            return java8Home.resolve("bin").resolve(exe("java"));
        }
    }

    static int run(String[] args) throws IOException, InterruptedException {
        if (args.length < 2) {
            throw new IllegalArgumentException(USAGE);
        }
        String command = args[0];
        List<Pack> packs = Pack.select(args[1]);
        Path runDir = Path.of("run");
        Path java8Home = Path.of("C:/Program Files/Java/jdk-8");
        Path instances = null;
        String baseline = "auto";
        String until = null;
        Set<String> skip = Set.of();
        int hold = 20;
        Boolean gallery = null;
        boolean server = true;
        boolean mods = false;
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
                case "--baseline" -> baseline = oneOf(option, value, "auto", "rerun", "skip", "forge");
                case "--until" -> until = value.isEmpty() ? null : value;
                case "--skip" -> skip = value.isEmpty() ? Set.of() : Set.of(value.split(","));
                case "--hold" -> hold = Integer.parseInt(value);
                case "--gallery" -> gallery = value.isEmpty() ? null : oneOf(option, value, "on", "off").equals("on");
                case "--server" -> server = oneOf(option, value, "on", "off").equals("on");
                case "--mods" -> mods = oneOf(option, value, "on", "off").equals("on");
                default -> throw new IllegalArgumentException("Unknown option " + option + "\n\n" + USAGE);
            }
        }
        Options options = new Options(runDir.toAbsolutePath().normalize(), java8Home, instances, baseline, until, skip,
                hold, gallery, server, mods);
        if (!Files.isRegularFile(options.java8())) {
            throw new IllegalArgumentException("No Java 8 at " + options.java8() + "; pass --java8-home (-Plich.java8Home)");
        }

        Http http = new Http(options.runDir().resolve("cache").resolve("harness-downloads"));
        PackInstaller installer = new PackInstaller(http, options.runDir());
        switch (command) {
            case "fetch" -> {
                for (Pack pack : packs) {
                    installer.install(pack);
                }
                return 0;
            }
            case "prism" -> {
                if (instances == null || packs.size() != 1) {
                    throw new IllegalArgumentException("prism needs one pack and --instances");
                }
                Pack pack = packs.getFirst();
                List<Path> created = PrismInstances.create(pack, installer.install(pack), instances, java8Home,
                        Path.of(System.getProperty("java.home")));
                System.out.println("[harness] Add Lich to the second one with:");
                System.out.println("  ./gradlew :installer:installPrism \"-Plich.mc=" + pack.mc() + "\" \"-Plich.prismInstance="
                        + created.get(1).toString().replace('\\', '/') + "\"");
                System.out.println("[harness] Prism lists new instances after a restart or a rescan (Folders > Instances, then back).");
                return 0;
            }
            case "run" -> {
                return runPacks(packs, installer, options);
            }
            default -> throw new IllegalArgumentException("Unknown command " + command + "\n\n" + USAGE);
        }
    }

    private static int runPacks(List<Pack> packs, PackInstaller installer, Options options)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        Path harnessDir = options.runDir().resolve("harness");
        String lich = lichVersion(options.runDir());
        GameLauncher launcher = new GameLauncher(options.runDir(), options.java8(),
                Path.of(System.getProperty("java.home"), "bin", exe("java")));
        History history = new History(harnessDir.resolve("history.jsonl"));
        List<Summary.Row> rows = new ArrayList<>();
        Map<String, TestServer> servers = new LinkedHashMap<>();
        try {
            for (Pack pack : packs) {
                System.out.println("[harness] === " + pack.name() + " (" + pack.id() + ", " + (rows.size() + 1) + "/"
                        + packs.size() + ")");
                Summary.Row row = runPack(pack, installer, launcher, options, lich, servers, history);
                rows.add(row);
                System.out.println("[harness] " + pack.id() + ": " + row.verdict().kind().text + ": " + row.verdict().reason()
                        + " (report: " + row.report() + ")");
                Summary.write(harnessDir.resolve("summary.md"), lich, rows, (System.nanoTime() - start) / 1_000_000_000L);
            }
        } finally {
            for (TestServer server : servers.values()) {
                server.close();
            }
        }
        System.out.println("[harness] Summary: " + harnessDir.resolve("summary.md"));
        return rows.stream().allMatch(r -> r.verdict().kind().ok) ? 0 : 1;
    }

    private static Summary.Row runPack(Pack pack, PackInstaller installer, GameLauncher launcher, Options options,
                                       String lich, Map<String, TestServer> servers, History history)
            throws IOException, InterruptedException {
        Path template = installer.install(pack);
        Scenario scenario = Scenario.of(pack, options.until(), options.hold(),
                options.server() && pack.noServer() == null && TestServer.available(options.runDir(), pack.mc()),
                options.gallery(), options.mods(), options.skip());
        if (scenario.server() && !servers.containsKey(pack.mc())) {
            servers.put(pack.mc(), TestServer.start(options.runDir(), pack.mc(), options.java8()));
        }
        Path packDir = launcher.packDir(pack);
        Files.createDirectories(packDir);
        System.out.println("[harness] Steps: " + String.join(", ", scenario.steps()));

        Path lichResult = packDir.resolve(Profile.LICH.id + ".json");
        boolean forgeOnly = options.baseline().equals("forge");
        RunResult storedLich = forgeOnly ? RunResult.read(lichResult) : null;
        if (forgeOnly && storedLich == null) {
            throw new IllegalArgumentException("No Lich run of " + pack.id() + " to compare Forge with: " + lichResult);
        }
        RunResult baseline = null;
        boolean reused = false;
        if (!options.baseline().equals("skip")) {
            String key = Baseline.key(pack, scenario, launcher.driverJar(), launcher.forgeProfile(pack), options.java8());
            baseline = options.baseline().equals("auto") ? Baseline.usable(packDir, key, scenario) : null;
            reused = baseline != null;
            if (reused) {
                System.out.println("[harness] Forge baseline from " + baseline.startedAt() + ": " + Verdict.describe(baseline));
            } else {
                RunResult previous = RunResult.read(Baseline.file(packDir));
                baseline = play(pack, template, Profile.FORGE, scenario, null, launcher, lich, key);
                baseline.write(Baseline.file(packDir));
                if (previous != null && previous.ending() != baseline.ending()) {
                    System.out.println("[harness] The Forge baseline changed: it " + Verdict.describe(previous)
                            + " before, now " + Verdict.describe(baseline));
                }
            }
        }

        RunResult run = forgeOnly ? storedLich : play(pack, template, Profile.LICH, scenario, baseline, launcher, lich, null);
        run.write(lichResult);
        Verdict verdict = Verdict.of(run, baseline);
        Path report = Report.write(packDir.resolve("report.md"), pack, PackInstaller.countMods(template), verdict, run,
                baseline, reused);
        History.Entry previous = history.last(pack.id());
        history.add(new History.Entry(LocalDateTime.now().format(TIME), pack.id(), lich, verdict.kind().name(),
                verdict.reason(), run.seconds(), reused));
        return new Summary.Row(pack, verdict, run, reused, previous, report);
    }

    private static RunResult play(Pack pack, Path template, Profile profile, Scenario scenario, RunResult baseline,
                                  GameLauncher launcher, String lich, String key) throws IOException, InterruptedException {
        System.out.println("[harness] " + pack.id() + ": starting " + profile.title);
        try (DriverLink link = new DriverLink()) {
            Process game = launcher.start(pack, template, profile,
                    scenario.properties(pack, link.port(), TestServer.port(pack.mc())));
            RunResult result = new GameRun(pack, profile, scenario, baseline, launcher.gameDir(pack, profile),
                    launcher.java(profile)).follow(game, link, lich, key);
            System.out.println("[harness] " + pack.id() + " " + profile.id + ": " + Verdict.describe(result) + " in "
                    + result.seconds() + " s");
            return result;
        }
    }

    /** The Lich commit the run tested, {@code +changes} when the working tree had uncommitted ones. */
    private static String lichVersion(Path runDir) {
        Path repo = runDir.getParent();
        String commit = git(repo, "rev-parse", "--short", "HEAD");
        if (commit == null) {
            return "unknown";
        }
        String status = git(repo, "status", "--porcelain", "--untracked-files=no");
        return commit + (status == null || status.isEmpty() ? "" : "+changes");
    }

    private static String git(Path repo, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        try {
            Process git = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return git.waitFor() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String oneOf(String option, String value, String... allowed) {
        if (!List.of(allowed).contains(value)) {
            throw new IllegalArgumentException(option + " takes " + String.join(", ", allowed) + ", not " + value);
        }
        return value;
    }

    static String exe(String name) {
        return System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name;
    }
}
