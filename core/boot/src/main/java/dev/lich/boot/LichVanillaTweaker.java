package dev.lich.boot;

import dev.lich.remap.SrgRemapper;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Primary tweaker of the vanilla profile. It launches {@code net.minecraft.client.main.Main}, which
 * exists in both 1.7.10 and 1.8.9. LaunchWrapper's own parser consumes {@code --version},
 * {@code --gameDir} and {@code --assetsDir}, so they are put back the way FML's tweaker does it. When boot
 * has MCP's mappings it registers Lich's obf → SRG remapper, as the first transformer, as FML does its own.
 */
public final class LichVanillaTweaker implements ITweaker {
    private static volatile boolean remapToSrg;

    private final List<String> args = new ArrayList<>();

    /** Registers {@link SrgRemapper} once the class loader exists; boot has given it its mappings. */
    static void remapToSrg() {
        remapToSrg = true;
    }

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        this.args.addAll(args);
        if (profile != null) {
            this.args.add("--version");
            this.args.add(profile);
        }
        if (gameDir != null) {
            this.args.add("--gameDir");
            this.args.add(gameDir.getAbsolutePath());
        }
        if (assetsDir != null) {
            this.args.add("--assetsDir");
            this.args.add(assetsDir.getAbsolutePath());
        }
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        if (remapToSrg) {
            classLoader.registerTransformer(SrgRemapper.class.getName());
        }
    }

    @Override
    public String getLaunchTarget() {
        return "net.minecraft.client.main.Main";
    }

    @Override
    public String[] getLaunchArguments() {
        return args.toArray(new String[0]);
    }
}
