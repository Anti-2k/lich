package dev.lich.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates the user's Prism instances of a pack, from the same game directory the harness runs: {@code
 * <pack>-forge-<mc>} (plain Forge on Java 8, the baseline) and {@code <pack>-lich-<mc>} (Forge through Lich on Java 25;
 * the installer's {@code installPrism} adds the Lich component to it). Existing instances are never touched: the user
 * may have worlds and settings in them.
 */
final class PrismInstances {
    private static final String MARKER = ".lich-harness";
    // The LWJGL 2 component Prism's metadata gives both versions (as in the user's own Forge instances).
    private static final String LWJGL2 = "2.9.4-nightly-20150209";

    private PrismInstances() {
    }

    static List<Path> create(Pack pack, Path template, Path instances, Path java8Home, Path java25Home) throws IOException {
        if (!Files.isDirectory(instances)) {
            throw new IOException("No Prism instances folder at " + instances);
        }
        Path forge = instances.resolve(pack.id() + "-forge-" + pack.mc());
        Path lich = instances.resolve(pack.id() + "-lich-" + pack.mc());
        create(pack, template, forge, pack.name() + " (Forge)", java8Home.resolve("bin").resolve("javaw.exe"), false);
        create(pack, template, lich, pack.name() + " (Lich)", java25Home.resolve("bin").resolve("javaw.exe"), true);
        return List.of(forge, lich);
    }

    private static void create(Pack pack, Path template, Path dir, String name, Path javaw, boolean lich) throws IOException {
        if (Files.exists(dir)) {
            log(dir.getFileName() + " exists already; left as it is (delete it to make a fresh one)");
            return;
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MARKER), pack.id() + "\n");
        Files.write(dir.resolve("instance.cfg"), List.of(
                "[General]",
                "ConfigVersion=1.3",
                "InstanceType=OneSix",
                "iconKey=default",
                "name=" + name,
                "OverrideJavaLocation=true",
                "JavaPath=" + javaw.toString().replace('\\', '/'),
                "AutomaticJava=false",
                // Prism would otherwise refuse Java 25 for these versions.
                "IgnoreJavaCompatibility=" + lich,
                "OverrideMemory=true",
                "MaxMemAlloc=" + pack.memoryMb(),
                "MinMemAlloc=512",
                "PermGen=128",
                "notes=Made by Lich's test harness from " + pack.url()), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mmc-pack.json"), """
                {
                    "components": [
                        {
                            "dependencyOnly": true,
                            "uid": "org.lwjgl",
                            "version": "%s"
                        },
                        {
                            "important": true,
                            "uid": "net.minecraft",
                            "version": "%s"
                        },
                        {
                            "uid": "net.minecraftforge",
                            "version": "%s"
                        }
                    ],
                    "formatVersion": 1
                }
                """.formatted(LWJGL2, pack.mc(), pack.forge()), StandardCharsets.UTF_8);
        Paths.copyTree(template, dir.resolve("minecraft"));
        log("Created " + dir + " (\"" + name + "\")");
    }

    private static void log(String message) {
        System.out.println("[harness] " + message);
    }
}
