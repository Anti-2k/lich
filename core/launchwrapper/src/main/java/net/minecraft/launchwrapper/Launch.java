package net.minecraft.launchwrapper;

import dev.lich.launchwrapper.LaunchCore;
import dev.lich.launchwrapper.LaunchHooks;
import org.apache.logging.log4j.Level;

import java.io.File;
import java.util.Map;

/**
 * LaunchWrapper 1.12's entry point and global state. The fields are the ones Forge and mods read; the
 * launch itself is in {@link LaunchCore}. Unlike 1.12, Lich ships no {@code VanillaTweaker}: it only
 * served pre-1.7 versions, and Lich's boot always names its own tweaker.
 */
public class Launch {
    public static File minecraftHome;
    public static File assetsDir;
    public static Map<String, Object> blackboard;
    public static LaunchClassLoader classLoader;

    private Launch() {
    }

    public static void main(String[] args) {
        try {
            LaunchCore.launch(args, LaunchHooks.NONE);
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "Unable to launch");
            System.exit(1);
        }
    }
}
