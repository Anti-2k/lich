package dev.lich.boot;

import dev.lich.compat.asm.AsmCompat;
import dev.lich.compat.jdk.JdkCompat;
import dev.lich.compat.lwjgl2.Lwjgl2Compat;
import dev.lich.compat.v1_7_10.Minecraft1710;
import dev.lich.compat.v1_7_10.forge.Forge1710;
import dev.lich.compat.v1_8_9.Minecraft189;
import dev.lich.compat.v1_8_9.forge.Forge189;
import dev.lich.launchwrapper.LaunchCore;
import dev.lich.launchwrapper.LaunchHooks;
import dev.lich.launchwrapper.LichExclusions;
import dev.lich.launchwrapper.LichSources;
import dev.lich.launchwrapper.LichTransformers;
import dev.lich.loader.LichLoader;
import dev.lich.loader.LichMod;
import dev.lich.loader.LichTweaker;
import dev.lich.loader.ModLoadingException;
import dev.lich.remap.SrgRemapper;
import dev.lich.remap.core.SrgFile;
import dev.lich.transform.CompatTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * Lich's main class, named by the version JSON instead of LaunchWrapper's. It refuses to start
 * without the JVM flags Lich needs, makes sure log4j runs with a Log4Shell-safe config, installs the
 * compat pass, picks the profile, and then runs Lich's LaunchWrapper with the same arguments the
 * vanilla launcher would pass.
 */
public final class Main {
    private static final String VANILLA_TWEAKER = LichVanillaTweaker.class.getName();
    private static final String MIXIN_TWEAKER = "org.spongepowered.asm.launch.MixinTweaker";

    private Main() {
    }

    public static void main(String[] args) {
        ClassLoader relauncher = Relaunch.relauncher();
        if (relauncher != null) {
            // Before anything loads an ASM class: see Relaunch.hook.
            Relaunch.hook(relauncher, LogManager.getLogger("Lich"));
        }
        List<String> missing = JvmFlags.missing(JvmFlags.required());
        boolean ignoreMissing = Boolean.getBoolean("lich.ignoreMissingFlags");
        if (!missing.isEmpty() && !ignoreMissing) {
            // Before any log4j call: logging may not even be set up safely yet.
            System.err.println(JvmFlags.explain(missing));
            System.exit(1);
        }
        ensureSafeLogging();
        ensureJava8UserAgent();

        Logger log = LogManager.getLogger("Lich");
        String version = Main.class.getPackage().getImplementationVersion();
        Runtime.Version java = Runtime.version();
        List<String> tweakClasses = Profile.tweakClasses(args);
        Profile profile = Profile.detect(tweakClasses);
        log.info("Lich {} on Java {} ({}), {} profile{}", version == null ? "(dev)" : version, java,
                System.getProperty("java.vendor"), profile.name().toLowerCase(Locale.ROOT),
                relauncher == null ? "" : ", relaunched by " + relauncher.getClass().getName());
        if (!missing.isEmpty()) {
            log.warn("Starting without these JVM arguments, as -Dlich.ignoreMissingFlags asks: {}", missing);
        }
        List<String> dropped = LichSystemClassLoader.droppedEntries();
        if (!dropped.isEmpty()) {
            log.info("Left out of the class path, Lich ships its own: {}", dropped);
        }
        String minecraft = Profile.minecraftVersion(tweakClasses, LichSystemClassLoader.mappings());
        boolean srgNames = profile == Profile.FORGE || useSrgRemapper(log);
        List<LichMod> mods = loadLichMods(log, args, profile, minecraft, srgNames, version, relauncher);
        installCompat(log, tweakClasses, minecraft, srgNames, !mods.isEmpty());

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
        if (!mods.isEmpty()) {
            // Mixin's tweaker, then Lich's, which registers the mods' configs once Mixin is up.
            launchArgs = Stream.concat(Stream.of(launchArgs), Stream.of("--tweakClass", MIXIN_TWEAKER,
                    "--tweakClass", LichTweaker.class.getName())).toArray(String[]::new);
        }

        // Through Launch.main, like a plain LaunchWrapper start: mods look for its frames on the stack.
        LaunchCore.useHooks(new Hooks(mods));
        Launch.main(launchArgs);
    }

