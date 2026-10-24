package dev.lich.boot;

import dev.lich.compat.asm.AsmCompat;
import dev.lich.compat.jdk.JdkCompat;
import dev.lich.compat.v1_7_10.Minecraft1710;
import dev.lich.compat.v1_8_9.Minecraft189;
import dev.lich.launchwrapper.LaunchCore;
import dev.lich.launchwrapper.LaunchHooks;
import dev.lich.launchwrapper.LichSources;
import dev.lich.launchwrapper.LichTransformers;
import dev.lich.transform.CompatTransformer;
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
 * without the JVM flags Lich needs, makes sure log4j runs with a Log4Shell-safe config, installs the
 * compat pass, picks the profile, and then runs Lich's LaunchWrapper with the same arguments the
 * vanilla launcher would pass.
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
        List<String> dropped = LichSystemClassLoader.droppedEntries();
        if (!dropped.isEmpty()) {
            log.info("Left out of the class path, Lich ships its own: {}", dropped);
        }
        installCompat(log, tweakClasses);

        String[] launchArgs = switch (profile) {
            case VANILLA -> {
                if (!tweakClasses.isEmpty()) {
                    log.warn("Extra tweakers in the vanilla profile aren't supported yet: {}", tweakClasses);
                }
                // First, so it's the primary tweaker that picks the launch target.
                yield Stream.concat(Stream.of("--tweakClass", VANILLA_TWEAKER), Stream.of(args)).toArray(String[]::new);
            }
            // FML's tweaker is already in the arguments and picks the launch target itself.
            case FORGE -> args;
        };

        try {
            LaunchCore.launch(launchArgs, new Hooks());
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "Unable to launch");
            System.exit(1);
        }
    }

    /**
     * Runs the compat pass on every class Lich defines from now on: in {@code LaunchClassLoader} ahead
     * of all transformers, and in the system class loader for the classes LaunchWrapper excludes.
     */
    private static void installCompat(Logger log, List<String> tweakClasses) {
        CompatTransformer.Builder builder = CompatTransformer.builder();
        JdkCompat.register(builder);
        AsmCompat.register(builder);
        CompatTransformer compat = builder
                .listener((className, what) -> log.info("Compat: {} in {}", what, className))
                .build();
        // Lich's own classes never need it, and the shims call the very methods it redirects to them.
        LichTransformers.addFirst((name, transformedName, bytes) ->
                transformedName.startsWith("dev.lich.") ? bytes : compat.transform(transformedName, bytes));
        if (ClassLoader.getSystemClassLoader() instanceof LichSystemClassLoader) {
            LichSystemClassLoader.setTransformer(compat::transform);
        }

        // Fixes to the game itself, after FML has patched and deobfuscated it (so, only with Forge for
        // now: the vanilla profile has no SRG names until Lich's own remapper, PLAN.md Phase 5).
        CompatTransformer.Builder gameFixes = CompatTransformer.builder();
        if (tweakClasses.contains(Profile.FML_1_7_10)) {
            Minecraft1710.registerSrgPatches(gameFixes);
        } else if (tweakClasses.contains(Profile.FML_1_8_9)) {
            Minecraft189.registerSrgPatches(gameFixes);
        }
        if (Profile.detect(tweakClasses) == Profile.FORGE) {
            CompatTransformer game = gameFixes
                    .listener((className, what) -> log.info("Game fix: {} in {}", what, className))
                    .build();
            LichTransformers.addLast((name, transformedName, bytes) ->
                    transformedName.startsWith("dev.lich.") ? bytes : game.transform(transformedName, bytes));
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
            // Lich's launch-time classes and shims stay in the system class loader with LaunchWrapper's:
            // one copy each, whichever loader the code that calls them comes from.
            classLoader.addClassLoaderExclusion("dev.lich.boot.");
            classLoader.addClassLoaderExclusion("dev.lich.launchwrapper.");
            classLoader.addClassLoaderExclusion("dev.lich.transform.");
            classLoader.addClassLoaderExclusion("dev.lich.compat.");
            // One ASM, Lich's, for Lich's shims and for LaunchClassLoader's transformers alike. FML 1.8.9
            // excludes it itself; 1.7.10 doesn't, and a second copy in LaunchClassLoader breaks the ASM
            // shims (loader constraint violations on org.objectweb.asm.Type).
            classLoader.addClassLoaderExclusion("org.objectweb.asm.");
            LichSystemClassLoader.lichJars().forEach(LichSources::hide);
        }
    }
}
