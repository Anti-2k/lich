package dev.lich.boot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Which game Lich is starting, decided from the tweakers the version JSON asks for. */
enum Profile {
    /** Plain Minecraft: Lich's own tweaker is the primary one. */
    VANILLA,
    /** Forge: FML's tweaker is the primary one, and FML does the obf → SRG remapping. */
    FORGE;

    static final String FML_1_7_10 = "cpw.mods.fml.common.launcher.FMLTweaker";
    /** FML's tweaker in 1.8.9 and in 1.12.2: the same class name. */
    static final String FML_1_8_9 = "net.minecraftforge.fml.common.launcher.FMLTweaker";
    private static final Set<String> FML_TWEAKERS = Set.of(FML_1_7_10, FML_1_8_9);

    /**
     * The Minecraft version. MCP's mappings ({@code mcp-<version>-srg.zip}) name it exactly, Forge or not. Without
     * them: FML's tweaker for 1.7.10 (its package differs), and for the one that 1.8.9 and 1.12.2 share, the
     * asset index the launcher passes ({@code 1.8} or {@code 1.12}); {@code null} if nothing tells.
     */
    static String minecraftVersion(List<String> tweakClasses, List<Path> mappings, String[] args) {
        if (!mappings.isEmpty()) {
            String name = mappings.getFirst().getFileName().toString();
            return name.substring("mcp-".length(), name.length() - "-srg.zip".length());
        }
        if (tweakClasses.contains(FML_1_7_10)) {
            return "1.7.10";
        }
        if (tweakClasses.contains(FML_1_8_9)) {
            return switch (assetIndex(args)) {
                case "1.12" -> "1.12.2";
                case "1.8" -> "1.8.9";
                // A launcher that doesn't pass the index: the older of the two, as before 1.12.2 was supported.
                default -> "1.8.9";
            };
        }
        return null;
    }

    /** The {@code --assetIndex} launch argument, in either spelling; the empty string without one. */
    static String assetIndex(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--assetIndex") && i + 1 < args.length) {
                return args[i + 1];
            } else if (args[i].startsWith("--assetIndex=")) {
                return args[i].substring("--assetIndex=".length());
            }
        }
        return "";
    }

    static Profile detect(List<String> tweakClasses) {
        return tweakClasses.stream().anyMatch(FML_TWEAKERS::contains) ? FORGE : VANILLA;
    }

    /** The {@code --gameDir} launch argument, in either spelling; the working directory without one, as in the game. */
    static Path gameDir(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--gameDir") && i + 1 < args.length) {
                return Path.of(args[i + 1]);
            } else if (args[i].startsWith("--gameDir=")) {
                return Path.of(args[i].substring("--gameDir=".length()));
            }
        }
        return Path.of(".");
    }

    /** The {@code --tweakClass} values in launch arguments, in order, in either spelling. */
    static List<String> tweakClasses(String[] args) {
        List<String> tweakClasses = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--tweakClass") && i + 1 < args.length) {
                tweakClasses.add(args[++i]);
            } else if (args[i].startsWith("--tweakClass=")) {
                tweakClasses.add(args[i].substring("--tweakClass=".length()));
            }
        }
        return tweakClasses;
    }
}