    /**
     * Finds the Lich mods and gets the launch ready for them: Lich's Mixin on the class path, and their jars
     * out of FML's sight. Mods that can't load stop the game here, with every problem in the log; so does a
     * vanilla profile without SRG names, which Lich mods are written against.
     */
    private static List<LichMod> loadLichMods(Logger log, String[] args, Profile profile, String minecraft, boolean srgNames,
                                              String version, ClassLoader relauncher) {
        List<LichMod> mods;
        try {
            mods = LichLoader.load(Profile.gameDir(args), minecraft, profile == Profile.FORGE, version);
        } catch (ModLoadingException e) {
            log.fatal(e.getMessage());
            System.exit(1);
            throw e;
        }
        if (mods.isEmpty()) {
            return mods;
        }
        if (!srgNames || minecraft == null) {
            log.fatal("Lich mods are installed, but this launch has no MCP mappings (mcp-<version>-srg.zip): reinstall Lich");
            System.exit(1);
        }
        if (relauncher != null || !(ClassLoader.getSystemClassLoader() instanceof LichSystemClassLoader system)) {
            log.warn("Lich mods can't load in a relaunched game yet; leaving out {}", mods);
            return List.of();
        }
        system.activateDeferred();
        // Mixin's LaunchWrapper service with the Java 8 cap lifted (Lich mods are Java 25 classes).
        System.setProperty("mixin.service", "dev.lich.mixin.LichMixinService");
        if (profile == Profile.FORGE) {
            mods.forEach(mod -> LichSources.hideModFile(mod.source().toFile()));
        }
        log.info("Lich mods: {}; hooks: {}", mods, LichLoader.hooks());
        return mods;
    }

    /**
     * In the vanilla profile, reads MCP's SRG mappings from the class path for {@link SrgRemapper}, which the
     * vanilla tweaker registers, so the game runs with SRG names as under Forge. Returns whether it will.
     * Without the mappings (an install from before Lich had them) the game runs obfuscated, without Lich's
     * game fixes, and Lich mods can't load.
     */
    private static boolean useSrgRemapper(Logger log) {
        List<Path> mappings = LichSystemClassLoader.mappings();
        if (mappings.isEmpty()) {
            log.warn("No MCP mappings (mcp-<version>-srg.zip) on the class path: the game runs with obfuscated names");
            return false;
        }
        try {
            SrgRemapper.use(SrgFile.readZip(mappings.getFirst(), "joined.srg"));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read MCP's mappings from " + mappings.getFirst(), e);
        }
        LichVanillaTweaker.remapToSrg();
        return true;
    }

    /**
     * Runs the compat pass on every class Lich defines from now on: in {@code LaunchClassLoader} ahead
     * of all transformers, and in the system class loader for the classes LaunchWrapper excludes.
     */
    private static void installCompat(Logger log, List<String> tweakClasses, String minecraft, boolean srgNames,
                                      boolean lichMods) {
        CompatTransformer.Builder builder = CompatTransformer.builder();
        JdkCompat.register(builder);
        AsmCompat.register(builder);
        if (tweakClasses.contains(Profile.FML_1_7_10)) {
            AsmCompat.registerFml1710(builder);
            if (lichMods) {
                Forge1710.hideLichMods(builder);
            }
        } else if (tweakClasses.contains(Profile.FML_1_8_9) && lichMods) {
            Forge189.hideLichMods(builder);
        }
        CompatTransformer compat = builder
                .listener((className, what) -> log.info("Compat: {} in {}", what, className))
                .build();
        // Lich's own classes never need it, and the shims call the very methods it redirects to them.
        LichTransformers.addFirst((name, transformedName, bytes) ->
                transformedName.startsWith("dev.lich.") ? bytes : compat.transform(transformedName, bytes));

        // LWJGL 2 calls onto LWJGL 3, last, so mods' transformers and Mixins still see (and target) the
        // original calls. The system class loader also runs it on what it defines (lwjgl_util, the sound
        // libraries, LaunchWrapper's exclusions).
        CompatTransformer lwjgl = usesLwjgl3()
                ? Lwjgl2Compat.register(CompatTransformer.builder())
                        .listener((className, what) -> log.info("LWJGL 3: {} in {}", what, className))
                        .build()
                : null;
        BiFunction<String, byte[], byte[]> systemPass = lwjgl == null ? compat::transform
                : (name, bytes) -> lwjgl.transform(name, compat.transform(name, bytes));
        if (Relaunch.relauncher() != null) {
            Relaunch.useCompat(systemPass);
        } else if (ClassLoader.getSystemClassLoader() instanceof LichSystemClassLoader) {
            LichSystemClassLoader.setTransformer(systemPass);
        }

        // Fixes to the game itself, on SRG names: after FML (or Lich's remapper) has deobfuscated it, and after
        // FML's binpatches, which checksum the bytes they patch.
        CompatTransformer.Builder gameFixes = CompatTransformer.builder();
        if ("1.7.10".equals(minecraft)) {
            Minecraft1710.registerSrgPatches(gameFixes);
        } else if ("1.8.9".equals(minecraft)) {
            Minecraft189.registerSrgPatches(gameFixes);
        }
        if (srgNames) {
            CompatTransformer game = gameFixes
                    .listener((className, what) -> log.info("Game fix: {} in {}", what, className))
                    .build();
            LichTransformers.addLast((name, transformedName, bytes) ->
                    transformedName.startsWith("dev.lich.") ? bytes : game.transform(transformedName, bytes));
        }
        if (lwjgl != null) {
            LichTransformers.addLast((name, transformedName, bytes) ->
                    transformedName.startsWith("dev.lich.") ? bytes : lwjgl.transform(transformedName, bytes));
        }
    }

