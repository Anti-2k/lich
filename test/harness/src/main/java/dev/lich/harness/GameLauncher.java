package dev.lich.harness;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts one profile's game for a pack: a fresh copy of the pack's game directory, the same options for both
 * profiles, and the command devlaunch makes (everything downloaded first, so the timings are the game's own), with the
 * driver added as a tweaker. The game is the harness's own child process, so its pid and exit are the game's.
 */
final class GameLauncher {
    static final String PLAYER = "Dev";
    private static final String MARKER = ".lich-harness";
    private static final String DRIVER = "dev.lich.harness.driver.Driver";

    private final Path runDir;
    private final Path java8;
    private final Path java25;

    GameLauncher(Path runDir, Path java8, Path java25) {
        this.runDir = runDir;
        this.java8 = java8;
        this.java25 = java25;
    }

    Path java(Profile profile) {
        return profile == Profile.LICH ? java25 : java8;
    }

    /** {@code run/harness/<pack>/<profile>}. */
    Path gameDir(Pack pack, Profile profile) {
        return packDir(pack).resolve(profile.id);
    }

    Path packDir(Pack pack) {
        return runDir.resolve("harness").resolve(pack.id());
    }

    /** The driver jar, from the harness's resources; its hash is part of the baseline key. */
    Path driverJar() throws IOException {
        Path jar = runDir.resolve("harness").resolve("harness-driver.jar");
        try (InputStream in = GameLauncher.class.getResourceAsStream("harness-driver.jar")) {
            if (in == null) {
                throw new IOException("harness-driver.jar is missing from the harness's resources");
            }
            Files.createDirectories(jar.getParent());
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        return jar;
    }

    /** The Forge profile a pack runs on: the one of the build it pins. */
    Path forgeProfile(Pack pack) throws IOException {
        String id = "forge" + versionSuffix(pack);
        return versions().resolve(id).resolve(id + ".json");
    }

    private Path versions() {
        return runDir.resolve("cache").resolve("versions");
    }

    /**
     * What follows the profile id in a pack's version: {@code -<mc>-<build>} when its Minecraft version has a profile
     * per Forge build, else {@code -<mc>}. A pack must never run on another build than the one its report names.
     */
    private String versionSuffix(Pack pack) throws IOException {
        String perBuild = "-" + pack.mc() + "-" + pack.forge();
        if (Files.isRegularFile(versions().resolve("forge" + perBuild).resolve("forge" + perBuild + ".json"))) {
            return perBuild;
        }
        String shared = "-" + pack.mc();
        Path json = versions().resolve("forge" + shared).resolve("forge" + shared + ".json");
        if (!Files.isRegularFile(json)) {
            throw new IOException("No " + json + "; run :installer:installDevProfiles first");
        }
        if (!Files.readString(json).contains(pack.forge())) {
            throw new IOException(pack.id() + " pins Forge " + pack.forge() + ", which Lich has no profile for (" + json + ")");
        }
        return shared;
    }

    Process start(Pack pack, Path template, Profile profile, Map<String, String> driverProperties) throws IOException {
        Path packDir = packDir(pack);
        Path gameDir = gameDir(pack, profile);
        if (Files.exists(gameDir)) {
            if (!Files.exists(gameDir.resolve(MARKER))) {
                throw new IOException(gameDir + " exists and isn't a harness directory; not deleting it");
            }
            PackInstaller.deleteTree(gameDir);
        }
        Paths.copyTree(template, gameDir);
        Files.writeString(gameDir.resolve(MARKER), pack.id() + " " + profile.id + "\n");
        options(gameDir.resolve("options.txt"));

        List<String> args = new ArrayList<>(List.of(
                "--version", profile.id + versionSuffix(pack), "--java", java(profile).toString(), "--run-dir", runDir.toString(),
                "--game-dir", gameDir.toString(), "--username", PLAYER,
                "--extra-cp", driverJar().toString(),
                "--game-arg", "--tweakClass", "--game-arg", DRIVER,
                "--jvm-arg", "-Xmx" + pack.memoryMb() + "M",
                // Lists every mixin applied, so the report can compare them (DOCS.md §7).
                "--jvm-arg", "-Dmixin.debug.verbose=true"));
        driverProperties.forEach((key, value) -> args.addAll(List.of("--jvm-arg", "-D" + key + "=" + value)));
        if (profile == Profile.LICH) {
            args.addAll(List.of("--jvm-arg", "-Dlich.dev.noMouseGrab=true"));
        }
        List<String> command = new ArrayList<>(dev.lich.devlaunch.Main.command(args));
        if (profile == Profile.FORGE) {
            noGrabMouse(command, packDir);
        }
        return new ProcessBuilder(command)
                .directory(gameDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(packDir.resolve(profile.id + ".out").toFile())
                .start();
    }

    /**
     * The Java 8 baseline runs the game's own LWJGL 2, where {@code -Dlich.dev.noMouseGrab} doesn't exist: a copy of
     * its jar whose {@code Mouse} never grabs the cursor goes first on the class path instead ({@link NoGrabMouse}).
     */
    private static void noGrabMouse(List<String> command, Path packDir) throws IOException {
        int cp = command.indexOf("-cp");
        if (cp < 0) {
            throw new IOException("No -cp in the game's command line");
        }
        for (String entry : command.get(cp + 1).split(File.pathSeparator)) {
            Path path = Path.of(entry);
            String name = path.getFileName().toString();
            if (name.startsWith("lwjgl-2") && name.endsWith(".jar")) {
                Path patched = NoGrabMouse.build(path, packDir.resolve("nograb-" + name));
                command.set(cp + 1, patched + File.pathSeparator + command.get(cp + 1));
                return;
            }
        }
        throw new IOException("No LWJGL 2 jar on the game's class path");
    }

    /**
     * The same settings for both profiles, whatever the pack ships: no sound, no fullscreen, no pause when the window
     * isn't focused (it usually isn't: the user works on the same desktop), view distance 8 (less to generate at each
     * tour stop) and 30 fps in the world, which leaves the integrated server the CPU (the driver lifts the cap in
     * menus). Byte for byte otherwise: the game writes the file in the platform charset, and packs ship it that way.
     */
    static void options(Path options) throws IOException {
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
        values.put("fullscreen", "false");
        values.put("renderDistance", "8");
        values.put("maxFps", "30");
        values.put("enableVsync", "false");
        List<String> lines = new ArrayList<>();
        values.forEach((k, v) -> lines.add(k + ":" + v));
        Files.write(options, lines, StandardCharsets.ISO_8859_1);
    }
}
