package dev.lich.boot;

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
    static final String FML_1_8_9 = "net.minecraftforge.fml.common.launcher.FMLTweaker"; // and 1.12.2
    private static final Set<String> FML_TWEAKERS = Set.of(FML_1_7_10, FML_1_8_9);

    static Profile detect(List<String> tweakClasses) {
        return tweakClasses.stream().anyMatch(FML_TWEAKERS::contains) ? FORGE : VANILLA;
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
