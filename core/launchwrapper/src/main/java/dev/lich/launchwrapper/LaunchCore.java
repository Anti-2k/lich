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
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
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
 * call the primary tweaker's launch target. {@link Launch#main} and Lich's boot both run it.
 *
 * <p>The one change: the class path comes from {@code java.class.path}, because 1.12's cast of the
 * application class loader to {@code URLClassLoader} fails on Java 9+.
 */
public final class LaunchCore {
    private static final String DEFAULT_TWEAK = "net.minecraft.launchwrapper.VanillaTweaker";

    private LaunchCore() {
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

    /** The application class path, as URLs, in order. */
    static URL[] classPath() {
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
