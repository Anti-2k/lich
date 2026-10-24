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
            new Launch().launch(args);
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "Unable to launch");
            System.exit(1);
        }
    }

    /**
     * 1.12's instance method, kept so that {@code Launch.launch} is on the stack while tweakers run, as
     * it is in 1.12: Mixin 0.7 looks for this frame to tell tweaker setup from a tweaker's constructor.
     */
    private void launch(String[] args) throws Exception {
        LaunchHooks hooks = LaunchCore.takeHooks();
        if (hooks == null && LaunchCore.startBoot(args)) {
            return;
        }
        LaunchCore.launch(args, hooks == null ? LaunchHooks.NONE : hooks);
    }
}
