package dev.lich.boot;

import dev.lich.launchwrapper.LaunchCore;
import dev.lich.launchwrapper.LaunchHooks;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.launchwrapper.LogWrapper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Lich's main class, named by the version JSON instead of LaunchWrapper's. It refuses to start
 * without the JVM flags Lich needs, makes sure log4j runs with a Log4Shell-safe config, picks the
 * profile, and then runs Lich's LaunchWrapper with the same arguments the vanilla launcher would pass.
 */
public final class Main {
    private static final String VANILLA_TWEAKER = LichVanillaTweaker.class.getName();

    private Main() {
    }

    public static void main(String[] args) {
        List<String> missing = JvmFlags.missing(JvmFlags.required());
        boolean ignoreMissing = Boolean.getBoolean("lich.ignoreMissingFlags");
        if (!missing.isEmpty() && !ignoreMissing) {
            // Before any log4j call: logging may not even be set up safely yet.
            System.err.println(JvmFlags.explain(missing));
            System.exit(1);
        }
        ensureSafeLogging();

        Logger log = LogManager.getLogger("Lich");
        String version = Main.class.getPackage().getImplementationVersion();
        Runtime.Version java = Runtime.version();
        List<String> tweakClasses = Profile.tweakClasses(args);
        Profile profile = Profile.detect(tweakClasses);
        log.info("Lich {} on Java {} ({}), {} profile", version == null ? "(dev)" : version, java,
                System.getProperty("java.vendor"), profile.name().toLowerCase(Locale.ROOT));
        if (!missing.isEmpty()) {
            log.warn("Starting without these JVM arguments, as -Dlich.ignoreMissingFlags asks: {}", missing);
        }

        String[] launchArgs = switch (profile) {
            case VANILLA -> {
                if (!tweakClasses.isEmpty()) {
                    log.warn("Extra tweakers in the vanilla profile aren't supported yet: {}", tweakClasses);
                }
                // First, so it's the primary tweaker that picks the launch target.
                yield Stream.concat(Stream.of("--tweakClass", VANILLA_TWEAKER), Stream.of(args)).toArray(String[]::new);
            }
            case FORGE -> {
                log.warn("Forge isn't supported yet (PLAN.md Phase 3); launching anyway");
                yield args;
            }
        };

        try {
            LaunchCore.launch(launchArgs, new Hooks());
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "Unable to launch");
            System.exit(1);
        }
    }

    /**
     * Without {@code -Dlog4j.configurationFile}, log4j 2.0-beta9 would use the client jar's own
     * log4j2.xml, which is open to Log4Shell. Launchers that follow the version JSON pass Mojang's
     * fixed config, so this only applies to the others.
     */
    static void ensureSafeLogging() {
        if (System.getProperty("log4j.configurationFile") == null) {
            System.setProperty("log4j.configurationFile", "dev/lich/boot/log4j2-safe.xml");
        }
    }

    private static final class Hooks implements LaunchHooks {
        @Override
        public void classLoaderCreated(LaunchClassLoader classLoader) {
            // Lich's launch-time classes stay in the application class loader with LaunchWrapper's.
            classLoader.addClassLoaderExclusion("dev.lich.boot.");
            classLoader.addClassLoaderExclusion("dev.lich.launchwrapper.");
        }
    }
}