    /**
     * Whether the game runs on LWJGL 3: the system class loader left LWJGL 3 and Lich's LWJGL 2 API on the class
     * path (and LWJGL 2 out), or the other way round. Asked of the class path, so it holds in a relaunch too.
     */
    private static boolean usesLwjgl3() {
        ClassLoader loader = Main.class.getClassLoader();
        return loader.getResource("org/lwjgl/glfw/GLFW.class") != null
                && loader.getResource("dev/lich/compat/lwjgl2/Lwjgl2Compat.class") != null;
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

    /**
     * Mods' HTTP requests (patron lists, update checks) go out as {@code Java/1.8.0_51 Java/25...} instead of
     * {@code Java/25...}, so servers that treat Java 8 differently answer as they did on Java 8: the JDK puts
     * {@code http.agent} before its own {@code Java/<version>}. Pastebin's Cloudflare refuses Java 8 with a 403, which
     * mods catch, and redirects Java 25 to HTTPS with an HTML page that LLibrary 1.5.1 then parses as its patron list
     * and crashes on. 1.8.0_51 is the Java that Mojang's launcher shipped with. Set before any connection: the JDK
     * reads the property once.
     */
    static void ensureJava8UserAgent() {
        if (System.getProperty("http.agent") == null) {
            System.setProperty("http.agent", "Java/1.8.0_51");
        }
    }

    private static final class Hooks implements LaunchHooks {
        /** Run from the API in LaunchClassLoader, where the mods' classes are. */
        private static final String ENTRYPOINTS = "dev.lich.api.impl.Entrypoints";

        private final List<LichMod> mods;

        Hooks(List<LichMod> mods) {
            this.mods = mods;
        }

        @Override
        public void classLoaderCreated(LaunchClassLoader classLoader) {
            // Lich's launch-time classes and shims stay in the system class loader with LaunchWrapper's:
            // one copy each, whichever loader the code that calls them comes from. That includes the loader,
            // whose state the API (in LaunchClassLoader) reads, and Lich's Mixin setup.
            classLoader.addClassLoaderExclusion("dev.lich.boot.");
            classLoader.addClassLoaderExclusion("dev.lich.launchwrapper.");
            classLoader.addClassLoaderExclusion("dev.lich.transform.");
            classLoader.addClassLoaderExclusion("dev.lich.compat.");
            classLoader.addClassLoaderExclusion("dev.lich.remap.");
            classLoader.addClassLoaderExclusion("dev.lich.loader.");
            classLoader.addClassLoaderExclusion("dev.lich.mixin.");
            // The mods themselves, next to the game, where FML's mod discovery doesn't look.
            for (LichMod mod : mods) {
                classLoader.addURL(mod.url());
                LichSources.hide(mod.url());
            }
            if (!mods.isEmpty()) {
                // MixinExtras runs in the system class loader with Lich's Mixin and defines the helper classes its
                // injectors use there (@Local's references): woven game code has to see that copy.
                classLoader.addClassLoaderExclusion("com.llamalad7.mixinextras.");
            }
            // One ASM, Lich's, for Lich's shims and for LaunchClassLoader's transformers alike. FML 1.8.9
            // excludes it itself; 1.7.10 doesn't, and a second copy in LaunchClassLoader breaks the ASM
            // shims (loader constraint violations on org.objectweb.asm.Type).
            classLoader.addClassLoaderExclusion("org.objectweb.asm.");
            // Nashorn, for scripting mods: one copy, untouched by mod transformers. Scripts still see game
            // classes, since its engines load them through the context class loader (LaunchClassLoader).
            classLoader.addClassLoaderExclusion("org.openjdk.nashorn.");
            // JAXB, one copy as the JDK's was on Java 8. FML excludes javax. already; the vanilla profile doesn't.
            for (String prefix : List.of("javax.xml.bind.", "javax.activation.", "com.sun.xml.bind.", "com.sun.xml.txw2.",
                    "com.sun.istack.", "com.sun.activation.")) {
                classLoader.addClassLoaderExclusion(prefix);
            }
            // LWJGL 3 and Lich's LWJGL 2 API on it: one copy, whatever mods do to LaunchWrapper's own exclusion.
            if (usesLwjgl3()) {
                LichExclusions.add("org.lwjgl.");
            }
            ClassLoader relauncher = Relaunch.relauncher();
            (relauncher == null ? LichSystemClassLoader.lichJars() : Relaunch.lichJars(relauncher)).forEach(LichSources::hide);
        }

        /** The mods' preLaunch entrypoints: the game's classes haven't loaded yet. */
        @Override
        public void launching(LaunchClassLoader classLoader) {
            if (mods.isEmpty()) {
                return;
            }
            try {
                Class.forName(ENTRYPOINTS, true, classLoader).getMethod("run", String.class).invoke(null, "preLaunch");
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Lich's API is missing from the class path", e);
            }
        }
    }
}
