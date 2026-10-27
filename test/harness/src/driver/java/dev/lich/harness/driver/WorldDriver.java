package dev.lich.harness.driver;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The harness's singleplayer driver (PLAN.md §7), an extra LaunchWrapper tweaker, so the same jar works in Forge on
 * Java 8 and in Lich. Once the game shows its first screen, a thread of its own creates a world with a fixed seed (or
 * opens the map the pack ships, {@code lich.harness.save}), tours it by teleporting (each stop generates new chunks),
 * stays a while, saves and quits, opens the world again, then quits the game. Commands are turned on for the player
 * either way, as opening to LAN with cheats does, for the teleports. Every step is logged as a {@code LICH-HARNESS
 * <event> [key=value ...]} line, which the harness follows in the game log; failures as {@code LICH-HARNESS failed
 * <reason>}. Settings come from {@code -Dlich.harness.*} properties.
 */
public final class WorldDriver implements ITweaker {
    static final String MARKER = "LICH-HARNESS ";
    private static final Logger LOG = LogManager.getLogger("LichHarness");
    /** A relaunch (Essential's) calls the tweakers again; one driver is enough, and it follows the relaunch. */
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                drive();
            }
        }, "Lich harness driver");
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

    private static void drive() {
        try {
            long seed = Long.getLong("lich.harness.seed", 1L);
            String worldType = System.getProperty("lich.harness.worldType", "default");
            String save = System.getProperty("lich.harness.save");
            int settle = Integer.getInteger("lich.harness.settle", 30);
            int stops = Integer.getInteger("lich.harness.tourStops", 8);
            int radius = Integer.getInteger("lich.harness.tourRadius", 1000);
            int stopSeconds = Integer.getInteger("lich.harness.tourStopSeconds", 20);
            int hold = Integer.getInteger("lich.harness.hold", 60);
            int reloadHold = Integer.getInteger("lich.harness.reloadHold", 30);

            Game game = Game.await();
            event("menu screen=" + game.screen());
            // Mods may open a screen of their own right after the first one.
            Thread.sleep(5000);

            String folder;
            if (save == null) {
                folder = "harness";
                event("creating seed=" + seed + " type=" + worldType);
                game.createWorld(folder, "Harness", seed, worldType);
            } else {
                folder = save;
                event("opening save=" + save);
                game.loadWorld(folder, folder);
            }
            awaitWorld(game);
            game.allowCommands();
            event("in-world");
            stay(game, settle);
            event("stats phase=spawn " + game.stats());

            double[] spawn = game.playerXZ();
            for (int i = 0; i < stops; i++) {
                double angle = 2 * Math.PI * i / stops;
                long x = Math.round(spawn[0] + radius * Math.cos(angle));
                long z = Math.round(spawn[1] + radius * Math.sin(angle));
                // From high up: the player falls to whatever generates below, land or sea (creative: no damage).
                game.chat("/tp " + x + " 250 " + z);
                event("tour stop=" + (i + 1) + "/" + stops + " x=" + x + " z=" + z);
                stay(game, stopSeconds);
            }
            game.chat("/tp " + Math.round(spawn[0]) + " 250 " + Math.round(spawn[1]));
            event("stats phase=toured " + game.stats());
            stay(game, hold);
            event("stats phase=held " + game.stats());

            awaitStopped(game.saveAndQuit());
            event("saved");
            Thread.sleep(5000);

            game.loadWorld(folder, folder);
            awaitWorld(game);
            game.allowCommands();
            event("reloaded");
            stay(game, reloadHold);
            event("stats phase=reloaded " + game.stats());
            awaitStopped(game.saveAndQuit());
            event("done");
            game.shutdown();
        } catch (Throwable t) {
            LOG.error(MARKER + "failed " + t, t);
        }
    }

    /** {@code launchIntegratedServer} returns once the server runs; the client joins it a moment later. */
    private static void awaitWorld(Game game) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (game.inWorld()) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Not in the world 300 s after the server started, screen " + game.screen());
    }

    /** Stays in the world, checking every second that it's still there. */
    private static void stay(Game game, int seconds) throws Exception {
        for (int i = 0; i < seconds; i++) {
            Thread.sleep(1000);
            if (!game.inWorld()) {
                throw new IllegalStateException("Left the world unexpectedly, screen " + game.screen());
            }
        }
    }

    /** The integrated server saves the world as it stops, after the client has left. */
    private static void awaitStopped(Object server) throws Exception {
        if (server == null) {
            throw new IllegalStateException("No integrated server to stop");
        }
        for (int i = 0; i < 300; i++) {
            if (Game.stopped(server)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("The integrated server didn't stop within 300 s of leaving the world");
    }

    private static void event(String text) {
        LOG.info(MARKER + text);
    }
}
