package dev.lich.launchwrapper;

import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.launchwrapper.LogWrapper;
import org.apache.logging.log4j.Level;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * What LaunchWrapper 1.12's {@code Launch} does, step for step: parse the launch arguments, load the
 * tweakers (tweakers may queue more tweakers, until none are left), collect their launch arguments and
 * call the primary tweaker's launch target. It always runs inside {@link Launch#main}, Lich's boot
 * included, so the stack looks like 1.12's; boot passes its hooks through {@link #useHooks}.
 *
 * <p>The one change: 1.12 casts the application class loader to {@code URLClassLoader} for the class
 * path, which fails on Java 9+. Lich asks its own defining loader when that is a {@code URLClassLoader}
 * (Lich's system class loader, which leaves out the jars Lich replaces) and reads
 * {@code java.class.path} otherwise.
 */
public final class LaunchCore {
    private static final String DEFAULT_TWEAK = "net.minecraft.launchwrapper.VanillaTweaker";

    private static final String BOOT = "dev.lich.boot.Main";

    /** Set by boot right before it calls {@link Launch#main}; {@code null} when nobody did. */
    private static volatile LaunchHooks pendingHooks;

    private LaunchCore() {
    }

    /** The hooks for the next {@link Launch#main} call. */
    public static void useHooks(LaunchHooks hooks) {
        pendingHooks = hooks;
    }

    /** For {@link Launch}: the hooks set by {@link #useHooks}, once; {@code null} if boot didn't set any. */
    public static LaunchHooks takeHooks() {
        LaunchHooks hooks = pendingHooks;
        pendingHooks = null;
        return hooks;
    }

    /**
     * For {@link Launch}, when it was started without boot: runs Lich's boot from {@code Launch}'s own
     * class loader, if it's there, and returns {@code true} once it's done (boot comes back through
     * {@code Launch.main} with its hooks). That happens when a mod relaunches the game in a class loader of
     * its own (Essential's loader calls {@code Launch.main} again, on fresh copies of every class) and when
     * a launcher names {@code Launch} as the main class: either way the game would otherwise run without
     * Lich's compat pass. Without boot (LaunchWrapper's own tests) it returns {@code false}.
     */
    public static boolean startBoot(String[] args) throws Exception {
        Class<?> boot;
        try {
            boot = Class.forName(BOOT, true, LaunchCore.class.getClassLoader());
        } catch (ClassNotFoundException noBoot) {
            return false;
        }
        try {
            boot.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
        return true;
    }

    /** Launches the game, or throws whatever stopped it; {@link Launch#main} turns that into exit code 1. */
    public static void launch(String[] args, LaunchHooks hooks) throws Exception {
        Launch.classLoader = new LaunchClassLoader(classPath());
        Launch.blackboard = new HashMap<>();
        Thread.currentThread().setContextClassLoader(Launch.classLoader);
        hooks.classLoaderCreated(Launch.classLoader);

        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        final OptionSpec<String> profileOption = parser.accepts("version", "The version we launched with").withRequiredArg();
        final OptionSpec<File> gameDirOption = parser.accepts("gameDir", "Alternative game directory").withRequiredArg().ofType(File.class);
        final OptionSpec<File> assetsDirOption = parser.accepts("assetsDir", "Assets directory").withRequiredArg().ofType(File.class);
        final OptionSpec<String> tweakClassOption = parser.accepts("tweakClass", "Tweak class(es) to load").withRequiredArg().defaultsTo(DEFAULT_TWEAK);
        final NonOptionArgumentSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        Launch.minecraftHome = options.valueOf(gameDirOption);
        Launch.assetsDir = options.valueOf(assetsDirOption);
        final String profileName = options.valueOf(profileOption);
        final List<String> tweakClassNames = new ArrayList<>(options.valuesOf(tweakClassOption));

        final List<String> argumentList = new ArrayList<>();
        // Tweakers add more tweak classes to this list while they're being loaded.
        Launch.blackboard.put("TweakClasses", tweakClassNames);
        Launch.blackboard.put("ArgumentList", argumentList);

        final Set<String> allTweakerNames = new HashSet<>();
        final List<ITweaker> allTweakers = new ArrayList<>();
        final List<ITweaker> tweakers = new ArrayList<>(tweakClassNames.size() + 1);
        Launch.blackboard.put("Tweaks", tweakers);
        ITweaker primaryTweaker = null;
        do {
            for (final Iterator<String> it = tweakClassNames.iterator(); it.hasNext(); ) {
                final String tweakName = it.next();
                if (allTweakerNames.contains(tweakName)) {
                    LogWrapper.log(Level.WARN, "Tweak class name %s has already been visited -- skipping", tweakName);
                    it.remove();
                    continue;
                } else {
                    allTweakerNames.add(tweakName);
                }
                LogWrapper.log(Level.INFO, "Loading tweak class name %s", tweakName);

                // The tweaker's own package is loaded by the application class loader, as in 1.12.
                Launch.classLoader.addClassLoaderExclusion(tweakName.substring(0, tweakName.lastIndexOf('.')));
                final ITweaker tweaker = (ITweaker) Class.forName(tweakName, true, Launch.classLoader)
                        .getDeclaredConstructor().newInstance();
                tweakers.add(tweaker);

                it.remove();
                if (primaryTweaker == null) {
                    LogWrapper.log(Level.INFO, "Using primary tweak class name %s", tweakName);
                    primaryTweaker = tweaker;
                }
            }

            for (final Iterator<ITweaker> it = tweakers.iterator(); it.hasNext(); ) {
                final ITweaker tweaker = it.next();
                LogWrapper.log(Level.INFO, "Calling tweak class %s", tweaker.getClass().getName());
                tweaker.acceptOptions(options.valuesOf(nonOption), Launch.minecraftHome, Launch.assetsDir, profileName);
                tweaker.injectIntoClassLoader(Launch.classLoader);
                allTweakers.add(tweaker);
                it.remove();
            }
        } while (!tweakClassNames.isEmpty());

        for (final ITweaker tweaker : allTweakers) {
            argumentList.addAll(Arrays.asList(tweaker.getLaunchArguments()));
        }

        final String launchTarget = primaryTweaker.getLaunchTarget();
        final Class<?> clazz = Class.forName(launchTarget, false, Launch.classLoader);
        final Method mainMethod = clazz.getMethod("main", String[].class);

        LogWrapper.info("Launching wrapped minecraft {%s}", launchTarget);
        mainMethod.invoke(null, (Object) argumentList.toArray(new String[0]));
    }

    /** The class path, as URLs, in order. */
    static URL[] classPath() {
        if (LaunchCore.class.getClassLoader() instanceof URLClassLoader loader) {
            return loader.getURLs();
        }
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                urls.add(Path.of(entry).toAbsolutePath().toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Bad class path entry: " + entry, e);
            }
        }
        return urls.toArray(new URL[0]);
    }
}
