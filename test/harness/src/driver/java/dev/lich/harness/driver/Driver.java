package dev.lich.harness.driver;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The harness's driver (PLAN.md §7), an extra LaunchWrapper tweaker, so the same jar plays the game in Forge on Java 8
 * and in Lich. It connects to the harness ({@code lich.harness.port}), then plays the {@link Script} on a thread of
 * its own while the game starts. LaunchWrapper loads a tweaker's package from the system class loader, so there is
 * one driver even when a mod (Essential) relaunches the whole launch, which calls the tweakers again.
 */
public final class Driver implements ITweaker {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private File gameDir;

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        if (this.gameDir == null) {
            this.gameDir = gameDir != null ? gameDir : new File(".");
        }
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        final Channel channel = new Channel(Integer.getInteger("lich.harness.port", 0));
        String jvm = ManagementFactory.getRuntimeMXBean().getName();
        channel.send("hello", "-", Channel.field("pid", jvm.substring(0, Math.max(0, jvm.indexOf('@'))))
                + " " + Channel.field("java", System.getProperty("java.version")), null);
        final Script script = new Script(channel, gameDir.getAbsoluteFile());
        Thread thread = new Thread(script::run, "Lich harness driver");
        thread.setDaemon(true);
        thread.start();
    }

    /** FML's tweaker comes first and picks the launch target; this one is never asked. */
    @Override
    public String getLaunchTarget() {
        return "net.minecraft.client.main.Main";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
