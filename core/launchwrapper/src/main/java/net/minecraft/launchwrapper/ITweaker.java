package net.minecraft.launchwrapper;

import java.io.File;
import java.util.List;

/** LaunchWrapper 1.12's tweaker interface, unchanged: Forge, Mixin and OptiFine all implement it. */
public interface ITweaker {
    void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile);

    void injectIntoClassLoader(LaunchClassLoader classLoader);

    String getLaunchTarget();

    String[] getLaunchArguments();
}
